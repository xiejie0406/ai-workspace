package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.mcp.transport.StreamableHttpTransport;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** 托管MCP每次STDIO RPC都要当前消费许可；HTTP在HeaderProvider内核验同一边界。 */
final class ManagedMcpTransport implements McpTransport {
    static final int MAX_BLOCKS = 128;
    static final int MAX_IMAGES = 10;
    static final long MAX_TYPED_BYTES = 64L * 1024 * 1024;
    static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
    static final int MAX_LOG_BYTES = 1024 * 1024;
    private static final List<String> DISCOVERY = List.of("initialize", "tools/list", "resources/list", "prompts/list");
    private static final java.util.Set<String> STRUCTURE_KEYS = java.util.Set.of("jsonrpc", "id", "result", "error", "code", "message",
            "content", "contents", "messages", "type", "mimeType", "data", "tools", "resources", "prompts", "name", "uri", "method");
    private static final java.util.Set<String> STRUCTURE_VALUES = java.util.Set.of("type", "mimeType", "name", "uri", "method");
    private final java.util.concurrent.ConcurrentHashMap<String,String> pendingMethods = new java.util.concurrent.ConcurrentHashMap<>();
    private long notificationBytes;
    private final McpTransport delegate;
    private final ManagedMcpGrant grant;
    ManagedMcpTransport(McpTransport delegate, ManagedMcpGrant grant) {
        this.delegate = delegate; this.grant = grant;
        if (delegate instanceof com.paicli.mcp.transport.StdioTransport stdio)
            stdio.onFailure(incomplete -> grant.uncertainExecution());
    }
    @Override public void send(JsonNode message) throws IOException {
        try {
            if (!(delegate instanceof StreamableHttpTransport)) grant.current();
            String method = message.path("method").asText();
            int responseLimit=DISCOVERY.contains(method)?MAX_METADATA_BYTES:(int)MAX_TYPED_BYTES;
            if(delegate instanceof StreamableHttpTransport http)http.setResponseLimit(responseLimit);
            if(delegate instanceof com.paicli.mcp.transport.StdioTransport stdio)stdio.setResponseLimit(responseLimit);
            if(message.has("id"))pendingMethods.put(message.get("id").toString(),method);
            delegate.send(message);
        } catch (IOException | RuntimeException incomplete) {
            grant.uncertainExecution();
            throw new IOException("MCP执行结果不完整，租约已停止，需要中心对账；不会自动重执行");
        }
    }
    @Override public void onReceive(Consumer<JsonNode> listener) {
        delegate.onReceive(message -> {
            try {
                String method=message.has("id")?pendingMethods.remove(message.get("id").toString()):"";
                if(method != null && DISCOVERY.contains(method) && jsonBytes(message)>MAX_METADATA_BYTES) throw new IllegalArgumentException("MCP元数据超过支持范围");
                if(!message.has("id")) { synchronized(this){notificationBytes+=jsonBytes(message);if(notificationBytes>MAX_LOG_BYTES)throw new IllegalArgumentException("MCP通知日志超过支持范围");} }
                validateBlocks(message, new int[2], 0);
                listener.accept(redact(message));
            } catch (RuntimeException incomplete) {
                var failure = CanonicalJson.MAPPER.createObjectNode().put("jsonrpc", "2.0");
                if (message != null && message.has("id")) failure.set("id", message.get("id"));
                var error = failure.putObject("error").put("code", -32098).put("message", "MCP_RESULT_INCOMPLETE：结果无法完整接收，租约已停止，需要中心对账");
                // Reader线程不能伪造消费ThreadLocal；完整安全原文随异常回调用线程保全。
                var evidence = new StringBuilder(); appendEvidence(message, evidence);
                error.putObject("data").put("mcpIncompleteEvidence", evidence.toString())
                        .put("evidenceFormat", "SAFE_JSON_TEXT_PRESERVES_DUPLICATE_REDACTED_KEYS");
                grant.uncertainExecution();
                listener.accept(failure);
            }
        });
    }
    private void appendEvidence(JsonNode node, StringBuilder target) {
        if (node == null) { target.append("null"); return; }
        if (node.isTextual()) { target.append(CanonicalJson.MAPPER.getNodeFactory().textNode(grant.redact(node.asText()))); return; }
        if (node.isObject()) {
            target.append('{'); var fields = node.fields(); boolean comma = false;
            while (fields.hasNext()) {
                var entry = fields.next(); if (comma) target.append(','); comma = true;
                String key = STRUCTURE_KEYS.contains(entry.getKey()) ? entry.getKey() : grant.redact(entry.getKey());
                target.append(CanonicalJson.MAPPER.getNodeFactory().textNode(key)).append(':');
                appendEvidence(entry.getValue(), target);
            }
            target.append('}'); return;
        }
        if (node.isArray()) { target.append('['); boolean comma = false; for (JsonNode item : node) { if (comma) target.append(','); comma = true; appendEvidence(item, target); } target.append(']'); return; }
        target.append(node);
    }
    static void preserveIncompleteException(com.paicli.mcp.jsonrpc.JsonRpcException incomplete, CenterToolRegistry tools) throws IOException {
        JsonNode data = incomplete.data();
        if (data != null && data.path("mcpIncompleteEvidence").isTextual())
            tools.preserveCurrentOutput(com.paicli.tool.ToolOutput.text(data.path("mcpIncompleteEvidence").asText()));
    }
    private void validateBlocks(JsonNode node, int[] counts, int depth) {
        if (node == null || depth > 128) throw new IllegalArgumentException("MCP结果层级超过支持范围");
        if (node.isObject()) {
            if ("image".equals(node.path("type").asText()) && ++counts[1] > MAX_IMAGES)
                throw new IllegalArgumentException("MCP图片数量超过支持范围");
            node.fields().forEachRemaining(entry -> {
                if(List.of("uri","mimeType","type").contains(entry.getKey()) && entry.getValue().isTextual() && entry.getValue().asText().length()>2048)
                    throw new IllegalArgumentException("MCP结构标签超过支持范围");
                if (List.of("content", "contents", "messages").contains(entry.getKey()) && entry.getValue().isArray()) {
                    counts[0] += entry.getValue().size();
                    if (counts[0] > MAX_BLOCKS) throw new IllegalArgumentException("MCP内容块超过支持范围");
                }
                validateBlocks(entry.getValue(), counts, depth + 1);
            });
        } else if (node.isArray()) node.forEach(value -> validateBlocks(value, counts, depth + 1));
    }
    private JsonNode redact(JsonNode node) {
        if (node.isTextual()) return CanonicalJson.MAPPER.getNodeFactory().textNode(grant.redact(node.asText()));
        if (node.isObject()) { var copy = CanonicalJson.MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String key = grant.redact(entry.getKey());
                if (STRUCTURE_KEYS.contains(entry.getKey()) && !key.equals(entry.getKey()))
                    throw new IllegalArgumentException("MCP结构字段脱敏后语义改变");
                if (copy.has(key)) throw new IllegalArgumentException("MCP秘密键脱敏后存在歧义");
                if (STRUCTURE_VALUES.contains(entry.getKey()) && entry.getValue().isTextual()
                        && !grant.redact(entry.getValue().asText()).equals(entry.getValue().asText()))
                    throw new IllegalArgumentException("MCP结构标识脱敏后语义改变");
                copy.set(key, redact(entry.getValue()));
            }); return copy; }
        if (node.isArray()) { var copy = CanonicalJson.MAPPER.createArrayNode(); node.forEach(value -> copy.add(redact(value))); return copy; }
        return node.deepCopy();
    }
    @Override public List<String> stderrLines() {
        List<String> lines=delegate.stderrLines().stream().map(grant::redact).toList();
        if(jsonBytes(CanonicalJson.MAPPER.valueToTree(lines))>MAX_LOG_BYTES){grant.uncertainExecution();throw new IllegalStateException("MCP_RESULT_INCOMPLETE：日志超过支持范围，已暂停对账");}return lines;
    }
    @Override public Long processId() { return delegate.processId(); }
    @Override public String transportName() { return delegate.transportName(); }
    static com.paicli.tool.ToolOutput requireBounded(com.paicli.tool.ToolOutput output) throws IOException {
        long bytes = output.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (output.imageParts().size() > MAX_IMAGES) throw new IOException("MCP图片数量超过支持范围");
        for (var image : output.imageParts()) {
            bytes += image.imageBase64() == null ? 0 : image.imageBase64().length();
            if (bytes > MAX_TYPED_BYTES) throw new IOException("MCP聚合结果超过支持范围");
        }
        if (bytes > MAX_TYPED_BYTES) throw new IOException("MCP聚合结果超过支持范围");
        return output;
    }
    static com.paicli.tool.ToolOutput strictOutput(com.paicli.mcp.protocol.McpCallToolResult result, ManagedMcpGrant grant) throws IOException {
        return strictOutput(result, grant, output -> {});
    }
    @FunctionalInterface interface OutputEvidence { void save(com.paicli.tool.ToolOutput output) throws IOException; }
    static com.paicli.tool.ToolOutput strictOutput(com.paicli.mcp.protocol.McpCallToolResult result, ManagedMcpGrant grant, OutputEvidence evidence) throws IOException {
        com.paicli.tool.ToolOutput output = null;
        try { output = result.toToolOutputStrict(); return requireBounded(output); }
        catch (IOException | RuntimeException incomplete) {
            // 执行已发生，转换错误也不能丢弃实际已收到的完整原结果。
            try { evidence.save(output == null ? com.paicli.tool.ToolOutput.text(CanonicalJson.MAPPER.valueToTree(result).toString()) : output); }
            finally { grant.uncertainExecution(); }
            throw new IOException("MCP图片或工具结果无法完整接收，租约已停止，需要中心对账；不会自动重执行");
        }
    }
    static <T> T requireMetadata(T value) throws IOException {
        if(jsonBytes(CanonicalJson.MAPPER.valueToTree(value))>MAX_METADATA_BYTES)throw new IOException("MCP派生元数据超过支持范围");return value;
    }
    static int jsonBytes(JsonNode value) { return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
    @Override public void close() {
        // grant到期后不再用旧header做DELETE；所有关闭都先停止本地进程/HTTP调用。
        grant.close(); closeLocal(delegate);
    }
    /** 显式中心STOP/RESTART可发远端DELETE；过期回调始终只closeLocal。 */
    void closeApproved() throws IOException {
        grant.current();
        try { delegate.close(); } finally { grant.close(); }
    }
    static void closeLocal(McpTransport transport) {
        if (transport instanceof StreamableHttpTransport http) http.closeLocal(); else transport.close();
    }
}
