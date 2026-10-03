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
    private final ReceiptSpool spool;
    private final TaskExecutor executor;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "paicli-runner-heartbeat"); t.setDaemon(true); return t; });
    private final FileChannel rootLockChannel;
    private final FileLock rootLock;
    private final WorkspaceLockDirectory.Held stateLock;
    private final DeviceCredentialCache credentials;
    private String deviceToken;
    private volatile java.time.Instant sessionExpiry=java.time.Instant.MIN;
    private volatile boolean closed;

    public RunnerEngine(CenterHttpClient center, Path root, Path stateDirectory, TaskExecutor executor) throws IOException {
        this(center,root,stateDirectory,executor,null,null);
    }
    public RunnerEngine(CenterHttpClient center,Path root,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials)throws IOException{
        this(center, root, stateDirectory, executor, deviceToken, credentials, Path.of(System.getProperty("user.home")));
    }
    RunnerEngine(CenterHttpClient center,Path root,Path stateDirectory,TaskExecutor executor,String deviceToken,DeviceCredentialCache credentials,Path lockHome)throws IOException{
        this.deviceToken=deviceToken;this.credentials=credentials;
        this.center = center; resources = new ResourceFingerprint(root); this.executor = executor;
        Path requestedState = stateDirectory.toAbsolutePath().normalize();
        if (requestedState.startsWith(resources.root())) throw new IOException("Runner 证据缓存必须位于工作区外，避免改变资源摘要");
        Path state = RunnerPrivateDirectory.createDirectories(requestedState).toRealPath();
        RunnerPrivateDirectory.requireDirectory(state);
        if (state.startsWith(resources.root())) throw new IOException("Runner 证据缓存必须位于工作区外，避免改变资源摘要");
        Path locks = WorkspaceLockDirectory.prepare(lockHome);
        WorkspaceLockDirectory.Held rootHeld=WorkspaceLockDirectory.acquire(locks,resources.rootFingerprint()+".lock");
        WorkspaceLockDirectory.Held stateHeld=null;
        try {
            stateHeld=WorkspaceLockDirectory.acquire(locks,"state-"+CanonicalJson.sha256(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+".lock");
            spool=new ReceiptSpool(state.resolve("receipts"));
        } catch(IOException|RuntimeException failure){try {if(stateHeld!=null)stateHeld.close();}finally{rootHeld.close();}throw failure;}
        stateLock=stateHeld;
        rootLockChannel=rootHeld.channel();rootLock=rootHeld.lock();
    }
    public JsonNode connect() throws IOException {
        JsonNode response=exchangeSession();replayReceipts();return response;
    }
    private synchronized JsonNode exchangeSession()throws IOException{
        ObjectNode request = CanonicalJson.MAPPER.createObjectNode(); request.put("rootFingerprint", resources.rootFingerprint()); request.put("capacity", 1);
        request.put("platform",System.getProperty("os.name","UNKNOWN"));request.put("distributionVersion",distributionVersion());
        var capabilities = request.putArray("capabilities");
        for (String capability : new String[]{"files", "command", "memory", "rag", "web", "mcp", "browser", "skills", "snapshot", "images", "wechat"}) capabilities.add(capability);
        // capability只表示支持协议，实际命令仍要求中心核验记录及本机REQUIRED检测同时通过。
        JsonNode response;
        if(deviceToken==null)response=center.post("/paicli/runner/v1/sessions",request,UUID.randomUUID().toString());
        else try(CenterHttpClient bootstrap=center.withRunnerToken(deviceToken)){response=bootstrap.post("/paicli/runner/v1/sessions",request,credentials==null?UUID.randomUUID().toString():credentials.exchangeKey(deviceToken));}
        java.time.Instant expiry=RunnerLease.expiry(response.path("expiresAt"));
        if (!expiry.isAfter(java.time.Instant.now())) throw new IOException("设备会话已过期");
        if(response.hasNonNull("deviceToken")){
            String bound=response.path("deviceToken").asText();if(credentials!=null)credentials.store(bound,deviceToken);deviceToken=bound;
        }
        center.replaceToken(response.path("sessionToken").asText());
        sessionExpiry=expiry;
        return response;
    }
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
        if(deviceToken==null)throw new IOException("缺少已绑定设备凭证，不能续期会话");exchangeSession();
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
        renewSession();
        JsonNode claim = center.post("/paicli/runner/v1/claims", CanonicalJson.MAPPER.createObjectNode(), UUID.randomUUID().toString());
        if (claim == null || claim.isNull() || claim.has("available") && !claim.path("available").asBoolean()) return false;
        RunnerLease lease = new RunnerLease(claim);
        if (spool.hasUncertainExecution(lease.taskId())) { lease.invalidate(); throw new IOException("上次执行状态不确定，需要中心人工对账"); }
        CancellationToken cancellation = CancellationContext.startRun();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { renewSession(); lease.heartbeat(center.post("/paicli/runner/v1/heartbeats", lease.identity(), UUID.randomUUID().toString())); if (lease.isCancellationRequested()) cancellation.cancel(); }
            catch (Exception failure) { lease.invalidate(); cancellation.cancel(); }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            CenterToolRegistry tools = new CenterToolRegistry(center, lease, spool, resources.root());
            String result = executor.execute(lease, tools);
            if (cancellation.isCancelled()) lease.ensureEvidenceAllowed(); else lease.ensureActive();
            event(lease, cancellation.isCancelled() ? "TASK_CANCELLED" : "TASK_COMPLETED", result);
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
