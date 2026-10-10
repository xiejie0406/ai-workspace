package com.paicli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 失败前已报用量必须留下证据，但不能把部分计量当最终收费退还预留。 */
class Sub2ApiUsageFailureTest {
    private static final List<LlmClient.Message> HELLO=List.of(LlmClient.Message.user("probe"));
    private static final String RESPONSE_USAGE="{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7,\"input_tokens_details\":{\"cached_tokens\":1}}";
    private static final String CHAT_USAGE="{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":1}}";

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void receivedUsageSurvivesInterruptedStreamAndFailedBudgetClosesNextRound(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(prefix(api,true)+delta(api)));
            var usages=new ArrayList<BudgetedLlmClient.Usage>();
            var client=new BudgetedLlmClient(transport(server,api),1000,256,usages::add);
            var failure=assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null));
            assertFalse(failure.cancelled());assertNotNull(failure.reportedUsage());
            assertUncertainReservation(client,usages,"FAILED");
            assertThrows(IOException.class,()->client.chat(HELLO,null));
            assertThrows(IOException.class,()->client.external(1,()->{throw new AssertionError("失败后不能继续外部模型请求");}));
            assertThrows(IOException.class,()->client.externalInputOnly(1,()->{throw new AssertionError("失败后不能继续共享预算的输入模型请求");}));
            assertEquals(1,server.getRequestCount());assertEquals(1,usages.size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void receivedUsageSurvivesExplicitCancellationWithoutRefundOrSecondRequest(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(prefix(api,true)+delta(api)));
            LlmClient transport=transport(server,api);var usages=new ArrayList<BudgetedLlmClient.Usage>();
            var client=new BudgetedLlmClient(transport,1000,256,usages::add);
            var failure=assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null,new LlmClient.StreamListener(){
                @Override public void onContentDelta(String text){transport.cancelInFlightCalls();}
            }));
            assertTrue(failure.cancelled());assertUncertainReservation(client,usages,"CANCELLED");
            assertThrows(IOException.class,()->client.chat(HELLO,null));assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void alreadyReportedUsageAboveReservationIsTheMinimumConservativeCharge(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(prefix(api,true).replace("tokens\":5","tokens\":500").replace("total_tokens\":7","total_tokens\":502")+delta(api)));
            var usages=new ArrayList<BudgetedLlmClient.Usage>();var client=new BudgetedLlmClient(transport(server,api),1000,256,usages::add);
            assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null));
            var usage=usages.get(0);assertFalse(usage.usagePresent());assertEquals("UNCERTAIN",usage.status());
            assertTrue(usage.reserved()<502,"保留原预留数值而非改写历史预留");assertEquals(502,usage.totalTokens());assertEquals(498,client.remainingTokens());
            assertEquals(500,usage.reportedUsage().inputTokens());assertEquals(2,usage.reportedUsage().outputTokens());
            assertEquals(502,usage.reportedUsage().totalTokens());assertEquals("FAILED",usage.reportedUsage().status());
            assertThrows(IOException.class,()->client.chat(HELLO,null));assertEquals(1,server.getRequestCount());assertEquals(1,usages.size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"failed","incomplete"})
    void responsesFailureTerminalRetainsItsValidatedUsageInJsonAndSse(String status)throws Exception{
        for(boolean streaming:List.of(false,true))try(var server=new MockWebServer()){
            String response="{\"status\":\""+status+"\",\"output\":[],\"usage\":"+RESPONSE_USAGE+",\"error\":{\"message\":\"synthetic-sensitive-body\"}}";
            server.enqueue(streaming?sse("data: {\"type\":\"response."+status+"\",\"response\":"+response+"}\n\n")
                    :new MockResponse().setHeader("Content-Type","application/json").setBody(response));
            var usages=new ArrayList<BudgetedLlmClient.Usage>();
            var client=new BudgetedLlmClient(transport(server,"responses"),1000,256,usages::add);
            var failure=assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null));
            assertFalse(failure.toString().contains("synthetic-sensitive-body"));assertNull(failure.getCause());
            assertUncertainReservation(client,usages,"FAILED");assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void absentUsageKeepsConservativeReservationAndNoReportedEvidence(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(delta(api)));var usages=new ArrayList<BudgetedLlmClient.Usage>();
            var client=new BudgetedLlmClient(transport(server,api),1000,256,usages::add);
            var failure=assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null));
            assertNull(failure.reportedUsage());assertNull(usages.get(0).reportedUsage());
            assertFalse(usages.get(0).usagePresent());assertEquals("UNCERTAIN",usages.get(0).status());
            assertEquals(1000-usages.get(0).reserved(),client.remainingTokens());
            assertThrows(IOException.class,()->client.chat(HELLO,null));assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void invalidUsageCannotBeSavedAsReportedEvidence(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(prefix(api,true).replace("tokens\":5","tokens\":-1")+delta(api)));
            var usages=new ArrayList<BudgetedLlmClient.Usage>();var client=new BudgetedLlmClient(transport(server,api),1000,256,usages::add);
            var failure=assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null));
            assertNull(failure.reportedUsage());assertNull(usages.get(0).reportedUsage());assertFalse(usages.get(0).usagePresent());
            assertEquals(1000-usages.get(0).reserved(),client.remainingTokens());assertEquals(1,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses","chat_completions"})
    void reportedEvidenceIsScopedToOneRequest(String api)throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(sse(prefix(api,true)+delta(api)));server.enqueue(sse(delta(api)));
            var client=transport(server,api);
            assertNotNull(assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null)).reportedUsage());
            assertNull(assertThrows(Sub2ApiRequestException.class,()->client.chat(HELLO,null)).reportedUsage());
            assertEquals(2,server.getRequestCount());
        }
    }

    @Test void originalUsageConstructorKeepsOtherProviderPayloadCompatible(){
        var usage=new BudgetedLlmClient.Usage(5,2,1,7,30,true,"COMPLETED");assertNull(usage.reportedUsage());assertTrue(usage.usagePresent());
    }

    private static void assertUncertainReservation(BudgetedLlmClient client,List<BudgetedLlmClient.Usage> usages,String status){
        assertEquals(1,usages.size());var usage=usages.get(0);assertFalse(usage.usagePresent());assertEquals("UNCERTAIN",usage.status());
        assertEquals(usage.reserved(),usage.totalTokens());assertEquals(1000-usage.reserved(),client.remainingTokens());
        var reported=usage.reportedUsage();assertNotNull(reported);assertEquals(5,reported.inputTokens());assertEquals(2,reported.outputTokens());
        assertEquals(1,reported.cachedInputTokens());assertEquals(7,reported.totalTokens());assertEquals(status,reported.status());
        assertNull(LlmRequestLimit.current());
    }
    private static LlmClient transport(MockWebServer server,String api){return api.equals("responses")
            ?new Sub2ApiResponsesClient("synthetic-key","fixture-model",server.url("/v1").toString())
            :new Sub2ApiChatClient("synthetic-key","fixture-model",server.url("/v1").toString());}
    private static MockResponse sse(String text){return new MockResponse().setHeader("Content-Type","text/event-stream").setBody(text);}
    private static String prefix(String api,boolean known){return api.equals("responses")
            ?"data: {\"type\":\"response.in_progress\",\"response\":{\"status\":\"in_progress\""+(known?",\"usage\":"+RESPONSE_USAGE:"")+"}}\n\n"
            :"data: {\"choices\":[]"+(known?",\"usage\":"+CHAT_USAGE:"")+"}\n\n";}
    private static String delta(String api){return api.equals("responses")?"data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
            :"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n";}
}
