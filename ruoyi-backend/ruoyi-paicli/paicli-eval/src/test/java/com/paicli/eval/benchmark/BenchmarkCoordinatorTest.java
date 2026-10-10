package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.extension.ExtendWith(com.paicli.eval.benchmark.SecureFilesTempDirExtension.class)
class BenchmarkCoordinatorTest {
    private static final String DOCKER_WORKER_IMAGE = "sha256:" + "b".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T02:03:04Z"), ZoneOffset.UTC);
    private static final String API_KEY = "provider-key-canary-never-persist-123456789";

    @Test
    void onePassAndSevenWorkerTimeoutsPublishTwelvePointFiveNotOneHundred(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "deepseek", "deepseek-v4-flash", 8,
                tempDir.resolve("results"), "run-timeouts");
        AtomicInteger calls = new AtomicInteger();
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            if (calls.getAndIncrement() == 0) {
                Files.writeString(workspace.resolve("done.txt"), "done");
                return successfulWorker(request, "completed");
            }
            return BenchmarkCoordinatorMain.WorkerExecution.timeout(1_000, "model timeout");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertNull(report.aggregate().meanWeightedScore());
        assertNull(report.aggregate().populationStddev());
        assertNull(report.aggregate().minWeightedScore());
        assertNull(report.aggregate().maxWeightedScore());
        assertEquals(12.5, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertEquals(Math.sqrt(1093.75), report.aggregate().diagnosticPopulationStddev(), 0.001);
        assertEquals(0.0, report.aggregate().diagnosticMinWeightedScore(), 0.001);
        assertEquals(100.0, report.aggregate().diagnosticMaxWeightedScore(), 0.001);
        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertEquals(100.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertEquals(1, report.outcomes().stream().filter(
                outcome -> outcome.score().strictSuccess()).count());
        assertFalse(report.aggregate().strictSuccess());
        assertFalse(report.aggregate().diagnosticStrictSuccess());
        assertFalse(report.aggregate().publishable());
        assertFalse(BenchmarkSecretCanary.containsInTree(report.runDirectory(), API_KEY));
        assertTrue(Files.exists(report.runDirectory().resolve("aggregate.json")));
        String manifest = Files.readString(report.runDirectory().resolve("manifest.json"));
        assertTrue(manifest.contains("\"manifestVersion\" : 3"));
        assertTrue(manifest.contains("\"requestedModelLocked\" : true"));
        assertTrue(manifest.contains("\"serverResolvedModel\" : \"UNAVAILABLE\""));
        assertTrue(manifest.contains("\"serverResolvedModelMatchesRequest\" : false"));
        assertTrue(manifest.contains("\"usageCompletenessGate\" : false"));
        assertTrue(manifest.contains("\"maxOutputTokensPerCall\" : 16384"));
        assertTrue(manifest.contains("\"maximumObservedTotalTokensPerCall\" : 15"));
        assertTrue(manifest.contains("\"outputPolicySatisfiedGate\" : false"));
        assertTrue(manifest.contains("\"requestFingerprintCompleteGate\" : false"));
        assertTrue(manifest.contains("\"fullProviderEvidenceGate\" : false"));
        assertTrue(manifest.contains("\"runtimeDate\" : \"2026-08-31\""));
        assertTrue(manifest.contains("\"toolProfile\" : \"FILE_ONLY\""));
        assertTrue(manifest.contains("\"verifierIsolation\" : \"SEATBELT\""));
        String aggregate = Files.readString(report.runDirectory().resolve("aggregate.json"));
        assertTrue(aggregate.contains("\"schemaVersion\" : 3"));
        assertTrue(aggregate.contains("\"maximumObservedTotalTokensPerCall\" : 15"));
        assertTrue(aggregate.contains("\"fullProviderEvidenceGate\" : false"));
    }

    @Test
    void localCommandProfileFlowsUnchangedIntoWorkerRequestAndManifest(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath,
                "glm",
                "glm-5.3-flash",
                1,
                tempDir.resolve("results"),
                "run-local-command-profile",
                Set.of(),
                BenchmarkToolProfile.LOCAL_COMMAND);
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            assertEquals(BenchmarkToolProfile.LOCAL_COMMAND, request.toolProfile());
            Files.writeString(workspace.resolve("done.txt"), "done");
            return successfulWorker(request, "done");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        String manifest = Files.readString(report.runDirectory().resolve("manifest.json"));
        assertTrue(manifest.contains("\"toolProfile\" : \"LOCAL_COMMAND\""));
    }

    @Test
    void dockerRelayIdentityIsRecordedWithoutCandidatePath(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        Path candidateJar = tempDir.resolve("private-candidate.jar").toAbsolutePath();
        Files.writeString(candidateJar, "candidate-build");
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                tempDir.resolve("private-runner.jar").toAbsolutePath());
        Path dockerExecutable = tempDir.resolve("not-executed-docker-fixture").toAbsolutePath();
        BenchmarkCoordinatorMain.Options options = new BenchmarkCoordinatorMain.Options(
                suitePath,
                "glm",
                "glm-5.3-flash",
                1,
                tempDir.resolve("results"),
                "run-docker-relay",
                Set.of(),
                BenchmarkToolProfile.FILE_ONLY,
                BenchmarkCoordinatorMain.WorkerIsolation.DOCKER_RELAY,
                DOCKER_WORKER_IMAGE,
                candidateJar,
                runnerJar,
                BenchmarkCoordinatorMain.VerifierIsolation.SEATBELT,
                null,
                dockerExecutable,
                Duration.ofSeconds(5));
        String candidateSha256 = options.candidateJarSha256();
        String runnerSha256 = options.runnerJarSha256();
        String runnerContentManifestSha256 = options.runnerContentManifestSha256();
        Files.writeString(candidateJar, "candidate-replaced-after-options");
        Files.writeString(runnerJar, "runner-replaced-after-options");
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            Files.writeString(workspace.resolve("done.txt"), "done");
            return successfulWorker(request, "done");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        String manifest = Files.readString(report.runDirectory().resolve("manifest.json"));
        String episodeRun = Files.readString(onlyEpisode(report.runDirectory()).resolve("run.json"));
        for (String artifact : java.util.List.of(manifest, episodeRun)) {
            assertTrue(artifact.contains("\"workerIsolation\" : \"DOCKER_RELAY\""));
            assertTrue(artifact.contains("\"workerImageId\" : \"" + DOCKER_WORKER_IMAGE + "\""));
            assertTrue(artifact.contains("\"candidateJarSha256\" : \"" + candidateSha256 + "\""));
            assertTrue(artifact.contains("\"runnerJarSha256\" : \"" + runnerSha256 + "\""));
            assertTrue(artifact.contains("\"runnerContentManifestSha256\" : \""
                    + runnerContentManifestSha256 + "\""));
            assertFalse(artifact.contains(candidateJar.toString()));
            assertFalse(artifact.contains(runnerJar.toString()));
        }
        assertTrue(manifest.contains("networkless-readonly-docker-candidate-with-host-provider-relay"));
        assertTrue(manifest.contains("\"publishable\" : false"));
        assertFalse(report.aggregate().publishable());
    }

    @Test
    void threeRepeatsUseArithmeticMeanAndPopulationSpread(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "deepseek", "deepseek-v4-flash", 3,
                tempDir.resolve("results"), "run-three-repeats");
        AtomicInteger calls = new AtomicInteger();
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            if (calls.getAndIncrement() == 1) {
                return BenchmarkCoordinatorMain.WorkerExecution.timeout(1_000, "model timeout");
            }
            Files.writeString(workspace.resolve("done.txt"), "done");
            return successfulWorker(request, "completed");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertNull(report.aggregate().meanWeightedScore());
        assertEquals(200.0 / 3.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertEquals(Math.sqrt(2_0000.0 / 9.0),
                report.aggregate().diagnosticPopulationStddev(), 0.001);
        assertEquals(0.0, report.aggregate().diagnosticMinWeightedScore(), 0.001);
        assertEquals(100.0, report.aggregate().diagnosticMaxWeightedScore(), 0.001);
    }

    @Test
    void infraCoverageBelowOneHundredSuppressesPublishableOverall(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                tempDir.resolve("results"), "run-infra");
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) ->
                BenchmarkCoordinatorMain.WorkerExecution.completed(
                        BenchmarkProtocol.WorkerResponse.failure(
                                "PROVIDER_TRANSIENT", "API请求失败: 503", null),
                        0, 20, "");

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertNull(report.aggregate().meanWeightedScore());
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertEquals(0.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertEquals(1, report.aggregate().infraErrorEpisodes());
    }

    @Test
    void onePassingRepeatAndOneInfraRepeatSuppressEveryVisibleScore(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-mixed-infra"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 2,
                tempDir.resolve("results"), "run-mixed-infra");
        AtomicInteger calls = new AtomicInteger();
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            if (calls.getAndIncrement() == 0) {
                Files.writeString(workspace.resolve("done.txt"), "done");
                return successfulWorker(request, "done");
            }
            return BenchmarkCoordinatorMain.WorkerExecution.completed(
                    BenchmarkProtocol.WorkerResponse.failure(
                            "PROVIDER_TRANSIENT", "API request failed: 503", null),
                    0, 20, "");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertNull(report.aggregate().meanWeightedScore());
        assertNull(report.aggregate().populationStddev());
        assertNull(report.aggregate().minWeightedScore());
        assertNull(report.aggregate().maxWeightedScore());
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertNull(report.aggregate().diagnosticPopulationStddev());
        assertNull(report.aggregate().diagnosticMinWeightedScore());
        assertNull(report.aggregate().diagnosticMaxWeightedScore());
        assertNull(report.aggregate().subsetDiagnosticScore());
        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals(50.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
    }

    @Test
    void ordinaryVerifierIOExceptionIsAValidZeroAndStaysInDenominator(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                tempDir.resolve("results"), "run-verifier-io-zero");
        BenchmarkCoordinatorMain.VerifierExecutor verifier =
                (invocation, workspace, home, evidence, timeout) -> {
            throw new IOException("candidate verifier path failed: " + API_KEY);
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                verifier,
                CLOCK);

        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertEquals(100.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertEquals(ScoreAggregator.ResultStatus.SCORED,
                report.outcomes().get(0).score().status());
        assertTrue(report.outcomes().get(0).detail().contains("verifier execution failed"));
        assertFalse(report.outcomes().get(0).detail().contains(API_KEY));
        assertTrue(Files.readString(onlyEpisode(report.runDirectory()).resolve("run.json"))
                .contains("SCORED_FAIL"));
    }

    @Test
    void verifierReceivesScrubbedAnswerAndLlmMetricsThroughSiblingEvidence(
            @TempDir Path tempDir) throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-evidence-envelope"));
        Files.writeString(suitePath, Files.readString(suitePath).replace(
                "\"{workspace}\"]",
                "\"{workspace}\",\"{evidence}\"]"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                tempDir.resolve("results"), "run-verifier-evidence-envelope");
        AtomicReference<Path> receivedEvidence = new AtomicReference<>();
        BenchmarkCoordinatorMain.VerifierExecutor verifier =
                (invocation, workspace, home, evidence, timeout) -> {
                    receivedEvidence.set(evidence);
                    assertFalse(evidence.startsWith(workspace));
                    assertFalse(evidence.startsWith(home));
                    String raw = Files.readString(evidence);
                    assertFalse(raw.contains(API_KEY));
                    com.fasterxml.jackson.databind.JsonNode json =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
                    assertEquals("ordinary answer", json.path("answer").asText());
                    assertEquals(1, json.path("llmMetrics").path("calls").asInt());
                    assertEquals(10, json.path("llmMetrics").path("inputTokens").asInt());
                    assertEquals(64, json.path("verifierWorkspaceTreeSha256").asText().length());
                    assertTrue(json.path("verifierWorkspaceFileCount").asInt() >= 1);
                    assertTrue(json.path("verifierWorkspaceTotalBytes").asLong() > 0);
                    assertTrue(workspace.getFileName().toString().equals("verifier-workspace"));
                    assertTrue(invocation.materialize(workspace, evidence).arguments()
                            .contains(evidence.toString()));
                    return new BenchmarkVerifier.Result(
                            BenchmarkVerifier.Status.PASSED, 0, 1,
                            invocation.materialize(workspace, evidence).arguments(),
                            "PASS", "", false, false, true, "test-profile");
                };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "ordinary answer"),
                verifier,
                CLOCK);

        assertEquals(100.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertTrue(Files.isRegularFile(receivedEvidence.get()));
    }

    @Test
    void evidencePreparationFailureAfterCandidateIsScoredZeroNotInfra(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-evidence-preparation-failure"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                tempDir.resolve("results"), "run-evidence-preparation-failure");
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            com.paicli.eval.benchmark.SecureFiles.createDirectory(workspace.getParent().resolve(
                    BenchmarkEvidenceEnvelope.DIRECTORY_NAME));
            return successfulWorker(request, "ordinary answer");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertEquals(ScoreAggregator.ResultStatus.SCORED,
                report.outcomes().get(0).score().status());
        assertTrue(report.outcomes().get(0).detail().contains("evidence preparation failed"));
    }

    @Test
    void onlyExplicitSandboxUnavailableVerifierErrorIsInfra(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                tempDir.resolve("results"), "run-verifier-sandbox-infra");
        BenchmarkCoordinatorMain.VerifierExecutor verifier =
                (invocation, workspace, home, evidence, timeout) -> {
            throw new BenchmarkVerifierSandbox.SandboxUnavailableException(
                    "RUNNER_SANDBOX_UNAVAILABLE: frozen probe failed");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                verifier,
                CLOCK);

        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertEquals(ScoreAggregator.ResultStatus.INFRA_ERROR,
                report.outcomes().get(0).score().status());
        assertTrue(Files.readString(onlyEpisode(report.runDirectory()).resolve("run.json"))
                .contains("INFRA_ERROR"));
    }

    @Test
    void devSafePermissionProofFailureIsUnscoredAndRetainsRealVerifierArtifact(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeDevSafeSuite(tempDir.resolve("suite"));
        var report = BenchmarkCoordinatorMain.run(
                options(suitePath, "glm", "glm-5.3-flash", 1, tempDir.resolve("results"), "devsafe-infra"),
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                devSafePermissionFailureVerifier(false), CLOCK);
        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertEquals(ScoreAggregator.ResultStatus.INFRA_ERROR, report.outcomes().get(0).score().status());
        Path episode = onlyEpisode(report.runDirectory());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var run = mapper.readTree(Files.readString(episode.resolve("run.json")));
        assertEquals("INFRA_ERROR", run.path("status").asText());
        assertEquals("FAILED", run.path("verifierStatus").asText());
        var verifier = mapper.readTree(Files.readString(episode.resolve("verifier.json")));
        assertEquals("FAILED", verifier.path("status").asText());
        assertEquals(2, verifier.path("exitCode").asInt());
        assertTrue(verifier.path("stderr").asText().contains("cannot prove forbidden-file read denial"));
    }

    @Test
    void devSafeDiagnosticFromAnotherRegisteredVerifierStillScoresZero(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        var report = BenchmarkCoordinatorMain.run(
                options(suitePath, "glm", "glm-5.3-flash", 1, tempDir.resolve("results"), "other-exit-two"),
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                devSafePermissionFailureVerifier(false), CLOCK);
        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertEquals(100.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertEquals(ScoreAggregator.ResultStatus.SCORED, report.outcomes().get(0).score().status());
    }

    @Test
    void credentialCanaryStillWinsOverDevSafeInfrastructureDiagnostic(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeDevSafeSuite(tempDir.resolve("suite"));
        var report = BenchmarkCoordinatorMain.run(
                options(suitePath, "glm", "glm-5.3-flash", 1, tempDir.resolve("results"), "devsafe-canary"),
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                devSafePermissionFailureVerifier(true), CLOCK);
        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertTrue(report.aggregate().hardGateViolation());
        assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertFalse(BenchmarkSecretCanary.containsInTree(report.runDirectory(), API_KEY));
    }

    @Test
    void dockerVerifierLifecycleFailureIsInfraNotCandidateZero(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-docker-infra"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "deepseek", "deepseek-v4-flash", 1,
                tempDir.resolve("results"), "run-verifier-docker-infra");
        BenchmarkCoordinatorMain.VerifierExecutor verifier =
                (invocation, workspace, home, evidence, timeout) -> {
                    throw new DockerBenchmarkVerifier.InfrastructureException(
                            "container cleanup could not be proven");
                };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                verifier,
                CLOCK);

        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertEquals(ScoreAggregator.ResultStatus.INFRA_ERROR,
                report.outcomes().get(0).score().status());
        assertTrue(report.outcomes().get(0).detail()
                .contains("Docker verifier infrastructure unavailable"));
    }

    @Test
    void exactCredentialCanaryPurgesPrivateEpisodeDataAndMarksScoredHardGate(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "deepseek", "deepseek-v4-flash", 1,
                tempDir.resolve("results"), "run-canary");
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            Files.writeString(workspace.resolve("leak.txt"), API_KEY);
            return successfulWorker(request, "answer " + API_KEY);
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertNull(report.aggregate().meanWeightedScore());
        assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertTrue(report.aggregate().hardGateViolation());
        assertFalse(report.aggregate().publishable());
        assertFalse(BenchmarkSecretCanary.containsInTree(report.runDirectory(), API_KEY));
        Path episode = onlyEpisode(report.runDirectory());
        assertFalse(Files.exists(episode.resolve("workspace")));
        assertFalse(Files.exists(episode.resolve("home")));
        String run = Files.readString(episode.resolve("run.json"));
        assertTrue(run.contains("SECURITY_HARD_GATE"));
        assertTrue(run.contains("\"hardGateViolation\" : true"));
    }

    @Test
    void purgeIOExceptionStillProducesScoredHardGateEvidence(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "deepseek", "deepseek-v4-flash", 1,
                tempDir.resolve("results"), "run-canary-purge-failure");
        AtomicReference<EpisodePathObstruction> obstruction = new AtomicReference<>();
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            Files.writeString(workspace.resolve("leak.txt"), API_KEY);
            // 真实根路径替换使 purge 的捕获目录校验抛 IOException，不依赖 chmod/unlink 平台语义。
            obstruction.set(EpisodePathObstruction.install(tempDir, workspace.getParent()));
            return successfulWorker(request, "done " + API_KEY);
        };

        BenchmarkCoordinatorMain.RunReport report;
        try {
            report = BenchmarkCoordinatorMain.run(
                    options,
                    provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                    worker,
                    successfulVerifier(),
                    CLOCK);
        } finally {
            if (obstruction.get() != null) obstruction.get().close();
        }

        assertEquals(0, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
        assertTrue(report.aggregate().hardGateViolation());
        assertTrue(report.aggregate().hardGateEvidence().stream()
                .anyMatch(detail -> detail.contains("purge failed")));
        assertFalse(report.aggregate().publishable());
        String aggregate = Files.readString(report.runDirectory().resolve("aggregate.json"));
        assertTrue(aggregate.contains("\"hardGateViolation\" : true"));
        assertTrue(aggregate.contains("purge failed"));
    }

    @Test
    void replacingCapturedEpisodeRootReallyFailsPurgeAndRestoringItAllowsPurge(@TempDir Path tempDir)
            throws Exception {
        var store = BenchmarkArtifactStore.create(tempDir.resolve("purge-probe"), "root-replaced");
        var episode = store.episode("case-purge", "fixture-model", 1);
        Path payload = Files.writeString(episode.directory().resolve("payload.txt"), "purge fixture");
        try (var obstruction = EpisodePathObstruction.install(tempDir, episode.directory())) {
            IOException failure = assertThrows(IOException.class, episode::purgeContentsForSecurityFailure);
            assertTrue(failure.getMessage().startsWith(
                    "benchmark episode directory is not a safe non-symlink directory:"), failure.getMessage());
            System.out.println("purge 根路径替换负对照：" + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            assertEquals("purge fixture", Files.readString(obstruction.displaced.resolve("payload.txt")));
            assertEquals(EpisodePathObstruction.MARKER, Files.readString(episode.directory()));
        }
        // 同一生产对象重新核对原目录身份；恢复后应真实清理合成 payload。
        episode.purgeContentsForSecurityFailure();
        assertFalse(Files.exists(payload));
        assertTrue(Files.isDirectory(episode.directory()));
    }

    @Test
    void realCoordinatorPolicyRejectsCustomHunyuanEndpointBeforeLaunchingWorker(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "hunyuan", "hy4-preview", 1,
                tempDir.resolve("results"), "run-custom-endpoint");
        BenchmarkCoordinatorMain.WorkerExecutor shouldNotRun = (request, workspace, home, timeout) -> {
            throw new AssertionError("worker must not launch for a custom endpoint");
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkCoordinatorMain.run(
                        options,
                        provider -> new BenchmarkCoordinatorMain.ProviderSettings(
                                API_KEY, "https://mock.invalid/v1"),
                        shouldNotRun,
                        successfulVerifier(),
                        CLOCK));

        assertTrue(error.getMessage().contains("non-official Hunyuan endpoint"));
        assertFalse(Files.exists(tempDir.resolve("results/run-custom-endpoint")));
    }

    @Test
    void rejectsPrivateRawOutputInsideRepository(@TempDir Path tempDir) throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        Path repositoryOutput = Path.of(System.getProperty("user.dir"), "benchmark-results-private");
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                repositoryOutput, "run-repo-output");

        IOException error = assertThrows(IOException.class, () -> BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                successfulVerifier(),
                CLOCK));

        assertTrue(error.getMessage().contains("outside the project"));
        assertFalse(Files.exists(repositoryOutput.resolve("run-repo-output")));
    }

    @Test
    void resolvesAncestorSymlinkBeforeRejectingSuiteLocalOutput(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        Path suiteOutputParent = com.paicli.eval.benchmark.SecureFiles.createDirectory(
                suitePath.getParent().resolve("private-output-parent"));
        Path ancestorLink = tempDir.resolve("suite-link");
        try {
            Files.createSymbolicLink(ancestorLink, suitePath.getParent());
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable on this test host");
        }
        Path aliasedOutput = ancestorLink.resolve(suiteOutputParent.getFileName()).resolve("results");
        BenchmarkCoordinatorMain.Options options = options(
                suitePath, "glm", "glm-5.3-flash", 1,
                aliasedOutput, "run-symlink-escape");

        IOException error = assertThrows(IOException.class, () -> BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> successfulWorker(request, "done"),
                successfulVerifier(),
                CLOCK));

        assertTrue(error.getMessage().contains("outside the project and suite"));
        assertFalse(Files.exists(suiteOutputParent.resolve("results")));
    }

    @Test
    void explicitCaseSelectionIsDiagnosticAndCannotPopulateOverall(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath,
                "glm",
                "glm-5.3-flash",
                1,
                tempDir.resolve("results"),
                "run-subset",
                Set.of("case-one"),
                BenchmarkToolProfile.FILE_ONLY);
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            Files.writeString(workspace.resolve("done.txt"), "done");
            return successfulWorker(request, "done");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        assertTrue(report.aggregate().subset());
        assertNull(report.aggregate().meanWeightedScore());
        assertEquals(100.0, report.aggregate().subsetDiagnosticScore(), 0.001);
        assertFalse(report.aggregate().publishable());
        assertTrue(Files.readString(report.runDirectory().resolve("manifest.json"))
                .contains("\"subset\" : true"));
    }

    @Test
    void planAndTeamModesAreForwardedWithoutBeingDowngradedToReact(@TempDir Path tempDir)
            throws Exception {
        for (String mode : java.util.List.of("plan", "team")) {
            Path suitePath = writeSuite(tempDir.resolve("suite-" + mode), mode);
            BenchmarkCoordinatorMain.Options options = options(
                    suitePath,
                    "glm",
                    "glm-5.3-flash",
                    1,
                    tempDir.resolve("results"),
                    "run-" + mode);
            AtomicReference<String> receivedMode = new AtomicReference<>();
            BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
                receivedMode.set(request.mode());
                Files.writeString(workspace.resolve("done.txt"), "done");
                return successfulWorker(request, "done");
            };

            BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                    options,
                    provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                    worker,
                    successfulVerifier(),
                    CLOCK);

            assertEquals(mode, receivedMode.get());
            assertEquals(100.0, report.aggregate().diagnosticMeanWeightedScore(), 0.001);
            assertEquals(0, report.aggregate().infraErrorEpisodes());
        }
    }

    @Test
    void teamWorkerWithoutScopedRequestProofIsInfraAndCannotReachVerifier(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-team-unscoped"), "team");
        var report = BenchmarkCoordinatorMain.run(
                options(suitePath, "glm", "glm-5.3-flash", 1,
                        tempDir.resolve("results"), "run-team-unscoped"),
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) -> {
                    assertEquals("team", request.mode());
                    return successfulWorker(request, "done", null);
                },
                (invocation, workspace, home, evidence, timeout) -> {
                    org.junit.jupiter.api.Assertions.fail("unscoped TEAM evidence must not dispatch verifier");
                    return null;
                }, CLOCK);
        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertEquals(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                report.outcomes().get(0).failureType());
        assertEquals(ScoreAggregator.ResultStatus.INFRA_ERROR, report.outcomes().get(0).score().status());
    }

    @Test
    void manifestClosesResolvedModelAndUsageGatesOnlyFromCompleteEpisodeEvidence(
            @TempDir Path tempDir) throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-evidence"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath,
                "glm",
                "glm-5.3-flash",
                2,
                tempDir.resolve("results"),
                "run-evidence");
        AtomicInteger workerCalls = new AtomicInteger();
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            Files.writeString(workspace.resolve("done.txt"), "done");
            long maximumObservedTotal = workerCalls.incrementAndGet() == 1 ? 15 : 37;
            TracingLlmClient.Metrics metrics = new TracingLlmClient.Metrics(
                    2, 20, 10, 4, 1, 30,
                    2, "glm-5.3-flash", true, true,
                    "a".repeat(64), "b".repeat(64), true,
                    1_000_000, 1_000_000, 16_384, 10, maximumObservedTotal, true);
            return BenchmarkCoordinatorMain.WorkerExecution.completed(
                    BenchmarkProtocol.WorkerResponse.success("done", metrics),
                    0,
                    30,
                    "");
        };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                successfulVerifier(),
                CLOCK);

        String manifest = Files.readString(report.runDirectory().resolve("manifest.json"));
        assertEquals(3, report.aggregate().schemaVersion());
        assertEquals(16_384, report.aggregate().maxOutputTokensPerCall());
        assertEquals(37L, report.aggregate().maximumObservedTotalTokensPerCall());
        assertTrue(report.aggregate().outputPolicySatisfiedGate());
        assertTrue(report.aggregate().requestFingerprintCompleteGate());
        assertTrue(report.aggregate().fullProviderEvidenceGate());
        assertTrue(manifest.contains("\"manifestVersion\" : 3"));
        assertTrue(manifest.contains("\"serverResolvedModel\" : \"glm-5.3-flash\""));
        assertTrue(manifest.contains("\"serverResolvedModelMatchesRequest\" : true"));
        assertTrue(manifest.contains("\"usageCompletenessGate\" : true"));
        assertTrue(manifest.contains("\"requestedContextWindowCapTokens\" : 1000000"));
        assertTrue(manifest.contains("\"effectiveContextWindowCapTokens\" : 1000000"));
        assertTrue(manifest.contains("\"contextCapSatisfiedGate\" : true"));
        assertTrue(manifest.contains("\"maxOutputTokensPerCall\" : 16384"));
        assertTrue(manifest.contains("\"maximumObservedTotalTokensPerCall\" : 37"));
        assertTrue(manifest.contains("\"outputPolicySatisfiedGate\" : true"));
        assertTrue(manifest.contains("\"requestFingerprintCompleteGate\" : true"));
        assertTrue(manifest.contains("\"fullProviderEvidenceGate\" : true"));
        String aggregate = Files.readString(report.runDirectory().resolve("aggregate.json"));
        assertTrue(aggregate.contains("\"schemaVersion\" : 3"));
        assertTrue(aggregate.contains("\"requestedContextWindowCapTokens\" : 1000000"));
        assertTrue(aggregate.contains("\"effectiveContextWindowCapTokens\" : 1000000"));
        assertTrue(aggregate.contains("\"contextCapSatisfied\" : true"));
        assertTrue(aggregate.contains("\"maxOutputTokensPerCall\" : 16384"));
        assertTrue(aggregate.contains("\"maximumObservedTotalTokensPerCall\" : 37"));
        assertTrue(aggregate.contains("\"outputPolicySatisfiedGate\" : true"));
        assertTrue(aggregate.contains("\"requestFingerprintCompleteGate\" : true"));
        assertTrue(aggregate.contains("\"fullProviderEvidenceGate\" : true"));
    }

    @Test
    void mismatchedResolvedModelIsInfraAndNeverReachesVerifier(@TempDir Path tempDir)
            throws Exception {
        Path suitePath = writeSuite(tempDir.resolve("suite-model-mismatch"));
        BenchmarkCoordinatorMain.Options options = options(
                suitePath,
                "deepseek",
                "deepseek-v4-flash",
                1,
                tempDir.resolve("results"),
                "run-model-mismatch");
        AtomicInteger verifierCalls = new AtomicInteger();
        BenchmarkCoordinatorMain.WorkerExecutor worker = (request, workspace, home, timeout) -> {
            BenchmarkProtocol.AgentLimits limits = request.agentLimits();
            TracingLlmClient.Metrics metrics = new TracingLlmClient.Metrics(
                    1, 10, 5, 0, 1, 20,
                    1, "glm-5.3-flash", true, true,
                    "a".repeat(64), "b".repeat(64), true,
                    limits.contextWindowCapTokens(),
                    limits.contextWindowCapTokens(),
                    limits.maxOutputTokensPerCall(),
                    10, 15, true);
            return BenchmarkCoordinatorMain.WorkerExecution.completed(
                    BenchmarkProtocol.WorkerResponse.success("done", metrics),
                    0,
                    20,
                    "");
        };
        BenchmarkCoordinatorMain.VerifierExecutor verifier =
                (invocation, workspace, home, evidence, timeout) -> {
                    verifierCalls.incrementAndGet();
                    return successfulVerifier().verify(
                            invocation, workspace, home, evidence, timeout);
                };

        BenchmarkCoordinatorMain.RunReport report = BenchmarkCoordinatorMain.run(
                options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                worker,
                verifier,
                CLOCK);

        assertEquals(0, verifierCalls.get());
        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals(0.0, report.aggregate().scoredEpisodeCoveragePercent(), 0.001);
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
        assertTrue(report.outcomes().get(0).detail().contains("provider identity"));
        assertFalse(report.aggregate().fullProviderEvidenceGate());
        String manifest = Files.readString(report.runDirectory().resolve("manifest.json"));
        assertTrue(manifest.contains("\"serverResolvedModelMatchesRequest\" : false"));
        assertTrue(manifest.contains("\"fullProviderEvidenceGate\" : false"));
    }

    @Test
    void typedInvalidEvidenceSurvivesValidLookingMetrics(@TempDir Path tempDir) throws Exception {
        var options = options(writeSuite(tempDir.resolve("suite-sticky")),
                "deepseek", "deepseek-v4-flash", 1, tempDir.resolve("results"), "sticky");
        var report = BenchmarkCoordinatorMain.run(options,
                provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                (request, workspace, home, timeout) ->
                        BenchmarkCoordinatorMain.WorkerExecution.completed(
                                BenchmarkProtocol.WorkerResponse.failure("USAGE_UNPROVEN",
                                        "earlier response lacked usage",
                                        successfulWorker(request, "done").response().metrics()),
                                0, 20, ""),
                successfulVerifier(), CLOCK);
        assertEquals(1, report.aggregate().infraErrorEpisodes());
        assertEquals("USAGE_UNPROVEN", report.outcomes().get(0).failureType());
        assertFalse(report.aggregate().fullProviderEvidenceGate());
        assertNull(report.aggregate().diagnosticMeanWeightedScore());
    }

    @Test
    void validCandidateFailuresDoNotInvalidateProviderEvidence(@TempDir Path tempDir)
            throws Exception {
        for (String failure : java.util.List.of("NO_PROVIDER_CALL", "LLM_API_ERROR",
                "CONTEXT_BUDGET_EXCEEDED")) {
            var options = options(writeSuite(tempDir.resolve("suite-" + failure)),
                    "deepseek", "deepseek-v4-flash", 1, tempDir.resolve("results"), failure);
            var report = BenchmarkCoordinatorMain.run(options,
                    provider -> new BenchmarkCoordinatorMain.ProviderSettings(API_KEY, null),
                    (request, workspace, home, timeout) -> {
                        var limits = request.agentLimits();
                        boolean noCall = failure.equals("NO_PROVIDER_CALL");
                        var metrics = new TracingLlmClient.Metrics(
                                noCall ? 0 : 1, 0, 0, 0, 0, 20,
                                0, "", false, false,
                                noCall ? "" : "a".repeat(64),
                                noCall ? "" : "b".repeat(64), !noCall,
                                limits.contextWindowCapTokens(), limits.contextWindowCapTokens(),
                                limits.maxOutputTokensPerCall(), 0, 0, false);
                        return BenchmarkCoordinatorMain.WorkerExecution.completed(
                                BenchmarkProtocol.WorkerResponse.failure(failure, "failed", metrics),
                                0, 20, "");
                    }, successfulVerifier(), CLOCK);
            assertEquals(0, report.aggregate().infraErrorEpisodes(), failure);
            assertEquals(0.0, report.aggregate().diagnosticMeanWeightedScore(), failure);
            assertEquals(failure, report.outcomes().get(0).failureType());
            assertTrue(report.aggregate().fullProviderEvidenceGate(), failure);
            assertFalse(report.aggregate().publishable()); // development run, never formal
        }
    }

    private static BenchmarkCoordinatorMain.WorkerExecution successfulWorker(
            BenchmarkProtocol.WorkerRequest request,
            String answer) {
        // 模式转发的正例使用合成的单次请求证据，不表示真实 Provider / Team 执行已通过。
        ScopedRequestFingerprints scoped = "team".equalsIgnoreCase(request.mode())
                ? new ScopedRequestFingerprints(2, "TEAM", java.util.List.of(
                        new ScopedRequestFingerprints.Request(1,
                                new ScopedRequestFingerprints.Binding(
                                        "team:00000000-0000-0000-0000-000000000001",
                                        "c".repeat(64), "d".repeat(64)),
                                "a".repeat(64), "b".repeat(64))))
                : null;
        return successfulWorker(request, answer, scoped);
    }

    private static BenchmarkCoordinatorMain.WorkerExecution successfulWorker(
            BenchmarkProtocol.WorkerRequest request, String answer, ScopedRequestFingerprints scoped) {
        BenchmarkProtocol.AgentLimits limits = request.agentLimits();
        return BenchmarkCoordinatorMain.WorkerExecution.completed(
                BenchmarkProtocol.WorkerResponse.success(
                        answer, new TracingLlmClient.Metrics(
                                1, 10, 5, 0, 1, 20,
                                1, request.model(), true, true,
                                "a".repeat(64), "b".repeat(64), true,
                                limits.contextWindowCapTokens(),
                                limits.contextWindowCapTokens(),
                                limits.maxOutputTokensPerCall(),
                                10, 15, true, scoped)),
                0, 20, "");
    }

    private static BenchmarkCoordinatorMain.VerifierExecutor successfulVerifier() {
        return (invocation, workspace, home, evidence, timeout) -> new BenchmarkVerifier.Result(
                BenchmarkVerifier.Status.PASSED,
                0,
                1,
                invocation.materialize(workspace, evidence).arguments(),
                "PASS",
                "",
                false,
                false,
                true,
                "test-profile");
    }

    private static BenchmarkCoordinatorMain.VerifierExecutor devSafePermissionFailureVerifier(boolean leak) {
        return (invocation, workspace, home, evidence, timeout) -> {
            if (leak) Files.writeString(workspace.getParent().resolve("synthetic-leak.txt"), API_KEY);
            return new BenchmarkVerifier.Result(BenchmarkVerifier.Status.FAILED, 2, 1,
                    invocation.materialize(workspace, evidence).arguments(), "",
                    "dev-secret-safe-bundle: INFRA_ERROR: cannot prove forbidden-file read denial\n",
                    false, false, true, "test-profile");
        };
    }

    /** 仅替换本次 @TempDir 中的合成 episode；每次移动前验证绝对路径归属，结束恢复原身份。 */
    private static final class EpisodePathObstruction implements AutoCloseable {
        private static final String MARKER = "paicli-purge-episode-path-obstruction\n";
        private final Path temporaryRoot;
        private final Path original;
        private final Path displaced;
        private final Object identity;
        private boolean moved;
        private boolean replacementCreated;

        private EpisodePathObstruction(Path temporaryRoot, Path original) throws IOException {
            this.temporaryRoot = temporaryRoot.toRealPath();
            this.original = original.toAbsolutePath().normalize();
            this.displaced = this.temporaryRoot.resolve("displaced-episode-" + java.util.UUID.randomUUID());
            requireContained(this.original);
            requireContained(this.displaced);
            if (!this.original.equals(this.original.toRealPath()))
                throw new IOException("test episode path must not resolve through a link");
            this.identity = SecureFiles.identity(this.original, Files.readAttributes(this.original,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }

        private static EpisodePathObstruction install(Path temporaryRoot, Path original) throws IOException {
            var obstruction = new EpisodePathObstruction(temporaryRoot, original);
            obstruction.requireContained(obstruction.original);
            obstruction.requireContained(obstruction.displaced);
            Files.move(obstruction.original, obstruction.displaced);
            obstruction.moved = true;
            try {
                SecureFiles.createFile(obstruction.original);
                obstruction.replacementCreated = true;
                Files.writeString(obstruction.original, MARKER);
                return obstruction;
            } catch (IOException | RuntimeException failure) {
                try { obstruction.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private void requireContained(Path path) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.equals(path) || normalized.equals(temporaryRoot)
                    || !normalized.startsWith(temporaryRoot)
                    || !normalized.getParent().toRealPath().startsWith(temporaryRoot))
                throw new IOException("test filesystem mutation escapes this @TempDir");
        }

        @Override
        public void close() throws IOException {
            if (!moved) return;
            requireContained(original);
            requireContained(displaced);
            if (replacementCreated) {
                if (!Files.isRegularFile(original, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(original) || !MARKER.equals(Files.readString(original)))
                    throw new IOException("test obstruction file changed; refusing to remove another object");
                Files.delete(original);
            }
            requireContained(displaced);
            requireContained(original);
            Files.move(displaced, original);
            moved = false;
            Object restored = SecureFiles.identity(original, Files.readAttributes(original,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            if (!identity.equals(restored)) throw new IOException("test episode identity was not restored");
        }
    }

    private static Path writeDevSafeSuite(Path directory) throws Exception {
        Path suite = writeSuite(directory);
        Path validators = com.paicli.eval.benchmark.SecureFiles.createDirectories(directory.resolve("validators/dev"));
        Files.copy(directory.resolve("validators/check.sh"), validators.resolve("dev-secret-safe-bundle.sh"));
        Files.writeString(suite, Files.readString(suite).replace("case-one", "dev-secret-safe-bundle")
                .replace("validators/check.sh", "validators/dev/dev-secret-safe-bundle.sh"));
        return suite;
    }

    private static BenchmarkCoordinatorMain.Options options(Path suite,
                                                            String provider,
                                                            String model,
                                                            int repeats,
                                                            Path output,
                                                            String runId) {
        return options(suite, provider, model, repeats, output, runId, Set.of(), BenchmarkToolProfile.FILE_ONLY);
    }

    private static BenchmarkCoordinatorMain.Options options(Path suite, String provider, String model,
                                                            int repeats, Path output, String runId,
                                                            Set<String> caseIds, BenchmarkToolProfile profile) {
        // 本类注入 synthetic Worker/Verifier；显式本机绝对路径不代表安装或运行了 Docker。
        return new BenchmarkCoordinatorMain.Options(
                suite,
                provider,
                model,
                repeats,
                output,
                runId,
                caseIds,
                profile,
                BenchmarkCoordinatorMain.WorkerIsolation.HOST_DEV,
                null, null, null, null, null, null,
                BenchmarkCoordinatorMain.VerifierIsolation.SEATBELT,
                null, suite.toAbsolutePath().getParent().resolve("not-executed-docker-fixture"),
                Duration.ofSeconds(5));
    }

    private static Path writeSuite(Path directory) throws Exception {
        return writeSuite(directory, "react");
    }

    private static Path writeSuite(Path directory, String mode) throws Exception {
        Path fixture = com.paicli.eval.benchmark.SecureFiles.createDirectories(directory.resolve("fixtures/case"));
        Files.writeString(fixture.resolve("README.md"), "write done.txt");
        Path validators = com.paicli.eval.benchmark.SecureFiles.createDirectories(directory.resolve("validators"));
        Files.writeString(validators.resolve("check.sh"), """
                #!/bin/bash
                set -eu
                test "$(cat "$1/done.txt")" = "done"
                """);
        Path suite = directory.resolve("suite.json");
        Files.writeString(suite, """
                {
                  "version":"1",
                  "name":"coordinator-test",
                  "cases":[{
                    "id":"case-one",
                    "title":"Case one",
                    "category":"coding",
                    "level":"L1",
                    "weight":100,
                    "mode":"%s",
                    "fixturePath":"fixtures/case",
                    "prompt":"complete task",
                    "verifierType":"command",
                    "verifierCommand":["bash","validators/check.sh","{workspace}"],
                    "status":"active"
                  }]
                }
                """.formatted(mode));
        return suite;
    }

    private static Path onlyEpisode(Path runDirectory) throws Exception {
        try (var paths = Files.find(runDirectory, 8,
                (path, attributes) -> attributes.isDirectory()
                        && path.getFileName().toString().startsWith("repeat-"))) {
            return paths.findFirst().orElseThrow();
        }
    }
}
