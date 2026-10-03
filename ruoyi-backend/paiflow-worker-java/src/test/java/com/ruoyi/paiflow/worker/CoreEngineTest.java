package com.ruoyi.paiflow.worker;

import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.VariablePool;
import com.iflytek.astron.workflow.engine.domain.WorkflowDSL;
import com.iflytek.astron.workflow.engine.domain.chain.Edge;
import com.iflytek.astron.workflow.engine.domain.chain.Node;
import com.iflytek.astron.workflow.engine.domain.chain.NodeData;
import com.iflytek.astron.workflow.engine.domain.chain.NodeMeta;
import com.iflytek.astron.workflow.engine.domain.chain.InputItem;
import com.iflytek.astron.workflow.engine.domain.chain.InputSchema;
import com.iflytek.astron.workflow.engine.domain.chain.Value;
import com.iflytek.astron.workflow.engine.node.impl.StartNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.EndNodeExecutor;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectPolicy;
import com.ruoyi.paiflow.worker.service.ExecutionScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CoreEngineTest {
    @Test
    void originalSequentialStartReferenceEndAlgorithmKeepsActualAssignedInput() throws Exception {
        CenterClient center = mock(CenterClient.class);
        try (ExecutionScope scope = scope(center)) {
            scope.bind();
            VariablePool variables = new VariablePool();
            WorkflowEngine engine = new WorkflowEngine(List.of(new StartNodeExecutor(), new EndNodeExecutor()));
            List<Object> frames = java.util.Collections.synchronizedList(new ArrayList<>());
            engine.execute(dsl(), variables, Map.of("question", "实际输入"), (type, payload) -> frames.add(payload));
            assertEquals("结果：实际输入", variables.get("node-end::2", "content"));
            assertFalse(frames.isEmpty());
            verifyNoInteractions(center);
        }
    }

    @Test
    void originalParallelAlgorithmExecutesTheEndBeforeReportingSuccess() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ParallelWorkflowEngine engine = new ParallelWorkflowEngine(List.of(new StartNodeExecutor(), new EndNodeExecutor()));
        try (ExecutionScope scope = scope(center)) {
            scope.bind();
            VariablePool variables = new VariablePool();
            engine.execute(dsl(), variables, Map.of("question", "并行输入"), (type, payload) -> { });
            assertEquals("结果：并行输入", variables.get("node-end::2", "content"));
            verifyNoInteractions(center);
        } finally { engine.close(); }
    }

    private ExecutionScope scope(CenterClient center) {
        return new ExecutionScope(center, new Assignment("paiflow.worker.v1", "run", "version", "0".repeat(64), "",
                "java", Map.of(), Map.of(), 1, "lease", Instant.now().plusSeconds(30), new EffectPolicy("read_only", List.of(), null), Map.of(), 0), Clock.systemUTC());
    }

    static WorkflowDSL dsl() {
        NodeData startData = new NodeData();
        startData.setNodeMeta(new NodeMeta("node-start", "开始"));
        InputItem startInput = new InputItem();
        startInput.setName("question");
        startInput.setSchema(new InputSchema("string", new Value("literal", "原默认值")));
        startData.setInputs(List.of(startInput));
        Node start = new Node();
        start.setId("node-start::1"); start.setData(startData);
        NodeData endData = new NodeData();
        endData.setNodeMeta(new NodeMeta("node-end", "结束"));
        endData.setNodeParam(Map.of("outputMode", 1, "template", "结果：{{text}}"));
        InputItem endInput = new InputItem();
        endInput.setName("text");
        endInput.setSchema(new InputSchema("string", new Value("ref", Map.of("nodeId", "node-start::1", "name", "question"))));
        endData.setInputs(List.of(endInput));
        Node end = new Node();
        end.setId("node-end::2"); end.setData(endData);
        Edge edge = new Edge(); edge.setSourceNodeId("node-start::1"); edge.setTargetNodeId("node-end::2");
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setNodes(List.of(start, end)); dsl.setEdges(List.of(edge)); dsl.setFlowId("flow-test"); dsl.setUuid("chat-test");
        return dsl;
    }
}
