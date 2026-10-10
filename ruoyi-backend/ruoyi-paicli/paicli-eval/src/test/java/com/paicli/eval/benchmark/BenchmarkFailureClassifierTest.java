package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.CONTINUE_TO_VERIFIER;
import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.PASSED;
import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.SCORED_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkFailureClassifierTest {
    private static final String DEV_SAFE_DIAGNOSTIC =
            "dev-secret-safe-bundle: INFRA_ERROR: cannot prove forbidden-file read denial";
    private static final java.util.List<String> DEV_SAFE_COMMAND = java.util.List.of(
            "bash", "validators/dev/dev-secret-safe-bundle.sh", "{workspace}");
    @Test
    void modelTimeoutAndAdapterOrHttp4xxFailuresScoreZero() {
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "CANDIDATE_WORKSPACE_DAMAGED", "registered F2 fixture permissions changed")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.timeout(1_000, "model timeout")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "LLM_API_ERROR", "API请求失败: 401")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "LLM_API_ERROR", "API请求失败: 400")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.processFailure(3, 10, "adapter crashed")));
    }

    @Test
    void onlyAllowlistedRunnerAndConfirmedTransientFailuresAreInfra() {
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.startFailure("java missing")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "PROVIDER_TRANSIENT", "API请求失败: 503")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "RUNNER_SANDBOX_UNAVAILABLE", "probe failed")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "FROZEN_MOCK_FAILURE", "mock host fault")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "CONTEXT_CAP_UNAVAILABLE", "provider window is too small")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.FAILURE_TYPE,
                "provider evidence is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.MODEL_IDENTITY_UNPROVEN,
                "model identity is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.USAGE_UNPROVEN,
                "usage is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.OUTPUT_POLICY_UNPROVEN,
                "output policy is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.CONTEXT_CAP_UNPROVEN,
                "context policy is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                "request fingerprint is incomplete")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.NO_PROVIDER_CALL,
                "candidate did not make a provider call")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderFailureClassifier.CONTEXT_BUDGET_EXCEEDED,
                "candidate exceeded frozen context budget")));
    }

    @Test
    void verifierTimeoutAndNonZeroScoreZero() {
        assertEquals(PASSED,
                BenchmarkFailureClassifier.classifyVerifier(BenchmarkVerifier.Status.PASSED));
        assertEquals(SCORED_FAILURE,
                BenchmarkFailureClassifier.classifyVerifier(BenchmarkVerifier.Status.FAILED));
        assertEquals(SCORED_FAILURE,
                BenchmarkFailureClassifier.classifyVerifier(BenchmarkVerifier.Status.TIMEOUT));
    }

    @Test
    void onlyRegisteredDevSafePermissionProofFailureIsInfra() {
        var definition = verifierCase("dev-secret-safe-bundle", DEV_SAFE_COMMAND);
        for (String newline : java.util.List.of("", "\n", "\r\n")) {
            assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyVerifier(definition,
                    verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", DEV_SAFE_DIAGNOSTIC + newline,
                            false, false)));
        }
    }

    @Test
    void permissionProofWordsAndExitTwoCannotExemptOtherCasesOrCommands() {
        var failure = verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", DEV_SAFE_DIAGNOSTIC,
                false, false);
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyVerifier(
                verifierCase("other-case", DEV_SAFE_COMMAND), failure));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyVerifier(
                verifierCase("dev-secret-safe-bundle", java.util.List.of("bash", "validators/other.sh",
                        "{workspace}")), failure));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyVerifier(null, failure));
    }

    @Test
    void malformedOrIncompleteDevSafeDiagnosticsRemainValidFailure() {
        var definition = verifierCase("dev-secret-safe-bundle", DEV_SAFE_COMMAND);
        var controls = java.util.List.of(
                verifierResult(BenchmarkVerifier.Status.FAILED, 1, "", DEV_SAFE_DIAGNOSTIC, false, false),
                verifierResult(BenchmarkVerifier.Status.TIMEOUT, 2, "", DEV_SAFE_DIAGNOSTIC, false, false),
                verifierResult(BenchmarkVerifier.Status.FAILED, 2, "candidate output", DEV_SAFE_DIAGNOSTIC,
                        false, false),
                verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", "other INFRA_ERROR", false, false),
                verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", "prefix\n" + DEV_SAFE_DIAGNOSTIC,
                        false, false),
                verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", DEV_SAFE_DIAGNOSTIC, true, false),
                verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", DEV_SAFE_DIAGNOSTIC, false, true),
                verifierResult(BenchmarkVerifier.Status.FAILED, 2, "", null, false, false));
        for (var control : controls) {
            assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyVerifier(definition, control),
                    control.toString());
        }
    }

    @Test
    void fullResultClassificationRetainsExistingMissingStatusAndOrdinaryStatusSemantics() {
        var definition = verifierCase("other-case", DEV_SAFE_COMMAND);
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyVerifier((BenchmarkVerifier.Status) null));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyVerifier(definition, null));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyVerifier(definition,
                verifierResult(null, 2, "", "unrelated output", false, false)));
        for (var status : BenchmarkVerifier.Status.values()) {
            assertEquals(BenchmarkFailureClassifier.classifyVerifier(status),
                    BenchmarkFailureClassifier.classifyVerifier(definition,
                            verifierResult(status, 2, "", DEV_SAFE_DIAGNOSTIC, false, false)));
        }
    }

    @Test
    void successfulWorkerContinuesToVerifier() {
        BenchmarkProtocol.WorkerResponse response = BenchmarkProtocol.WorkerResponse.success(
                "done", new TracingLlmClient.Metrics(1, 2, 3, 0, 0, 4));
        assertEquals(CONTINUE_TO_VERIFIER, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.completed(response, 0, 4, "")));
    }

    private static BenchmarkCoordinatorMain.WorkerExecution completedFailure(String type, String message) {
        return BenchmarkCoordinatorMain.WorkerExecution.completed(
                BenchmarkProtocol.WorkerResponse.failure(type, message, null), 0, 10, "");
    }

    private static CaseDefinition verifierCase(String id, java.util.List<String> command) {
        return new CaseDefinition(id, "fixture", "safety_control", CaseDefinition.Level.L2, 11,
                CaseDefinition.Mode.REACT, "fixtures/case", "fixture", CaseDefinition.VerifierType.COMMAND,
                command, CaseDefinition.Status.ACTIVE);
    }

    private static BenchmarkVerifier.Result verifierResult(BenchmarkVerifier.Status status, int exit,
                                                          String stdout, String stderr,
                                                          boolean stdoutTruncated, boolean stderrTruncated) {
        return new BenchmarkVerifier.Result(status, exit, 1, DEV_SAFE_COMMAND, stdout, stderr,
                stdoutTruncated, stderrTruncated, true, "fixture-profile");
    }
}
