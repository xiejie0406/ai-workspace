package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;

/** 只读审查的内存复现：复用中心已有离线策略测试夹具，不启动服务或持久数据库。 */
public final class OptionalEventFieldProbe {
    public static void main(String[] args) throws Exception {
        RunLeasePolicyTest fixture = new RunLeasePolicyTest();
        fixture.setup();
        var serviceField = RunLeasePolicyTest.class.getDeclaredField("service");
        serviceField.setAccessible(true);
        RunLeaseService service = (RunLeaseService) serviceField.get(fixture);
        String id = "00000000-0000-0000-0000-000000000001";
        var worker = new WorkerIdentityService.Principal("worker-a", "python");
        JSONObject event = JSONObject.of("schemaVersion", "paiflow.events.v1", "runId", id,
                "leaseId", "lease-a", "attempt", 1, "seq", 1, "eventType", "node.started",
                "timestamp", "2026-10-03T00:00:00Z", "payload", new JSONObject(), "iterationId", 123);
        System.out.println("numericIterationIdAccepted=" + service.append(id, worker, event).getBooleanValue("accepted"));
        event.put("seq", 2);
        event.remove("iterationId");
        event.put("nodeId", null);
        System.out.println("explicitNullNodeIdAccepted=" + service.append(id, worker, event).getBooleanValue("accepted"));
    }
}
