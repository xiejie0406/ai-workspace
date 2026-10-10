package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SideGitManager;
import com.paicli.tool.ToolOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 模型恢复只使用中心选定、预览并重新批准的commit；原offset不能选择本地快照。 */
public final class NativeSnapshotAdapter {
    private final CenterHttpClient center;
    private final RunnerLease lease;
    private final CenterToolRegistry tools;
    private final SideGitManager snapshots;
    private final ResourceFingerprint resources;
    public NativeSnapshotAdapter(CenterHttpClient center,RunnerLease lease,CenterToolRegistry tools,SideGitManager snapshots)throws IOException {
        this.center=center;this.lease=lease;this.tools=tools;this.snapshots=snapshots;
        this.resources=new ResourceFingerprint(Path.of(tools.getProjectPath()));
    }
    public void install() {tools.setSnapshotPreviewer(this::preview);tools.setSnapshotRestorer(this::restore);}
    public ToolOutput preview(String argumentsJson) {
        boolean actualPreserved=false;
        try {
            lease.ensureActive();ObjectNode binding=requiredBinding("snapshot__preview");JsonNode arguments=CanonicalJson.parseObject(argumentsJson);
            requireFrozenArguments(binding,arguments);
            requireNumber(arguments,"snapshotMetadataId");requireNumber(arguments,"snapshotVersion");
            ObjectNode result=CanonicalJson.MAPPER.createObjectNode();ObjectNode proof=actualPreview(snapshots,resources,arguments);
            result.set("restorePreview",proof);result.put("currentFingerprint",proof.path("currentFingerprint").asText());result.put("commitId",requiredCommit(arguments));
            result.set("snapshotMetadataId",arguments.path("snapshotMetadataId"));result.set("snapshotVersion",arguments.path("snapshotVersion"));
            String full=result.toString();boolean largeDiff=false;
            for(JsonNode file:proof.path("files"))if(file.path("diff").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64*1024){largeDiff=true;break;}
            if(full.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4*1024*1024||largeDiff) {
                // 裸JSON产物保留全部真实diff；回执保留可核对的六字段proof而非卸载提示字符串。
                tools.preserveCurrentOutput(ToolOutput.text(full));
                actualPreserved=true;
                if(!(tools.getToolResultOffloader() instanceof HttpToolResultOffloader offloader))throw new IOException("大快照预览缺少中心产物通道");
                JsonNode stored=offloader.uploadContent("snapshot__preview",full);
                ObjectNode projection=proof.deepCopy();projection.set("files",NativeControlExecutor.snapshotProof(proof.path("files")));result.set("restorePreview",projection);
                result.set("previewArtifactId",stored.path("artifactId"));result.set("previewArtifactSha256",stored.path("sha256"));result.set("previewArtifactSize",stored.path("size"));
                if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw new IOException("快照预览proof超过16MiB回执支持范围，完整产物已保留");
            }
            return ToolOutput.text(result.toString());
        } catch(Exception failure) {if(actualPreserved)lease.invalidate();return ToolOutput.failure("中心快照预览未完成: "+failure.getMessage());}
    }
    public RestoreResult restore(int offset)throws Exception {
        lease.ensureActive();ObjectNode binding=requiredBinding("revert_turn");JsonNode frozen=binding.path("arguments");requireFrozenArguments(binding,frozen);
        JsonNode context;
        try {context=center.post("/paicli/runner/v1/revert-context",binding,java.util.UUID.randomUUID().toString());}
        catch(IOException unavailable){lease.invalidate();throw new IOException("中心冻结恢复上下文未确认，未执行恢复",unavailable);}
        for(String field:List.of("taskId","sessionId","directoryContextId","leaseId","fencingToken","version")) {
            String expected=lease.claim().path(field).asText();
            if(expected.isBlank()||!expected.equals(context.path(field).asText()))throw new IOException("中心恢复上下文不属于当前消费范围");
        }
        if(!binding.path("argsHash").asText().equals(context.path("argsHash").asText())||!frozen.equals(context.path("arguments")))throw new IOException("中心恢复参数没有绑定当前已批准调用");
        if(Math.max(1,frozen.path("offset").asInt(1))!=offset)throw new IOException("恢复序号与已批准参数不同");
        requireNumber(frozen,"snapshotMetadataId");requireNumber(frozen,"snapshotVersion");
        if(frozen.path("previewToolCallId").asText().isBlank())throw new IOException("恢复缺少已确认的中心预览回执");
        ObjectNode actual=actualPreview(snapshots,resources,frozen);JsonNode approved=frozen.path("restorePreview");
        if(!approved.path("canRestore").asBoolean(false)||!actual.path("canRestore").asBoolean(false)||!approved.path("files").isArray()
                ||!NativeControlExecutor.snapshotProof(approved.path("files")).equals(NativeControlExecutor.snapshotProof(actual.path("files")))
                ||!frozen.path("currentFingerprint").asText().equals(actual.path("currentFingerprint").asText()))throw new IOException("恢复目标或当前文件已改变，必须重新预览和批准");
        lease.ensureActive();
        if(!frozen.path("currentFingerprint").asText().equals(resources.capture("control__SNAPSHOT_RESTORE",frozen)))throw new IOException("恢复前工作区已改变，未覆盖用户文件");
        RestoreResult result;
        try {result=snapshots.restoreSnapshot(requiredCommit(frozen));}
        catch(Exception incomplete) {
            ObjectNode evidence=CanonicalJson.MAPPER.createObjectNode();evidence.put("status","RESTORE_RESULT_INCOMPLETE");evidence.set("approvedArguments",frozen);evidence.set("preview",actual);
            if(incomplete instanceof com.paicli.snapshot.SnapshotRestoreException restoreFailure)evidence.set("restoreFailure",CanonicalJson.MAPPER.valueToTree(restoreFailure.evidence()));
            try {tools.preserveCurrentOutput(ToolOutput.text(evidence.toString()));}finally{lease.invalidate();}
            throw new IOException("恢复结果不完整，已保留目标与预览证据，停止并等待中心对账",incomplete);
        }
        // 完整真实结果先保存，取消或租约变化不能丢掉已发生的文件恢复证据。
        tools.preserveCurrentOutput(ToolOutput.text(CanonicalJson.MAPPER.valueToTree(result).toString()));
        return result;
    }
    private ObjectNode requiredBinding(String name)throws IOException {
        ObjectNode binding=tools.currentExecutionBinding();if(binding==null||!name.equals(binding.path("toolName").asText()))throw new IOException("恢复能力缺少对应的已消费单次许可");return binding;
    }
    private static void requireFrozenArguments(ObjectNode binding,JsonNode arguments)throws IOException {
        if(!arguments.isObject()||!CanonicalJson.hash(arguments).equals(binding.path("argsHash").asText())||!arguments.equals(binding.path("arguments")))throw new IOException("快照执行参数未冻结到当前消费许可");
    }
    private static void requireNumber(JsonNode arguments,String key)throws IOException {if(!arguments.path(key).asText().matches("[1-9][0-9]{0,18}"))throw new IOException("缺少正式快照版本或记录ID");}
    private static String requiredCommit(JsonNode arguments)throws IOException {String id=arguments.path("snapshotId").asText();if(!id.matches("[a-f0-9]{40}"))throw new IOException("中心快照commit无效");return id;}
    static ObjectNode actualPreview(SideGitManager snapshots,ResourceFingerprint resources,JsonNode arguments)throws Exception {
        String id=requiredCommit(arguments),before=resources.capture("control__SNAPSHOT_RESTORE",arguments);
        ObjectNode preview=CanonicalJson.MAPPER.valueToTree(snapshots.previewRestore(id));
        String current=resources.capture("control__SNAPSHOT_RESTORE",arguments);if(!before.equals(current))throw new IOException("预览期间工作区已改变，请重新核对");
        preview.put("currentFingerprint",current);preview.put("snapshotId",id);
        var target=CanonicalJson.MAPPER.createArrayNode();
        for(JsonNode file:NativeControlExecutor.snapshotProof(preview.path("files"))) {
            if(file.path("targetHash").isNull())continue;ObjectNode row=CanonicalJson.MAPPER.createObjectNode();
            for(String field:List.of("path","targetHash","targetMode"))row.set(field,file.path(field));target.add(row);
        }
        preview.put("targetManifestHash",CanonicalJson.hash(target));return preview;
    }
}
