package com.ruoyi.paiflow.worker;

import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.VariablePool;
import com.iflytek.astron.workflow.engine.constants.NodeExecStatusEnum;
import com.iflytek.astron.workflow.engine.constants.NodeStatusEnum;
import com.iflytek.astron.workflow.engine.constants.NodeTypeEnum;
import com.iflytek.astron.workflow.engine.domain.NodeRunResult;
import com.iflytek.astron.workflow.engine.domain.NodeState;
import com.iflytek.astron.workflow.engine.domain.WorkflowDSL;
import com.iflytek.astron.workflow.engine.domain.chain.*;
import com.iflytek.astron.workflow.engine.node.NodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.StartNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.EndNodeExecutor;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectPolicy;
import com.ruoyi.paiflow.worker.service.ExecutionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class BranchEngineTest {
    @Test @Timeout(5)
    void parallelSkippedDescendantsRemainSkippedAndJoinStillRunsOnce() throws Exception {
        CenterClient center = forbiddenCenter();
        RecordingPlugin plugin = new RecordingPlugin();
        ParallelWorkflowEngine engine = new ParallelWorkflowEngine(List.of(new StartNodeExecutor(), plugin, new EndNodeExecutor()));
        WorkflowDSL dsl = branchDsl();
        try (ExecutionScope scope = scope(center)) {
            scope.bind();
            VariablePool variables = new VariablePool();
            engine.execute(dsl, variables, Map.of("question", "汇合结果"), (type, payload) -> { });
            assertBranchResult(dsl, variables, plugin);
        } finally { engine.close(); }
    }

    @Test @Timeout(5)
    void sequentialSkippedDescendantsRemainSkippedAndJoinKeepsActiveInput() throws Exception {
        CenterClient center = forbiddenCenter();
        RecordingPlugin plugin = new RecordingPlugin();
        WorkflowEngine engine = new WorkflowEngine(List.of(new StartNodeExecutor(), plugin, new EndNodeExecutor()));
        WorkflowDSL dsl = branchDsl();
        try (ExecutionScope scope = scope(center)) {
            scope.bind();
            VariablePool variables = new VariablePool();
            engine.execute(dsl, variables, Map.of("question", "汇合结果"), (type, payload) -> { });
            assertBranchResult(dsl, variables, plugin);
        }
    }

    private void assertBranchResult(WorkflowDSL dsl, VariablePool variables, RecordingPlugin plugin) {
        assertEquals(Map.of("plugin::active", 1), plugin.calls);
        assertEquals("结果：汇合结果", variables.get("node-end::2", "content"));
        assertEquals(NodeStatusEnum.SKIP, dsl.getNodes().get(3).getStatus());
        assertEquals(NodeStatusEnum.SKIP, dsl.getNodes().get(4).getStatus());
        assertEquals(NodeStatusEnum.SUCCESS, dsl.getNodes().get(1).getStatus());
    }

    private WorkflowDSL branchDsl() {
        WorkflowDSL dsl = CoreEngineTest.dsl();
        Node active = plugin("plugin::active");
        Node skipped = plugin("plugin::skipped");
        Node descendant = plugin("plugin::descendant");
        dsl.setNodes(List.of(dsl.getNodes().get(0), dsl.getNodes().get(1), active, skipped, descendant));
        dsl.setEdges(List.of(new Edge("node-start::1", "plugin::active", null),
                new Edge("node-start::1", "plugin::skipped", "intent_chain|fail_one_of"),
                new Edge("plugin::active", "node-end::2", null),
                new Edge("plugin::skipped", "plugin::descendant", null),
                new Edge("plugin::descendant", "node-end::2", null)));
        return dsl;
    }

    private Node plugin(String id) {
        Node node = new Node(); node.setId(id);
        NodeData data = new NodeData(); data.setNodeMeta(new NodeMeta("plugin", "内存执行替身")); node.setData(data);
        return node;
    }

    private ExecutionScope scope(CenterClient center) {
        return new ExecutionScope(center, new Assignment("paiflow.worker.v1", "branch-test", "version", "0".repeat(64), "",
                "java", Map.of(), Map.of(), 1, "lease", Instant.now().plusSeconds(10), new EffectPolicy("read_only", List.of(), null), Map.of(), 0), Clock.systemUTC());
    }

    /** 此图无需中心交互，任何调用立即失败；不让Mockito冷启动计入引擎5秒期限。 */
    private CenterClient forbiddenCenter() {
        return (CenterClient) java.lang.reflect.Proxy.newProxyInstance(CenterClient.class.getClassLoader(),
                new Class<?>[]{CenterClient.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("分支内存检查禁止中心调用：" + method.getName());
                });
    }

    private static class RecordingPlugin implements NodeExecutor {
        final Map<String, Integer> calls = new ConcurrentHashMap<>();
        @Override public NodeTypeEnum getNodeType() { return NodeTypeEnum.PLUGIN; }
        @Override public NodeRunResult execute(NodeState state) {
            ExecutionScope.current();
            calls.merge(state.node().getId(), 1, Integer::sum);
            NodeRunResult result = new NodeRunResult(); result.setStatus(NodeExecStatusEnum.SUCCESS); return result;
        }
    }
}
