package com.ruoyi.paismart.application.intelligence;

import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import com.ruoyi.system.secret.ManagedSecretService;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实H2调用持久化及回放；供应商HTTP和额度服务为隔离端口。 */
class Sub2UsagePersistenceTest {
    JdbcTemplate db;ModelGateway gateway;ControlledHttp http;QuotaService quota;ProviderService.Provider provider;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:usage_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(source);
        db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1))");db.update("INSERT INTO sys_user VALUES(1,'0','0')");
        db.execute("CREATE TABLE ps_model_call(id VARCHAR(36) PRIMARY KEY,user_id BIGINT,operation_id VARCHAR(160),scope VARCHAR(20),request_hash VARCHAR(64),status VARCHAR(25),reservation_id VARCHAR(36),response_json CLOB,actual_tokens BIGINT,estimated BOOLEAN,reason VARCHAR(500),created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(user_id,scope,operation_id))");
        quota=mock(QuotaService.class);when(quota.reserve(eq(1L),anyString(),eq("LLM"),anyLong())).thenAnswer(i->new QuotaService.Reservation(UUID.randomUUID().toString(),1,"LLM",i.getArgument(1),"BALANCE",i.getArgument(3),null,"RESERVED",LocalDate.now()));
        var calls=new ModelCallService(db,new TransactionTemplate(new JdbcTransactionManager(source)),quota);org.springframework.test.util.ReflectionTestUtils.setField(calls,"members",mock(MembershipService.class));
        var providers=mock(ProviderService.class);var settings=mock(IntelligenceSettings.class);http=mock(ControlledHttp.class);
        provider=new ProviderService.Provider("offline","LLM","Sub2API","https://model.invalid/v1","gpt-6.1-sol",0,"offline",true,true,4,Map.of("providerCode","sub2api","apiProtocol","responses","reasoningEffort","low","maxOutputTokens",4096));
        when(providers.snapshot(provider)).thenReturn(Map.of("id","offline","revision",4));when(providers.resolvedSecret(provider)).thenReturn(new ManagedSecretService.Resolved(null,"offline-only"));gateway=new ModelGateway(providers,http,settings,calls);
    }
    Map<String,Object> response(Object usage){
        var result=new LinkedHashMap<String,Object>();result.put("id","response-offline");result.put("status","completed");result.put("output",List.of(Map.of("id","message-offline","type","message","role","assistant","status","completed","content",List.of(Map.of("type","output_text","text","合成回答")))));if(usage!=null)result.put("usage",usage);return result;
    }
    Map<String,Object> send(String operation,int max){return gateway.complete(1,operation,List.of(Map.of("role","user","content","合成题目")),List.of(),max,provider,60);}
    Map<String,Object> stored(){return Jsons.map(db.queryForObject("SELECT response_json FROM ps_model_call",String.class));}
    Map<String,Object> diagnostic(Map<String,Object> message){return Values.map(message.get(Sub2ApiResponsesCodec.USAGE_DIAGNOSTICS));}
    @ParameterizedTest @ValueSource(ints={1500,300,4096})
    void exactRequestedCapAndSupplierBreakdownPersistAndReplayWithoutRebilling(int max){
        when(http.json(eq("POST"),anyString(),anyString(),any(),anyInt())).thenReturn(response(Map.of("input_tokens",400,"output_tokens",4096,"total_tokens",4496,"output_tokens_details",Map.of("reasoning_tokens",80,"private_field","DO_NOT_PERSIST"),"api_key","DO_NOT_PERSIST")));
        var first=send("evaluation-budget",max);var replay=send("evaluation-budget",max);assertEquals(first,replay);assertEquals(first,stored());
        var detail=diagnostic(stored());assertEquals(max,detail.get("requestedMaxOutputTokens"));assertEquals(true,detail.get("usagePresent"));
        assertEquals(Map.of("input_tokens",400,"output_tokens",4096,"total_tokens",4496,"output_tokens_details",Map.of("reasoning_tokens",80)),detail.get("usage"));assertFalse(Jsons.write(detail).contains("DO_NOT_PERSIST"));
        assertEquals(4496L,db.queryForObject("SELECT actual_tokens FROM ps_model_call",Long.class));assertEquals(false,db.queryForObject("SELECT estimated FROM ps_model_call",Boolean.class));
        var body=org.mockito.ArgumentCaptor.forClass(Object.class);verify(http,times(1)).json(eq("POST"),anyString(),anyString(),body.capture(),eq(60));assertEquals(max,Values.map(body.getValue()).get("max_output_tokens"));
        verify(quota,times(1)).reserve(eq(1L),eq("model:evaluation-budget"),eq("LLM"),anyLong());verify(quota,times(1)).settle(anyString(),eq(4496L));verify(quota,never()).release(anyString());
    }
    @Test void missingUsageKeepsOriginalEstimateAndRecordedAbsence(){
        when(http.json(eq("POST"),anyString(),anyString(),any(),anyInt())).thenReturn(response(null));var first=send("no-usage",300);assertEquals(first,send("no-usage",300));
        assertEquals(false,diagnostic(stored()).get("usagePresent"));assertEquals(Map.of(),diagnostic(stored()).get("usage"));assertEquals(300,diagnostic(stored()).get("requestedMaxOutputTokens"));assertEquals(true,db.queryForObject("SELECT estimated FROM ps_model_call",Boolean.class));assertTrue(db.queryForObject("SELECT actual_tokens FROM ps_model_call",Long.class)>0);
        verify(http,times(1)).json(eq("POST"),anyString(),anyString(),any(),anyInt());verify(quota,times(1)).settle(anyString(),longThat(n->n>0));
    }
    @Test void inconsistentSupplierTotalRemainsOriginalBillableTotalAndAllNumbersAreVisible(){
        when(http.json(eq("POST"),anyString(),anyString(),any(),anyInt())).thenReturn(response(Map.of("input_tokens",3,"output_tokens",4,"total_tokens",99)));
        send("inconsistent-usage",300);assertEquals(Map.of("input_tokens",3,"output_tokens",4,"total_tokens",99),diagnostic(stored()).get("usage"));assertEquals(99L,db.queryForObject("SELECT actual_tokens FROM ps_model_call",Long.class));verify(quota).settle(anyString(),eq(99L));
    }
    @Test void invalidNegativeAndFractionalUsageStaysDiagnosableWithoutInventingZeroBill(){
        when(http.json(eq("POST"),anyString(),anyString(),any(),anyInt())).thenReturn(response(Map.of("total_tokens",-1,"input_tokens",1.5,"output_tokens",4)));
        send("invalid-numeric-usage",300);assertEquals(Map.of("total_tokens",-1,"input_tokens",1.5,"output_tokens",4),diagnostic(stored()).get("usage"));assertEquals(true,db.queryForObject("SELECT estimated FROM ps_model_call",Boolean.class));verify(quota).settle(anyString(),longThat(n->n>0));
    }
    @Test void nonnumericUsageFieldsDoNotCopyArbitraryNestedSecretData(){
        when(http.json(eq("POST"),anyString(),anyString(),any(),anyInt())).thenReturn(response(Map.of("total_tokens",9,"input_tokens",Map.of("secret","DO_NOT_PERSIST"),"output_tokens","DO_NOT_PERSIST")));
        send("invalid-usage-types",300);var detail=diagnostic(stored());assertEquals(Map.of("total_tokens",9),detail.get("usage"));assertEquals(Map.of("input_tokens","non_numeric_type","output_tokens","non_numeric_string"),detail.get("invalidFields"));assertFalse(Jsons.write(detail).contains("DO_NOT_PERSIST"));verify(quota).settle(anyString(),eq(9L));
    }
    @Test void historicalMessageWithoutMetadataReplaysThroughExistingStructure(){
        when(http.json(eq("POST"),anyString(),anyString(),any(),anyInt())).thenReturn(response(Map.of("total_tokens",8)));send("historical",300);var historical=stored();historical.remove(Sub2ApiResponsesCodec.USAGE_DIAGNOSTICS);db.update("UPDATE ps_model_call SET response_json=?",Jsons.write(historical));
        assertEquals(historical,send("historical",300));verify(http,times(1)).json(eq("POST"),anyString(),anyString(),any(),anyInt());verify(quota,times(1)).settle(anyString(),eq(8L));
    }
    @Test void completeStreamPersistsUsageAndReplaysOnce(){
        doAnswer(i->{Function<ControlledHttp.SseFrame,Boolean> frames=i.getArgument(3);assertTrue(frames.apply(new ControlledHttp.SseFrame("response.completed",Jsons.write(Map.of("type","response.completed","response",response(Map.of("input_tokens",3,"output_tokens",5,"total_tokens",8)))))));return null;}).when(http).sseFrames(anyString(),anyString(),any(),any(),any());
        var messages=List.<Map<String,Object>>of(Map.of("role","user","content","合成题目"));var first=gateway.streamTurn(1,"stream-complete",messages,List.of(),p->{},()->false,provider,300);assertEquals(first,gateway.streamTurn(1,"stream-complete",messages,List.of(),p->{},()->false,provider,300));assertEquals(300,diagnostic(stored()).get("requestedMaxOutputTokens"));assertEquals(Map.of("input_tokens",3,"output_tokens",5,"total_tokens",8),diagnostic(stored()).get("usage"));verify(http,times(1)).sseFrames(anyString(),anyString(),any(),any(),any());verify(quota,times(1)).settle(anyString(),eq(8L));
    }
    @Test void incompleteStreamRetainsKnownUsageAndCapOnOriginalStoppedPath(){
        doAnswer(i->{Function<ControlledHttp.SseFrame,Boolean> frames=i.getArgument(3);frames.apply(new ControlledHttp.SseFrame("response.incomplete",Jsons.write(Map.of("type","response.incomplete","response",Map.of("id","response-offline","status","incomplete","usage",Map.of("input_tokens",3,"output_tokens",5,"total_tokens",8))))));return null;}).when(http).sseFrames(anyString(),anyString(),any(),any(),any());
        assertThrows(Sub2ApiTextAdapter.ProtocolFailure.class,()->gateway.streamTurn(1,"stream-incomplete",List.of(Map.of("role","user","content","合成题目")),List.of(),p->{},()->false,provider,300));assertEquals("STOPPED",db.queryForObject("SELECT status FROM ps_model_call",String.class));assertEquals(300,diagnostic(stored()).get("requestedMaxOutputTokens"));assertEquals(Map.of("input_tokens",3,"output_tokens",5,"total_tokens",8),diagnostic(stored()).get("usage"));verify(quota).settle(anyString(),eq(8L));verify(quota,never()).release(anyString());
    }
}
