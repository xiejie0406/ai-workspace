package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;

/** 保留原Renderer语义，由中心事件送达终端；不在Runner收集或批准用户决定。 */
final class NativeEventRenderer implements Renderer {
    private final PrintStream output;
    private final Consumer<ObjectNode> events;
    NativeEventRenderer(PrintStream output, Consumer<ObjectNode> events) { this.output = output; this.events = events; }
    public void start() { }
    public void close() { }
    public PrintStream stream() { return output; }
    public boolean supportsThinkingPanel() { return true; }
    private ObjectNode event(String type) { output.flush(); return CanonicalJson.MAPPER.createObjectNode().put("presentation", type); }
    public void beginThinking(String label) { events.accept(event("THINKING_BEGIN").put("label", label)); }
    public void appendThinking(String delta) { events.accept(event("THINKING_DELTA").put("delta", delta)); }
    public void endThinking() { events.accept(event("THINKING_END")); }
    public void appendAssistantContentDelta(String delta) { events.accept(event("ASSISTANT_DELTA").put("delta", delta)); }
    public void finishAssistantContent() { events.accept(event("ASSISTANT_END")); }
    public void appendToolCalls(List<LlmClient.ToolCall> calls) { var event = event("TOOL_CALLS"); event.set("toolCalls", CanonicalJson.MAPPER.valueToTree(calls)); events.accept(event); }
    public void appendDiff(String path, String before, String after) { events.accept(event("DIFF").put("path", path).put("before", before).put("after", after)); }
    public void updateStatus(StatusInfo status) { var event = event("STATUS"); event.set("status", CanonicalJson.MAPPER.valueToTree(status)); events.accept(event); }
    public ApprovalResult promptApproval(ApprovalRequest request) { throw new IllegalStateException("Runner审批必须经中心许可，不能在执行进程交互批准"); }
    public int openPalette(String title, List<String> items) { throw new IllegalStateException("选择列表属于终端交互"); }
}
