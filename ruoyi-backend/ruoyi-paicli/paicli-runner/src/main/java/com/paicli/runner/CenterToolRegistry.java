package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.CommandSandboxMode;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultOffloader;
import com.paicli.llm.LlmClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 三条 Agent 路径均经同一提案、审批、消费和回执边界，不以模型回答代替许可。 */
public class CenterToolRegistry extends ToolRegistry {
    private static final Set<String> READ_ONLY = Set.of("read_file", "list_dir", "glob_files", "grep_code", "search_code", "web_search", "web_fetch", "load_skill", "snapshot__preview");
    private static final Set<String> RESTORE_FIELDS = Set.of("snapshotMetadataId", "snapshotId", "snapshotVersion", "previewToolCallId", "currentFingerprint", "restorePreview");
    private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();
    private final CenterHttpClient center;
    private final RunnerLease lease;
    private final ReceiptSpool spool;
    private final ResourceFingerprint resources;
    private final PendingImageEvidence imageEvidence;
    private final ReentrantReadWriteLock workspaceLock;
    private final ThreadLocal<String> callIds = new ThreadLocal<>();
    private final ThreadLocal<String> modelCallHashes = new ThreadLocal<>();
    private final Set<String> startedCalls = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<ObjectNode> executionBinding = new ThreadLocal<>();
    private final ThreadLocal<java.util.List<Path>> executionEvidence = new ThreadLocal<>();
    private final ThreadLocal<PendingImageEvidence.Reservation> executionCapacity = new ThreadLocal<>();
    private final ThreadLocal<Path> controlEvidence = new ThreadLocal<>();
    private final ThreadLocal<java.util.function.Supplier<ToolOutput>> controlHandler = new ThreadLocal<>();
    private final ConcurrentHashMap<String,JsonNode> imageReferences=new ConcurrentHashMap<>();
    private volatile String planVersion;
    private java.util.Map<String,String> centralSkillResources = java.util.Map.of();

    public void setCentralSkillBindings(JsonNode configurations) {
        var ordered = new java.util.ArrayList<JsonNode>(); configurations.forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparingInt(config -> switch(config.path("scope").asText().toUpperCase(java.util.Locale.ROOT)) {
            case "BUILTIN" -> 0; case "USER", "PERSONAL" -> 1; default -> 2;
        }));
        var resourcesByName = new java.util.HashMap<String,String>();
        for(JsonNode config:ordered) if(config.path("enabled").asBoolean()) resourcesByName.put(config.path("name").asText(), CanonicalJson.hash(CanonicalJson.MAPPER.valueToTree(java.util.Map.of(
                "id",config.path("id").asText(),"version",config.path("version").asText(),"sha256",config.path("sha256").asText()))));
        centralSkillResources = java.util.Map.copyOf(resourcesByName);
    }

    public CenterToolRegistry(CenterHttpClient center, RunnerLease lease, ReceiptSpool spool, Path root) throws IOException {
        this.center = center; this.lease = lease; this.spool = spool;
        imageEvidence=new PendingImageEvidence(spool.stateDirectory());
        resources = new ResourceFingerprint(root);
        if (!resources.rootFingerprint().equals(lease.claim().path("rootFingerprint").asText())) throw new IOException("设备登记的工作根与本机不符");
        planVersion = lease.claim().path("payload").path("planVersion").asText("0");
        workspaceLock = LOCKS.computeIfAbsent(resources.root(), ignored -> new ReentrantReadWriteLock(true));
        setProjectPath(resources.root().toString());
        // 真实执行器随后装配中心产物适配，绝不启用原仓库内的隐式文件卸载。
        setToolResultOffloader(new ToolResultOffloader(resources.root(),false,32000));
        setSanitizeCommandEnvironment(true);
        Path sandbox=RunnerPrivateDirectory.createDirectories(spool.stateDirectory().resolve("sandbox"));
        if(java.nio.file.Files.isSymbolicLink(sandbox)||!sandbox.toRealPath().equals(sandbox.toAbsolutePath().normalize())||sandbox.toRealPath().startsWith(resources.root()))throw new IOException("沙箱派生缓存必须位于工作根外且不能是链接");
        configureCommandSandbox(CommandSandboxMode.REQUIRED, resources.root(),sandbox);
    }

    @Override public String executeTool(String name, String argumentsJson) { return executeToolOutput(name, argumentsJson).text(); }
    @Override public ToolOutput executeToolOutput(String name, String argumentsJson) {
        String callId = callIds.get();
        if (callId == null) callId = UUID.randomUUID().toString();
        Lock lock = READ_ONLY.contains(name) ? workspaceLock.readLock() : workspaceLock.writeLock();
        Path evidence = null;
        ToolOutput result = null;
        PendingImageEvidence.Reservation capacity=null;
        java.util.ArrayList<Path> pendingImages=new java.util.ArrayList<>();
        lock.lock();
        try {
            lease.ensureActive();
            if(!startedCalls.add(callId)){
                lease.invalidate();throw new IOException("同一中心工具调用不能再次执行；需新调用及重新许可");
            }
            if (CancellationContext.isCancelled()) throw new IOException("任务已取消");
            JsonNode arguments = CanonicalJson.parseObject(argumentsJson);
            if ("revert_turn".equals(name)) {
                for (String field : RESTORE_FIELDS) if (arguments.has(field)) throw new IOException("模型恢复参数不能自行提供中心预览或批准字段");
            }
            String argsHash = CanonicalJson.hash(arguments);
            String before = captureResource(name, arguments);
            ObjectNode proposal = binding(callId, argsHash, before);
            proposal.put("toolName", name); proposal.set("arguments", arguments); proposal.put("sensitive", !READ_ONLY.contains(name));
            JsonNode permit = center.post("/paicli/runner/v1/proposals", proposal, operationKey(lease.taskId(), callId, "proposal"));
            if ("PREVIEW_REQUIRED".equals(permit.path("decision").asText())) {
                if (!"revert_turn".equals(name)) throw new IOException("中心为非恢复工具返回了快照预览要求");
                arguments = prepareSnapshotRestore(callId, argsHash, (ObjectNode) arguments, permit);
                argumentsJson = arguments.toString();
                argsHash = CanonicalJson.hash(arguments);
                before = arguments.path("currentFingerprint").asText();
                if (!before.equals(captureResource(name, arguments))) throw new IOException("实际预览后工作区已改变，需要重新预览和批准");
                callId = UUID.randomUUID().toString();
                if (!startedCalls.add(callId)) throw new IOException("恢复正式调用ID发生冲突");
                proposal = binding(callId, argsHash, before);
                proposal.put("toolName", name); proposal.set("arguments", arguments); proposal.put("sensitive", true);
                permit = center.post("/paicli/runner/v1/proposals", proposal, operationKey(lease.taskId(), callId, "proposal"));
                if ("PREVIEW_REQUIRED".equals(permit.path("decision").asText())) throw new IOException("中心没有确认正式快照预览回执，恢复保持拒绝");
            }
            while ("PENDING".equals(permit.path("decision").asText())) {
                lease.ensureActive();
                if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) throw new IOException("用户取消了工具审批等待");
                if (permit.hasNonNull("expiresAt") && !RunnerLease.expiry(permit.get("expiresAt")).isAfter(Instant.now())) throw new IOException("工具审批已过期");
                try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("工具审批已取消", interrupted); }
                permit = center.post("/paicli/runner/v1/permits", binding(callId, argsHash, before), UUID.randomUUID().toString());
            }
            verifyPermit(permit, argsHash, before);
            if (!before.equals(captureResource(name, arguments))) throw new IOException("审批期间资源发生变化，需要重新提案");
            lease.ensureActive();
            // 消费响应丢失也视为不确定执行，重启后仅对账，不重复消费或重跑。
            try{capacity=imageEvidence.reserve();}catch(IOException unavailable){lease.invalidate();throw unavailable;}
            evidence = spool.intent(lease.identity(), callId, name, argsHash, before);
            ObjectNode consume = binding(callId, argsHash, before); consume.put("permitToken", permit.path("permitToken").asText());
            JsonNode consumed = center.post("/paicli/runner/v1/permits/consume", consume, operationKey(lease.taskId(), callId, "consume"));
            if (!consumed.path("consumed").asBoolean()) throw new IOException("中心未确认消费许可");
            for(String field : new String[]{"toolCallId","argsHash","resourceHash","fencingToken","version"})
                if(!consume.path(field).asText().equals(consumed.path(field).asText()))throw new IOException("中心消费确认绑定不符："+field);
            spool.consumed(evidence, permit.path("permitToken").asText());
            lease.ensureActive();
            if (!before.equals(captureResource(name, arguments))) throw new IOException("许可消费后资源改变，执行已拒绝");
            ObjectNode localExecutionBinding=consume.deepCopy();localExecutionBinding.put("toolName",name);localExecutionBinding.set("arguments",arguments.deepCopy());
            executionBinding.set(localExecutionBinding);
            executionEvidence.set(pendingImages);
            executionCapacity.set(capacity);
            try {
                result = executeAuthorized(name, argumentsJson);
                // 在offload、图片网络上传和afterHash之前保留全部结果，不能只保留首张图。
                pendingImages.add(capacity.preserveOutput(consume,result));
                // 控制事件/传输已经标为不确定时，只保全结果；不能用普通FAILED回执清掉已消费记录。
                lease.ensureEvidenceAllowed();
                // 内部预览的完整proof须进入正式回执；普通卸载提示不能代替预览正文。
                String text="snapshot__preview".equals(name)?result.text():getToolResultOffloader().offloadIfOversized(name,argumentsJson,result.text());
                result=new ToolOutput(text,result.imageParts(),result.successful(),result.discoveredUrls());
            }
            finally { executionBinding.remove();executionEvidence.remove();executionCapacity.remove();controlEvidence.remove(); }
            ObjectNode receipt = lease.identity();
            receipt.put("toolCallId", callId); receipt.put("permitToken", permit.path("permitToken").asText());
            if(modelCallHashes.get()!=null)receipt.put("modelToolCallIdHash",modelCallHashes.get());
            receipt.put("eventId", UUID.randomUUID().toString()); receipt.put("status", result.successful() ? "COMPLETED" : "FAILED");
            receipt.put("beforeHash", before); receipt.put("afterHash", captureResource(name, arguments));
            receipt.set("result", receiptResult(result,consume,pendingImages));
            spool.receipt(evidence, receipt);
            JsonNode acknowledgement = center.post("/paicli/runner/v1/receipts", receipt, operationKey(lease.taskId(), callId, "receipt"));
            ReceiptAcknowledgement.require(acknowledgement,receipt);
            spool.acknowledged(evidence);
            // 中心已确认后清理派生原图；清理失败保留本机资料，不能否定已确认的正式结果。
            for(Path pending:pendingImages)try{imageEvidence.acknowledged(pending);}catch(IOException cleanup){ /* 仍可由人工对账清理。 */ }
            return result;
        } catch (Exception failure) {
            // 已执行但回执失败时必须停住租约，不能让 Agent 当成可重试的普通工具错误。
            if (evidence != null) lease.invalidate();
            return ToolOutput.failure(result == null ? "中心执行边界拒绝：" + failure.getMessage() : "工具已执行，回执尚未获中心确认；任务已暂停对账");
        } finally { if(capacity!=null)capacity.close();lock.unlock(); }
    }
    private ObjectNode prepareSnapshotRestore(String originalCallId, String originalHash, ObjectNode originalArguments, JsonNode response) throws IOException {
        if (!originalCallId.equals(response.path("toolCallId").asText()) || !originalHash.equals(response.path("originalArgsHash").asText())
                || !lease.identity().path("version").asText().equals(response.path("version").asText())
                || !"snapshot__preview".equals(response.path("previewToolName").asText())) throw new IOException("中心快照选择不属于当前恢复提案");
        JsonNode selected = response.path("snapshotContext");
        ObjectNode previewArguments = CanonicalJson.MAPPER.createObjectNode();
        for (String field : List.of("snapshotMetadataId", "snapshotVersion")) {
            if (!selected.path(field).asText().matches("[1-9][0-9]{0,18}")) throw new IOException("中心快照记录或版本无效");
            previewArguments.set(field, selected.path(field));
        }
        String commit = selected.path("snapshotId").asText();
        if (!commit.matches("[a-f0-9]{40}") || (selected.has("commitId") && !commit.equals(selected.path("commitId").asText()))) throw new IOException("中心快照commit不一致");
        previewArguments.put("snapshotId", commit);
        String previewCallId = UUID.randomUUID().toString(), priorCallId = callIds.get();
        callIds.set(previewCallId);
        ToolOutput previewOutput;
        try { previewOutput = executeToolOutput("snapshot__preview", previewArguments.toString()); }
        finally { if (priorCallId == null) callIds.remove(); else callIds.set(priorCallId); }
        lease.ensureActive();
        if (!previewOutput.successful()) throw new IOException("实际快照预览或回执未完成：" + previewOutput.text());
        JsonNode body = CanonicalJson.parseObject(previewOutput.text()), proof = body.path("restorePreview");
        for (String field : List.of("snapshotMetadataId", "snapshotVersion")) if (!selected.path(field).asText().equals(body.path(field).asText())) throw new IOException("实际预览记录或版本不同于中心选定目标");
        if (!commit.equals(body.path("commitId").asText()) || !commit.equals(proof.path("snapshotId").asText())
                || !proof.isObject() || !proof.path("files").isArray() || !proof.path("canRestore").isBoolean()
                || !proof.path("canRestore").booleanValue()) throw new IOException("实际预览目标不符或存在恢复冲突");
        String current = proof.path("currentFingerprint").asText();
        if (!current.matches("[a-f0-9]{64}") || !current.equals(body.path("currentFingerprint").asText())) throw new IOException("实际快照预览缺少一致的当前工作区指纹");
        ObjectNode frozen = originalArguments.deepCopy();
        for (String field : List.of("snapshotMetadataId", "snapshotId", "snapshotVersion")) frozen.set(field, previewArguments.path(field));
        frozen.put("previewToolCallId", previewCallId); frozen.put("currentFingerprint", current); frozen.set("restorePreview", proof.deepCopy());
        return frozen;
    }
    private JsonNode receiptResult(ToolOutput result,ObjectNode consumedBinding,java.util.List<Path> pendingImages)throws IOException{
        ObjectNode serialized=CanonicalJson.MAPPER.valueToTree(result);
        if(!result.hasImageParts())return serialized;
        var parts=CanonicalJson.MAPPER.createArrayNode();int index=0;
        for(JsonNode part:serialized.path("imageParts")){
            if("image_base64".equals(part.path("type").asText())){
                ObjectNode upload=consumedBinding.deepCopy();upload.set("part",part);
                JsonNode reference=center.post("/paicli/runner/v1/tool-outputs/images",upload,operationKey(lease.taskId(),consumedBinding.path("toolCallId").asText(),"image-"+index));
                if(!"image_artifact".equals(reference.path("type").asText())||!reference.path("artifactId").asText().matches("[1-9][0-9]{0,18}"))throw new IOException("中心图片产物未确认");
                imageReferences.put(imageKey(part.path("imageBase64").asText(),part.path("mimeType").asText()),reference.deepCopy());
                parts.add(reference);
            }else parts.add(part);
            index++;
        }
        serialized.set("imageParts",parts);return serialized;
    }
    public JsonNode imageArtifactReference(LlmClient.ContentPart part){
        if(part==null||!"image_base64".equals(part.type()))return null;
        try{JsonNode reference=imageReferences.get(imageKey(part.imageBase64(),part.mimeType()));return reference==null?null:reference.deepCopy();}
        catch(IOException invalid){return null;}
    }
    private String imageKey(String base64,String mime)throws IOException{
        try{return CanonicalJson.sha256(java.util.Base64.getDecoder().decode(base64))+":"+(mime==null?"":mime.toLowerCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException invalid){throw new IOException("工具图片base64非法",invalid);}
    }
    private String captureResource(String tool,JsonNode args)throws IOException{
        if("load_skill".equals(tool)){
            String fingerprint=centralSkillResources.get(args.path("name").asText());
            if(fingerprint==null)throw new IOException("Skill未在本轮已批准上下文中启用");return fingerprint;
        }
        String path=args.path("path").asText();
        if("read_file".equals(tool)&&path.matches("\\.paicli/tool-outputs/center/[1-9][0-9]{0,18}\\.txt")){
            String id=path.substring(path.lastIndexOf('/')+1,path.length()-4);
            ObjectNode body=lease.identity();body.put("metadataOnly",true);
            JsonNode metadata=center.post("/paicli/runner/v1/tool-outputs/"+id+"/read",body,UUID.randomUUID().toString());
            String sha=metadata.path("sha256").asText();if(!sha.matches("[a-f0-9]{64}"))throw new IOException("中心输出指纹缺失");return sha;
        }
        return resources.capture(tool,args);
    }
    protected ToolOutput executeAuthorized(String name, String argumentsJson) {
        if ("snapshot__preview".equals(name)) return executeSnapshotPreview(argumentsJson);
        if (name.startsWith("control__")) {
            var handler = controlHandler.get();
            return handler == null ? ToolOutput.failure("控制操作缺少受控处理器") : handler.get();
        }
        return super.doExecuteTool(name, argumentsJson);
    }
    public ToolOutput executeControl(String operation, String argumentsJson, java.util.function.Supplier<ToolOutput> handler) {
        if (operation == null || !operation.matches("[A-Z][A-Z_]{1,63}")) return ToolOutput.failure("未知控制操作");
        controlHandler.set(java.util.Objects.requireNonNull(handler));
        callIds.set("control-" + lease.taskId() + "-" + operation);
        try { return executeToolOutput("control__" + operation, argumentsJson); }
        finally { controlHandler.remove(); callIds.remove(); }
    }
    /** 仅已消费许可的实际执行线程可给中心正式记忆等写入提供绑定。 */
    public ObjectNode currentExecutionBinding() {
        ObjectNode current = executionBinding.get();
        return current == null ? null : current.deepCopy();
    }
    /** 控制执行器须在上报正式事件前保全完整实际结果；此入口不能赋予许可或恢复执行。 */
    public void preserveCurrentOutput(ToolOutput output) throws IOException {
        ObjectNode binding=executionBinding.get();
        java.util.List<Path> pending=executionEvidence.get();
        PendingImageEvidence.Reservation capacity=executionCapacity.get();
        if(binding==null||pending==null||capacity==null||controlEvidence.get()!=null){
            lease.invalidate();throw new IOException("控制结果保全缺少已消费绑定或发生重复结果");
        }
        try{
            Path path=capacity.preserveOutput(binding,java.util.Objects.requireNonNull(output));
            pending.add(path);controlEvidence.set(path);
        }catch(IOException|RuntimeException failure){lease.invalidate();throw failure;}
    }
    public void updatePlanVersion(String version) {
        if(version==null||!version.matches("[0-9]{1,18}"))throw new IllegalArgumentException("无效的中心计划版本");
        planVersion=version;
    }
    static String operationKey(String taskId, String callId, String operation) {
        return CanonicalJson.sha256((taskId + "\n" + callId + "\n" + operation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private ObjectNode binding(String callId, String argsHash, String resourceHash) {
        ObjectNode body = lease.identity(); body.put("toolCallId", callId); body.put("argsHash", argsHash);
        if(modelCallHashes.get()!=null)body.put("modelToolCallIdHash",modelCallHashes.get());
        body.put("resourceHash", resourceHash); body.put("planVersion", planVersion); return body;
    }
    private void verifyPermit(JsonNode permit, String argsHash, String resourceHash) throws IOException {
        if (!"PERMITTED".equals(permit.path("decision").asText()) || permit.path("permitToken").asText().isBlank()) {
            StringBuilder message=new StringBuilder("中心未许可工具执行");
            String decision=permit.path("approvalDecision").asText();String feedback=permit.path("feedback").asText();
            if(!decision.isBlank())message.append("；审批决定：").append(decision);
            if(!feedback.isBlank())message.append("；反馈：").append(feedback);
            if("REVISED".equals(decision)){
                JsonNode revised=permit.path("revisedArguments");String revisedHash=permit.path("revisedArgsHash").asText();
                if(!revised.isObject()||!CanonicalJson.hash(revised).equals(revisedHash))throw new IOException("中心修订意见参数摘要不符，执行保持拒绝");
                String candidate=revised.toString();
                message.append("；候选参数仅作修改意见，需要新toolCallId、新参数摘要及重新批准：");
                if(candidate.length()<=16000)message.append(candidate);
                else message.append(candidate,0,16000).append("…（仅预览；完整参数见中心审批 ").append(permit.path("approvalId").asText()).append("，sha256=").append(revisedHash).append("）");
            }
            throw new IOException(message.toString());
        }
        for (String name : new String[]{"policyVersion", "fencingToken"})
            if (!lease.claim().path(name).asText().equals(permit.path(name).asText())) throw new IOException("工具许可 " + name + " 不符");
        if (!argsHash.equals(permit.path("argsHash").asText()) || !resourceHash.equals(permit.path("resourceHash").asText())
                || !planVersion.equals(permit.path("planVersion").asText()) || !RunnerLease.expiry(permit.path("expiresAt")).isAfter(Instant.now())) throw new IOException("工具许可参数、资源、计划版本或有效期不符");
    }
    /** 模型编号仅作关联；每次实际调用有独立中心ID，不能借provider重复ID重放已消费许可。 */
    @Override protected ToolOutput executeInvocation(ToolInvocation invocation) {
        callIds.set(UUID.randomUUID().toString());
        if(invocation.id()!=null&&!invocation.id().isBlank())modelCallHashes.set(CanonicalJson.sha256(invocation.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try { return executeToolOutput(invocation.name(), invocation.argumentsJson()); }
        finally { callIds.remove();modelCallHashes.remove(); }
    }
}
