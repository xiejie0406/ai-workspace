package com.ruoyi.paiflow.worker;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.node.impl.StartNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.EndNodeExecutor;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.*;
import com.ruoyi.paiflow.worker.service.WorkerTaskRunner;
import com.ruoyi.paiflow.worker.service.WorkerException;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

/** 使用实际中心预算常量复验原事件预算冲突，不连接网络或数据库。旧失败日志保留。 */
public class BudgetWireProbe {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var input = Map.<String,Object>of("question", "x".repeat(300 * 1024));
        com.ruoyi.paiflow.service.RunJsonBoundary.check(input, com.ruoyi.paiflow.service.RunProtocolLimits.INPUT_BYTES);
        var dsl = mapper.convertValue(CoreEngineTest.dsl(), Map.class);
        byte[] canonical = mapper.writeValueAsBytes(dsl);
        var assignment = new Assignment("paiflow.worker.v1", "run-probe", "version-probe",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)), Base64.getEncoder().encodeToString(canonical),
                "java", input, Map.of(), 1, "lease-probe", Instant.now().plusSeconds(30), new EffectPolicy("read_only", List.of(), null), dsl, 0);
        var wireTask = JSON.parseObject(mapper.writeValueAsString(assignment));
        com.ruoyi.paiflow.service.RunJsonBoundary.check(wireTask, com.ruoyi.paiflow.service.RunProtocolLimits.ASSIGNMENT_BYTES);
        long[] largest = {0};
        CenterClient center = new CenterClient() {
            @Override public Lease claim() { throw new AssertionError("预算复验不领取任务"); }
            @Override public Assignment verify(Lease lease) { return assignment; }
            @Override public void beginExecution(Lease lease, Assignment task) {
                if (task != assignment) throw new AssertionError("预算复验必须使用同一实际快照");
            }
            @Override public Heartbeat heartbeat(Lease lease) { return new Heartbeat(true, false); }
            @Override public void event(Event event) {
                try {
                    byte[] bytes = mapper.writeValueAsBytes(event);
                    largest[0] = Math.max(largest[0], bytes.length);
                    com.ruoyi.paiflow.service.RunJsonBoundary.check(JSON.parseObject(bytes), com.ruoyi.paiflow.service.RunProtocolLimits.EVENT_BYTES);
                } catch (Exception error) { throw new WorkerException("CENTER_REJECTED", "实际中心事件预算拒绝", error); }
            }
            @Override public void result(Lease lease, Result result) { }
            @Override public EffectGrant authorizeEffect(Lease lease, Effect effect) { throw new AssertionError("纯图禁止外调"); }
            @Override public void completeEffect(Lease lease, EffectCompletion completion) { throw new AssertionError("纯图禁止外调"); }
            @Override public ArtifactGrant prepareArtifact(Lease lease, String key, String type, long size) { throw new AssertionError("禁止文件写入"); }
            @Override public void commitArtifact(Lease lease, String id, String hash, long size) { throw new AssertionError("禁止文件写入"); }
        };
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        var parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (var runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel, Clock.systemUTC(), 30, 1, mapper)) {
            var result = runner.execute(new Lease("run-probe", "lease-probe", 1));
            if (!"COMPLETED".equals(result.state())) throw new AssertionError("预算修正后实际原引擎必须完成：" + result.state());
            System.out.println("inputAcceptedBytes=" + mapper.writeValueAsBytes(input).length + ",assignmentAcceptedBytes=" + mapper.writeValueAsBytes(assignment).length
                    + ",largestActualEventBytes=" + largest[0] + ",actualTerminal=" + result.state());
        } finally { parallel.close(); }
    }
}
