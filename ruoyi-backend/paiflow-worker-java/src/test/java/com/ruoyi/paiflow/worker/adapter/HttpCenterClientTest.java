package com.ruoyi.paiflow.worker.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Event;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;
import com.ruoyi.paiflow.worker.service.WorkerException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpCenterClientTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final Lease lease = new Lease("run-1", "lease-1", 1);

    @Test void officialVerifyTopLevelContextBindsInternalAssignmentWithoutChangingDslOrRawTask() throws Exception {
        ObjectNode response=verification();ObjectNode data=(ObjectNode)response.get("data");
        ObjectNode raw=(ObjectNode)data.get("assignment");raw.remove("executionContext");
        JsonNode original=raw.deepCopy();ObjectNode context=mapper.createObjectNode().put("flowId","fixed-flow")
                .put("userId","ruoyi:1").put("appId","debug:9").put("chatId","实际中心会话");
        data.set("executionContext",context);
        var actual=client(response,null).verify(lease);
        assertEquals(mapper.convertValue(context,Map.class),actual.executionContext());
        assertEquals(original.get("dsl"),mapper.valueToTree(actual.dsl()));assertEquals(original.path("dslHash").asText(),actual.dslHash());
        assertEquals(original.path("dslCanonicalBase64").asText(),actual.dslCanonicalBase64());assertEquals(original,raw);
        assertThrows(UnsupportedOperationException.class,()->actual.executionContext().put("chatId","changed"));
    }

    @Test void verifyAcceptsMatchingDualContextsAndRejectsConflictingOrMalformedTopContext() throws Exception {
        ObjectNode context=mapper.createObjectNode().put("flowId","fixed-flow").put("userId","ruoyi:1").put("appId","debug:9").put("chatId","");
        ObjectNode matching=verification();((ObjectNode)matching.get("data")).set("executionContext",context);
        ((ObjectNode)matching.path("data").path("assignment")).set("executionContext",context.deepCopy());
        assertEquals("",client(matching,null).verify(lease).executionContext().get("chatId"));
        for(String changed:java.util.List.of("flowId","userId","appId","chatId")){
            ObjectNode mismatch=matching.deepCopy();((ObjectNode)mismatch.path("data").path("executionContext")).put(changed,"different");
            assertEquals("LEASE_LOST",assertThrows(WorkerException.class,()->client(mismatch,null).verify(lease)).getCode());
        }
        for(JsonNode wrong:java.util.List.of(mapper.nullNode(),mapper.valueToTree("bad"),mapper.createObjectNode(),context.deepCopy().put("extra",true),context.deepCopy().put("chatId",1),context.deepCopy().put("userId","worker:1"))){
            ObjectNode invalid=matching.deepCopy();((ObjectNode)invalid.get("data")).set("executionContext",wrong);
            assertEquals("LEASE_LOST",assertThrows(WorkerException.class,()->client(invalid,null).verify(lease)).getCode());
        }
        ObjectNode legacy=verification();assertNull(client(legacy,null).verify(lease).executionContext());
        ((ObjectNode)legacy.get("data")).putNull("executionContext");assertNull(client(legacy,null).verify(lease).executionContext());
    }

    @Test
    void formalModelAndToolOperationsPreserveAllSevenFieldsIncludingTopLevelNull() throws Exception {
        for (String operation : java.util.List.of("model.invoke", "tools.read")) {
            AtomicReference<HttpRequest> captured = new AtomicReference<>();
            ObjectNode ack = operationAck("RUNNING");
            boolean tool = "tools.read".equals(operation);
            String nodeId = tool ? "plugin::original" : "spark-llm::original";
            Map<String, Object> parameters = tool ? Map.of("resourceId", "tool:1:version:2:operation:ZWNobw", "resourceVersion", "2",
                    "kind", "tool", "provider", "spark-link", "resourceBindings", Map.of(), "legacyPayload",
                    Map.of("header", Map.of("app_id", "fixed-app"), "parameter", Map.of("tool_id", "1", "operation_id", "echo", "version", "V1.0"),
                            "payload", Map.of("message", Map.of())))
                    : Map.of("resourceId", "model:1:version:2", "resourceVersion", "2", "provider", "openai", "modelName", "fixed-model",
                            "mode", "chat", "messages", java.util.List.of(Map.of("role", "user", "content", "原消息中文")),
                            "options", Map.of("temperature", 0.5, "maxTokens", 1024, "topK", 4, "patchId", "0", "respFormat", 0,
                                    "searchDisable", true, "extraParams", Map.of()));
            if (tool) {
                ObjectNode data = (ObjectNode) ack.get("data");
                data.put("nodeId", nodeId).put("operationId", "tool-op:e34337c2-0a20-4b15-8eba-5148f71e72f2")
                        .put("resourceId", "tool:1:version:2:operation:ZWNobw").put("provider", "spark-link").put("kind", "tool")
                        .put("operation", operation).put("idempotencyKey", "run-1:original-key").putNull("failureKind")
                        .putNull("legacyResponse").put("stopped", false).putNull("proofId");
                data.set("resourceBindings", mapper.createObjectNode());
            }
            Map<String, Object> actual = client(ack, captured).operation(lease, nodeId, operation,
                    parameters, "run-1:original-key");
            JsonNode body = mapper.readTree(requestBody(captured.get()));
            assertEquals(7, body.size());
            assertEquals(java.util.Set.of("leaseId", "attempt", "nodeId", "iterationId", "operation", "parameters", "idempotencyKey"),
                    mapper.convertValue(body, Map.class).keySet());
            assertTrue(body.has("iterationId"));
            assertTrue(body.get("iterationId").isNull());
            assertEquals("lease-1", body.path("leaseId").asText());
            assertEquals(1, body.path("attempt").asInt());
            assertEquals(nodeId, body.path("nodeId").asText());
            assertEquals(operation, body.path("operation").asText());
            assertEquals("run-1:original-key", body.path("idempotencyKey").asText());
            assertEquals(mapper.valueToTree(parameters), body.get("parameters"));
            assertEquals(ack.get("data"), mapper.valueToTree(actual));
            assertEquals("/context/internal/paiflow/v1/runs/run-1/operations", captured.get().uri().getPath());
            assertEquals("Bearer test-service-token", captured.get().headers().firstValue("Authorization").orElseThrow());
        }
        // 即使调用者mapper省略Map中的null，正式ObjectNode仍必须发送必填iterationId。
        ObjectMapper omitNulls = mapper.copy().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpCenterClient center = new HttpCenterClient(URI.create("https://center.invalid"), "test-token", omitNulls, request -> {
            captured.set(request);
            return new HttpCenterClient.WireResponse(200, mapper.writeValueAsBytes(operationAck("RUNNING")));
        });
        center.operation(lease, "spark-llm::original", "model.invoke", Map.of(), "original-key");
        assertTrue(mapper.readTree(requestBody(captured.get())).get("iterationId").isNull());
    }

    @Test
    void framesReadsTheSameOperationWithExactCursorAndKeepsOriginalUsageAndNullFinish() throws Exception {
        ObjectNode ack = operationAck("RUNNING");
        ObjectNode data = (ObjectNode) ack.get("data");
        data.put("operationState", "RUNNING").put("nextCursor", 2).put("done", false).put("lastSeq", 2).putNull("finishSeq").putNull("failureKind");
        data.set("frames", mapper.readTree("[{\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3,\"total_tokens\":7}}]"));
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        Map<String, Object> actual = client(ack, captured).operationFrames(lease, "e34337c2-0a20-4b15-8eba-5148f71e72f2", 1, 64);
        assertEquals("/context/internal/paiflow/v1/runs/run-1/operations/e34337c2-0a20-4b15-8eba-5148f71e72f2/frames", captured.get().uri().getPath());
        assertEquals(mapper.valueToTree(Map.of("leaseId", "lease-1", "attempt", 1, "afterCursor", 1, "limit", 64)),
                mapper.readTree(requestBody(captured.get())));
        assertEquals(data, mapper.valueToTree(actual));
        assertEquals(false, actual.get("done"));
        assertTrue(actual.containsKey("finishSeq"));
        assertNull(actual.get("finishSeq"));
    }

    @Test
    void toolStatusAndCancelKeepCompleteUnknownAckAndEncodeOneOperationPathSegment() throws Exception {
        String operationId = "tool-op:e34337c2-0a20-4b15-8eba-5148f71e72f2";
        ObjectNode ack = operationAck("EFFECT_UNKNOWN");
        ObjectNode data = (ObjectNode) ack.get("data");
        data.put("operationId", operationId).put("nodeId", "plugin::original").put("resourceId", "tool:1:version:2:operation:ZWNobw")
                .put("operation", "tools.invoke").put("idempotencyKey", "natural-tool-key")
                .put("kind", "tool").put("provider", "spark-link").put("failureKind", "cancelled")
                .put("stopped", false).putNull("proofId").putNull("legacyResponse");
        data.set("resourceBindings", mapper.createObjectNode());
        for (String action : java.util.List.of("status", "cancel")) {
            AtomicReference<HttpRequest> captured = new AtomicReference<>();
            HttpCenterClient center = client(ack, captured);
            Map<String, Object> actual = "status".equals(action) ? center.operationStatus(lease, operationId) : center.cancelOperation(lease, operationId);
            assertEquals("/context/internal/paiflow/v1/runs/run-1/operations/tool-op%3Ae34337c2-0a20-4b15-8eba-5148f71e72f2/" + action,
                    captured.get().uri().getRawPath());
            assertEquals(mapper.valueToTree(Map.of("leaseId", "lease-1", "attempt", 1)), mapper.readTree(requestBody(captured.get())));
            assertEquals(data, mapper.valueToTree(actual));
            assertEquals("EFFECT_UNKNOWN", actual.get("state"));
            assertEquals(false, actual.get("stopped"));
            assertNull(actual.get("proofId"));
        }
    }

    @Test
    void operationReadsEnforcePublicBudgetsAndNeverRetryUnknownTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpCenterClient center = new HttpCenterClient(URI.create("https://center.invalid"), "test-token", mapper, request -> {
            calls.incrementAndGet(); throw new IOException("隔离Exchange模拟回执丢失");
        });
        for (long cursor : new long[]{-1, 10001}) {
            assertEquals("OPERATION_PROTOCOL_INVALID", assertThrows(WorkerException.class,
                    () -> center.operationFrames(lease, "original-operation", cursor, 64)).getCode());
        }
        for (int limit : new int[]{0, 65}) {
            assertThrows(WorkerException.class, () -> center.operationFrames(lease, "original-operation", 0, limit));
        }
        assertEquals(0, calls.get());
        assertEquals("CENTER_UNAVAILABLE", assertThrows(WorkerException.class,
                () -> center.operationFrames(lease, "original-operation", 10000, 1)).getCode());
        assertEquals(1, calls.get());
        assertThrows(WorkerException.class, () -> center.operationStatus(lease, "original-operation"));
        assertEquals(2, calls.get());
        assertThrows(WorkerException.class, () -> center.cancelOperation(lease, "original-operation"));
        assertEquals(3, calls.get());
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        HttpCenterClient overBudget = new HttpCenterClient(URI.create("https://center.invalid"), "test-token", mapper,
                request -> new HttpCenterClient.WireResponse(200, oversized));
        assertEquals("CENTER_RESPONSE_TOO_LARGE", assertThrows(WorkerException.class,
                () -> overBudget.operationFrames(lease, "original-operation", 0, 64)).getCode());
        assertEquals("CENTER_RESPONSE_TOO_LARGE", assertThrows(WorkerException.class,
                () -> overBudget.operationStatus(lease, "original-operation")).getCode());
        assertEquals("CENTER_RESPONSE_TOO_LARGE", assertThrows(WorkerException.class,
                () -> overBudget.cancelOperation(lease, "original-operation")).getCode());
    }

    @Test
    void legacyEffectAuthorizationStillOmitsOptionalNullApproval() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        var effect = new com.ruoyi.paiflow.worker.domain.WorkerProtocol.Effect("lease-1", 1, "model.infer", "legacy-resource",
                "https://approved.invalid/api", null);
        client(mapper.readTree("{\"code\":200,\"data\":{\"authorized\":true,\"grantId\":\"legacy-grant\"}}"), captured).authorizeEffect(lease, effect);
        JsonNode body = mapper.readTree(requestBody(captured.get()));
        assertEquals(5, body.size());
        assertFalse(body.has("approvalRef"));
        assertFalse(body.has("iterationId"));
    }

    private ObjectNode operationAck(String state) {
        ObjectNode data = mapper.createObjectNode().put("runId", "run-1").put("leaseId", "lease-1").put("attempt", 1)
                .put("workflowVersionId", "version-1").put("dslHash", "0".repeat(64)).put("nodeId", "spark-llm::original")
                .putNull("iterationId").put("operationId", "e34337c2-0a20-4b15-8eba-5148f71e72f2")
                .put("resourceId", "model:1:version:2").put("resourceVersion", "2").put("provider", "openai")
                .put("state", state);
        ObjectNode envelope = mapper.createObjectNode().put("code", 200);
        envelope.set("data", data); return envelope;
    }

    @Test
    void beginRequiresExactSnapshotBindingAndOnlySendsTheLeaseIdentity() throws Exception {
        var task = mapper.treeToValue(verification().path("data").path("assignment"), com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment.class);
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        client(beginAck(), captured).beginExecution(lease, task);
        assertEquals("https://center.invalid/context/internal/paiflow/v1/runs/run-1/execution/begin", captured.get().uri().toString());
        assertEquals("Bearer test-service-token", captured.get().headers().firstValue("Authorization").orElseThrow());
        assertEquals(mapper.valueToTree(Map.of("leaseId", "lease-1", "attempt", 1)), mapper.readTree(requestBody(captured.get())));
    }

    @Test
    void missingCoercedOrMismatchedBeginAckCannotAuthorizeExecution() throws Exception {
        var task = mapper.treeToValue(verification().path("data").path("assignment"), com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment.class);
        for (String field : java.util.List.of("begun", "runId", "leaseId", "attempt", "workflowVersionId", "dslHash")) {
            ObjectNode missing = beginAck(); ((ObjectNode) missing.get("data")).remove(field);
            assertThrows(WorkerException.class, () -> client(missing, null).beginExecution(lease, task));
            ObjectNode mismatched = beginAck(); ObjectNode data = (ObjectNode) mismatched.get("data");
            if ("begun".equals(field)) data.put(field, false);
            else if ("attempt".equals(field)) data.put(field, 2);
            else data.put(field, "different");
            assertThrows(WorkerException.class, () -> client(mismatched, null).beginExecution(lease, task));
        }
        ObjectNode scalarBoolean = beginAck(); ((ObjectNode) scalarBoolean.get("data")).put("begun", "true");
        assertThrows(WorkerException.class, () -> client(scalarBoolean, null).beginExecution(lease, task));
        for (JsonNode attempt : java.util.List.of(mapper.readTree("1.0"), mapper.readTree("\"1\""), mapper.readTree("4294967297"))) {
            ObjectNode invalid = beginAck(); ((ObjectNode) invalid.get("data")).set("attempt", attempt);
            assertThrows(WorkerException.class, () -> client(invalid, null).beginExecution(lease, task));
        }
    }

    @Test
    void beginConflictAndUnknownTransportAreSentOnceAndNeverRetried() throws Exception {
        var task = mapper.treeToValue(verification().path("data").path("assignment"), com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment.class);
        for (boolean unknown : new boolean[]{false, true}) {
            AtomicInteger calls = new AtomicInteger();
            HttpCenterClient center = new HttpCenterClient(URI.create("https://center.invalid"), "test-token", mapper, request -> {
                calls.incrementAndGet();
                if (unknown) throw new IOException("离线模拟许可ACK丢失");
                return new HttpCenterClient.WireResponse(409, "{\"code\":409}".getBytes(StandardCharsets.UTF_8));
            });
            assertThrows(WorkerException.class, () -> center.beginExecution(lease, task));
            assertEquals(1, calls.get());
        }
    }

    private ObjectNode beginAck() throws Exception {
        ObjectNode response = mapper.createObjectNode().put("code", 200);
        ObjectNode data = mapper.createObjectNode().put("begun", true).put("runId", "run-1").put("leaseId", "lease-1").put("attempt", 1)
                .put("workflowVersionId", "version-1").put("dslHash", verification().path("data").path("dslHash").asText());
        response.set("data", data); return response;
    }

    @Test
    void claimOnlyReturnsLeaseIdentityAndDoesNotSendAnyTaskOrCredentialsInBody() throws Exception {
        ObjectNode response = mapper.createObjectNode().put("code", 200);
        response.set("data", verification().path("data").path("assignment"));
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        assertEquals(lease, client(response, captured).claim());
        assertEquals("https://center.invalid/context/internal/paiflow/v1/runs/claim", captured.get().uri().toString());
        assertEquals(mapper.createObjectNode(), mapper.readTree(requestBody(captured.get())));
        assertEquals("Bearer test-service-token", captured.get().headers().firstValue("Authorization").orElseThrow());
        assertNull(client(mapper.readTree("{\"code\":200,\"data\":null}"), null).claim());
    }

    @Test
    void malformedClaimOrDifferentEngineCannotReachRunner() throws Exception {
        ObjectNode assignment = (ObjectNode) verification().path("data").path("assignment");
        ObjectNode response = mapper.createObjectNode().put("code", 200);
        response.set("data", assignment);
        assignment.put("engineProfile", "python");
        assertThrows(WorkerException.class, () -> client(response, null).claim());
        assignment.put("engineProfile", "java").put("leaseId", "invalid/path");
        assertThrows(WorkerException.class, () -> client(response, null).claim());
        assignment.put("leaseId", "lease-1").remove("attempt");
        assertThrows(WorkerException.class, () -> client(response, null).claim());
    }

    @Test
    void verifyConsumesUnifiedEnvelopeAndOnlySendsLeaseIdentity() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        var assignment = client(verification(), captured).verify(lease);
        assertEquals("run-1", assignment.runId());
        assertEquals("feda530fdaa8148211c21053707be246e665f0a750b56942bf89f3ce5e8c2fae", assignment.dslHash());
        assertEquals("https://center.invalid/context/internal/paiflow/v1/runs/run-1/lease/verify", captured.get().uri().toString());
        assertEquals("Bearer test-service-token", captured.get().headers().firstValue("Authorization").orElseThrow());
        assertEquals(mapper.valueToTree(Map.of("leaseId", "lease-1", "attempt", 1)), mapper.readTree(requestBody(captured.get())));
    }

    @Test
    void unverifiedLeaseAndMismatchedMetadataCannotBecomeAnAssignment() throws Exception {
        ObjectNode response = verification();
        ((ObjectNode) response.get("data")).put("valid", false);
        ObjectNode rejected = response;
        assertEquals("LEASE_LOST", assertThrows(WorkerException.class, () -> client(rejected, null).verify(lease)).getCode());
        response = verification();
        ((ObjectNode) response.get("data")).put("lastEventSeq", 99);
        ObjectNode mismatch = response;
        assertThrows(WorkerException.class, () -> client(mismatch, null).verify(lease));
        response = verification();
        ((ObjectNode) response.get("data")).put("cancelRequested", true);
        ObjectNode cancelled = response;
        assertEquals("RUN_CANCELLED", assertThrows(WorkerException.class, () -> client(cancelled, null).verify(lease)).getCode());
    }

    @Test
    void heartbeatUsesExplicitValidAndCancelFlagsRatherThanHttpSuccess() throws Exception {
        JsonNode response = mapper.readTree("{\"code\":200,\"data\":{\"valid\":false,\"cancelRequested\":true,\"runId\":\"run-1\"}}");
        var heartbeat = client(response, null).heartbeat(lease);
        assertFalse(heartbeat.valid());
        assertTrue(heartbeat.cancelRequested());
        JsonNode missing = mapper.readTree("{\"code\":200,\"data\":{}}");
        assertThrows(WorkerException.class, () -> client(missing, null).heartbeat(lease));
    }

    @Test
    void eventAckMustConfirmAcceptedAndExactSequence() throws Exception {
        Event event = new Event("paiflow.events.v1", "run-1", "lease-1", 1, 13, null, null, "text.delta", Instant.parse("2026-10-03T00:00:00Z"), Map.of("delta", "text"));
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"seq\":13}}"), captured).event(event);
        JsonNode request = mapper.readTree(requestBody(captured.get()));
        assertFalse(request.has("nodeId"));
        assertFalse(request.has("iterationId"));
        assertTrue(request.path("timestamp").isTextual());
        assertEquals(event.timestamp().toString(), request.path("timestamp").asText());
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"seq\":12}}"), null).event(event));
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":false,\"seq\":13}}"), null).event(event));
    }

    @Test
    void scalarEventPayloadIsRejectedBeforeTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpCenterClient center = new HttpCenterClient(URI.create("https://center.invalid"), "test-token", mapper, request -> {
            calls.incrementAndGet(); throw new AssertionError("无效事件不能发送");
        });
        Event invalid = new Event("paiflow.events.v1", "run-1", "lease-1", 1, 13, null, null,
                "text.delta", Instant.parse("2026-10-03T00:00:00Z"), "text");
        assertEquals("EVENT_PROTOCOL_INVALID", assertThrows(WorkerException.class, () -> center.event(invalid)).getCode());
        assertEquals(0, calls.get());
    }

    @Test
    void eventRequestLargerThanResponseBudgetReachesInterceptedTransportIntact() throws Exception {
        String text = "x".repeat(3 * 1024 * 1024);
        Event event = new Event("paiflow.events.v1", "run-1", "lease-1", 1, 13, null, null,
                "text.delta", Instant.parse("2026-10-03T00:00:00Z"), Map.of("delta", text));
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"seq\":13}}"), captured).event(event);
        byte[] bytes = requestBody(captured.get());
        assertTrue(bytes.length > 2 * 1024 * 1024);
        assertTrue(bytes.length < 4 * 1024 * 1024);
        assertEquals(text, mapper.readTree(bytes).path("payload").path("delta").asText());
    }

    @Test
    void terminalAckMustConfirmSameStateAndNetworkErrorsAreNotRetried() throws Exception {
        Result result = new Result("lease-1", 1, "FAILED", null, null);
        client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"state\":\"FAILED\"}}"), null).result(lease, result);
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"state\":\"COMPLETED\"}}"), null).result(lease, result));
        AtomicInteger calls = new AtomicInteger();
        HttpCenterClient failed = new HttpCenterClient(URI.create("https://center.invalid"), "test-token", mapper, request -> {
            calls.incrementAndGet(); throw new IOException("offline transport only");
        });
        assertEquals("CENTER_UNAVAILABLE", assertThrows(WorkerException.class, () -> failed.heartbeat(lease)).getCode());
        assertEquals(1, calls.get());
    }

    @Test
    void effectCompletionAckMustConfirmExactGrantAndState() throws Exception {
        var completion = new com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectCompletion("lease-1", 1,
                "grant-1", "COMPLETED", java.util.Map.of("sha256", "actual-response-hash", "byteLength", 12));
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"grantId\":\"grant-1\",\"state\":\"COMPLETED\"}}"), captured).completeEffect(lease, completion);
        assertEquals("/context/internal/paiflow/v1/runs/run-1/effects/complete", captured.get().uri().getPath());
        JsonNode request = mapper.readTree(requestBody(captured.get()));
        assertEquals("grant-1", request.path("grantId").asText());
        assertEquals("actual-response-hash", request.path("receipt").path("sha256").asText());
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":false,\"grantId\":\"grant-1\",\"state\":\"COMPLETED\"}}"), null).completeEffect(lease, completion));
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"grantId\":\"grant-2\",\"state\":\"COMPLETED\"}}"), null).completeEffect(lease, completion));
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"grantId\":\"grant-1\",\"state\":\"UNKNOWN\"}}"), null).completeEffect(lease, completion));
    }

    @Test
    void requiredAssignmentFieldCannotSilentlyBecomePrimitiveZero() throws Exception {
        ObjectNode missing = verification();
        ((ObjectNode) missing.path("data").path("assignment")).remove("lastEventSeq");
        assertThrows(WorkerException.class, () -> client(missing, null).verify(lease));
        ObjectNode decimal = verification();
        ((ObjectNode) decimal.path("data").path("assignment")).put("attempt", 1.25);
        assertThrows(WorkerException.class, () -> client(decimal, null).verify(lease));
    }

    @Test
    void artifactCommitRequiresExactArtifactAcknowledgement() throws Exception {
        client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"artifactId\":\"artifact-1\"}}"), null)
                .commitArtifact(lease, "artifact-1", "0".repeat(64), 12);
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":false,\"artifactId\":\"artifact-1\"}}"), null)
                .commitArtifact(lease, "artifact-1", "0".repeat(64), 12));
        assertThrows(WorkerException.class, () -> client(mapper.readTree("{\"code\":200,\"data\":{\"accepted\":true,\"artifactId\":\"artifact-2\"}}"), null)
                .commitArtifact(lease, "artifact-1", "0".repeat(64), 12));
    }

    private HttpCenterClient client(JsonNode response, AtomicReference<HttpRequest> captured) throws Exception {
        byte[] body = mapper.writeValueAsBytes(response);
        return new HttpCenterClient(URI.create("https://center.invalid/context"), "test-service-token", mapper, request -> {
            if (captured != null) captured.set(request);
            return new HttpCenterClient.WireResponse(200, body);
        });
    }

    private ObjectNode verification() throws Exception {
        JsonNode fixture = mapper.readTree(Path.of(System.getProperty("basedir", ".")).resolve("../../contracts/paiflow/fixtures/dsl-canonical.json").toFile());
        ObjectNode assignment = mapper.createObjectNode();
        assignment.put("schemaVersion", "paiflow.worker.v1").put("runId", "run-1").put("leaseId", "lease-1").put("attempt", 1)
                .put("workflowVersionId", "version-1").put("dslHash", fixture.path("dslHash").asText())
                .put("dslCanonicalBase64", fixture.path("dslCanonicalBase64").asText()).put("engineProfile", "java")
                .put("deadline", "2026-10-03T01:00:00Z").put("lastEventSeq", 12);
        assignment.set("dsl", fixture.get("dsl"));
        assignment.set("input", mapper.createObjectNode());
        assignment.set("resourceSnapshots", mapper.createObjectNode());
        assignment.set("effectPolicy", mapper.readTree("{\"mode\":\"read_only\",\"allowedOperations\":[]}"));
        ObjectNode data = mapper.createObjectNode().put("valid", true).put("cancelRequested", false)
                .put("dslHash", fixture.path("dslHash").asText()).put("workflowVersionId", "version-1").put("lastEventSeq", 12);
        data.set("assignment", assignment);
        ObjectNode response = mapper.createObjectNode().put("code", 200);
        response.set("data", data);
        return response;
    }

    private byte[] requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) { byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes); }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(output.toByteArray()); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
