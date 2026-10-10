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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @BeforeEach void prepare() throws Exception {var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:chat_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");try(Connection c=source.getConnection()){for(String file:List.of("V001__control_billing_jobs.sql","V003__intelligence.sql","V005__evaluation_datasets.sql","V006__message_feedback.sql"))ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/"+file));}db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(40),status VARCHAR(1),del_flag VARCHAR(1))");db.update("INSERT INTO sys_user VALUES(1,'root','0','0'),(2,'owner','0','0'),(3,'manager','0','0')");jobs=new JobService(db,tx);limits=mock(RateLimitService.class);models=mock(ModelGateway.class);spaces=mock(SpaceService.class);when(spaces.visibleSpaceIds(any())).thenReturn(Set.of("org1","org2"));when(spaces.canManageSpace(any(),eq("org1"))).thenReturn(true);search=mock(SearchService.class);citations=mock(CitationService.class);chat=new ChatService(db,tx,jobs,models,search,citations,mock(IntelligenceSettings.class),mock(MembershipService.class),limits,spaces);
        // 当前提交及消费会冻结Provider；使用隔离原供应商对象，不触发真实连接。
        var provider=new ProviderService.Provider("offline-provider","LLM","隔离供应商","https://model.invalid","offline-model",0,"ai.paismart.offline",true,true,1,Map.of());
        var providers=mock(ProviderService.class);when(providers.active("LLM")).thenReturn(provider);when(providers.queuedText(anyMap())).thenReturn(provider);
        org.springframework.test.util.ReflectionTestUtils.setField(chat,"providers",providers);
    }
    Map<String,Object> send(String text){return chat.submit(2,Map.of("text",text,"clientRequestId","request"));}
    Map<String,Object> sendFeedback(String text){
        String conversation=chat.createConversation(2,"原回答的评价").get("id").toString();
        db.update("INSERT INTO ps_chat_message(id,conversation_id,generation_id,role,content,references_json,state,created_at) VALUES('previous-message',?,'previous-generation','assistant','已有实际回答','[]','COMPLETE',TIMESTAMP '2026-01-01 00:00:00')",conversation);
        db.update("INSERT INTO ps_chat_generation(id,conversation_id,user_id,assistant_message_id,client_request_id,state,request_json,created_at,updated_at) VALUES('previous-generation',?,2,'previous-message','baseline','COMPLETE','{\"text\":\"原问题\"}',TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')",conversation);
        return chat.submit(2,Map.of("text",text,"conversationId",conversation,"clientRequestId","request"));
    }
    JobService.Job claim(){String id=db.queryForObject("SELECT id FROM ps_job",String.class);return jobs.claimById(id,180).orElseThrow();}
    @Test void missingConversationOnRetryReusesOneGenerationAndOneRateCharge(){var first=send("你好");var second=send("你好");assertEquals(first.get("generationId"),second.get("generationId"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_conversation",Long.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));verify(limits,times(1)).acquire(2,"CHAT");}
    @Test void changedRetryInputCannotCreateAnotherConversation(){send("原问题");assertThrows(IllegalStateException.class,()->send("不同问题"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ps_conversation",Long.class));}
    @Test void stoppedQueuedGenerationPersistsTerminalStateWithoutCallingModel(){var generation=send("你好");String id=generation.get("generationId").toString();chat.stop(2,id);var result=chat.processJob(claim());assertEquals("STOPPED",result.get("state"));assertEquals("STOPPED",chat.generation(2,id).get("state"));verifyNoInteractions(models);}
    @Test void restartPreservesTextAndReportsInterruptedAsJobFailure(){var generation=send("你好");String id=generation.get("generationId").toString();db.update("UPDATE ps_chat_generation SET state='RUNNING' WHERE id=?",id);db.update("UPDATE ps_chat_message SET content='已保存文本',state='RUNNING' WHERE id=?",generation.get("messageId"));assertThrows(IllegalStateException.class,()->chat.processJob(claim()));assertEquals("INTERRUPTED",chat.generation(2,id).get("state"));assertEquals("已保存文本",chat.generation(2,id).get("text"));verifyNoInteractions(models);}
    @Test void failedTerminalGenerationCannotReturnSuccessfulJobResult(){send("你好");db.update("UPDATE ps_chat_generation SET state='FAILED'");assertThrows(IllegalStateException.class,()->chat.processJob(claim()));verifyNoInteractions(models);}
    @Test void canceledJobCannotMutateGenerationOrCallModel(){send("你好");var job=claim();jobs.cancel(job.id(),job.rowVersion());assertThrows(IllegalStateException.class,()->chat.processJob(job));assertEquals("QUEUED",db.queryForObject("SELECT state FROM ps_chat_generation",String.class));verifyNoInteractions(models);}
    @Test void managerAuditsOnlyExplicitlyBoundManagedOrganization(){var privateConversation=chat.createConversation(2,"私人");var organization=chat.createConversation(2,"组织","org1");var actor=new SpaceService.Scope(3,false);var page=chat.auditPage(actor,null,null,1,20);assertEquals(1L,page.get("total"));assertThrows(IllegalArgumentException.class,()->chat.auditMessages(actor,privateConversation.get("id").toString(),1,20));assertEquals(0L,chat.auditMessages(actor,organization.get("id").toString(),1,20).get("total"));assertEquals(2L,chat.auditPage(new SpaceService.Scope(1,true),null,null,1,20).get("total"));}
    @Test void scopedEmptyConversationReceiptMatchesStoredSelectionAndList(){
        var created=chat.createConversation(1,"管理员空会话","org1");String id=created.get("id").toString();
        assertEquals("org1",created.get("space_id"));assertEquals(created.get("space_id"),created.get("spaceId"));
        assertEquals("org1",db.queryForObject("SELECT space_id FROM ps_conversation WHERE id=? AND user_id=1",String.class,id));
        var selected=chat.selectConversation(1,id);assertEquals(created.get("space_id"),selected.get("space_id"));assertEquals(created.get("rowVersion"),selected.get("rowVersion"));
        var page=chat.conversationPage(1,false,null,1,20);assertEquals(1L,page.get("total"));var listed=(Map<?,?>)((List<?>)page.get("rows")).get(0);
        assertEquals(id,listed.get("id"));assertEquals(created.get("spaceId"),listed.get("spaceId"));
        assertThrows(IllegalArgumentException.class,()->chat.selectConversation(2,id));
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_chat_message",Long.class));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));verifyNoInteractions(models);
    }
    @Test void nullEmptyAndBlankConversationScopesRemainUnbound(){
        for(String scope:Arrays.asList(null,"","   ")){
            var created=chat.createConversation(2,"不限定组织",scope);String id=created.get("id").toString();
            assertTrue(created.containsKey("space_id"));assertTrue(created.containsKey("spaceId"));assertNull(created.get("space_id"));assertNull(created.get("spaceId"));
            assertNull(chat.selectConversation(2,id).get("space_id"));
        }
        var page=chat.conversationPage(2,false,null,1,20);assertEquals(3L,page.get("total"));for(Object row:(List<?>)page.get("rows"))assertNull(((Map<?,?>)row).get("spaceId"));
        verifyNoInteractions(spaces,models);assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));
    }
    @Test void invisibleConversationScopeIsRejectedBeforeCreatingRows(){
        assertThrows(IllegalArgumentException.class,()->chat.createConversation(2,"无权空间","hidden-org"));
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_conversation",Long.class));assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_job",Long.class));
        verify(spaces).visibleSpaceIds(new SpaceService.Scope(2,false));verifyNoInteractions(models);
    }
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
    @Test void stopAfterFirstToolPreventsAnotherToolAndAnotherModelTurn(){
        var generation=send("先统计，再检索资料");String id=generation.get("generationId").toString();
        turns(plan(tool("stats","knowledge_stats",Map.of()),tool("search","search_knowledge",Map.of("query","资料"))));
        when(search.statistics(any())).thenAnswer(i->{chat.stop(2,id);return Map.of("documents",1);});
        assertEquals("STOPPED",chat.processJob(claim()).get("state"));assertEquals("STOPPED",chat.generation(2,id).get("state"));
        verify(search,times(1)).statistics(any());verify(search,never()).search(any(),anyString(),anyInt(),nullable(String.class));
        verify(models,times(1)).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());verify(models,never()).stream(anyLong(),anyString(),anyList(),any(),any(),any());
        assertEquals(1,chat.events(2,id,0).stream().filter(e->"stopped".equals(e.get("kind"))).count());
    }
    @Test void stopDuringCitationVerificationRetainsAnswerAndCannotReportComplete(){
        var generation=send("回答并核验引用");String id=generation.get("generationId").toString();turns(Map.of("content","已保存的回答[1]"));
        when(citations.verifyAnswer(any(),anyString(),anyString(),anyList(),anyMap(),any(),any())).thenAnswer(i->{
            Runnable guard=i.getArgument(6);guard.run();chat.stop(2,id);guard.run();return Map.of();
        });
        assertEquals("STOPPED",chat.processJob(claim()).get("state"));var saved=chat.generation(2,id);assertEquals("STOPPED",saved.get("state"));assertEquals("已保存的回答[1]",saved.get("text"));
        assertEquals("STOPPED",db.queryForObject("SELECT state FROM ps_chat_message WHERE id=?",String.class,generation.get("messageId")));
        var events=chat.events(2,id,0);assertEquals(1,events.stream().filter(e->"stopped".equals(e.get("kind"))).count());assertTrue(events.stream().noneMatch(e->"done".equals(e.get("kind"))));
        verify(models,times(1)).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());verify(models,never()).stream(anyLong(),anyString(),anyList(),any(),any(),any());
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
        sendFeedback("反馈：回答有帮助");turns(plan(tool("t","submit_feedback",Map.of("rating","good","reason","要点清楚"))),Map.of("content","反馈已记录"));chat.processJob(claim());
        assertEquals("要点清楚",db.queryForObject("SELECT note FROM ps_tool_feedback",String.class));assertEquals("GOOD",db.queryForObject("SELECT rating FROM ps_tool_feedback",String.class));var schemas=org.mockito.ArgumentCaptor.forClass(List.class);verify(models,times(2)).streamTurn(anyLong(),anyString(),anyList(),schemas.capture(),any(),any(),any());assertTrue(schemas.getValue().toString().contains("reason"));verifyNoInteractions(search);
    }
    @ParameterizedTest(name="明确评价 {0} 应记录 {1}")
    @CsvSource({
        "反馈：回答有帮助,good", "反馈：回答没帮助,bad", "这次回答很有帮助，请记录好评,GOOD",
        "这个答案准确又清楚，点赞,good", "答案解决了我的问题,good", "我很满意,GOOD",
        "回答非常有用,good", "回复很实用,GOOD", "很有用，谢谢,good", "答案很棒,GOOD", "这个回答非常好,good",
        "这次回复不太满意，请记差评,bad", "回答没有帮助，请记录差评,BAD", "这个回答不准确,bad",
        "The answer was helpful,GOOD", "The response was not helpful,bad", "Bad answer,BAD",
        "回答没用,bad", "回答没有用,BAD", "这个回答不够有用,bad", "反馈：回复毫无帮助,bad", "这次回答太差了,bad",
        "这个回答讲得很清楚,good", "这个答案让我很满意,GOOD", "反馈：好评,good", "反馈：差评,BAD",
        "'回答很有帮助，而且很准确',good", "请给这个回答好评,good", "给这个回答差评,BAD", "请记录好评,GOOD"
    })
    void explicitFeedbackMatchesRatingAndPersistsWithoutSearch(String prompt,String rating){
        sendFeedback(prompt);turns(plan(tool("feedback","submit_feedback",Map.of("rating",rating,"reason","原评价理由"))),Map.of("content","反馈已记录"));
        chat.processJob(claim());assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback",Long.class));
        assertEquals(rating.toUpperCase(Locale.ROOT),db.queryForObject("SELECT rating FROM ps_tool_feedback",String.class));
        assertEquals("原评价理由",db.queryForObject("SELECT note FROM ps_tool_feedback",String.class));verifyNoInteractions(search);
    }
    @ParameterizedTest(name="非明确或不匹配评价 {0} 不应记录 {1}")
    @CsvSource(quoteCharacter='`',value={
        "请解释反馈功能，不要提交反馈,good", "反馈功能是什么意思？,good", "Please explain feedback; do not submit feedback.,good",
        "反馈：回答没帮助,good", "回答有帮助,bad", "回答不满意,GOOD", "回答没有帮助,GOOD",
        "这次回答很满意，但不要记录反馈,good", "请解释什么是好评,good", "你觉得这个回答有帮助吗？,good",
        "如果回答有帮助就提交好评,good", "示例：回答有帮助,good", "Feedback means a good or bad rating,good",
        "回答有帮助但不准确,good", "回答有帮助但不准确,bad", "这个回答可能有帮助,good",
        "今天天气很好,good", "请概括本文,good", "反馈：回答有帮助,neutral",
        "回答没有错误,bad", "回答不是没帮助,good", "The answer isn't bad,bad",
        "The answer was good but don't provide feedback,good", "Good answer but no feedback please,good",
        "解释这个很好的回答,good", "请评价这个有帮助的答案,good",
        "你觉得这个回答有帮助,good", "请帮我解释这个很好的回答,good", "这个回答可以点赞吗,good",
        "我没说这个回答有帮助,good", "这篇文章提到这个回答有帮助,good",
        "Could you review this good answer,good", "这个回答正确的原因,good", "Please evaluate this helpful response,good",
        "而且很准确,good", "请证明回答正确，而且很准确,good",
        "请给这篇文章好评,good", "请记录反馈,good", "请给这个回答好评,bad",
        "原文说回答有帮助,good", "用户说回答有帮助,good", "“回答有帮助”,good", "'good answer',good"
    })
    void feedbackWithoutUnambiguousUserRatingDoesNotWrite(String prompt,String rating){
        sendFeedback(prompt);turns(plan(tool("feedback","submit_feedback",Map.of("rating",rating,"reason","模型参数不能代替用户意图"))),Map.of("content","没有记录反馈"));
        chat.processJob(claim());assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ps_tool_feedback",Long.class));
        verify(models,times(2)).streamTurn(anyLong(),anyString(),anyList(),anyList(),any(),any(),any());verifyNoInteractions(search);
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
