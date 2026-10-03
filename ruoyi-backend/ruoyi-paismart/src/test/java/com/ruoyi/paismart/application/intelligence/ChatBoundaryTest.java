package com.ruoyi.paismart.application.intelligence;

import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.billing.RateLimitService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.intelligence.IntelligenceSettings;
import java.sql.Connection;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实持久会话/作业边界，模型及外部设施仅作禁用端口。 */
class ChatBoundaryTest {
    JdbcTemplate db;JobService jobs;ChatService chat;RateLimitService limits;ModelGateway models;SpaceService spaces;SearchService search;CitationService citations;
    @BeforeEach void prepare() throws Exception {var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:chat_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");try(Connection c=source.getConnection()){for(String file:List.of("V001__control_billing_jobs.sql","V003__intelligence.sql","V005__evaluation_datasets.sql"))ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/"+file));}db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(40),status VARCHAR(1),del_flag VARCHAR(1))");db.update("INSERT INTO sys_user VALUES(1,'root','0','0'),(2,'owner','0','0'),(3,'manager','0','0')");jobs=new JobService(db,tx);limits=mock(RateLimitService.class);models=mock(ModelGateway.class);spaces=mock(SpaceService.class);when(spaces.visibleSpaceIds(any())).thenReturn(Set.of("org1","org2"));when(spaces.canManageSpace(any(),eq("org1"))).thenReturn(true);search=mock(SearchService.class);citations=mock(CitationService.class);chat=new ChatService(db,tx,jobs,models,search,citations,mock(IntelligenceSettings.class),mock(MembershipService.class),limits,spaces);}
    Map<String,Object> send(String text){return chat.submit(2,Map.of("text",text,"clientRequestId","request"));}
    JobService.Job claim(){String id=db.queryForObject("SELECT id FROM ps_job",String.class);return jobs.claimById(id,180).orElseThrow();}
    @Test void missingConversationOnRetryReusesOneGenerationAndOneRateCharge(){var first=send("你好");var second=send("你好");assertEquals(first.get("generationId"),second.get("generationId"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_conversation",Long.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));verify(limits,times(1)).acquire(2,"CHAT");}
    @Test void changedRetryInputCannotCreateAnotherConversation(){send("原问题");assertThrows(IllegalStateException.class,()->send("不同问题"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_conversation",Long.class));}
    @Test void stoppedQueuedGenerationPersistsTerminalStateWithoutCallingModel(){var generation=send("你好");String id=generation.get("generationId").toString();chat.stop(2,id);var result=chat.processJob(claim());assertEquals("STOPPED",result.get("state"));assertEquals("STOPPED",chat.generation(2,id).get("state"));verifyNoInteractions(models);}
    @Test void restartPreservesTextAndReportsInterruptedAsJobFailure(){var generation=send("你好");String id=generation.get("generationId").toString();db.update("UPDATE ps_chat_generation SET state='RUNNING' WHERE id=?",id);db.update("UPDATE ps_chat_message SET content='已保存文本',state='RUNNING' WHERE id=?",generation.get("messageId"));assertThrows(IllegalStateException.class,()->chat.processJob(claim()));assertEquals("INTERRUPTED",chat.generation(2,id).get("state"));assertEquals("已保存文本",chat.generation(2,id).get("text"));verifyNoInteractions(models);}
    @Test void failedTerminalGenerationCannotReturnSuccessfulJobResult(){send("你好");db.update("UPDATE ps_chat_generation SET state='FAILED'");assertThrows(IllegalStateException.class,()->chat.processJob(claim()));verifyNoInteractions(models);}
    @Test void canceledJobCannotMutateGenerationOrCallModel(){send("你好");var job=claim();jobs.cancel(job.id(),job.rowVersion());assertThrows(IllegalStateException.class,()->chat.processJob(job));assertEquals("QUEUED",db.queryForObject("SELECT state FROM ps_chat_generation",String.class));verifyNoInteractions(models);}
    @Test void managerAuditsOnlyExplicitlyBoundManagedOrganization(){var privateConversation=chat.createConversation(2,"私人");var organization=chat.createConversation(2,"组织","org1");var actor=new SpaceService.Scope(3,false);var page=chat.auditPage(actor,null,null,1,20);assertEquals(1L,page.get("total"));assertThrows(IllegalArgumentException.class,()->chat.auditMessages(actor,privateConversation.get("id").toString(),1,20));assertEquals(0L,chat.auditMessages(actor,organization.get("id").toString(),1,20).get("total"));assertEquals(2L,chat.auditPage(new SpaceService.Scope(1,true),null,null,1,20).get("total"));}
    @Test void historyFilteringAndPagingUseFullCount(){for(int i=0;i<120;i++)chat.createConversation(2,i%2==0?"资料甲":"资料乙");var page=chat.conversationPage(2,false,"资料甲",3,20);assertEquals(60L,page.get("total"));assertEquals(20,((List<?>)page.get("rows")).size());}
    @Test void operationsCancellationAndManualRetryPreserveOldMessage(){var generation=send("你好");var job=claim();db.update("UPDATE ps_chat_message SET content='旧内容' WHERE id=?",generation.get("messageId"));chat.onCancelled(job);assertEquals("STOPPED",chat.generation(2,generation.get("generationId").toString()).get("state"));chat.onRetry(job);var restarted=chat.generation(2,generation.get("generationId").toString());assertEquals("QUEUED",restarted.get("state"));assertNotEquals(generation.get("messageId"),restarted.get("messageId"));assertEquals("",restarted.get("text"));assertEquals("旧内容",db.queryForObject("SELECT content FROM ps_chat_message WHERE id=?",String.class,generation.get("messageId")));}
    @Test void uncertainModelCallBlocksManualRetry(){var generation=send("你好");var job=claim();chat.onCancelled(job);db.update("INSERT INTO ps_model_call(id,user_id,scope,operation_id,request_hash,status,reservation_id,created_at,updated_at) VALUES('call',2,'LLM',?,'hash','UNCERTAIN','reservation',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","chat-final:"+generation.get("generationId")+":attempt:0");assertThrows(IllegalStateException.class,()->chat.onRetry(job));assertEquals("STOPPED",chat.generation(2,generation.get("generationId").toString()).get("state"));}

    Map<String,Object> tool(String id,String name,Map<String,Object> args){return Map.of("id",id,"type","function","function",Map.of("name",name,"arguments",com.ruoyi.paismart.infrastructure.Jsons.write(args)));}
    Map<String,Object> plan(Object... calls){return Map.of("role","assistant","content","","tool_calls",List.of(calls));}
    void retrieval(List<SearchService.Evidence> evidence){
        when(search.search(any(),anyString(),anyInt(),nullable(String.class))).thenReturn(new SearchService.SearchResult(evidence,"RRF",List.of(),evidence.size()));
        when(search.sharedEvidence(any(),anyList(),anyString())).thenAnswer(i->i.getArgument(1));when(search.expand(any(),anyList())).thenAnswer(i->i.getArgument(1));
        when(citations.register(anyString(),any(),anyInt(),anyString())).thenAnswer(i->{SearchService.Evidence e=i.getArgument(1);return new LinkedHashMap<>(Map.of("id",e.id(),"documentId",e.documentId(),"number",i.getArgument(2),"text",e.text(),"contentVersion",e.contentVersion()));});
    }
    SearchService.Evidence evidence(String id,String parentKey){return new SearchService.Evidence(id,"doc",1,1,2,"文件","小块原文","位置","不可作事实的生成说明",0.7,"RRF","父块真实证据",parentKey);}
    @SafeVarargs final void turns(Map<String,Object>... answers){java.util.concurrent.atomic.AtomicInteger cursor=new java.util.concurrent.atomic.AtomicInteger();doAnswer(i->{Map<String,Object> answer=answers[Math.min(cursor.getAndIncrement(),answers.length-1)];java.util.function.Consumer<String> token=i.getArgument(4);String content=com.ruoyi.paismart.infrastructure.intelligence.Values.text(answer.get("content"));if(!content.isEmpty())token.accept(content);return answer;}).when(models).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());}
    @Test void reactNaturalAnswerIsPersistedWithoutSecondPaidFinalCall(){
        var generation=send("你好");turns(Map.of("role","assistant","content","你好，欢迎"));
        chat.processJob(claim());assertEquals("你好，欢迎",chat.generation(2,generation.get("generationId").toString()).get("text"));verify(models,times(1)).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());verify(models,never()).stream(anyLong(),anyString(),anyList(),any(),any(),any());
    }
    @Test void naturalDeltaIsAlreadyDurableBeforeTheModelTurnCompletes(){
        var generation=send("你好");String id=generation.get("generationId").toString();
        doAnswer(i->{java.util.function.Consumer<String> output=i.getArgument(4);output.accept("第一段");assertEquals("RUNNING",chat.generation(2,id).get("state"));assertEquals("第一段",chat.generation(2,id).get("text"));assertEquals("第一段",chat.events(2,id,0).get(0).get("text"));output.accept("第二段");return Map.of("content","第一段第二段");}).when(models).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());
        chat.processJob(claim());assertEquals("第一段第二段",chat.generation(2,id).get("text"));assertEquals(2,chat.events(2,id,0).stream().filter(e->"delta".equals(e.get("kind"))).count());verify(models,never()).stream(anyLong(),anyString(),anyList(),any(),any(),any());
    }
    @Test void stopWhileStreamingRetainsPartialAnswerWithoutAnotherModelCall(){
        var generation=send("你好");String id=generation.get("generationId").toString();
        doAnswer(i->{java.util.function.Consumer<String> output=i.getArgument(4);java.util.function.BooleanSupplier cancel=i.getArgument(5);output.accept("已收到");chat.stop(2,id);assertTrue(cancel.getAsBoolean());throw new java.util.concurrent.CancellationException();}).when(models).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());
        assertEquals("STOPPED",chat.processJob(claim()).get("state"));assertEquals("已收到",chat.generation(2,id).get("text"));assertEquals("stopped",chat.events(2,id,0).get(1).get("kind"));verify(models,times(1)).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());verify(models,never()).stream(anyLong(),anyString(),anyList(),any(),any(),any());
    }
    @Test void toolPlanningContentIsStreamedOnceAndPreservedBeforeTheFinalAnswer(){
        var generation=send("统计知识");when(search.statistics(any())).thenReturn(Map.of("documents",1));turns(Map.of("role","assistant","content","先统计。","tool_calls",List.of(tool("t1","knowledge_stats",Map.of()))),Map.of("content","共有一份。"));
        chat.processJob(claim());assertEquals("先统计。共有一份。",chat.generation(2,generation.get("generationId").toString()).get("text"));verify(search,times(1)).statistics(any());
    }
    @Test void streamedSummaryIsDurableFinalAnswerAndKeepsRawCitation(){
        retrieval(List.of(evidence("chunk","shared")));var generation=send("概括主题");
        turns(plan(tool("t1","generate_summary",Map.of("topic","主题","maxDocs",3))));
        doAnswer(i->{java.util.function.Consumer<String> out=i.getArgument(3);out.accept("摘要");out.accept("[1]");return null;}).when(models).stream(anyLong(),startsWith("summary:"),anyList(),any(),any(),any());
        assertEquals("COMPLETE",chat.processJob(claim()).get("state"));assertEquals("摘要[1]",chat.generation(2,generation.get("generationId").toString()).get("text"));verify(models,never()).stream(anyLong(),startsWith("chat-final:"),anyList(),any(),any(),any());verify(citations).register(anyString(),any(),eq(1),eq("主题"));
        var messages=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(eq(2L),startsWith("summary:"),messages.capture(),any(),any(),any());String input=messages.getValue().toString();assertTrue(input.contains("父块真实证据"));assertFalse(input.contains("不可作事实的生成说明"));
    }
    @Test void partialSummaryFailureKeepsDurableTextAndAvoidsRewrite(){
        retrieval(List.of(evidence("chunk","shared")));var generation=send("概括主题");turns(plan(tool("t","generate_summary",Map.of("topic","主题"))));
        doAnswer(i->{java.util.function.Consumer<String> out=i.getArgument(3);out.accept("已生成一半[1]");throw new IllegalStateException("未知远程结果");}).when(models).stream(anyLong(),startsWith("summary:"),anyList(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->chat.processJob(claim()));var saved=chat.generation(2,generation.get("generationId").toString());assertEquals("FAILED",saved.get("state"));assertEquals("已生成一半[1]",saved.get("text"));assertEquals(1,((List<?>)saved.get("references")).size());verify(models,never()).stream(anyLong(),startsWith("chat-final:"),anyList(),any(),any(),any());
    }
    @Test void malformedToolArgumentsProduceFailureResponseAndContinue(){
        var generation=send("知识问题");turns(plan(tool("t","search_knowledge",Map.of("query","问题","administrator",true))),Map.of("content","资料不足"));
        chat.processJob(claim());assertEquals("资料不足",chat.generation(2,generation.get("generationId").toString()).get("text"));verifyNoInteractions(search);
        var inputs=org.mockito.ArgumentCaptor.forClass(List.class);verify(models,times(2)).streamTurn(eq(2L),anyString(),inputs.capture(),anyList(),any(),any(),any());assertTrue(inputs.getAllValues().get(1).toString().contains("success=false")||inputs.getAllValues().get(1).toString().contains("\\\"success\\\":false")||inputs.getAllValues().get(1).toString().contains("\"success\":false"));
    }
    @Test void everyToolCallIncludingBudgetExcessReceivesAResponse(){
        send("统计知识");List<Object> calls=new ArrayList<>();for(int i=0;i<10;i++)calls.add(tool("t"+i,"knowledge_stats",Map.of()));when(search.statistics(any())).thenReturn(Map.of("documents",1));turns(Map.of("role","assistant","tool_calls",calls));
        doAnswer(i->{java.util.function.Consumer<String> out=i.getArgument(3);out.accept("统计完成");return null;}).when(models).stream(anyLong(),anyString(),anyList(),any(),any(),any());chat.processJob(claim());
        var input=org.mockito.ArgumentCaptor.forClass(List.class);verify(models).stream(eq(2L),startsWith("chat-final:"),input.capture(),any(),any(),any());long responses=input.getValue().stream().filter(x->"tool".equals(((Map<?,?>)x).get("role"))).count();assertEquals(10,responses);verify(search,times(8)).statistics(any());
    }
    @Test void originalFeedbackReasonParameterPersistsAndToolSchemaExposesIt(){
        send("反馈：回答有帮助");turns(plan(tool("t","submit_feedback",Map.of("rating","good","reason","要点清楚"))),Map.of("content","反馈已记录"));chat.processJob(claim());
        assertEquals("要点清楚",db.queryForObject("SELECT note FROM ps_tool_feedback",String.class));assertEquals("GOOD",db.queryForObject("SELECT rating FROM ps_tool_feedback",String.class));var schemas=org.mockito.ArgumentCaptor.forClass(List.class);verify(models,times(2)).streamTurn(anyLong(),anyString(),anyList(),schemas.capture(),any(),any(),any());assertTrue(schemas.getValue().toString().contains("reason"));verifyNoInteractions(search);
    }
    @Test void multipleSearchesKeepStableNumbersAndParentEvidenceForVerification(){
        var first=evidence("one","shared");var second=evidence("two","shared");retrieval(List.of(first,second));send("检索资料");turns(plan(tool("t1","search_knowledge",Map.of("query","第一查询"))),plan(tool("t2","search_knowledge",Map.of("query","第二查询"))),Map.of("content","回答[1][2]"));chat.processJob(claim());verify(citations,times(2)).register(anyString(),any(),anyInt(),eq("第一查询"));verify(citations,never()).register(anyString(),any(),anyInt(),eq("第二查询"));
        var context=org.mockito.ArgumentCaptor.forClass(Map.class);verify(citations).verifyAnswer(any(),startsWith("verify:"),eq("回答[1][2]"),anyList(),context.capture(),any(),any());assertEquals(Map.of(1,"父块真实证据",2,"父块真实证据"),context.getValue());
    }

    @Test void dateOnlyHistoryIncludesLastMicrosecondAndRejectsNextDayAndOtherOwner(){
        var day=chat.createConversation(2,"当天");var outside=chat.createConversation(2,"次日");chat.createConversation(3,"他人");
        db.update("UPDATE ps_conversation SET updated_at=TIMESTAMP '2026-10-02 23:59:59.999999' WHERE id=?",day.get("id"));db.update("UPDATE ps_conversation SET updated_at=TIMESTAMP '2026-10-03 00:00:00' WHERE id=?",outside.get("id"));
        var page=chat.conversationPage(2,false,null,1,20,"2026-10-02","2026-10-02");assertEquals(1L,page.get("total"));assertEquals(day.get("id"),((Map<?,?>)((List<?>)page.get("rows")).get(0)).get("id"));
        assertThrows(IllegalArgumentException.class,()->chat.conversationPage(2,false,null,1,20,"2026-10-03","2026-10-02"));assertThrows(IllegalArgumentException.class,()->chat.conversationPage(2,false,null,1,20,"2026-13-02",null));
    }
    @Test void auditOwnerAndDateFiltersCannotExpandManagerScope(){
        chat.createConversation(2,"私人");var org=chat.createConversation(2,"组织","org1");db.update("UPDATE ps_conversation SET updated_at=TIMESTAMP '2026-10-02 15:00:00'");
        var actor=new SpaceService.Scope(3,false);var page=chat.auditPage(actor,2L,null,1,20,"2026-10-02T14","2026-10-02T16:00");assertEquals(1L,page.get("total"));assertEquals(org.get("id"),((Map<?,?>)((List<?>)page.get("rows")).get(0)).get("id"));assertEquals(0L,chat.auditPage(actor,2L,null,1,20,"2026-10-03","2026-10-03").get("total"));
    }
}
