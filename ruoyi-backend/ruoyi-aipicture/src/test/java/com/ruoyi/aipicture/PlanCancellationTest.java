package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.tool.*;
import com.ruoyi.aipicture.worker.*;

/** 真实调用 send/cancel 的受控交错；无模型、HTTP 或数据库连接。 */
class PlanCancellationTest {
    private static final long OWNER=11;
    private AiPictureRepository db;
    private RunService runs;
    private SessionService sessions;
    private AiPictureWorkerClient worker;
    private PlanService plans;
    private String session,asset;
    private Map<String,Object> sessionState;
    private volatile Map<String,Object> storedTurn;
    private final List<Map<String,Object>> storedSteps=new CopyOnWriteArrayList<>();
    private final Map<String,Map<String,Object>> commands=new ConcurrentHashMap<>();
    private ExecutorService sender;
    private CountDownLatch plannerEntered,releasePlanner;

    @BeforeEach void setup() throws Exception {
        db=mock(AiPictureRepository.class);runs=mock(RunService.class);sessions=mock(SessionService.class);worker=mock(AiPictureWorkerClient.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());var tx=new TransactionTemplate(manager);
        plans=new PlanService(db,runs,sessions,new ToolRegistry(new AiPictureProperties()),worker,tx,new CommandIdempotency(db,tx));
        session=Json.id();asset=Json.id();sessionState=Json.of("id",session,"user_id",OWNER,"title","原会话","current_asset_id",asset,"row_version",4,"revision",2,"document",DocumentCommandService.documentOf(Json.of("id",asset,"width",400,"height",300)));
        when(db.owned(eq("aip_session"),eq(session),eq(OWNER),anyBoolean())).thenAnswer(call->Json.copy(sessionState));
        when(db.owned(eq("aip_asset"),eq(asset),eq(OWNER),eq(false))).thenReturn(Json.of("id",asset,"format","png","has_alpha",false));
        when(sessions.lock(OWNER,session,4)).thenAnswer(call->Json.copy(sessionState));
        when(db.one(anyString(),anyMap())).thenAnswer(call->readRow(call.getArgument(0),call.getArgument(1)));
        when(db.execute(anyString(),anyMap())).thenAnswer(call->writeRow(call.getArgument(0),call.getArgument(1)));
        when(db.query(anyString(),anyMap())).thenAnswer(call->{String sql=call.getArgument(0);return sql.contains("FROM aip_plan_step")&&!sql.startsWith("SELECT run_id")?storedSteps.stream().map(Json::copy).toList():List.of();});
        sender=Executors.newSingleThreadExecutor();plannerEntered=new CountDownLatch(1);releasePlanner=new CountDownLatch(1);
    }
    @AfterEach void cleanup() {releasePlanner.countDown();sender.shutdownNow();SecurityContextHolder.clearContext();}

    private synchronized Map<String,Object> readRow(String sql,Map<String,Object> p) {
        if(sql.contains("FROM aip_command")) return commands.get(Json.string(p.get("command"))+":"+p.get("key"));
        if(sql.contains("FROM aip_agent_turn")) {
            if(storedTurn==null||Json.number(p.get("owner"))!=OWNER) return null;
            if(p.get("key")!=null&&!Objects.equals(p.get("key"),storedTurn.get("command_key"))) return null;
            if(p.get("id")!=null&&!Objects.equals(p.get("id"),storedTurn.get("id"))) return null;
            if(p.get("session")!=null&&!Objects.equals(p.get("session"),session)) return null;
            return Json.copy(storedTurn);
        }
        return null;
    }
    private synchronized int writeRow(String sql,Map<String,Object> p) {
        if(sql.startsWith("INSERT INTO aip_agent_turn")) storedTurn=Json.of("id",p.get("id"),"user_id",p.get("owner"),"session_id",p.get("session"),"goal",p.get("goal"),"reply","","status","planning","base_revision",p.get("revision"),"base_version",p.get("version"),"steps",List.of(),"command_key",p.get("key"),"request_hash",p.get("hash"));
        else if(sql.startsWith("UPDATE aip_agent_turn")) {
            if(sql.contains("status='cancelled'")) storedTurn.put("status","cancelled");
            else if(sql.contains("status='failed'")) {storedTurn.put("status","failed");storedTurn.put("error_code",p.get("code"));storedTurn.put("message",p.get("message"));}
            else {storedTurn.put("status",p.get("status")!=null?p.get("status"):"executing");if(p.containsKey("reply")) {storedTurn.put("reply",p.get("reply"));storedTurn.put("steps",com.alibaba.fastjson2.JSON.parseArray(Json.string(p.get("steps"))));storedTurn.put("plan_hash",p.get("hash"));}}
        } else if(sql.startsWith("INSERT INTO aip_plan_step")) storedSteps.add(Json.of("id",p.get("id"),"tool",p.get("tool"),"params",Json.map(p.get("params")),"depends_on",com.alibaba.fastjson2.JSON.parseArray(Json.string(p.get("dependencies"))),"status","pending"));
        else if(sql.startsWith("INSERT INTO aip_command")) commands.put(Json.string(p.get("command"))+":"+p.get("key"),Json.of("request_hash",p.get("hash"),"result",Map.of()));
        else if(sql.startsWith("UPDATE aip_command")) commands.get(Json.string(p.get("command"))+":"+p.get("key")).put("result",Json.map(p.get("result")));
        return 1;
    }
    private Map<String,Object> metadata() {return Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey",Json.id(),"expectedVersion",4);}
    private Map<String,Object> request() {var value=metadata();value.put("text","保留原取消意图");return value;}
    private void authenticate(Set<String> permissions) {var user=new LoginUser();user.setUserId(OWNER);user.setPermissions(permissions);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
    private Map<String,Object> response(int count) {
        var steps=new ArrayList<Object>();for(int i=0;i<count;i++) steps.add(Json.of("id","step-"+i,"tool","move_layer","params",Json.of("dx",1),"depends_on",i==0?List.of():List.of("step-"+(i-1))));
        return Json.of("result",Json.of("reply","迟到的规划正文","steps",steps));
    }
    private Future<Map<String,Object>> blockedSend(Map<String,Object> response,AiPictureException failure) {
        when(worker.call(eq("plan"),anyMap())).thenAnswer(call->{plannerEntered.countDown();if(!releasePlanner.await(5,TimeUnit.SECONDS)) throw new AssertionError("测试未释放受控规划");if(failure!=null) throw failure;return response;});
        var request=request();return sender.submit(()->{authenticate(Set.of("*:*:*"));try{return plans.send(OWNER,session,request);}finally{SecurityContextHolder.clearContext();}});
    }
    private Map<String,Object> cancelWhileBlocked() throws Exception {
        assertTrue(plannerEntered.await(5,TimeUnit.SECONDS),"已登记 planning 后才开始取消");assertEquals("planning",storedTurn.get("status"));
        var cancelled=plans.action(OWNER,session,Json.string(storedTurn.get("id")),"cancel",metadata());assertEquals("cancelled",cancelled.get("status"));return cancelled;
    }
    private void assertCancelledWithoutLatePlan(Future<Map<String,Object>> sent) throws Exception {
        releasePlanner.countDown();var result=sent.get(5,TimeUnit.SECONDS);
        assertAll(()->assertEquals("cancelled",result.get("status")),()->assertEquals("cancelled",storedTurn.get("status")),()->assertEquals("",storedTurn.get("reply")),()->assertTrue(storedSteps.isEmpty()),()->assertTrue(Json.list(storedTurn.get("steps")).isEmpty()));
        verify(runs,never()).continuePlan(anyString(),anyLong());
    }

    private String existingTurn(String state) {
        String id=Json.id();var step=Json.of("id","step-0","tool","move_layer","params",Json.of("dx",1),"depends_on",List.of(),"status",state);
        storedSteps.add(step);
        storedTurn=Json.of("id",id,"user_id",OWNER,"session_id",session,"goal","保留正式结果","reply","正式计划正文","status",state,"base_revision",2,"base_version",4,"steps",List.of(Json.copy(step)),"message",state.equals("failed")?"原失败原因":"");
        return id;
    }
    private void assertTerminalCancelRejected(String id,String originalState) {
        String original=Json.write(storedTurn),originalSteps=Json.write(storedSteps);
        var error=assertThrows(AiPictureException.class,()->plans.action(OWNER,session,id,"cancel",metadata()));
        assertAll(()->assertEquals(409,error.status()),()->assertEquals("PLAN_STATE",error.errorCode()),()->assertEquals(originalState,storedTurn.get("status")),()->assertEquals(original,Json.write(storedTurn)),()->assertEquals(originalSteps,Json.write(storedSteps)));
        verify(runs,never()).cancel(anyLong(),anyString());
        verify(db,never()).execute(startsWith("UPDATE aip_agent_turn SET status='cancelled'"),anyMap());
        verify(db,never()).execute(startsWith("UPDATE aip_plan_step SET status='cancelled'"),anyMap());
    }

    @ParameterizedTest @ValueSource(strings={"succeeded","failed","cancelled","external_result_unknown"})
    void terminalPlanCannotBeOverwrittenByANewCancel(String state) {
        assertTerminalCancelRejected(existingTurn(state),state);
    }
    @Test void completionBeforeCancellationAcquiresTurnLockKeepsItsTerminalResult() {
        String id=existingTurn("executing");
        when(db.one(contains("FROM aip_agent_turn"),anyMap())).thenAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.endsWith("FOR UPDATE")) {storedTurn.put("status","succeeded");storedSteps.get(0).put("status","succeeded");}
            return readRow(sql,call.getArgument(1));
        });
        var error=assertThrows(AiPictureException.class,()->plans.action(OWNER,session,id,"cancel",metadata()));
        assertAll(()->assertEquals("PLAN_STATE",error.errorCode()),()->assertEquals("succeeded",storedTurn.get("status")),()->assertEquals("succeeded",storedSteps.get(0).get("status")),()->assertEquals("正式计划正文",storedTurn.get("reply")));
        verify(db).one(endsWith("FOR UPDATE"),argThat(p->id.equals(p.get("id"))));
        verifyNoInteractions(runs);
    }
    @Test void rejectedCancellationOfAFailedPlanKeepsExplicitRetryAvailable() {
        authenticate(Set.of("*:*:*"));String id=existingTurn("failed");assertTerminalCancelRejected(id,"failed");
        when(db.count(contains("FROM aip_plan_step"),anyMap())).thenReturn(1L);
        when(db.count(contains("status='external_result_unknown'"),anyMap())).thenReturn(0L);
        var retried=plans.action(OWNER,session,id,"retry",metadata());
        assertEquals("executing",retried.get("status"));verify(runs).resumePlanBatches(eq(id),eq(OWNER),anyMap(),anyMap());verify(runs).continuePlan(id,OWNER);
    }
    @Test void sameCancellationKeyReplaysTheAcceptedResultWithoutCancellingAgain() {
        String id=existingTurn("waiting_confirmation");var command=metadata();
        var accepted=plans.action(OWNER,session,id,"cancel",command);assertEquals("cancelled",accepted.get("status"));
        var replay=plans.action(OWNER,session,id,"cancel",command);assertEquals(accepted,replay);
        verify(db,times(1)).execute(startsWith("UPDATE aip_agent_turn SET status='cancelled'"),anyMap());
        verify(db,times(1)).execute(startsWith("UPDATE aip_plan_step SET status='cancelled'"),anyMap());verifyNoInteractions(runs);
    }

    @Test void lateSingleStepCannotResumeCancelledPlanning() throws Exception {var sent=blockedSend(response(1),null);cancelWhileBlocked();assertCancelledWithoutLatePlan(sent);}
    @Test void lateMultiStepCannotReplaceCancelledPlanningWithConfirmation() throws Exception {var sent=blockedSend(response(2),null);cancelWhileBlocked();assertCancelledWithoutLatePlan(sent);}
    @Test void latePlannerFailureCannotOverwriteCancelledPlanning() throws Exception {var sent=blockedSend(null,new AiPictureException(503,"PLANNER_UNAVAILABLE","受控规划失败"));cancelWhileBlocked();assertCancelledWithoutLatePlan(sent);}
    @Test void ordinarySingleStepStillExecutesAndDuplicateSendDoesNotReplan() {
        authenticate(Set.of("*:*:*"));when(worker.call(eq("plan"),anyMap())).thenReturn(response(1));var request=request();var result=plans.send(OWNER,session,request);
        assertEquals("executing",result.get("status"));assertEquals(1,storedSteps.size());assertEquals("迟到的规划正文",result.get("reply"));plans.send(OWNER,session,request);
        verify(worker,times(1)).call(eq("plan"),anyMap());verify(runs,times(1)).continuePlan(Json.string(result.get("id")),OWNER);
    }
    @Test void ordinaryMultiStepRequiresHashConfirmationAndConfirmationReplayIsIdempotent() {
        authenticate(Set.of("*:*:*"));when(worker.call(eq("plan"),anyMap())).thenReturn(response(2));var result=plans.send(OWNER,session,request());assertEquals("waiting_confirmation",result.get("status"));verifyNoInteractions(runs);
        String id=Json.string(result.get("id"));var wrong=metadata();wrong.put("plan_hash","wrong");assertEquals("PLAN_CHANGED",assertThrows(AiPictureException.class,()->plans.action(OWNER,session,id,"confirm",wrong)).errorCode());
        var confirm=metadata();confirm.put("plan_hash",result.get("plan_hash"));plans.action(OWNER,session,id,"confirm",confirm);plans.action(OWNER,session,id,"confirm",confirm);assertEquals("executing",storedTurn.get("status"));verify(runs,times(1)).continuePlan(id,OWNER);
    }
    @Test void ordinaryPlannerFailureStillPersistsFailureAndReturnsTheError() {
        when(worker.call(eq("plan"),anyMap())).thenThrow(new AiPictureException(503,"PLANNER_UNAVAILABLE","受控规划失败"));assertEquals("PLANNER_UNAVAILABLE",assertThrows(AiPictureException.class,()->plans.send(OWNER,session,request())).errorCode());assertEquals("failed",storedTurn.get("status"));assertEquals("受控规划失败",storedTurn.get("message"));verifyNoInteractions(runs);
    }
    @Test void revokedToolPermissionStillRejectsPlanBeforeCreatingSteps() {
        authenticate(Set.of("aipicture:plan:confirm"));when(worker.call(eq("plan"),anyMap())).thenReturn(response(1));assertEquals("TOOL_PERMISSION_DENIED",assertThrows(AiPictureException.class,()->plans.send(OWNER,session,request())).errorCode());assertEquals("failed",storedTurn.get("status"));assertTrue(storedSteps.isEmpty());verifyNoInteractions(runs);
    }
    @Test void cancellationCannotReadAnotherOwnersTurn() {
        authenticate(Set.of("*:*:*"));when(worker.call(eq("plan"),anyMap())).thenReturn(response(2));var result=plans.send(OWNER,session,request());String id=Json.string(result.get("id"));
        when(db.owned("aip_session",session,12,false)).thenThrow(AiPictureException.missing());assertEquals(404,assertThrows(AiPictureException.class,()->plans.action(12,session,id,"cancel",metadata())).status());assertEquals("waiting_confirmation",storedTurn.get("status"));verifyNoInteractions(runs);
    }
}
