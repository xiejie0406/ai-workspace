package com.ruoyi.paiflow.worker.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkerProtocol {
    private WorkerProtocol() { }

    public record Lease(String runId, String leaseId, int attempt) { }
    public record EffectPolicy(String mode, List<String> allowedOperations, String approvalRef) { }
    public record Assignment(String schemaVersion, String runId, String workflowVersionId, String dslHash, String dslCanonicalBase64,
            String engineProfile, Map<String, Object> input, Map<String, Map<String, Object>> resourceSnapshots,
            int attempt, String leaseId, Instant deadline, EffectPolicy effectPolicy, Map<String, Object> dsl,
            long lastEventSeq) { }
    public record Heartbeat(boolean valid, boolean cancelRequested) { }
    public record Event(String schemaVersion, String runId, String leaseId, int attempt, long seq, String nodeId,
            String iterationId, String eventType, Instant timestamp, Object payload) { }
    public record Result(String leaseId, int attempt, String state, Map<String, Object> output, Error error) { }
    public record Error(String code, String message) { }
    public record Effect(String leaseId, int attempt, String operation, String resourceId, String target,
            String approvalRef) { }
    public record EffectGrant(boolean authorized, String grantId) { }
    public record EffectCompletion(String leaseId, int attempt, String grantId, String state, Map<String, Object> receipt) { }
    public record ArtifactGrant(String artifactId, String uploadUrl, String readUrl, Map<String, String> headers) { }
}
