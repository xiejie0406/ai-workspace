package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runner.*;
import com.paicli.tool.ToolOutput;
import java.nio.file.Path;
import java.util.UUID;

/** 复用原项目事实扫描；项目规则正式内容仅在中心保存。 */
public final class CenterProjectMemoryControl implements NativeControlExecutor.Extension {
    @Override public boolean supports(String operation) { return "INIT_PROJECT_MEMORY".equals(operation); }
    @Override public ToolOutput execute(String operation, JsonNode arguments, JsonNode context,
                                       CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools) throws Exception {
        lease.ensureActive();
        String content = ProjectMemoryInitializer.generate(Path.of(tools.getProjectPath()));
        ObjectNode event = lease.identity(); String id = UUID.randomUUID().toString(); event.put("eventId", id); event.put("type", "PROJECT_MEMORY");
        ObjectNode payload = event.putObject("payload"); payload.put("content", content); payload.put("force", arguments.path("force").asBoolean());
        payload.put("sha256", CanonicalJson.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        NativeControlExecutor.sendControlEvent(center, lease, tools, "PROJECT_MEMORY", payload);
        return ToolOutput.text("项目规则已扫描并保存中心");
    }
}
