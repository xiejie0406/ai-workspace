package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.llm.LlmClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RunnerBoundaryTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    private Path lockHome() throws IOException {
        Path home=Files.createDirectory(temp.resolve("lock-home"));
        DeviceCredentialCache.restrict(home);
        return home;
    }
    private static MockResponse ok(JsonNode data) { return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"code\":200,\"data\":" + data + "}"); }
    private ObjectNode claim(Path root) throws IOException {
        ObjectNode claim = CanonicalJson.MAPPER.createObjectNode();
        claim.put("taskId", "1"); claim.put("sessionId", "2"); claim.put("leaseId", "3"); claim.put("fencingToken", 8); claim.put("version", 4); claim.put("policyVersion", 1);
        claim.put("rootFingerprint", new ResourceFingerprint(root).rootFingerprint()); claim.put("expiresAt", Instant.now().plusSeconds(60).toString()); return claim;
    }
    private static ObjectNode permit(JsonNode proposal) {
        ObjectNode permit = proposal.deepCopy(); permit.put("decision", "PERMITTED"); permit.put("permitToken", "short-lived-permit"); permit.put("expiresAt", Instant.now().plusSeconds(60).toString()); return permit;
    }
    private static ObjectNode consumed(JsonNode binding){ObjectNode result=binding.deepCopy();result.put("consumed",true);return result;}
    private static ObjectNode acknowledged(JsonNode receipt, boolean archived) {
        ObjectNode result = receipt.deepCopy();
        result.remove("permitToken");
        result.put("accepted", true).put("acknowledged", true).put("archived", archived);
        if (archived) result.put("status", "LATE_ARCHIVED");
        return result;
    }
    @Test void canonicalHashIsStableAcrossObjectKeyOrderButKeepsArrayOrder() throws Exception {
        assertEquals(CanonicalJson.hash(CanonicalJson.parseObject("{\"b\":2,\"a\":{\"y\":3,\"x\":1}}")), CanonicalJson.hash(CanonicalJson.parseObject("{\"a\":{\"x\":1,\"y\":3},\"b\":2}")));
        assertNotEquals(CanonicalJson.hash(CanonicalJson.parseObject("{\"a\":[1,2]}")), CanonicalJson.hash(CanonicalJson.parseObject("{\"a\":[2,1]}")));
        assertThrows(IOException.class, () -> CanonicalJson.parseObject("[]"));
    }
    @Test void leaseRejectsExpiredMissingAndChangedVersion() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); ObjectNode value = claim(root);
        value.put("expiresAt", Instant.now().minusSeconds(1).toString()); ObjectNode expired = value; assertThrows(IOException.class, () -> new RunnerLease(expired));
        value = claim(root); value.remove("fencingToken"); ObjectNode missing = value; assertThrows(IOException.class, () -> new RunnerLease(missing));
        RunnerLease lease = new RunnerLease(claim(root)); ObjectNode heartbeat = CanonicalJson.MAPPER.createObjectNode().put("version", 9).put("expiresAt", Instant.now().plusSeconds(60).toString());
        assertThrows(IOException.class, () -> lease.heartbeat(heartbeat)); assertThrows(IOException.class, lease::ensureActive);
    }
    @Test void pathEscapeAndResourceChangeAreDetected() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); ResourceFingerprint resources = new ResourceFingerprint(root);
        assertThrows(IOException.class, () -> resources.resolve("../outside")); JsonNode args = CanonicalJson.parseObject("{\"path\":\"a.txt\"}");
        String before = resources.capture("write_file", args); Files.writeString(root.resolve("a.txt"), "new"); assertNotEquals(before, resources.capture("write_file", args));
    }
    @Test void cancellationStopsNewActionsButAllowsFinalEvidence()throws Exception{
        Path root=Files.createDirectory(temp.resolve("root"));RunnerLease lease=new RunnerLease(claim(root));
        ObjectNode heartbeat=CanonicalJson.MAPPER.createObjectNode().put("version",5).put("expiresAt",Instant.now().plusSeconds(60).toString()).put("cancelRequested",true);
        lease.heartbeat(heartbeat);assertTrue(lease.isCancellationRequested());assertThrows(IOException.class,lease::ensureActive);assertDoesNotThrow(lease::ensureEvidenceAllowed);
    }
    @Test void uncertainExecutionIsNeverConvertedIntoRunnableQueue() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); ReceiptSpool spool = new ReceiptSpool(temp.resolve("evidence"));
        Path evidence = spool.intent(new RunnerLease(claim(root)).identity(), "c1", "write_file", "arg", "res");
        assertTrue(spool.hasUncertainExecution("1")); spool.consumed(evidence, "permit"); assertTrue(spool.hasUncertainExecution("1"));
        spool.receipt(evidence, CanonicalJson.MAPPER.createObjectNode().put("taskId", "1")); assertFalse(spool.hasUncertainExecution("1")); assertEquals(1, spool.pending().size());
        spool.acknowledged(evidence); assertTrue(spool.pending().isEmpty());
        assertThrows(IOException.class, () -> spool.read(temp.resolve("outside.json")));
    }
    @Test void tokenIsNeverSentToRedirectTargetOrQueryString() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer destination = new MockWebServer()) {
            server.start(); destination.start(); server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", destination.url("/leak")));
            try (CenterHttpClient client = new CenterHttpClient(server.url("/").toString(), "private-test-token", true)) {
                assertThrows(IOException.class, () -> client.get("/paicli/runner/v1/context"));
                RecordedRequest request = server.takeRequest(); assertEquals("private-test-token", request.getHeader("X-PaiCLI-Runner-Token")); assertFalse(request.getPath().contains("private-test-token")); assertEquals(0, destination.getRequestCount());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new CenterHttpClient("http://example.invalid", "secret", true));
    }
    @Test void refusedPermitNeverExecutesTool() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); AtomicInteger executions = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.start(); server.enqueue(ok(CanonicalJson.MAPPER.createObjectNode().put("decision", "DENIED")));
            try (CenterHttpClient client = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                CenterToolRegistry registry = registry(client, root, executions);
                assertFalse(registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}").successful()); assertEquals(0, executions.get());
                assertFalse(Files.exists(root.resolve(".paicli-command-sandbox")));assertFalse(Files.exists(root.resolve(".paicli")));assertFalse(Files.exists(root.resolve(".git")));
            }
        }
    }
    @Test void scopedTransportDoesNotReplaceOrCloseLeaseTransport()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(ok(CanonicalJson.MAPPER.createObjectNode()));server.enqueue(ok(CanonicalJson.MAPPER.createObjectNode()));
            try(CenterHttpClient leaseClient=new CenterHttpClient(server.url("/").toString(),"lease-token",true)){
                try(CenterHttpClient scoped=leaseClient.withRunnerToken("bridge-token")){scoped.get("/paicli/runner/v1/wechat");}
                leaseClient.get("/paicli/runner/v1/context");
                assertEquals("bridge-token",server.takeRequest().getHeader("X-PaiCLI-Runner-Token"));
                assertEquals("lease-token",server.takeRequest().getHeader("X-PaiCLI-Runner-Token"));
            }
        }
    }
    @Test void revisedApprovalReturnsFeedbackWithoutConsumingOrExecutingCandidate()throws Exception{
        Path root=Files.createDirectory(temp.resolve("root"));AtomicInteger executions=new AtomicInteger();
        ObjectNode revised=CanonicalJson.MAPPER.createObjectNode().put("path","safe.txt").put("content","reviewed");
        ObjectNode denied=CanonicalJson.MAPPER.createObjectNode().put("decision","DENIED").put("approvalDecision","REVISED")
                .put("feedback","请只修改safe.txt").put("revisedArgsHash",CanonicalJson.hash(revised));denied.set("revisedArguments",revised);
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(ok(denied));
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"session",true)){
                ToolOutput result=registry(client,root,executions).executeToolOutput("write_file","{\"path\":\"original.txt\",\"content\":\"x\"}");
                assertFalse(result.successful());assertTrue(result.text().contains("请只修改safe.txt"));assertTrue(result.text().contains("新toolCallId"));
                assertTrue(result.text().contains("safe.txt"));assertEquals(0,executions.get());assertEquals(1,server.getRequestCount());
            }
        }
        assertTrue(new ReceiptSpool(temp.resolve("spool")).pending().isEmpty());assertFalse(Files.exists(root.resolve("safe.txt")));
    }
    @Test void consumedPermitWithDifferentFenceNeverExecutesTool()throws Exception{
        Path root=Files.createDirectory(temp.resolve("root"));AtomicInteger executions=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest req){try{
                JsonNode binding=CanonicalJson.MAPPER.readTree(req.getBody().readUtf8());
                return req.getPath().endsWith("/proposals")?ok(permit(binding)):ok(consumed(binding).put("fencingToken",999));
            }catch(IOException failure){throw new IllegalStateException(failure);}}});server.start();
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"device",true)){
                assertFalse(registry(client,root,executions).executeToolOutput("write_file","{\"path\":\"a.txt\",\"content\":\"x\"}").successful());
                assertEquals(0,executions.get());
            }
            assertTrue(new ReceiptSpool(temp.resolve("spool")).hasUncertainExecution("1"));
        }
    }
    @Test void missingConsumeConfirmationNeverExecutesTool() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); AtomicInteger executions = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest req) {
                try { return req.getPath().endsWith("/proposals") ? ok(permit(CanonicalJson.MAPPER.readTree(req.getBody().readUtf8()))) : ok(CanonicalJson.MAPPER.createObjectNode()); }
                catch (IOException e) { throw new IllegalStateException(e); }
            }}); server.start();
            try (CenterHttpClient client = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                CenterToolRegistry registry = registry(client, root, executions); assertFalse(registry.executeToolOutput("write_file", "{\"path\":\"a.txt\"}").successful()); assertEquals(0, executions.get());
            }
            assertTrue(new ReceiptSpool(temp.resolve("spool")).hasUncertainExecution("1"));
        }
    }
    @Test void batchKeepsModelIdsButUsesIndependentCenterIdsAndAcknowledgedReceipts() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); AtomicInteger executions = new AtomicInteger(); List<String> ids = new CopyOnWriteArrayList<>(),modelHashes=new CopyOnWriteArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest req) {
                try {
                    JsonNode data = CanonicalJson.MAPPER.readTree(req.getBody().readUtf8());
                    if (req.getPath().endsWith("/proposals")) { ids.add(data.path("toolCallId").asText());modelHashes.add(data.path("modelToolCallIdHash").asText()); return ok(permit(data)); }
                    if (req.getPath().endsWith("/consume")) return ok(consumed(data));
                    if (req.getPath().endsWith("/receipts")) return ok(acknowledged(data,false));
                    return ok(CanonicalJson.MAPPER.createObjectNode());
                } catch (IOException e) { throw new IllegalStateException(e); }
            }}); server.start();
            try (CenterHttpClient client = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                var results = registry(client, root, executions).executeTools(List.of(new ToolInvocation("call-a", "read_file", "{\"path\":\"a.txt\"}"), new ToolInvocation("call-b", "read_file", "{\"path\":\"b.txt\"}")));
                assertEquals(2, executions.get()); assertTrue(results.stream().allMatch(result -> result.successful()));
                assertEquals(List.of("call-a","call-b"),results.stream().map(result->result.id()).toList());
                assertEquals(2,ids.stream().distinct().count());assertTrue(ids.stream().noneMatch(id->List.of("call-a","call-b").contains(id)));
                assertTrue(modelHashes.containsAll(List.of(CanonicalJson.sha256("call-a".getBytes(java.nio.charset.StandardCharsets.UTF_8)),CanonicalJson.sha256("call-b".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
            }
            assertTrue(new ReceiptSpool(temp.resolve("spool")).pending().isEmpty());
        }
    }
    @Test void cachedConsumeCannotAuthorizeSecondInvocationWithSameProviderCallId() throws Exception {
        Path root=Files.createDirectory(temp.resolve("root"));
        Path existing=root.resolve("unchanged.txt");Files.writeString(existing,"unchanged");
        String arguments="{\"path\":\"unchanged.txt\",\"content\":\"unchanged\"}";
        AtomicInteger executions=new AtomicInteger(),freshConsumptions=new AtomicInteger();
        List<JsonNode> proposals=new CopyOnWriteArrayList<>(),receipts=new CopyOnWriteArrayList<>();
        java.util.Map<String,String> requestHashes=new java.util.HashMap<>();
        java.util.Map<String,JsonNode> responses=new java.util.HashMap<>();
        java.util.Set<String> completedCalls=new java.util.HashSet<>();
        try(MockWebServer server=new MockWebServer()) {
            // 复用中心PcIdempotency的语义：同路径/键/输入直接重放最初响应，
            // 不重新进入状态校验；同键内容变化则409。执行器仅计数，不写文件。
            server.setDispatcher(new Dispatcher(){@Override public synchronized MockResponse dispatch(RecordedRequest request){try{
                JsonNode body=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                String path=request.getPath(),cacheKey=path+":"+request.getHeader("Idempotency-Key"),hash=CanonicalJson.hash(body);
                if(path.endsWith("/proposals"))proposals.add(body.deepCopy());
                if(path.endsWith("/receipts"))receipts.add(body.deepCopy());
                if(requestHashes.containsKey(cacheKey)) {
                    if(!hash.equals(requestHashes.get(cacheKey)))return new MockResponse().setResponseCode(409)
                            .setHeader("Content-Type","application/json").setBody("{\"code\":409,\"msg\":\"PC_IDEMPOTENCY_REUSED\"}");
                    return ok(responses.get(cacheKey));
                }
                ObjectNode reply;
                if(path.endsWith("/proposals")) {
                    reply=permit(body);
                    if(completedCalls.contains(body.path("toolCallId").asText())) {reply.put("decision","COMPLETED");reply.remove("permitToken");}
                } else if(path.endsWith("/consume")) {freshConsumptions.incrementAndGet();reply=consumed(body);}
                else if(path.endsWith("/receipts")) {completedCalls.add(body.path("toolCallId").asText());reply=acknowledged(body,false);}
                else return new MockResponse().setResponseCode(404);
                requestHashes.put(cacheKey,hash);responses.put(cacheKey,reply.deepCopy());return ok(reply);
            }catch(IOException invalid){return new MockResponse().setResponseCode(500);}}});server.start();
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"session",true)) {
                CenterToolRegistry tools=registry(client,root,executions);
                var first=tools.executeTools(List.of(new ToolInvocation("call_0","write_file",arguments))).get(0);
                assertTrue(first.successful());assertEquals(1,executions.get());assertEquals(1,freshConsumptions.get());
                // 下一轮供应商再次使用call_0。可拒绝重用，或取得新的内部执行ID和新许可；
                // 旧consume成功响应绝不能再授权一次真实执行。
                var second=tools.executeTools(List.of(new ToolInvocation("call_0","write_file",arguments))).get(0);
                assertAll(
                        ()->assertEquals(freshConsumptions.get(),executions.get(),"每次实际执行都必须有一次独立消费，不能复用consume幂等成功响应"),
                        ()->assertEquals("call_0",first.id()),()->assertEquals("call_0",second.id()),
                        ()->assertEquals("unchanged",Files.readString(existing)),
                        ()->assertEquals(1,proposals.stream().map(p->p.path("resourceHash").asText()).distinct().count()),
                        ()->assertTrue(receipts.stream().allMatch(r->r.path("beforeHash").equals(r.path("afterHash"))))) ;
            }
        }
    }
    @Test void receiptFailureStopsLeaseAndRetainsCompletedEvidence() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")); AtomicInteger executions = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest req) {
                try {
                    if (req.getPath().endsWith("/proposals")) return ok(permit(CanonicalJson.MAPPER.readTree(req.getBody().readUtf8())));
                    if (req.getPath().endsWith("/consume")) return ok(consumed(CanonicalJson.MAPPER.readTree(req.getBody().readUtf8())));
                    return new MockResponse().setResponseCode(503);
                } catch (IOException e) { throw new IllegalStateException(e); }
            }}); server.start();
            try (CenterHttpClient client = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                CenterToolRegistry registry = registry(client, root, executions);
                assertFalse(registry.executeToolOutput("read_file", "{\"path\":\"a.txt\"}").successful()); assertEquals(1, executions.get());
                assertFalse(registry.executeToolOutput("read_file", "{\"path\":\"a.txt\"}").successful()); assertEquals(1, executions.get());
            }
            ReceiptSpool spool = new ReceiptSpool(temp.resolve("spool")); assertEquals("RECEIPT", spool.read(spool.pending().get(0)).path("phase").asText());
        }
    }
    @Test void missingOrMismatchedAcknowledgementRetainsCompletedEvidence() throws Exception {
        ObjectNode receipt = claim(Files.createDirectory(temp.resolve("root")));
        receipt.put("toolCallId", "call-1").put("status", "COMPLETED");
        assertThrows(IOException.class, () -> ReceiptAcknowledgement.require(CanonicalJson.MAPPER.createObjectNode(), receipt));
        assertThrows(IOException.class, () -> ReceiptAcknowledgement.require(acknowledged(receipt,false).put("fencingToken",99),receipt));
        assertThrows(IOException.class, () -> ReceiptAcknowledgement.require(acknowledged(receipt,true).put("status","COMPLETED"),receipt));
        assertDoesNotThrow(() -> ReceiptAcknowledgement.require(acknowledged(receipt,false),receipt));
        assertDoesNotThrow(() -> ReceiptAcknowledgement.require(acknowledged(receipt,true),receipt));
    }
    @Test void restartUploadsLateReceiptWithoutExecutingPreparedOrConsumedActions() throws Exception {
        Path root=Files.createDirectory(temp.resolve("root")); Path state=temp.resolve("state");
        ReceiptSpool spool=new ReceiptSpool(state.resolve("receipts")); ObjectNode old=claim(root);
        old.put("expiresAt",Instant.now().minusSeconds(60).toString());
        Path prepared=spool.intent(old,"prepared","write_file","arg","res");
        Path consumed=spool.intent(old,"consumed","write_file","arg","res");spool.consumed(consumed,"consumed-permit");
        Path completed=spool.intent(old,"completed","read_file","arg","res");spool.consumed(completed,"old-permit");
        ObjectNode receipt=old.deepCopy().put("toolCallId","completed").put("permitToken","old-permit").put("status","COMPLETED");
        spool.receipt(completed,receipt); AtomicInteger executions=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()) {
            server.start();server.enqueue(ok(acknowledged(receipt,true)));
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"renewed-session",true);
                RunnerEngine engine=new RunnerEngine(client,root,state,(lease,tools)->{executions.incrementAndGet();return "unexpected";},null,null,lockHome())) {
                engine.replayReceipts();
            }
            RecordedRequest sent=server.takeRequest();assertEquals("/paicli/runner/v1/receipts",sent.getPath());
            assertEquals("completed",CanonicalJson.MAPPER.readTree(sent.getBody().readUtf8()).path("toolCallId").asText());
            assertEquals(1,server.getRequestCount());assertEquals(0,executions.get());
        }
        assertFalse(Files.exists(completed));assertTrue(Files.exists(prepared));assertTrue(Files.exists(consumed));
        assertTrue(spool.hasUncertainExecution("1"));
    }
    @Test void restartRejectsUnconfirmedArchiveAndKeepsReceipt() throws Exception {
        Path root=Files.createDirectory(temp.resolve("root"));Path state=temp.resolve("state");
        ReceiptSpool spool=new ReceiptSpool(state.resolve("receipts"));ObjectNode receipt=claim(root);
        receipt.put("toolCallId","old-call").put("status","COMPLETED");Path evidence=spool.intent(receipt,"old-call","read_file","arg","res");spool.receipt(evidence,receipt);
        try(MockWebServer server=new MockWebServer()) {
            server.start();server.enqueue(ok(CanonicalJson.MAPPER.createObjectNode()));
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"session",true);
                RunnerEngine engine=new RunnerEngine(client,root,state,(lease,tools)->{fail("不能重放执行");return "";},null,null,lockHome())) {
                assertThrows(IOException.class,engine::replayReceipts);
            }
        }
        assertTrue(Files.exists(evidence));assertEquals("RECEIPT",spool.read(evidence).path("phase").asText());
    }
    @Test void imageUploadFailureKeepsOriginalPrivateEvidenceAndNeverRetriesTool()throws Exception {
        Path root=Files.createDirectory(temp.resolve("root"));AtomicInteger executions=new AtomicInteger();
        LlmClient.ContentPart image=LlmClient.ContentPart.imageBase64("AQID","image/png"),second=LlmClient.ContentPart.imageBase64("AQIE","image/png");
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try{
                JsonNode body=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals"))return ok(permit(body));
                if(request.getPath().endsWith("/consume"))return ok(consumed(body));
                return new MockResponse().setResponseCode(503);
            }catch(IOException failure){throw new IllegalStateException(failure);}}});server.start();
            ReceiptSpool spool=new ReceiptSpool(temp.resolve("spool"));
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"session",true)) {
                CenterToolRegistry tools=new CenterToolRegistry(client,new RunnerLease(claim(root)),spool,root){
                    @Override protected ToolOutput executeAuthorized(String name,String arguments){executions.incrementAndGet();return new ToolOutput("captured",List.of(image,second));}
                };
                assertFalse(tools.executeToolOutput("read_file","{\"path\":\"image.png\"}").successful());
                assertFalse(tools.executeToolOutput("read_file","{\"path\":\"image.png\"}").successful());assertEquals(1,executions.get());
            }
            assertEquals("CONSUMED",spool.read(spool.pending().get(0)).path("phase").asText());
        }
        Path pending;try(var files=Files.list(temp.resolve("pending-derived-images"))){pending=files.findFirst().orElseThrow();}
        JsonNode saved=CanonicalJson.MAPPER.readTree(Files.readAllBytes(pending));assertEquals("AQID",saved.path("result").path("imageParts").get(0).path("imageBase64").asText());
        assertEquals("AQIE",saved.path("result").path("imageParts").get(1).path("imageBase64").asText());assertEquals("captured",saved.path("result").path("text").asText());
        assertEquals("EXECUTION_RESULT_UNCONFIRMED",saved.path("phase").asText());assertFalse(saved.has("permitToken"));assertFalse(saved.toString().contains("session-token"));
    }
    @Test void pendingImageStoresExactPartsAndOnlyRemovesConfirmedLocalEvidence()throws Exception {
        PendingImageEvidence pending=new PendingImageEvidence(temp);ObjectNode binding=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("toolCallId","c1").put("permitToken","private-permit");
        ObjectNode part=CanonicalJson.MAPPER.createObjectNode().put("type","image_base64").put("imageBase64","AQID").put("mimeType","image/png");
        Path saved=pending.preserve(binding,0,part);assertEquals(saved,pending.preserve(binding,0,part));
        JsonNode contents=CanonicalJson.MAPPER.readTree(Files.readAllBytes(saved));assertEquals(part,contents.path("part"));assertFalse(contents.has("permitToken"));
        assertThrows(IOException.class,()->pending.acknowledged(temp.resolve("foreign.json")));assertTrue(Files.exists(saved));
        pending.acknowledged(saved);assertFalse(Files.exists(saved));
    }
    @Test void fullEvidenceCacheRefusesBeforePermitConsumptionOrExecution()throws Exception {
        Path root=Files.createDirectory(temp.resolve("root"));AtomicInteger executions=new AtomicInteger();
        new PendingImageEvidence(temp);
        java.util.Map<Path,byte[]> original=new java.util.LinkedHashMap<>();
        // 本项验证缓存已满时的执行边界；持久化自身由PendingImageEvidenceTest独立检查。
        // 一次性准备256份合法旧证据，避免fixture建立过程反复扫描整个目录。
        for(int i=0;i<256;i++){
            ObjectNode stored=CanonicalJson.MAPPER.createObjectNode().put("toolCallId","old-"+i).put("phase","EXECUTION_RESULT_UNCONFIRMED");
            stored.set("result",CanonicalJson.MAPPER.valueToTree(ToolOutput.text("已发生，待核对")));
            byte[] bytes=CanonicalJson.MAPPER.writeValueAsBytes(stored);
            Path file=RunnerPrivateDirectory.writeAtomic(temp.resolve("pending-derived-images"),CanonicalJson.sha256(bytes)+".json",bytes);
            original.put(file,bytes);
        }
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                try{assertEquals("/paicli/runner/v1/proposals",request.getPath());return ok(permit(CanonicalJson.MAPPER.readTree(request.getBody().readUtf8())));}
                catch(IOException invalid){return new MockResponse().setResponseCode(500);}
            }});server.start();
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"session",true)){
                ToolOutput result=registry(client,root,executions).executeToolOutput("write_file","{\"path\":\"new.txt\",\"content\":\"new\"}");
                assertFalse(result.successful());assertTrue(result.text().contains("执行前停止"));
            }
            assertEquals(1,server.getRequestCount());assertEquals(0,executions.get());
        }
        assertFalse(Files.exists(root.resolve("new.txt")));assertTrue(new ReceiptSpool(temp.resolve("spool")).pending().isEmpty());
        try(var entries=Files.list(temp.resolve("pending-derived-images"))){assertEquals(256,entries.count());}
        for(var entry:original.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));
    }
    @Test void deviceExchangeReportsActualPlatformAndPackagedOrDevelopmentVersion()throws Exception{
        Path root=Files.createDirectory(temp.resolve("root"));
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(ok(CanonicalJson.MAPPER.createObjectNode().put("expiresAt",Instant.now().plusSeconds(900).toString()).put("sessionToken","fresh-session")));
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"bootstrap",true);
                RunnerEngine engine=new RunnerEngine(client,root,temp.resolve("state"),(lease,tools)->"",null,null,lockHome())) {
                engine.connect();
            }
            JsonNode sent=CanonicalJson.MAPPER.readTree(server.takeRequest().getBody().readUtf8());assertEquals(System.getProperty("os.name"),sent.path("platform").asText());
            assertFalse(sent.path("distributionVersion").asText().isBlank());assertEquals(1,sent.path("capacity").asInt());
            assertTrue(java.util.stream.StreamSupport.stream(sent.path("capabilities").spliterator(),false)
                    .anyMatch(value->"sub2api.responses.v1".equals(value.asText())));
            assertTrue(java.util.stream.StreamSupport.stream(sent.path("capabilities").spliterator(),false)
                    .anyMatch(value->"sub2api.chat_completions.v1".equals(value.asText())));
        }
    }
    @Test void changedOversizedResourceAfterExecutionKeepsFullResultForReconciliation()throws Exception{
        Path root=Files.createDirectory(temp.resolve("root"));ReceiptSpool spool=new ReceiptSpool(temp.resolve("spool"));AtomicInteger executions=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try{
                JsonNode input=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());return request.getPath().endsWith("/proposals")?ok(permit(input)):ok(consumed(input));
            }catch(IOException failure){throw new IllegalStateException(failure);}}});server.start();
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"session",true)){
                CenterToolRegistry tools=new CenterToolRegistry(client,new RunnerLease(claim(root)),spool,root){
                    @Override protected ToolOutput executeAuthorized(String name,String arguments){
                        executions.incrementAndGet();try(var file=new java.io.RandomAccessFile(root.resolve("oversized.bin").toFile(),"rw")){file.setLength(64L*1024*1024+1);}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
                        return ToolOutput.text("真实执行已经产生文件，后置摘要须停住对账");
                    }
                };
                assertFalse(tools.executeToolOutput("write_file","{\"path\":\"oversized.bin\",\"content\":\"fixture\"}").successful());
                assertFalse(tools.executeToolOutput("write_file","{\"path\":\"oversized.bin\",\"content\":\"fixture\"}").successful());assertEquals(1,executions.get());
            }
        }
        assertTrue(spool.hasUncertainExecution("1"));Path evidence;try(var files=Files.list(temp.resolve("pending-derived-images"))){evidence=files.findFirst().orElseThrow();}
        JsonNode saved=CanonicalJson.MAPPER.readTree(Files.readAllBytes(evidence));assertEquals("真实执行已经产生文件，后置摘要须停住对账",saved.path("result").path("text").asText());
    }
    private CenterToolRegistry registry(CenterHttpClient client, Path root, AtomicInteger executions) throws IOException {
        return new CenterToolRegistry(client, new RunnerLease(claim(root)), new ReceiptSpool(temp.resolve("spool")), root) {
            @Override protected ToolOutput executeAuthorized(String name, String argumentsJson) { executions.incrementAndGet(); return ToolOutput.text("已执行"); }
        };
    }
}
