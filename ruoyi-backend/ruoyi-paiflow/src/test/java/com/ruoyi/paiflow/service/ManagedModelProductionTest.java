package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.secret.ManagedSecretService;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 隔离fixture：假材料、动态localhost端口；不连接真实Provider/数据库、不证明业务验收。 */
class ManagedModelProductionTest {
    private static ModelRuntimeProperties.Profile profile(){var p=new ModelRuntimeProperties.Profile();p.setEnabled(true);p.setInvocationAllowed(true);p.setValidationAllowed(true);p.setSourceProvider("openai-compatible");p.setRuntimeProvider("openai");p.setDomain("fixture-model");p.setEndpoint("https://provider.invalid/v1/chat/completions");p.setCredentialRef("env:FIXTURE_REFERENCE");p.setSecretAlias("ai.fixture.paiflow");p.setSecretProvider("fixture");p.setCapability("chat");p.setAuthMode("bearer");p.setMaxCalls(3);p.setMaxTokens(10000);p.setMaxRequestBytes(65536);p.setTimeoutSeconds(5);p.setAllowedUserIds(List.of(42L));p.setAllowedSpaceIds(List.of(7L));return p;}
    private static JSONObject parameters(String provider,String mode){var p=new JSONObject();p.put("modelId",1);p.put("source",provider);p.put("domain","fixture-model");var options=ModelOperationOptions.fromNode(p);
        return JSONObject.of("resourceId","model:1:version:2","resourceVersion","2","provider",provider,"modelName","fixture-model","mode",mode,"messages",List.of(JSONObject.of("role","user","content","fixture input")),"options",options);}
    private static PrivateGrant grant(String provider,String mode){var p=parameters(provider,mode);var binding=JSONObject.of("runId","fixture-run","leaseId","fixture-lease","attempt",1,"nodeId","spark-llm::fixture","iterationId",null,"workflowVersionId","9","dslHash","a".repeat(64),"operationId","fixture-operation","resourceId","model:1:version:2","resourceVersion","2","provider",provider);
        var material=new PrivateMaterial("env:FIXTURE_REFERENCE","fixture-version",provider.equals("openai")?"https://provider.invalid/v1":"wss://provider.invalid/v4/chat","fixture-key","fixture-app","fixture-secret");
        return new PrivateGrant("fixture-operation","fixture-grant","fixture-private-token",binding,canonical(p,REQUEST_BYTES),sha(canonical(p,REQUEST_BYTES)),canonical(p.getJSONObject("options"),REQUEST_BYTES),Instant.now().plusSeconds(20),material);}
    @Test void metadataOnlyAvailabilityAndScopeNeverReadPlaintext(){
        var secrets=mock(ManagedSecretService.class);var p=profile();var properties=new ModelRuntimeProperties();properties.setProfiles(List.of(p));var service=new ManagedModelRuntime(properties,secrets);
        var metadata=new ManagedSecretService.Metadata(p.getSecretAlias(),"AI","paiflow","fixture","chat","bearer","isolated fixture","ACTIVE",2,4L,LocalDateTime.now());when(secrets.metadata("AI",p.getSecretAlias())).thenReturn(metadata);
        var model=mock(ModelRuntimeCatalog.FixedModel.class);when(model.sourceProvider()).thenReturn(p.getSourceProvider());when(model.domain()).thenReturn(p.getDomain());when(model.privateEndpoint()).thenReturn(p.getEndpoint());when(model.credentialRef()).thenReturn(p.getCredentialRef());when(model.credentialVersion()).thenReturn("managed:ai.fixture.paiflow:version:2:row:4");
        assertTrue(service.available(model));verify(secrets,never()).resolve(anyString(),anyString());assertThrows(ServiceException.class,()->ManagedModelRuntime.scope(p,99,7));assertThrows(ServiceException.class,()->ManagedModelRuntime.scope(p,42,8));
    }
    @Test void exactSecretRotationBetweenMetadataAndResolveRejectsPrivateMaterial(){
        var secrets=mock(ManagedSecretService.class);var p=profile();var properties=new ModelRuntimeProperties();properties.setProfiles(List.of(p));var service=new ManagedModelRuntime(properties,secrets);
        var before=new ManagedSecretService.Metadata(p.getSecretAlias(),"AI","paiflow","fixture","chat","bearer","fixture","ACTIVE",2,4L,LocalDateTime.now());var after=new ManagedSecretService.Metadata(p.getSecretAlias(),"AI","paiflow","fixture","chat","bearer","fixture","ACTIVE",3,5L,LocalDateTime.now());
        when(secrets.metadata("AI",p.getSecretAlias())).thenReturn(before);when(secrets.resolve("AI",p.getSecretAlias())).thenReturn(new ManagedSecretService.Resolved(after,"fixture-secret"));assertThrows(ServiceException.class,()->service.material(p,p.getCredentialRef(),"managed:ai.fixture.paiflow:version:2:row:4"));
    }
    @Test void fixedDnsRejectsPrivateAddressWithoutExplicitDeploymentGrant(){var transport=new ModelPrivateTransport();assertThrows(ServiceException.class,()->transport.client(URI.create("https://127.0.0.1/v1/chat/completions"),2,List.of()));var client=transport.client(URI.create("http://127.0.0.1:12345/api/v1/modserv/list"),2,List.of("127.0.0.1"));assertFalse(client.followRedirects());assertFalse(client.followSslRedirects());assertFalse(client.retryOnConnectionFailure());assertEquals(java.net.Proxy.NO_PROXY,client.proxy());ModelPrivateTransport.close(client);}
    @Test void originalSparkFunctionWireAndSignedUrlPreserveSchemasWithoutPlainCredentials(){
        var grant=grant("xinghuo","function_call");var p=JSON.parseObject(grant.parametersJson());p.getJSONObject("options").put("questionType","not_knowledge");p.getJSONObject("options").put("functionChoice","extractor_parameter");p.put("functions",List.of(JSONObject.of("name","extractor_parameter","description","fixture","parameters",JSONObject.of("type","object"))));
        var wire=ModelProviderWire.request(p,grant.privateMaterial(),"fixture-owner",true);assertEquals(p.get("functions"),wire.getJSONObject("payload").getJSONObject("functions").get("text"));assertEquals("extractor_parameter",wire.getJSONObject("parameter").getJSONObject("chat").get("function_choice"));
        var uri=ModelProviderWire.signedSpark(grant.privateMaterial(),Instant.parse("2026-10-05T00:00:00Z"));assertEquals("provider.invalid",uri.getHost());assertEquals("/v4/chat",uri.getPath());assertFalse(uri.toString().contains("fixture-secret"));assertFalse(uri.toString().contains("fixture-key"));
    }
    @Test void messagesComeFromFixedInputsAndAcceptedUpstreamCompletion(){
        var node=JSON.parseObject("{\"id\":\"spark-llm::m\",\"data\":{\"inputs\":[{\"name\":\"q\",\"schema\":{\"value\":{\"type\":\"ref\",\"content\":{\"nodeId\":\"text-joiner::a\",\"name\":\"answer\"}}}}],\"nodeParam\":{\"template\":\"答：{{q}}\",\"systemTemplate\":\"固定说明\"}}}");
        var event=JSONObject.of("eventType","node.completed","nodeId","text-joiner::a","iterationId",null,"payload",JSONObject.of("legacyFrame",JSONObject.of("workflow_step",JSONObject.of("node",JSONObject.of("id","text-joiner::a","outputs",JSONObject.of("answer","真实已确认输出"))))));
        var messages=ModelMessageSources.llm(node,JSONObject.of("q","伪根输入"),new JSONArray(List.of(event)));assertEquals("答：真实已确认输出",messages.getJSONObject(1).getString("content"));event.put("eventType","node.started");assertThrows(ServiceException.class,()->ModelMessageSources.llm(node,new JSONObject(),new JSONArray(List.of(event))));
    }
    @Test void reservationCountsPromptBytesAndOutputLimitAndUnknownAttemptStillReserves(){var p=parameters("openai","chat");long expected=canonical(p,REQUEST_BYTES).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+2048;assertEquals(expected,ManagedModelInvocationPolicy.reservation(p));p.getJSONObject("options").getJSONObject("extraParams").put("max_tokens",4096);assertEquals(4096,ManagedModelInvocationPolicy.reservedTokens(p.getJSONObject("options")));}
    @Test void realLocalSseFixtureCommitsOriginalNullFramesAndFinishesOnlyAfterAck() throws Exception {
        try(var server=new MockWebServer()){server.start(InetAddress.getLoopbackAddress(),0);String body="data: {\"choices\":[{\"delta\":{\"content\":\"fixture\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\ndata: [DONE]\n\n";server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody(body));
            var runtime=mock(ManagedModelRuntime.class);when(runtime.profileByGrant(any())).thenReturn(profile());var transport=new ModelPrivateTransport(){@Override public OkHttpClient client(URI uri,int seconds,List<String> addresses){return new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())).build();}};
            var producer=new ManagedModelProducer(runtime,transport);var done=new CountDownLatch(1);var frames=new CopyOnWriteArrayList<JSONObject>();var failure=new AtomicReference<FailureFacts>();var grant=grant("openai","chat");var callbacks=new ProducerCallbacks(){
                public JSONObject assertActive(PrivateGrant g){var ack=g.binding();ack.put("active",true);return ack;}
                public JSONObject commitFrame(PrivateGrant g,long seq,JSONObject raw){frames.add(copy(raw));return frameAck(g,seq);}
                public JSONObject finishStream(PrivateGrant g,long last,long finish,String reason){assertEquals(2,last);assertEquals(2,finish);assertEquals("stop",reason);var ack=finishAck(g,last,finish,reason);done.countDown();return ack;}
                public JSONObject confirmStop(PrivateGrant g,StopEvidence e){throw new AssertionError("关闭不能充当停止证明");}
                public void recordFailure(PrivateGrant g,FailureFacts f){failure.set(f);done.countDown();}};
            try{assertTrue(producer.submit(grant,callbacks).accepted());assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(failure.get());assertEquals(2,frames.size());assertTrue(canonical(frames.get(1),FRAME_BYTES).contains("\"content\":null"));var request=server.takeRequest(1,TimeUnit.SECONDS);assertNotNull(request);assertEquals("Bearer fixture-key",request.getHeader("Authorization"));assertEquals(2048,JSON.parseObject(request.getBody().readUtf8()).getIntValue("max_tokens"));assertFalse(producer.stop(grant.operationId(),grant.grantId(),"fixture-cancel").stopped());}finally{producer.close();}
        }
    }
    private static JSONObject frameAck(PrivateGrant g,long seq){var ack=g.binding();ack.putAll(JSONObject.of("accepted",true,"seq",seq,"lastSeq",seq,"done",false,"state","RUNNING","failureKind",null));return ack;}
    private static JSONObject finishAck(PrivateGrant g,long last,long finish,String reason){var ack=g.binding();ack.putAll(JSONObject.of("accepted",true,"seq",last,"lastSeq",last,"finishSeq",finish,"done",true,"state","stop".equals(reason)?"COMPLETED":"FAILED","failureKind","stop".equals(reason)?null:"provider"));return ack;}
    @Test void incompleteOrWrongTerminalAckCannotConfirmProductionCompletion(){
        var g=grant("openai","chat");var frame=frameAck(g,1);assertDoesNotThrow(()->ManagedModelProducer.checkFrameAck(g,frame,1));frame.put("done",true);assertThrows(ServiceException.class,()->ManagedModelProducer.checkFrameAck(g,frame,1));
        var ack=finishAck(g,3,2,"stop");assertDoesNotThrow(()->ManagedModelProducer.checkFinishAck(g,ack,3,2,"stop"));ack.remove("done");assertThrows(ServiceException.class,()->ManagedModelProducer.checkFinishAck(g,ack,3,2,"stop"));ack.put("done",true);ack.put("finishSeq",3);assertThrows(ServiceException.class,()->ManagedModelProducer.checkFinishAck(g,ack,3,2,"stop"));
        var denied=finishAck(g,2,2,"length");assertDoesNotThrow(()->ManagedModelProducer.checkFinishAck(g,denied,2,2,"length"));denied.put("state","COMPLETED");assertThrows(ServiceException.class,()->ManagedModelProducer.checkFinishAck(g,denied,2,2,"length"));
    }
    @Test void fractionalCallerDeadlineOverridesConfiguredLimitAndRunDeadlineStillBoundsIt(){
        var now=Instant.parse("2026-10-05T00:00:00Z");var p=JSONObject.of("timeoutSeconds",0.25);
        assertEquals(now.plusMillis(250),ManagedModelInvocationPolicy.callDeadline(p,5,now.plusSeconds(20),now));assertEquals(now.plusMillis(100),ManagedModelInvocationPolicy.callDeadline(p,5,now.plusMillis(100),now));
        p.put("timeoutSeconds",Double.NaN);assertThrows(ServiceException.class,()->ManagedModelInvocationPolicy.callDeadline(p,5,now.plusSeconds(20),now));p.put("timeoutSeconds",6);assertThrows(ServiceException.class,()->ManagedModelInvocationPolicy.callDeadline(p,5,now.plusSeconds(20),now));
    }
    @Test void changedPrivateEndpointAddressOrSecretPurposeRevokesExistingRunPermit(){
        var p=profile();var runtime=mock(ManagedModelRuntime.class);var catalog=mock(ModelRuntimeCatalog.class);var fixed=mock(ModelRuntimeCatalog.FixedModel.class);
        when(catalog.resolvePinned(eq(7L),eq(42L),eq("model:1:version:2"),any())).thenReturn(fixed);when(runtime.profile(fixed)).thenReturn(p);
        var policy=new ManagedModelInvocationPolicy(runtime,catalog,mock(com.ruoyi.paiflow.mapper.ModelOperationMapper.class));
        var candidate=new RunCandidate("fixture-run",42,7,9,"a".repeat(64),"{}","{\"model:1:version:2\":{\"kind\":\"model\"}}","{}",Instant.now().plusSeconds(20),"fixture-fingerprint");
        var permit=policy.approveRun(candidate);assertTrue(policy.currentRun(candidate,permit));p.setAllowedInferenceAddresses(List.of("127.0.0.1"));assertFalse(policy.currentRun(candidate,permit));p.setAllowedInferenceAddresses(List.of());assertTrue(policy.currentRun(candidate,permit));p.setInferenceEndpoint("http://127.0.0.1:12345/v1/chat/completions");assertFalse(policy.currentRun(candidate,permit));p.setInferenceEndpoint(null);p.setAuthMode("changed-purpose");assertFalse(policy.currentRun(candidate,permit));
        verify(runtime,never()).resolve(any(ModelRuntimeCatalog.FixedModel.class));
    }
    @Test void explicitOpenaiCompletionCeilingDoesNotAlsoSendLegacyCeiling(){
        var g=grant("openai","chat");var p=JSON.parseObject(g.parametersJson());var extra=p.getJSONObject("options").getJSONObject("extraParams");extra.put("max_completion_tokens",512);
        var wire=ModelProviderWire.request(p,g.privateMaterial(),"fixture",true);assertEquals(512,wire.getIntValue("max_completion_tokens"));assertFalse(wire.containsKey("max_tokens"));extra.put("max_tokens",256);assertThrows(ServiceException.class,()->ModelProviderWire.request(p,g.privateMaterial(),"fixture",true));
    }
    @Test void realLocalWebsocketFixtureSendsOriginalFunctionCallAndRequiresPersistedFinish() throws Exception {
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}@Override public void onMessage(WebSocket socket,String text){
                var wire=JSON.parseObject(text);assertEquals("",wire.getJSONObject("header").getString("uid"));assertEquals("extractor_parameter",wire.getJSONObject("payload").getJSONObject("functions").getJSONArray("text").getJSONObject(0).getString("name"));
                socket.send("{\"header\":{\"code\":0,\"status\":2},\"payload\":{\"choices\":{\"status\":2,\"seq\":0,\"text\":[{\"role\":\"assistant\",\"content\":\"\",\"function_call\":{\"name\":\"extractor_parameter\",\"arguments\":\"{}\"}}]},\"usage\":{\"text\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}}}");
            }}));server.start(InetAddress.getLoopbackAddress(),0);
            var original=grant("xinghuo","function_call");var p=JSON.parseObject(original.parametersJson());p.getJSONObject("options").put("questionType","not_knowledge");p.getJSONObject("options").put("functionChoice","extractor_parameter");p.put("functions",List.of(JSONObject.of("name","extractor_parameter","description","fixture","parameters",JSONObject.of("type","object"))));var json=canonical(p,REQUEST_BYTES);
            var g=new PrivateGrant(original.operationId(),original.grantId(),original.token(),original.binding(),json,sha(json),original.optionsJson(),original.deadline(),original.privateMaterial());assertEquals("",g.providerUid());
            var runtime=mock(ManagedModelRuntime.class);when(runtime.profileByGrant(any())).thenReturn(profile());var transport=new ModelPrivateTransport(){@Override public OkHttpClient client(URI uri,int seconds,List<String> addresses){return new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v4/chat")).build())).build();}};
            var producer=new ManagedModelProducer(runtime,transport);var done=new CountDownLatch(1);var failure=new AtomicReference<FailureFacts>();var frames=new AtomicInteger();var finished=new AtomicBoolean();
            var callbacks=new ProducerCallbacks(){public JSONObject assertActive(PrivateGrant grant){var ack=grant.binding();ack.put("active",true);return ack;}public JSONObject commitFrame(PrivateGrant grant,long seq,JSONObject raw){frames.incrementAndGet();return frameAck(grant,seq);}public JSONObject finishStream(PrivateGrant grant,long last,long finish,String reason){assertEquals(1,last);assertEquals(1,finish);assertEquals("stop",reason);finished.set(true);done.countDown();return finishAck(grant,last,finish,reason);}public JSONObject confirmStop(PrivateGrant grant,StopEvidence e){throw new AssertionError("关闭不能充当停止证明");}public void recordFailure(PrivateGrant grant,FailureFacts f){failure.set(f);done.countDown();}};
            try{producer.submit(g,callbacks);assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(failure.get());assertTrue(finished.get());assertEquals(1,frames.get());}finally{producer.close();}
        }
    }
}
