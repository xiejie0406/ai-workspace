package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** capacity=1 默认执行。领取失败或失联不复用旧租约，重启只重传回执。 */
public final class RunnerEngine implements AutoCloseable {
    @FunctionalInterface public interface TaskExecutor { String execute(RunnerLease lease, CenterToolRegistry tools) throws Exception; }
    private final CenterHttpClient center;
    private final ResourceFingerprint resources;
    private final PersonalDirectoryContext personalDirectories;
    private final Path lockHome;
    private final Path state;
    private final ReceiptSpool spool;
    private final TaskExecutor executor;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "paicli-runner-heartbeat"); t.setDaemon(true); return t; });
    private final FileChannel rootLockChannel;
    private final FileLock rootLock;
    private final WorkspaceLockDirectory.Held stateLock;
    private final DeviceCredentialCache credentials;
    private final RunnerTerminalHost terminalHost;
    private String deviceToken;
    private volatile java.time.Instant sessionExpiry=java.time.Instant.MIN;
    private volatile boolean closed;
    private volatile JsonNode readinessIdentity;
    private boolean readinessScheduled;
    private volatile boolean exchangeHealthy;

    public RunnerEngine(CenterHttpClient center, Path root, Path stateDirectory, TaskExecutor executor) throws IOException {
        this(center,root,stateDirectory,executor,null,null);
    }
    public RunnerEngine(CenterHttpClient center,Path root,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials)throws IOException{
        this(center, root, stateDirectory, executor, deviceToken, credentials, Path.of(System.getProperty("user.home")));
    }
    RunnerEngine(CenterHttpClient center,Path root,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials,Path lockHome)throws IOException{
        this(center,root,stateDirectory,executor,deviceToken,credentials,lockHome,null);
    }
    public RunnerEngine(CenterHttpClient center,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials,PersonalDirectoryContext directories)throws IOException{
        this(center,directories.runtime(),stateDirectory,executor,deviceToken,credentials,Path.of(System.getProperty("user.home")),directories);
    }
    RunnerEngine(CenterHttpClient center,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials,PersonalDirectoryContext directories,Path lockHome)throws IOException{
        this(center,directories.runtime(),stateDirectory,executor,deviceToken,credentials,lockHome,directories);
    }
    private RunnerEngine(CenterHttpClient center,Path root,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials,Path lockHome,PersonalDirectoryContext directories)throws IOException{
        this.personalDirectories=directories;this.lockHome=lockHome;
        this.deviceToken=deviceToken;this.credentials=credentials;
        this.center = center; resources = new ResourceFingerprint(root); this.executor = executor;
        Path requestedState = stateDirectory.toAbsolutePath().normalize();
        if(directories!=null&&requestedState.startsWith(directories.home()))throw new IOException("Runner私有派生状态不能放在本人文件目录内");
        if (requestedState.startsWith(resources.root())) throw new IOException("Runner 证据缓存必须位于工作区外，避免改变资源摘要");
        state = RunnerPrivateDirectory.createDirectories(requestedState).toRealPath();
        RunnerPrivateDirectory.requireDirectory(state);
        if (state.startsWith(resources.root())) throw new IOException("Runner 证据缓存必须位于工作区外，避免改变资源摘要");
        Path locks = WorkspaceLockDirectory.prepare(lockHome);
        WorkspaceLockDirectory.Held rootHeld=WorkspaceLockDirectory.acquire(locks,(directories==null?"":"host-")+resources.rootFingerprint()+".lock");
        WorkspaceLockDirectory.Held stateHeld=null;
        try {
            stateHeld=WorkspaceLockDirectory.acquire(locks,"state-"+CanonicalJson.sha256(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+".lock");
            spool=new ReceiptSpool(state.resolve("receipts"));
        } catch(IOException|RuntimeException failure){try {if(stateHeld!=null)stateHeld.close();}finally{rootHeld.close();}throw failure;}
        stateLock=stateHeld;
        rootLockChannel=rootHeld.channel();rootLock=rootHeld.lock();
        RunnerTerminalHost requestedHost=null;
        try{
            if("true".equalsIgnoreCase(System.getenv("PAICLI_WEB_TERMINAL_ENABLED")))requestedHost=new RunnerTerminalHost(center,resources.root(),state,directories);
        }catch(IOException failure){try{stateLock.close();}finally{try{rootLock.release();}finally{rootLockChannel.close();}}throw failure;}
        terminalHost=requestedHost;
    }
    public JsonNode connect() throws IOException {
        try{
            JsonNode response=exchangeSession();replayReceipts();if(terminalHost!=null)terminalHost.start();
            publishReadiness();
            if(!readinessScheduled){readinessScheduled=true;heartbeats.scheduleWithFixedDelay(()->{try{writeReadiness();}catch(IOException unavailable){/* 监管按checkedAt超时拒绝旧信号，不记录凭证。 */}},3,3,TimeUnit.SECONDS);}
            return response;
        }catch(IOException failure){exchangeHealthy=false;publishReadiness();throw failure;}
    }
    /** 运营宿主核验已经存在的本人子目录；浏览器或模型传入任意外部路径不能扩展边界。 */
    public JsonNode proveDirectory(Path directory)throws IOException{
        if(personalDirectories==null)throw new IOException("旧固定根宿主不支持个人目录证明");
        renewSession();ObjectNode proof=personalDirectories.proof(directory);
        JsonNode result=center.post("/paicli/runner/v1/personal-directory-proof",proof,UUID.randomUUID().toString());
        ObjectNode envelope=CanonicalJson.MAPPER.createObjectNode().put("ownerUserId",personalDirectories.ownerUserId())
            .put("directoryContextId",result.path("id").asText()).put("runnerId",result.path("runnerId").asText())
            .put("rootFingerprint",proof.path("rootFingerprint").asText());envelope.set("directoryContext",result);
        if(!directory.toRealPath().equals(personalDirectories.resolve(envelope)))throw new IOException("个人目录证明响应改变了已核验路径");
        return result;
    }
    private synchronized JsonNode exchangeSession()throws IOException{
        ObjectNode request = CanonicalJson.MAPPER.createObjectNode(); request.put("rootFingerprint", resources.rootFingerprint()); request.put("capacity", 1);
        if(personalDirectories!=null){
            request.put("ownerUserId",personalDirectories.ownerUserId());
            request.put("runtimeCanonicalPath",personalDirectories.runtime().toString()).put("runtimeFingerprint",resources.rootFingerprint());
            if(personalDirectories.filesReady())request.put("canonicalRootPath",personalDirectories.home().toString())
                .put("rootFingerprint",new ResourceFingerprint(personalDirectories.home()).rootFingerprint());
            else request.remove("rootFingerprint");
        }
        request.put("platform",System.getProperty("os.name","UNKNOWN"));request.put("distributionVersion",distributionVersion());
        var capabilities = request.putArray("capabilities");
        for (String capability : new String[]{"files", "command", "memory", "rag", "web", "mcp", "browser", "skills", "snapshot", "images", "wechat", "sub2api.responses.v1", "sub2api.chat_completions.v1", "sub2api.responses.images.v1", "embedding.identity.v1", "embedding.release.v1"}) capabilities.add(capability);
        if(terminalHost!=null){capabilities.add("terminal");request.put("ptyMode",terminalHost.candidateMode());request.put("ptyVersion",PtyProcessManager.VERSION);}
        // capability只表示支持协议，实际命令仍要求中心核验记录及本机REQUIRED检测同时通过。
        JsonNode response;
        if(deviceToken==null)response=center.post("/paicli/runner/v1/sessions",request,UUID.randomUUID().toString());
        else try(CenterHttpClient bootstrap=center.withRunnerToken(deviceToken)){
            String key=credentials==null?UUID.randomUUID().toString():credentials.exchangeKey(deviceToken);
            JsonNode exchangeRequest=credentials==null?request:credentials.exchangeRequest(deviceToken,request);
            response=bootstrap.post("/paicli/runner/v1/sessions",exchangeRequest,key);
        }
        java.time.Instant expiry=RunnerLease.expiry(response.path("expiresAt"));
        String sessionToken=response.path("sessionToken").asText();
        if(sessionToken.isBlank()||sessionToken.indexOf('\n')>=0||sessionToken.indexOf('\r')>=0)throw new IOException("中心会话响应缺少合法凭证");
        if(personalDirectories!=null){
            if(!personalDirectories.ownerUserId().equals(response.path("ownerUserId").asText())
                ||!personalDirectories.runtime().equals(Path.of(response.path("runtimeCanonicalPath").asText()).toRealPath())
                ||!resources.rootFingerprint().equals(response.path("runtimeFingerprint").asText()))throw new IOException("宿主运行目录与中心设备身份不符");
            if(response.hasNonNull("directoryContext")){
                ObjectNode proof=response.deepCopy();proof.put("rootFingerprint",response.path("directoryContext").path("rootFingerprint").asText());proof.put("runnerId",response.path("directoryContext").path("runnerId").asText());
                if(!personalDirectories.home().equals(personalDirectories.resolve(proof)))throw new IOException("宿主默认个人目录与中心设备身份不符");
            }
        }
        if(response.hasNonNull("deviceToken")){
            String bound=response.path("deviceToken").asText();if(credentials!=null)credentials.store(bound,deviceToken);deviceToken=bound;
        }else if(credentials!=null&&deviceToken!=null)credentials.completeExchange(deviceToken);
        if (!expiry.isAfter(java.time.Instant.now())) throw new IOException("重放的设备会话已过期，已保留设备绑定，可重新交换会话");
        center.replaceToken(sessionToken);
        if(terminalHost!=null)terminalHost.refreshDeviceConnection();
        sessionExpiry=expiry;
        readinessIdentity=response.deepCopy();
        exchangeHealthy=true;
        return response;
    }
    private synchronized void writeReadiness()throws IOException{
        RunnerReadiness.write(state,readinessIdentity,sessionExpiry,!closed&&exchangeHealthy&&sessionExpiry.isAfter(java.time.Instant.now())&&terminalHost!=null&&terminalHost.ready());
    }
    /** 就绪文件是监管信号，短暂读句柄竞争不能杀掉健康任务/PTY；失败后监管按旧checkedAt判断。 */
    private void publishReadiness()throws IOException{try{writeReadiness();}catch(RunnerReadiness.PublicationBusy unavailable){/* 不修权限、不移除重解析节点，不发布虚假成功。 */}}
    private static String distributionVersion(){
        String packaged=RunnerEngine.class.getPackage().getImplementationVersion();
        if(packaged!=null&&!packaged.isBlank())return packaged;
        try(var input=RunnerEngine.class.getResourceAsStream("/META-INF/maven/com.ruoyi/paicli-runner/pom.properties")){
            if(input!=null){java.util.Properties metadata=new java.util.Properties();metadata.load(input);String version=metadata.getProperty("version");if(version!=null&&!version.isBlank())return version;}
        }catch(IOException unavailable){ /* 源码技术测试无分发清单时如实标记。 */ }
        return "development-unpackaged";
    }
    private synchronized void renewSession()throws IOException{
        if(sessionExpiry.isAfter(java.time.Instant.now().plusSeconds(60)))return;
        if(deviceToken==null)throw new IOException("缺少已绑定设备凭证，不能续期会话");
        try{exchangeSession();}catch(IOException failure){exchangeHealthy=false;throw failure;}
    }
    public void replayReceipts() throws IOException {
        for (Path path : spool.pending()) {
            JsonNode value = spool.read(path);
            if ("RECEIPT".equals(value.path("phase").asText())) {
                JsonNode receipt = value.path("receipt");
                JsonNode acknowledgement = center.post("/paicli/runner/v1/receipts", receipt, CenterToolRegistry.operationKey(receipt.path("taskId").asText(), receipt.path("toolCallId").asText(), "receipt"));
                ReceiptAcknowledgement.require(acknowledgement, receipt);
                spool.acknowledged(path);
            }
        }
    }
    public boolean runOnce() throws IOException {
        if (closed) throw new IOException("Runner 已关闭");
        try{renewSession();}catch(IOException failure){exchangeHealthy=false;publishReadiness();throw failure;}
        publishReadiness();
        JsonNode claim;
        try{claim=center.post("/paicli/runner/v1/claims",CanonicalJson.MAPPER.createObjectNode(),UUID.randomUUID().toString());exchangeHealthy=true;}
        catch(IOException failure){exchangeHealthy=false;publishReadiness();throw failure;}
        if (claim == null || claim.isNull() || claim.has("available") && !claim.path("available").asBoolean()) return false;
        RunnerLease lease = new RunnerLease(claim);
        if (spool.hasUncertainExecution(lease.taskId())) { lease.invalidate(); throw new IOException("上次执行状态不确定，需要中心人工对账"); }
        CancellationToken cancellation = CancellationContext.startRun();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { renewSession(); lease.heartbeat(center.post("/paicli/runner/v1/heartbeats", lease.identity(), UUID.randomUUID().toString())); if (lease.isCancellationRequested()) cancellation.cancel(); }
            catch (Exception failure) { lease.invalidate(); cancellation.cancel(); }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            Path project=personalDirectories==null?resources.root():PersonalDirectoryContext.textOnly(claim)?personalDirectories.resolveRuntime(claim):personalDirectories.resolve(claim);
            Path cache=personalDirectories==null?state:RunnerPrivateDirectory.createDirectories(state.resolve("directories").resolve(personalDirectories.ownerUserId()).resolve(new ResourceFingerprint(project).rootFingerprint()));
            try(PhysicalDirectoryLease held=personalDirectories==null||PersonalDirectoryContext.textOnly(claim)?null:PhysicalDirectoryLease.acquire(project,lockHome)){
            CenterToolRegistry tools = new CenterToolRegistry(center, lease, spool, project,cache);
            String result = executor.execute(lease, tools);
            if (cancellation.isCancelled()) lease.ensureEvidenceAllowed(); else lease.ensureActive();
            event(lease, cancellation.isCancelled() ? "TASK_CANCELLED" : "TASK_COMPLETED", result);
            }
        } catch (Exception failure) {
            // 租约失效时不尝试写终态，中心保留RECONCILE_REQUIRED。
            try { lease.ensureEvidenceAllowed(); event(lease, cancellation.isCancelled() ? "TASK_CANCELLED" : "TASK_FAILED", failure.getMessage()); }
            catch (IOException ignored) { throw new IOException("任务执行或对账未完成，未重跑任务", failure); }
        } finally { heartbeat.cancel(false); CancellationContext.clear(cancellation); }
        return true;
    }
    private void event(RunnerLease lease, String type, String message) throws IOException {
        ObjectNode body = lease.identity(); body.put("eventId", UUID.randomUUID().toString()); body.put("type", type);
        ObjectNode payload = body.putObject("payload"); payload.put("message", message == null ? "" : message);
        if ("TASK_COMPLETED".equals(type) && "TEAM".equalsIgnoreCase(lease.claim().path("payload").path("mode").asText())) {
            // Native执行器仅在原Team全部审查通过且轨迹已确认时返回，中心还必须核对轨迹。
            payload.put("reviewStatus", "PASSED");
        }
        center.post("/paicli/runner/v1/events", body, body.path("eventId").asText());
    }
    @Override public synchronized void close() throws IOException {
        if(closed)return;closed=true;heartbeats.shutdownNow();IOException failure=null;
        try{publishReadiness();}catch(IOException unavailable){failure=unavailable;}
        if(terminalHost!=null)terminalHost.close();
        try {
            if(executor instanceof AutoCloseable resource)resource.close();
        }catch(Exception closeFailure){failure=new IOException("Runner执行资源关闭失败",closeFailure);}
        finally {
            try { center.close(); }
            finally { try { stateLock.close(); } finally { try { rootLock.release(); } finally { rootLockChannel.close(); } } }
        }
        if(failure!=null)throw failure;
    }
}
