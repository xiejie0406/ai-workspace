package com.paicli.cli;

import com.paicli.render.PlainRenderer;
import com.paicli.runtime.center.CenterCommandGateway;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CenterCliTest {
    @Test void currentCleanWaitsForRealPreviewThenResubmitsSameCommandAtMostOnce()throws Exception {
        var gateway=new FakeGateway();gateway.response=new CenterCommandGateway.CommandResult(true,"preview queued",Map.of("cleanPreviewRequired",true,"taskId","99"));
        gateway.responses.add(new CenterCommandGateway.CommandResult(true,"clean queued",Map.of("taskId","100")));
        gateway.events=List.of(new CenterCommandGateway.Event(1,"TASK_COMPLETED",Map.of("taskId","99"),true),new CenterCommandGateway.Event(2,"TASK_COMPLETED",Map.of("taskId","100"),true));
        try(var renderer=renderer()){assertTrue(cli(gateway).dispatch("/snapshot clean current",renderer));}
        assertEquals(List.of("clean current","clean current"),gateway.arguments);
        var changed=new FakeGateway();changed.response=gateway.response;changed.responses.add(new CenterCommandGateway.CommandResult(true,"new preview",Map.of("cleanPreviewRequired",true,"taskId","100")));changed.events=gateway.events;
        try(var renderer=renderer()){assertFalse(cli(changed).dispatch("/snapshot clean current",renderer));}assertEquals(2,changed.commands.size());
    }
    @Test void allCleanUsesIndependentTaskCursorsAndNeverCurrentSessionEventsForOtherWorkspaces()throws Exception {
        var gateway=new FakeGateway();var rows=List.of(Map.of("id","51","sessionId","21","workspaceId","31","cleanPreviewRequired",true),Map.of("id","52","sessionId","22","workspaceId","32","cleanPreviewRequired",true));
        gateway.response=new CenterCommandGateway.CommandResult(true,"previews",Map.of("tasks",rows,"blocked",List.of()));
        gateway.responses.add(new CenterCommandGateway.CommandResult(true,"cleanup",Map.of("tasks",List.of(Map.of("id","61","sessionId","21","workspaceId","31"),Map.of("id","62","sessionId","22","workspaceId","32")),"blocked",List.of())));
        try(var renderer=renderer()){assertTrue(cli(gateway).dispatch("/snapshot clean all",renderer));}
        assertEquals(List.of("51:0","51:1","52:0","52:1","61:0","61:1","62:0","62:1"),gateway.taskPolls);assertEquals(0,gateway.polls);assertEquals(List.of("clean all","clean all"),gateway.arguments);
    }
    @Test void partiallyQueuedCleanDisplaysBlockedWorkspaceAndDoesNotRepeatAllAsIfCompleted()throws Exception {
        var gateway=new FakeGateway();gateway.response=new CenterCommandGateway.CommandResult(true,"partial",Map.of("tasks",List.of(Map.of("id","51","sessionId","21","workspaceId","31","cleanPreviewRequired",true)),"blocked",List.of(Map.of("workspaceId","32","reason","offline"))));var output=new ByteArrayOutputStream();
        try(var renderer=new PlainRenderer(new PrintStream(output),new BufferedReader(new StringReader("")))){assertFalse(cli(gateway).dispatch("/snapshot clean all",renderer));}
        assertEquals(1,gateway.commands.size());assertEquals(0,gateway.polls);assertTrue(output.toString().contains("offline"));assertTrue(output.toString().contains("32"));
    }
    @Test void verifiedMemoryListsAndSearchesWarnAfterThirtyDaysWithoutChangingFormalStatus()throws Exception {
        var now=java.time.Instant.parse("2026-10-03T00:00:00Z");
        var old=Map.<String,Object>of("id","1","lastVerifiedAt",now.minus(java.time.Duration.ofDays(31)).toString(),"metadata",Map.of("status","VERIFIED"));
        var boundary=Map.<String,Object>of("id","2","lastVerifiedAt",now.minus(java.time.Duration.ofDays(30)).toString(),"metadata",Map.of("status","VERIFIED"));
        var pending=Map.<String,Object>of("id","3","lastVerifiedAt",old.get("lastVerifiedAt"),"metadata",Map.of("status","PENDING"));
        var conflict=Map.<String,Object>of("id","4","lastVerifiedAt",old.get("lastVerifiedAt"),"metadata",Map.of("status","CONFLICT"));
        var invalid=Map.<String,Object>of("id","5","lastVerifiedAt","unknown","metadata",Map.of("status","VERIFIED"));
        assertEquals(List.of("1"),CenterCli.staleMemoryIds(Map.of("entries",List.of(old,boundary,pending,conflict,invalid)),now));
        var displayOld=new LinkedHashMap<String,Object>(old);displayOld.put("lastVerifiedAt",java.time.Instant.EPOCH.toString());
        var gateway=new FakeGateway();gateway.response=new CenterCommandGateway.CommandResult(true,"memory list",Map.of("entries",List.of(displayOld,pending)));var output=new ByteArrayOutputStream();
        try(var renderer=new PlainRenderer(new PrintStream(output),new BufferedReader(new StringReader("")))) {var cli=cli(gateway);assertTrue(cli.dispatch("/memory list",renderer));assertTrue(cli.dispatch("/memory search fact",renderer));}
        assertTrue(output.toString().contains("[可能已过时]"));assertTrue(output.toString().contains("PENDING"));assertEquals("VERIFIED",((Map<?,?>)old.get("metadata")).get("status"));
    }
    private static CenterCli cli(FakeGateway gateway) {
        return new CenterCli(gateway, new CenterCommandGateway.Session("10", "20", "30", "ask", "deepseek", "initial-model", "40", 0));
    }
    private static PlainRenderer renderer() {
        return new PlainRenderer(new PrintStream(new ByteArrayOutputStream()), new BufferedReader(new StringReader("n\n")));
    }
    @Test void bareAliasesNeverReachModelAndHistoryClearIsOnlyLocal() throws Exception {
        FakeGateway gateway = new FakeGateway(); var cli = cli(gateway);
        try (var renderer = renderer()) {
            assertTrue(cli.dispatch("clear", renderer)); assertTrue(cli.dispatch("cancel", renderer));
            assertTrue(cli.dispatch("/history clear", renderer)); assertTrue(cli.dispatch("quit", renderer));
        }
        assertEquals(List.of("clear", "cancel"), gateway.commands);
        assertEquals(0, gateway.submissions);
    }
    @Test void queuedControlUsesActualTaskIdAndIgnoresOtherTaskTerminal() throws Exception {
        FakeGateway gateway = new FakeGateway(); gateway.response = new CenterCommandGateway.CommandResult(true, "queued", Map.of("kind", "CONTROL", "id", "99", "state", "ENQUEUED"));
        gateway.events = List.of(new CenterCommandGateway.Event(1, "TASK_COMPLETED", Map.of("taskId", "98"), true),
                new CenterCommandGateway.Event(2, "TASK_FAILED", Map.of("taskId", "99"), true));
        try (var renderer = renderer()) { assertFalse(cli(gateway).dispatch("/index", renderer)); }
        assertEquals(1, gateway.polls); assertEquals(0, gateway.submissions);
    }
    @Test void planTurnTaskIdIsAwaitedAndOwnCompletionSucceeds() throws Exception {
        FakeGateway gateway = new FakeGateway(); gateway.response = new CenterCommandGateway.CommandResult(true, "queued", Map.of("taskId", "99"));
        gateway.events = List.of(new CenterCommandGateway.Event(1, "TASK_COMPLETED", Map.of("taskId", "99"), true));
        try (var renderer = renderer()) { assertTrue(cli(gateway).dispatch("/plan inspect code", renderer)); }
        assertEquals(1, gateway.polls);
    }
    @Test void confirmedModelChangeUpdatesCompleteModelIdAndRealContextWindow() throws Exception {
        FakeGateway gateway = new FakeGateway(); gateway.response = new CenterCommandGateway.CommandResult(true, "switched", Map.of("model", "actual/provider/full-model-id", "contextWindow", 256000, "approvalMode", "AUTO"));
        var cli = cli(gateway);
        try (var renderer = renderer()) { assertTrue(cli.dispatch("/model 41", renderer)); }
        assertEquals("actual/provider/full-model-id", cli.status().model()); assertEquals(256000, cli.status().contextWindow());
        assertFalse(cli.status().hitlEnabled());
    }
    @Test void failedModelChangeDoesNotAlterPresentation() throws Exception {
        FakeGateway gateway = new FakeGateway(); gateway.response = new CenterCommandGateway.CommandResult(false, "rejected", Map.of("model", "wrong", "contextWindow", 1));
        var cli = cli(gateway);
        try (var renderer = renderer()) { assertFalse(cli.dispatch("/model 41", renderer)); }
        assertEquals("initial-model", cli.status().model());
    }
    @Test void persistentPlanStatusIsNotReplacedByItsAutoApprovalPolicy() throws Exception {
        FakeGateway gateway=new FakeGateway();var cli=cli(gateway);
        gateway.response=new CenterCommandGateway.CommandResult(true,"actual",Map.of("mode","PLAN","approvalMode","AUTO"));
        gateway.events=List.of(new CenterCommandGateway.Event(1,"TASK_COMPLETED",Map.of("taskId","99"),true));
        try(var renderer=renderer()) {assertTrue(cli.dispatch("/status",renderer));assertTrue(cli.dispatch("plan remains selected",renderer));}
        assertEquals(List.of("plan"),gateway.executionModes);
        cli.updatePresentation(Map.of("mode","TEAM","approvalMode","ASK"));
        assertTrue(cli.status().sessionMode().startsWith("TEAM"));assertTrue(cli.status().hitlEnabled());
    }
    @Test void restoreWaitsForPreviewThenRequestsNewFrozenRestoreWithoutUnboundedRequeue() throws Exception {
        FakeGateway gateway=new FakeGateway();var cli=cli(gateway);
        gateway.response=new CenterCommandGateway.CommandResult(true,"preview queued",Map.of("previewRequired",true,"snapshotMetadataId","12","taskId","99"));
        gateway.events=List.of(new CenterCommandGateway.Event(1,"TASK_COMPLETED",Map.of("taskId","99"),true),new CenterCommandGateway.Event(2,"TASK_COMPLETED",Map.of("taskId","100"),true));
        gateway.responses.add(new CenterCommandGateway.CommandResult(true,"restore queued",Map.of("taskId","100")));
        try(var renderer=renderer()) {assertTrue(cli.dispatch("/restore 1",renderer));}
        assertEquals(List.of("1","id:12"),gateway.arguments);assertEquals(2,gateway.polls);
        var broken=new FakeGateway();broken.response=gateway.response;
        broken.events=gateway.events;broken.responses.add(new CenterCommandGateway.CommandResult(true,"still preview",Map.of("previewRequired",true,"snapshotMetadataId","12","taskId","100")));
        try(var renderer=renderer()) {assertFalse(cli(broken).dispatch("/restore 1",renderer));}
        assertEquals(2,broken.commands.size());assertEquals(0,broken.submissions);
    }
    @Test void plainRendererShowsThinkingAndActualControlPayloadWithFullArtifactReference() throws Exception {
        FakeGateway gateway=new FakeGateway();var cli=cli(gateway);var output=new ByteArrayOutputStream();
        try(var renderer=new PlainRenderer(new PrintStream(output),new BufferedReader(new StringReader("n\n")))) {
            cli.presentEvent("MODEL_DELTA",Map.of("presentation","THINKING_DELTA","delta","真实推理"),renderer);
            cli.presentEvent("CONNECTION_RESULT",Map.of("tools",List.of(Map.of("name","actual_tool")),"eventArtifactId","65","eventArtifactSha256","abc"),renderer);
        }
        String text=output.toString();assertTrue(text.contains("真实推理"));assertTrue(text.contains("actual_tool"));assertTrue(text.contains("/output 65"));
    }
    @Test void unknownSlashNeverCallsGatewayOrModel() throws Exception {
        FakeGateway gateway = new FakeGateway();
        try (var renderer = renderer()) { assertFalse(cli(gateway).dispatch("/not-a-command", renderer)); }
        assertTrue(gateway.commands.isEmpty()); assertEquals(0, gateway.submissions);
    }
    @Test void barePlanAndTeamChooseOnlyNextTaskWithoutQueuingEmptyGoal() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.events = List.of(new CenterCommandGateway.Event(1, "TASK_COMPLETED", Map.of("taskId", "99"), true));
        var cli = cli(gateway);
        try (var renderer = renderer()) {
            assertTrue(cli.dispatch("/plan", renderer)); assertTrue(cli.dispatch("真实计划任务", renderer));
            gateway.events = List.of(new CenterCommandGateway.Event(2, "TASK_COMPLETED", Map.of("taskId", "99"), true));
            assertTrue(cli.dispatch("普通任务", renderer));
            assertTrue(cli.dispatch("/team", renderer));
            gateway.events = List.of(new CenterCommandGateway.Event(3, "TASK_COMPLETED", Map.of("taskId", "99"), true));
            assertTrue(cli.dispatch("真实团队任务", renderer));
        }
        assertTrue(gateway.commands.isEmpty()); assertEquals(List.of("plan", "ask", "team"), gateway.executionModes);
    }
    @Test void emptyModeAndHitlReadActualStatusAndContextAliasesRemainCommands() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.response = new CenterCommandGateway.CommandResult(true, "真实状态", Map.of("model", "actual", "approvalMode", "ASK", "contextWindow", 64000));
        var cli = cli(gateway);
        try (var renderer = renderer()) {
            assertTrue(cli.dispatch("/mode", renderer)); assertTrue(cli.dispatch("/hitl", renderer));
            assertTrue(cli.dispatch("/ctx", renderer)); assertTrue(cli.dispatch("/status", renderer));
        }
        assertEquals(List.of("status", "status", "ctx", "status"), gateway.commands);
        assertEquals(0, gateway.submissions); assertEquals("actual", cli.status().model());
    }
    @Test void structuredThinkingToolsDiffAndStatusReachRendererWithoutDuplicateText() throws Exception {
        FakeGateway gateway = new FakeGateway(); var cli = cli(gateway); var renderer = new RecordingRenderer();
        assertTrue(cli.presentEvent("MODEL_DELTA", Map.of("presentation", "THINKING_BEGIN", "label", "thinking"), renderer));
        assertTrue(cli.presentEvent("MODEL_DELTA", Map.of("presentation", "THINKING_DELTA", "delta", "实际思考"), renderer));
        assertTrue(cli.presentEvent("MODEL_DELTA", Map.of("presentation", "TOOL_CALLS", "toolCalls", List.of(new com.paicli.llm.LlmClient.ToolCall("1", new com.paicli.llm.LlmClient.ToolCall.Function("read_file", "{\"path\":\"x\"}")))), renderer));
        assertTrue(cli.presentEvent("MODEL_DELTA", Map.of("presentation", "DIFF", "path", "x", "before", "old", "after", "new"), renderer));
        assertEquals(List.of("thinking", "实际思考", "read_file", "x:old:new"), renderer.operations);
        assertEquals("", renderer.output.toString());
    }
    private static final class RecordingRenderer implements com.paicli.render.Renderer {
        final List<String> operations = new ArrayList<>(); final ByteArrayOutputStream output = new ByteArrayOutputStream();
        public void start() {} public void close() {} public PrintStream stream() { return new PrintStream(output); }
        public boolean supportsThinkingPanel() { return true; }
        public void beginThinking(String label) { operations.add(label); } public void appendThinking(String delta) { operations.add(delta); }
        public void appendToolCalls(List<com.paicli.llm.LlmClient.ToolCall> calls) { operations.add(calls.get(0).function().name()); }
        public void appendDiff(String path,String before,String after) { operations.add(path+":"+before+":"+after); }
        public void updateStatus(com.paicli.render.StatusInfo status) { }
        public com.paicli.hitl.ApprovalResult promptApproval(com.paicli.hitl.ApprovalRequest request) { return com.paicli.hitl.ApprovalResult.reject("fixture"); }
        public int openPalette(String title,List<String> items) { return -1; }
    }
    private static final class FakeGateway implements CenterCommandGateway {
        final List<String> commands = new ArrayList<>(), executionModes = new ArrayList<>(), arguments=new ArrayList<>();
        final Deque<CommandResult> responses=new ArrayDeque<>(); int submissions; int polls;
        final List<String> taskPolls=new ArrayList<>();
        CommandResult response = new CommandResult(true, "ok", Map.of()); List<Event> events = List.of();
        public Session open(OpenSession request) { throw new UnsupportedOperationException(); }
        public Turn submit(String session, String text, String mode, List<Attachment> attachments) { submissions++; executionModes.add(mode); return new Turn("99", "ENQUEUED"); }
        public CommandResult command(String session, String name, String argument) { commands.add(name);arguments.add(argument);return commands.size()>1&&!responses.isEmpty()?responses.removeFirst():response; }
        public List<Event> events(String session, long after, int limit) { polls++; return events; }
        public List<Event> taskEvents(String task,long after,int limit){taskPolls.add(task+":"+after);assertEquals(200,limit);return List.of(new Event(after==0?1:2,after==0?"APPROVAL_REQUIRED":"TASK_COMPLETED",Map.of("taskId",task),after!=0));}
        public void cancel(String session, String turn) { }
    }
}
