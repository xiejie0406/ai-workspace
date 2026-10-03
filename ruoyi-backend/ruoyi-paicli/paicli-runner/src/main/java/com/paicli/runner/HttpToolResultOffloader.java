package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolResultOffloader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** 完整工具输出保存在中心artifact；虚拟文件读回不写受控工作区。 */
public final class HttpToolResultOffloader extends ToolResultOffloader {
    private final CenterHttpClient center; private final RunnerLease lease; private final CenterToolRegistry tools;
    private final Path pending;
    public HttpToolResultOffloader(CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools, Path cache) {
        super(cache, true, 32000); this.center = center; this.lease = lease; this.tools = tools;
        this.pending = cache.getParent().resolve("pending-derived-outputs").toAbsolutePath().normalize();
    }
    @Override public String offloadIfOversized(String tool, String args, String text) {
        return text == null || text.length() <= thresholdChars() ? text : offload(tool, text, 2000);
    }
    @Override public String offload(String tool, String text, int head) {
        try {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            String sha = CanonicalJson.sha256(raw);
            JsonNode stored = uploadContent(tool, text);
            String id = stored.path("artifactId").asText();
            if (!id.matches("[0-9]+") || !sha.equalsIgnoreCase(stored.path("sha256").asText())) throw new IOException("中心工具输出摘要未确认");
            String path = ".paicli/tool-outputs/center/" + id + ".txt";
            int preview = Math.min(Math.max(1, head), 8000);
            return "[工具输出过大，完整内容已保存中心artifact]\n原始大小: " + text.length() + " 字符 / " + text.lines().count() + " 行\nsha256: " + sha
                    + "\n文件: " + path + "\n读回: read_file {\"path\":\"" + path + "\",\"offset\":1,\"limit\":200}\n--- 开头预览 ---\n"
                    + text.substring(0, Math.min(text.length(), preview)) + "\n--- 结尾预览 ---\n" + text.substring(Math.max(0, text.length() - 800));
        } catch (IOException failure) { lease.invalidate(); throw new UncheckedIOException("中心输出卸载未确认，停止执行", failure); }
    }
    /** 控制事件也可上传完整JSON，再上报小型产物引用；所有请求保留原consume身份。 */
    public JsonNode uploadContent(String tool, String text) throws IOException {
        lease.ensureEvidenceAllowed();ObjectNode binding=tools.currentExecutionBinding();if(binding==null)throw new IOException("输出卸载缺少已消费中心工具绑定");
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);String sha=CanonicalJson.sha256(raw);Path captured=persistEvidence(binding,tool,raw,sha);
        if(raw.length<1||raw.length>128L*1024*1024)throw new IOException("完整输出已保全，但超过128MB中心产物上限");
        binding.put("toolName",tool);JsonNode stored;
        if(raw.length<=4*1024*1024){ObjectNode input=binding.deepCopy();input.put("content",text);input.put("sha256",sha);stored=center.post("/paicli/runner/v1/tool-outputs",input,UUID.randomUUID().toString());}
        else{
            ObjectNode begin=binding.deepCopy();begin.put("size",raw.length);begin.put("sha256",sha);JsonNode upload=center.post("/paicli/runner/v1/tool-outputs/chunks/begin",begin,UUID.randomUUID().toString());String id=upload.path("uploadId").asText();
            if(!id.matches("[1-9][0-9]{0,18}")||!sha.equals(upload.path("sha256").asText())||upload.path("size").asLong()!=raw.length)throw new IOException("中心分块上传冻结参数未确认");
            for(int offset=0,index=0;offset<raw.length;offset+=4*1024*1024,index++){
                lease.ensureEvidenceAllowed();byte[] chunk=java.util.Arrays.copyOfRange(raw,offset,Math.min(raw.length,offset+4*1024*1024));String hash=CanonicalJson.sha256(chunk);ObjectNode part=binding.deepCopy();part.put("index",index);part.put("sha256",hash);part.put("base64",java.util.Base64.getEncoder().encodeToString(chunk));
                JsonNode ack=center.post("/paicli/runner/v1/tool-outputs/"+id+"/chunks",part,UUID.randomUUID().toString());if(ack.path("index").asInt(-1)!=index||!hash.equals(ack.path("sha256").asText())||ack.path("size").asLong()!=chunk.length)throw new IOException("中心输出分块确认不符");
            }
            stored=center.post("/paicli/runner/v1/tool-outputs/"+id+"/complete",binding,UUID.randomUUID().toString());
        }
        if(!stored.path("artifactId").asText().matches("[1-9][0-9]{0,18}")||!sha.equals(stored.path("sha256").asText())||stored.path("size").asLong()!=raw.length)throw new IOException("中心完整输出摘要或容量未确认");
        java.nio.file.Files.deleteIfExists(captured);java.nio.file.Files.deleteIfExists(Path.of(captured.toString()+".json"));return stored;
    }
    private Path persistEvidence(ObjectNode binding, String tool, byte[] text, String sha) throws IOException {
        RunnerPrivateDirectory.createDirectories(pending);
        ObjectNode metadata = lease.identity();
        metadata.put("toolCallId", binding.path("toolCallId").asText()); metadata.put("toolName", tool); metadata.put("sha256", sha); metadata.put("capturedAt", java.time.Instant.now().toString()); metadata.put("bytes", text.length);
        ObjectNode key = metadata.deepCopy(); key.remove("capturedAt"); String name = CanonicalJson.hash(key);
        Path target = pending.resolve(name + ".txt");
        atomicEvidence(target, text); atomicEvidence(Path.of(target.toString() + ".json"), CanonicalJson.MAPPER.writeValueAsBytes(metadata));
        return target;
    }
    private void atomicEvidence(Path target, byte[] bytes) throws IOException {
        if (java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            RunnerPrivateDirectory.requireRegularFile(target);
            if (!java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(target))) throw new IOException("待对账证据冲突");
            return;
        }
        Path temporary = RunnerPrivateDirectory.createTempFile(pending, "capture-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            RunnerPrivateDirectory.requireDirectory(pending); RunnerPrivateDirectory.requireRegularFile(temporary);
            java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            RunnerPrivateDirectory.requireRegularFile(target);
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
    }
    public String read(String path, int offset, int limit) throws IOException {
        lease.ensureActive();
        if (!path.matches("\\.paicli/tool-outputs/center/[0-9]+\\.txt")) throw new IOException("中心输出引用无效");
        String id = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
        ObjectNode body = tools.currentExecutionBinding();
        if (body == null) throw new IOException("中心输出读取缺少已消费许可");
        body.put("offset", offset); body.put("limit", limit);
        JsonNode result = center.post("/paicli/runner/v1/tool-outputs/" + id + "/read", body, UUID.randomUUID().toString());
        if (!result.path("sha256").asText().matches("[a-fA-F0-9]{64}")) throw new IOException("中心输出摘要缺失");
        return result.path("text").asText();
    }
}
