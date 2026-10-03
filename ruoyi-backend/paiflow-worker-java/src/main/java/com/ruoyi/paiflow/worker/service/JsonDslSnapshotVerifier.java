package com.ruoyi.paiflow.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonDslSnapshotVerifier {
    private JsonDslSnapshotVerifier() { }

    public static void verify(String canonicalBase64, String hash, Object dsl, ObjectMapper mapper) {
        byte[] canonical = DslSnapshotVerifier.verify(canonicalBase64, hash);
        try {
            JsonNode recorded = mapper.readTree(canonical);
            JsonNode received = mapper.valueToTree(dsl);
            if (!recorded.equals(received)) throw new WorkerException("DSL_INVALID", "DSL对象与中心规范化快照不一致");
        } catch (WorkerException error) { throw error; }
        catch (Exception error) { throw new WorkerException("DSL_INVALID", "DSL快照不能作为JSON执行", error); }
    }
}
