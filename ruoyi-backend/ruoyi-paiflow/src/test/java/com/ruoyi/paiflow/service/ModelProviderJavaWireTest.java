package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;

/** 与原 Java Spring AI 实际请求金样逐值比较；不调用真实供应商。 */
class ModelProviderJavaWireTest {
    private static JSONArray golden() throws Exception {
        try (var in=ModelProviderJavaWireTest.class.getResourceAsStream("/fixtures/java-model-wire/original-sdk.json")) {
            assertNotNull(in);
            return JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static final ModelOperationPorts.PrivateMaterial MATERIAL=
        new ModelOperationPorts.PrivateMaterial("env:FIXTURE_REFERENCE","fixture-version","https://provider.invalid/v1","fixture-key");
    @Test void allFiveOriginalSdkRequestsKeepNullableOptionsAndCamelCaseOverrides() throws Exception {
        for(Object raw:golden()) {
            var sample=(JSONObject)raw;
            var actual=ModelProviderWire.request(sample.getJSONObject("actualRequest").getJSONObject("parameters"),MATERIAL,"",true,"java");
            assertEquals(sample.getJSONObject("originalWire"),actual,"原 SDK variant="+sample.getIntValue("variant"));
        }
    }
    @Test void originalTwoChoiceStreamRetainsItsFirstChoiceCompletionAndUsageTail() throws Exception {
        var frames=golden().getJSONObject(3).getJSONArray("rawFrames");
        var options=golden().getJSONObject(3).getJSONObject("actualRequest").getJSONObject("parameters").getJSONObject("options");
        assertNull(ModelProviderWire.facts("openai",frames.getJSONObject(0),"java",options).finish());
        assertEquals("stop",ModelProviderWire.facts("openai",frames.getJSONObject(2),"java",options).finish());
        assertEquals(7,ModelProviderWire.facts("openai",frames.getJSONObject(3),"java",options).usage().getIntValue("total_tokens"));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->ModelProviderWire.facts("openai",frames.getJSONObject(0)));
        options.put("n",1);
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->ModelProviderWire.facts("openai",frames.getJSONObject(0),"java",options));
    }
    @Test void javaOptionsDoNotSelectTheJavaProtocolWithoutPrivateEngineBinding() throws Exception {
        var params=golden().getJSONObject(0).getJSONObject("actualRequest").getJSONObject("parameters");
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->ModelProviderWire.request(params,MATERIAL,"",true));
        params.put("provider","xinghuo");
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->ModelProviderWire.request(params,MATERIAL,"",true,"java"));
    }
    @Test void managedProducerSendsOriginalTwoChoiceAndLengthRequestsAndKeepsEveryRawFrame() throws Exception {
        var samples=golden();
        for(int variant:List.of(3,4))try(var server=new MockWebServer()) {
            server.start(InetAddress.getLoopbackAddress(),0);
            var sample=samples.getJSONObject(variant);var frames=sample.getJSONArray("rawFrames");
            var body=new StringBuilder();for(Object frame:frames)body.append("data: ").append(canonical(frame,FRAME_BYTES)).append("\n\n");body.append("data: [DONE]\n\n");
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody(body.toString()));
            var parameters=sample.getJSONObject("actualRequest").getJSONObject("parameters");
            var binding=JSONObject.of("runId","fixture-run","leaseId","fixture-lease","attempt",1,"nodeId","spark-llm::fixture","iterationId",null,"workflowVersionId","9","dslHash","a".repeat(64),"operationId","fixture-operation","resourceId",parameters.get("resourceId"),"resourceVersion",parameters.get("resourceVersion"),"provider","openai");
            String json=canonical(parameters,REQUEST_BYTES);
            var grant=new PrivateGrant("fixture-operation","fixture-grant","fixture-token",binding,json,sha(json),canonical(parameters.get("options"),REQUEST_BYTES),Instant.now().plusSeconds(20),MATERIAL,"","java");
            var runtime=mock(ManagedModelRuntime.class);var profile=new ModelRuntimeProperties.Profile();profile.setTimeoutSeconds(5);profile.setMaxRequestBytes(65536);when(runtime.profileByGrant(grant)).thenReturn(profile);
            var transport=new ModelPrivateTransport(){@Override public OkHttpClient client(URI uri,int seconds,List<String> addresses){return new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())).build();}};
            var received=new CopyOnWriteArrayList<JSONObject>();var finished=new CountDownLatch(1);var failure=new AtomicReference<FailureFacts>();var finishReason=new AtomicReference<String>();
            var callbacks=new ProducerCallbacks(){
                public JSONObject assertActive(PrivateGrant g){var ack=g.binding();ack.put("active",true);return ack;}
                public JSONObject commitFrame(PrivateGrant g,long seq,JSONObject raw){received.add(copy(raw));var ack=g.binding();ack.putAll(JSONObject.of("accepted",true,"seq",seq,"lastSeq",seq,"done",false,"state","RUNNING","failureKind",null));return ack;}
                public JSONObject finishStream(PrivateGrant g,long last,long end,String reason){assertEquals(frames.size(),last);assertEquals(3,end);finishReason.set(reason);var ack=g.binding();ack.putAll(JSONObject.of("accepted",true,"seq",last,"lastSeq",last,"finishSeq",end,"done",true,"state","stop".equals(reason)?"COMPLETED":"FAILED","failureKind","stop".equals(reason)?null:"provider"));finished.countDown();return ack;}
                public JSONObject confirmStop(PrivateGrant g,StopEvidence evidence){throw new AssertionError("本地关闭不能证明供应商停止");}
                public void recordFailure(PrivateGrant g,FailureFacts facts){failure.set(facts);finished.countDown();}
            };
            var producer=new ManagedModelProducer(runtime,transport);
            try {
                assertTrue(producer.submit(grant,callbacks).accepted());assertTrue(finished.await(5,TimeUnit.SECONDS));assertNull(failure.get());
                assertEquals(variant==4?"length":"stop",finishReason.get());assertEquals(frames,new JSONArray(received));
                var request=server.takeRequest(1,TimeUnit.SECONDS);assertNotNull(request);assertEquals(sample.getJSONObject("originalWire"),JSON.parseObject(request.getBody().readUtf8()));
                assertEquals("Bearer fixture-key",request.getHeader("Authorization"));
            } finally {producer.close();}
        }
    }
}
