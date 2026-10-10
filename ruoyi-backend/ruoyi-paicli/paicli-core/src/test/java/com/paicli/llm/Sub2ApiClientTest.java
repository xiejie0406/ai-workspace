package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.config.PaiCliConfig;
import com.paicli.runtime.CancellationContext;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.paicli.llm.AbstractOpenAiCompatibleClient.mapper;
import static org.junit.jupiter.api.Assertions.*;

/** 本机合成HTTP/SSE服务验证真正请求边界；不使用账号或真实模型。 */
class Sub2ApiClientTest {
    private static final List<LlmClient.Message> HELLO=List.of(LlmClient.Message.user("hello"));
    private static final String TEXT="{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"probe\"}]}";
    private static final String CALL="{\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_probe\",\"name\":\"paicli_probe_echo\",\"arguments\":\"{\\\"value\\\":\\\"probe\\\"}\"}";
    private static final String REASONING="{\"type\":\"reasoning\",\"id\":\"rs_probe\",\"summary\":[],\"encrypted_content\":\"synthetic-encrypted-state\"}";
    private static String response(String output,String usage){return "{\"status\":\"completed\",\"model\":\"fixture-model\",\"output\":["+output+"]"+(usage==null?"":",\"usage\":"+usage)+"}";}
    private static String usage(){return "{\"input_tokens\":10,\"output_tokens\":3,\"input_tokens_details\":{\"cached_tokens\":2}}";}
    private static String event(String type,String payload){return "event: "+type+"\ndata: {\"type\":\""+type+"\","+payload+"}\n\n";}
    private static MockResponse json(String content){return new MockResponse().setHeader("Content-Type","application/json").setBody(content);}
    private static MockResponse sse(String content){return new MockResponse().setHeader("Content-Type","text/event-stream").setBody(content);}
    private static PaiCliConfig config(MockWebServer server,String api){
        PaiCliConfig c=new PaiCliConfig();c.setDefaultProvider("sub2api");
        var p=new PaiCliConfig.ProviderConfig("synthetic-key",server.url("/v1").toString(),"fixture-model");p.setWireApi(api);c.getProviders().put("sub2api",p);return c;
    }

    @Test void responsesToolRoundTripRetainsEncryptedStateAndCallId()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(event("response.function_call_arguments.delta","\"delta\":\"{\"")+event("response.completed","\"response\":"+response(REASONING+","+CALL,usage()))));
            server.enqueue(sse(event("response.output_text.delta","\"delta\":\"pro\"")+event("response.output_text.delta","\"delta\":\"be\"")+event("response.completed","\"response\":"+response(TEXT,usage()))));
            var client=LlmClientFactory.createFromConfig(config(server,"responses"));
            assertTrue((Boolean)Sub2ApiVerifier.verify(client).get("tools"));
            var first=server.takeRequest(2,TimeUnit.SECONDS);var second=server.takeRequest(2,TimeUnit.SECONDS);
            assertEquals("/v1/responses",first.getPath());JsonNode body=mapper.readTree(second.getBody().readUtf8());
            assertFalse(body.path("store").asBoolean());assertFalse(body.has("previous_response_id"));
            assertEquals("reasoning.encrypted_content",body.path("include").get(0).asText());
            String history=body.path("input").toString();assertTrue(history.contains("synthetic-encrypted-state"));assertTrue(history.contains("function_call_output"));
            assertEquals(2,java.util.stream.StreamSupport.stream(body.path("input").spliterator(),false).filter(n->"call_probe".equals(n.path("call_id").asText())).count());
            assertTrue(body.path("tools").get(0).has("name"));assertFalse(body.path("tools").get(0).has("function"));
            assertFalse(body.path("tools").get(0).path("strict").asBoolean());
        }
    }

    @Test void chatCompletionsUsesOwnCodecAndNoVendorExtensions()throws Exception{
        try(var server=new MockWebServer()){
            String tool="{\"id\":\"call_probe\",\"index\":0,\"type\":\"function\",\"function\":{\"name\":\"paicli_probe_echo\",\"arguments\":\"{\\\"value\\\":\\\"probe\\\"}\"}}";
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"tool_calls\":["+tool+"]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"));
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"probe\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"));
            PaiCliConfig c=config(server,"chat_completions");c.getProviders().get("sub2api").getModels().put("fixture-model",new ModelProfile(null,128000,false,"disabled",null,false,false,256,"manual"));
            var client=LlmClientFactory.createFromConfig(c);assertTrue((Boolean)Sub2ApiVerifier.verify(client).get("stream"));
            var first=server.takeRequest();var second=server.takeRequest();assertEquals("/v1/chat/completions",first.getPath());
            JsonNode body=mapper.readTree(first.getBody().readUtf8());assertFalse(body.has("thinking"));assertFalse(body.has("max_tokens"));assertEquals(256,body.path("max_completion_tokens").asInt());
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());assertTrue(second.getBody().readUtf8().contains("tool_call_id"));
        }
    }

    @Test void managedKeyAndClassifierProfileApplyToResponses()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(json(response(TEXT,usage())));server.enqueue(json(response(TEXT,usage())));
            var c=config(server,"responses");var client=LlmClientFactory.createApprovalClassifier("sub2api",null,c);
            AtomicReference<String> key=new AtomicReference<>("grant-first");LlmClientFactory.attachManagedKey(client,key::get);
            try(var limit=LlmRequestLimit.bind(25)){client.chat(HELLO,null);}
            key.set("grant-second");client.chat(HELLO,null);
            var first=server.takeRequest();var second=server.takeRequest();assertEquals("Bearer grant-first",first.getHeader("Authorization"));assertEquals("Bearer grant-second",second.getHeader("Authorization"));
            var request=mapper.readTree(first.getBody().readUtf8());assertEquals(25,request.path("max_output_tokens").asInt());assertFalse(request.has("thinking"));
            assertEquals(400,mapper.readTree(second.getBody().readUtf8()).path("max_output_tokens").asInt());assertFalse(client.supportsImageInput());
        }
    }

    @Test void explicitSub2ApiNeverFallsBackToAnotherConfiguredProvider()throws Exception{
        try(var server=new MockWebServer()){
            var c=config(server,"responses");c.getProviders().get("sub2api").setApiKey("");c.getProviders().put("deepseek",new PaiCliConfig.ProviderConfig("other-key",null,null));
            assertNull(LlmClientFactory.createFromConfig(c));c.getProviders().get("sub2api").setWireApi(null);assertThrows(IllegalArgumentException.class,()->LlmClientFactory.createFromConfig(c));assertEquals(0,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"http://public.example/v1","https://example.com/v1/responses","https://example.com/v1/chat/completions","https://user:pass@example.com/v1","https://example.com/v1?token=x","https://example.com/v1#fragment","file:///tmp/v1"})
    void rejectsAmbiguousOrUnsafeRoot(String root){assertThrows(IllegalArgumentException.class,()->Sub2ApiEndpoint.endpoint(root,"responses"));}

    @Test void slashNormalizationNeverDuplicatesVersionOrEndpoint(){assertEquals("https://example.com/prefix/v1/responses",Sub2ApiEndpoint.endpoint("https://example.com/prefix/v1/","responses"));}

    @ParameterizedTest @ValueSource(strings={"error","response.failed","response.incomplete","response.refusal.delta"})
    void failureEventsDoNotReplayOrLeakServerText(String type)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(event(type,"\"message\":\"synthetic-sensitive-body\"")));var client=LlmClientFactory.createFromConfig(config(server,"responses"));
            IOException error=assertThrows(IOException.class,()->client.chat(HELLO,null));assertFalse(error.toString().contains("synthetic-sensitive-body"));assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void unknownHttpAcceptanceNeverAutoRetries(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(503).setBody("synthetic-sensitive-body"));var client=LlmClientFactory.createFromConfig(config(server,api));
            var error=assertThrows(IOException.class,()->client.chat(HELLO,null));assertFalse(error.toString().contains("synthetic-sensitive-body"));assertEquals(1,server.getRequestCount());
        }
    }

    @Test void missingTerminalAndConflictingStreamRemainFailure()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(event("response.output_text.delta","\"delta\":\"partial\"")));
            server.enqueue(sse(event("response.output_text.delta","\"delta\":\"wrong\"")+event("response.completed","\"response\":"+response(TEXT,usage()))));
            var client=LlmClientFactory.createFromConfig(config(server,"responses"));assertThrows(IOException.class,()->client.chat(HELLO,null));assertThrows(IOException.class,()->client.chat(HELLO,null));assertEquals(2,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"length","content_filter","unknown"})
    void chatTruncationAndRefusalCannotBecomeCompletion(String finish)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\""+finish+"\"}]}\n\ndata: [DONE]\n\n"));
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions")).chat(HELLO,null));assertEquals(1,server.getRequestCount());
        }
    }

    @Test void doneAloneIsNotChatFinishEvidence()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: [DONE]\n\n"));assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions")).chat(HELLO,null));
        }
    }

    @Test void usageMissingStaysUnknownAndMalformedUsageFails()throws Exception{
        var unknown=Sub2ApiResponsesCodec.response(mapper.readTree(response(TEXT,null)));assertFalse(unknown.usagePresent());
        for(String bad:List.of("{\"input_tokens\":-1,\"output_tokens\":3}","{\"input_tokens\":2147483648,\"output_tokens\":3}","{\"input_tokens\":\"10\",\"output_tokens\":3}","{\"input_tokens\":10}"))
            assertThrows(IOException.class,()->Sub2ApiResponsesCodec.response(mapper.readTree(response(TEXT,bad))));
    }

    @Test void historyRoundTripPreservesProtocolItemsAndCountsReasoning()throws Exception{
        var result=Sub2ApiResponsesCodec.response(mapper.readTree(response(REASONING+","+CALL,usage())));
        var original=LlmClient.Message.assistant(result);var restored=mapper.readValue(mapper.writeValueAsBytes(original),LlmClient.Message.class);
        assertEquals(original.responseItems(),restored.responseItems());assertEquals(original.responseItems(),restored.withoutReasoningContent().responseItems());
        assertTrue(com.paicli.memory.TokenBudget.estimateMessagesTokens(List.of(restored))>com.paicli.memory.TokenBudget.estimateMessagesTokens(List.of(LlmClient.Message.assistant("",result.toolCalls()))));
    }

    @Test void chatUsageAndMissingToolIdsCannotBecomeSuccessfulEvidence()throws Exception{
        for(String usage:List.of("{\"prompt_tokens\":-1,\"completion_tokens\":1}","{\"prompt_tokens\":1}","{\"prompt_tokens\":1,\"completion_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":2}}"))try(var server=new MockWebServer()){
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"probe\"},\"finish_reason\":\"stop\"}],\"usage\":"+usage+"}\n\ndata: [DONE]\n\n"));
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions")).chat(HELLO,null));assertEquals(1,server.getRequestCount());
        }
        try(var server=new MockWebServer()){
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"));
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions")).chat(HELLO,null));
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void taskCancellationInterruptsBlockedSocket(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));var executor=Executors.newSingleThreadExecutor();
            AtomicReference<com.paicli.runtime.CancellationToken> token=new AtomicReference<>();
            try{var running=executor.submit(()->{var current=CancellationContext.startRun();token.set(current);try{assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,api)).chat(HELLO,null));}finally{CancellationContext.clear(current);}});
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));token.get().cancel();running.get(3,TimeUnit.SECONDS);assertEquals(1,server.getRequestCount());
            }finally{executor.shutdownNow();}
        }
    }

    @Test void responsesDiscoveryUsesSameRootAndManagedGrant()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(json("{\"data\":[{\"id\":\"exact-model\"}]}"));var client=LlmClientFactory.createFromConfig(config(server,"responses"));LlmClientFactory.attachManagedKey(client,()->"discovery-grant");
            assertEquals(List.of("exact-model"),new ModelDiscovery().discover(client));var request=server.takeRequest();assertEquals("/v1/models",request.getPath());assertEquals("Bearer discovery-grant",request.getHeader("Authorization"));assertEquals("GET",request.getMethod());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void imagesRejectedBeforeRequest(String api)throws Exception{
        try(var server=new MockWebServer()){
            var client=LlmClientFactory.createFromConfig(config(server,api));assertThrows(IOException.class,()->client.chat(List.of(LlmClient.Message.user(List.of(LlmClient.ContentPart.imageUrl("https://example.com/image")))),null));assertEquals(0,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void cancelOnlyClosesOwnedRequest(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));var first=LlmClientFactory.createFromConfig(config(server,api));
            var executor=Executors.newSingleThreadExecutor();
            try{
                Future<?> running=executor.submit(()->assertThrows(IOException.class,()->first.chat(HELLO,null)));
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));first.cancelInFlightCalls();running.get(3,TimeUnit.SECONDS);
                server.enqueue(json(response(TEXT,usage())));var other=LlmClientFactory.createFromConfig(config(server,"responses"));assertEquals("probe",other.chat(HELLO,null).content());
                assertThrows(IOException.class,()->first.chat(HELLO,null));assertEquals(2,server.getRequestCount());
            }finally{executor.shutdownNow();}
        }
    }

    @Test void cancelledTaskCannotSendARequest()throws Exception{
        try(var server=new MockWebServer()){
            var token=CancellationContext.startRun();token.cancel();
            try{assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"responses")).chat(HELLO,null));assertEquals(0,server.getRequestCount());}
            finally{CancellationContext.clear(token);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void trailingJsonCannotBecomeAnExecutableToolCall(String api)throws Exception{
        for(String args:List.of("{}{}","{} synthetic-sensitive-body","[]","{\"value\":\"probe\""))try(var server=new MockWebServer()){
            var call=mapper.createObjectNode().put("type","function_call").put("status","completed")
                    .put("call_id","call_probe").put("name","paicli_probe_echo").put("arguments",args);
            if(api.equals("responses"))server.enqueue(json(response(call.toString(),usage())));
            else{
                var delta=mapper.createObjectNode();delta.putArray("tool_calls").addObject().put("index",0).put("id","call_probe")
                        .putObject("function").put("name","paicli_probe_echo").put("arguments",args);
                server.enqueue(sse("data: {\"choices\":[{\"delta\":"+delta+",\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"));
            }
            var error=assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,api)).chat(HELLO,null));
            assertFalse(error.toString().contains("synthetic-sensitive-body"));assertEquals(1,server.getRequestCount());
        }
    }

    @Test void anotherChoiceTerminalCannotCompleteTheConsumedChoice()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null},{\"index\":1,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions")).chat(HELLO,null));
            assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"1","-1","\"0\""})
    void invalidSingleChoiceIndexCannotProvideCompletion(String index)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse("data: {\"choices\":[{\"index\":"+index+",\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions")).chat(HELLO,null));
            assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void malformedDetailAndTotalUsageCannotBecomeEvidence(String api)throws Exception{
        String input=api.equals("responses")?"input_tokens":"prompt_tokens";
        String output=api.equals("responses")?"output_tokens":"completion_tokens";
        String details=api.equals("responses")?"output_tokens_details":"completion_tokens_details";
        for(String value:List.of("-1","4","2147483648","\"1\""))try(var server=new MockWebServer()){
            String malformed="{\""+input+"\":10,\""+output+"\":3,\""+details+"\":{\"reasoning_tokens\":"+value+"}}";
            enqueueText(server,api,malformed);
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,api)).chat(HELLO,null));assertEquals(1,server.getRequestCount());
        }
        for(String total:List.of("-1","12","2147483648","\"13\""))try(var server=new MockWebServer()){
            enqueueText(server,api,"{\""+input+"\":10,\""+output+"\":3,\"total_tokens\":"+total+"}");
            assertThrows(IOException.class,()->LlmClientFactory.createFromConfig(config(server,api)).chat(HELLO,null));assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void validReasoningIsIncludedInOutputAndAbsentUsageRemainsUnknown(String api)throws Exception{
        try(var server=new MockWebServer()){
            String counters=api.equals("responses")?"{\"input_tokens\":10,\"output_tokens\":3,\"total_tokens\":13,\"output_tokens_details\":{\"reasoning_tokens\":2}}"
                    :"{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13,\"completion_tokens_details\":{\"reasoning_tokens\":2}}";
            enqueueText(server,api,counters);enqueueText(server,api,null);
            var client=LlmClientFactory.createFromConfig(config(server,api));var known=client.chat(HELLO,null);var unknown=client.chat(HELLO,null);
            assertTrue(known.usagePresent());assertEquals(10,known.inputTokens());assertEquals(3,known.outputTokens());
            assertFalse(unknown.usagePresent());assertEquals(2,server.getRequestCount());
        }
    }

    private static void enqueueText(MockWebServer server,String api,String counters){
        if(api.equals("responses"))server.enqueue(json(response(TEXT,counters)));
        else server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"probe\"},\"finish_reason\":\"stop\"}]"+
                (counters==null?"":",\"usage\":"+counters)+"}\n\ndata: [DONE]\n\n"));
    }
}
