package com.paicli.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.runner.CenterHttpClient;
import com.paicli.runner.CenterToolRegistry;
import com.paicli.runner.NativeControlExecutor;
import com.paicli.runner.NativeTaskExecutor;
import com.paicli.runner.RunnerLease;
import com.paicli.runner.CanonicalJson;
import com.paicli.tool.ToolOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 原 Harness 三路审查、Lead汇总和三份报告均经中心控制任务入口。 */
public final class CenterHarnessControl implements NativeControlExecutor.Extension {
    @Override public boolean supports(String operation) { return List.of("HARNESS_QUICK", "HARNESS_NORMAL", "HARNESS_INLINE").contains(operation); }
    @Override public ToolOutput execute(String operation, JsonNode arguments, JsonNode context,
                                       CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools) throws Exception {
        throw new java.io.IOException("中心Harness需要Runner明确派发私有缓存目录");
    }
    @Override public ToolOutput execute(String operation, JsonNode arguments, JsonNode context,
                                       CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools, Path derivedState) throws Exception {
        LlmClient client = NativeTaskExecutor.budgeted(center, lease, context, NativeTaskExecutor.managedProvider(center, lease, context));
        ConversationLedger ledger = ConversationLedger.central(lease.sessionId(), context.path("lastMessageSequence").asLong(), NativeTaskExecutor.ledgerEntries(context), entry -> {
            if (entry.message() != null && "user".equals(entry.message().role())) return;
            try { send(center, lease, "LEDGER_MESSAGE", NativeTaskExecutor.ledgerPayload(entry, tools)); }
            catch (Exception failure) { lease.invalidate(); throw new IllegalStateException("中心Harness审查轨迹未确认", failure); }
        });
        String depthName=arguments.path("depth").asText("HARNESS_QUICK".equals(operation)?"QUICK":"NORMAL");
        if(!List.of("QUICK","NORMAL").contains(depthName))throw new java.io.IOException("中心Harness审查深度非法");
        var depth=BetterHarnessOptions.Depth.valueOf(depthName);
        boolean inline=arguments.path("inline").asBoolean("HARNESS_INLINE".equals(operation));
        if(inline!= "HARNESS_INLINE".equals(operation))throw new java.io.IOException("中心Harness选项与冻结操作不一致");
        BetterHarnessOptions options = new BetterHarnessOptions(depth, inline);
        var runner = new BetterHarnessRunner(client, Path.of(tools.getProjectPath()), ledger, NativeTaskExecutor.approvedSkills(context), derivedState.resolve("harness-cache"));
        var result = runner.run(options);
        ObjectNode payload = CanonicalJson.MAPPER.createObjectNode();
        payload.set("harnessId", arguments.path("harnessId")); payload.put("mode", operation); payload.put("reportMarkdown", result.reportMarkdown());
        payload.put("findingCount", result.findingCount()); payload.put("durable", result.durable());
        if (result.durable()) {
            payload.put("reportHtml", Files.readString(result.reportHtmlPath()));
            payload.set("findings", CanonicalJson.MAPPER.readTree(Files.readString(result.findingsJsonPath())));
            payload.put("markdownSha256", CanonicalJson.sha256(Files.readAllBytes(result.reportMarkdownPath())));
            payload.put("htmlSha256", CanonicalJson.sha256(Files.readAllBytes(result.reportHtmlPath())));
        }
        NativeControlExecutor.sendControlEvent(center, lease, tools, "HARNESS_RESULT", payload);
        lease.ensureActive(); return ToolOutput.text(result.reportMarkdown());
    }
    private static void send(CenterHttpClient center, RunnerLease lease, String type, JsonNode payload) throws Exception {
        lease.ensureEvidenceAllowed(); ObjectNode body = lease.identity(); String id = UUID.randomUUID().toString();
        body.put("eventId", id); body.put("type", type); body.set("payload", payload); center.post("/paicli/runner/v1/events", body, id);
    }
}
