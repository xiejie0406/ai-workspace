package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.paiflow.adapter.legacy.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 调度/Token解析器为受控边界；账号、菜单、成员、版本、Run、CP均读实际H2 Mapper。 */
class LegacyDebugStreamPersistenceTest extends WorkflowDebugTestFixture {
    LegacyDebugBridgeService bridge;LegacyDebugStreamService streams;TokenService tokens;
    ScheduledExecutorService scheduler;ThreadPoolTaskExecutor io;ScheduledFuture<?> future;
    LoginUser cached;JSONObject body;
    @BeforeEach void setupStream(){
        bridge=proxy(new LegacyDebugBridgeService(workflows,defaults,access,runs,runMapper,checkpoints,checkpointMapper));tokens=mock(TokenService.class);scheduler=mock(ScheduledExecutorService.class);io=mock(ThreadPoolTaskExecutor.class);future=mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class),eq(0L),eq(250L),eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation->future);
        doAnswer(invocation->{((Runnable)invocation.getArgument(0)).run();return null;}).when(io).execute(any(Runnable.class));
        cached=identities.current();cached.setToken("fixture_session_uuid");cached.setExpireTime(System.currentTimeMillis()+300_000);when(tokens.getLoginUserByToken("Bearer fixture_debug")).thenAnswer(invocation->cached);streams=new LegacyDebugStreamService(bridge,tokens,identities,scheduler,io);
    }
    LegacyDebugStreamService.Session session(){return streams.session("Bearer fixture_debug");}
    LegacyDebugBridgeService.Attachment start(){var graph=canvas();var built=ordinary(createAgent(graph),graph,"stream_build_key");body=JSONObject.of("flow_id",built.get("flowId"),"inputs",JSONObject.of("question","本地实际题面"),"chatId","stream_chat","regen",false,"debugReceipt",receipt(built),"requestKey","stream_run_key");return bridge.chat(body,Long.toString(space),null);}
    JSONObject replyBody(LegacyDebugBridgeService.Attachment run,JSONObject cp){return JSONObject.of("flow_id",body.get("flow_id"),"runId",run.runId(),"debugReceipt",body.get("debugReceipt"),"eventId",cp.get("checkpointId"),"eventType","abort","expectedRevision",cp.get("revision").toString(),"replyKey","stream_reply_key");}
    @Test void sessionCannotSwapToAnotherActorOrAnotherLoginOfTheSameActor(){var valid=session();cached=new LoginUser(43L,8L,users.selectUserById(43L),Set.of());cached.setToken("different_uuid");cached.setExpireTime(System.currentTimeMillis()+300_000);assertEquals(401,denied(()->streams.withSession(valid,()->null)));cached.setUserId(42L);assertEquals(401,denied(()->streams.withSession(valid,()->null)));assertFalse(valid.toString().contains("fixture_session_uuid"));assertFalse(valid.toString().contains("Bearer"));}
    @Test void logoutAndExpirationRejectBeforeAnyBackgroundRead(){var valid=session();cached=null;var calls=new java.util.concurrent.atomic.AtomicInteger();assertEquals(401,denied(()->streams.withSession(valid,()->calls.incrementAndGet())));cached=new LoginUser(42L,8L,users.selectUserById(42L),Set.of());cached.setToken("fixture_session_uuid");cached.setExpireTime(System.currentTimeMillis()-1);assertEquals(401,denied(()->streams.withSession(valid,()->calls.incrementAndGet())));assertEquals(0,calls.get());}
    @Test void backgroundReadsUseOriginalFreshPermissionsAndRestoreAllThreadContext() throws Exception {
        var run=start();var valid=session();var previous=SecurityContextHolder.getContext();var request=RequestContextHolder.getRequestAttributes();assertNotNull(request);streams.withSession(valid,()->{assertNotSame(request,RequestContextHolder.getRequestAttributes());assertNotSame(previous,SecurityContextHolder.getContext());assertEquals("QUEUED",bridge.page(run,0).state());assertTrue(RequestContextHolder.currentRequestAttributes().getAttributeNames(RequestAttributes.SCOPE_REQUEST).length>0);return null;});assertSame(previous,SecurityContextHolder.getContext());assertSame(request,RequestContextHolder.getRequestAttributes());revoke("paiflow:run:query");assertEquals(403,denied(()->streams.withSession(valid,()->bridge.page(run,0))));assertSame(previous,SecurityContextHolder.getContext());assertSame(request,RequestContextHolder.getRequestAttributes());
    }
    @Test void missingRequestAndExceptionalWorkLeaveNoBackgroundPermissionAttributes() throws Exception {
        var valid=session();RequestContextHolder.resetRequestAttributes();var previous=SecurityContextHolder.getContext();assertThrows(IllegalStateException.class,()->streams.withSession(valid,()->{access.requirePermission("paiflow:run:query");throw new IllegalStateException("fixture work failure");}));assertNull(RequestContextHolder.getRequestAttributes());assertSame(previous,SecurityContextHolder.getContext());
    }
    @Test void disabledAccountAndRemovedMembershipCannotKeepReadingWithCachedToken() throws Exception {
        var run=start();var valid=session();assertEquals(1,sql("UPDATE sys_user SET status='1' WHERE user_id=42"));assertEquals(401,denied(()->streams.withSession(valid,()->bridge.page(run,0))));sql("UPDATE sys_user SET status='0' WHERE user_id=42");assertEquals(1,sql("DELETE FROM paiflow_space_member WHERE space_id="+space+" AND user_id=42"));assertTrue(Set.of(403,404).contains(denied(()->streams.withSession(valid,()->bridge.page(run,0)))));assertEquals("QUEUED",runMapper.find(run.runId()).getState());
    }
    @Test void fifthPerUserReservationRejectsAndCloseCanReleaseOnlyOnce(){var valid=session();var reserved=new ArrayList<LegacyDebugStreamService.Reservation>();for(int i=0;i<4;i++)reserved.add(streams.reserve(valid));assertEquals(503,denied(()->streams.reserve(valid)));reserved.get(0).close();reserved.get(0).close();try(var replacement=streams.reserve(valid)){assertEquals(503,denied(()->streams.reserve(valid)));}reserved.forEach(LegacyDebugStreamService.Reservation::close);for(int i=0;i<4;i++)try(var next=streams.reserve(valid)){assertNotNull(next);}}
    @Test void globalReservationsAreBoundedAcrossDifferentUsersAndReleasedAfterRejection(){
        var identity=mock(IdentityMappingService.class);var token=mock(TokenService.class);var bounded=new LegacyDebugStreamService(bridge,token,identity,scheduler,io);var reserved=new ArrayList<LegacyDebugStreamService.Reservation>();for(long actor=100;actor<116;actor++){var login=new LoginUser();login.setUserId(actor);login.setToken("fixture_"+actor);login.setExpireTime(System.currentTimeMillis()+300_000);when(identity.current()).thenReturn(login);when(token.getLoginUserByToken("fixture")).thenReturn(login);var valid=bounded.session("fixture");for(int i=0;i<4;i++)reserved.add(bounded.reserve(valid));}var last=bounded.session("fixture");assertEquals(503,denied(()->bounded.reserve(last)));reserved.get(0).close();reserved.get(0).close();var firstLogin=new LoginUser();firstLogin.setUserId(100L);firstLogin.setToken("fixture_100");firstLogin.setExpireTime(System.currentTimeMillis()+300_000);when(identity.current()).thenReturn(firstLogin);when(token.getLoginUserByToken("fixture")).thenReturn(firstLogin);try(var replacement=bounded.reserve(bounded.session("fixture"))){assertEquals(503,denied(()->bounded.reserve(last)));}reserved.forEach(LegacyDebugStreamService.Reservation::close);
    }
    @Test void schedulerFailureReleasesReservationAndDoesNotClaimRunCancellation() throws Exception {
        var run=start();var valid=session();when(scheduler.scheduleWithFixedDelay(any(Runnable.class),eq(0L),eq(250L),eq(TimeUnit.MILLISECONDS))).thenThrow(new RejectedExecutionException("fixture scheduler stopped"));try(var reservation=streams.reserve(valid)){assertEquals(503,denied(()->reservation.open(run)));}assertEquals("QUEUED",runMapper.find(run.runId()).getState());var held=new ArrayList<LegacyDebugStreamService.Reservation>();for(int i=0;i<4;i++)held.add(streams.reserve(valid));held.forEach(LegacyDebugStreamService.Reservation::close);
    }
    @Test void actualMockMvcSseUsesDatabaseSequenceAndOnlyOfficialCancellationEndsStream() throws Exception {
        start();var controller=new LegacyDebugController(bridge,streams);var mvc=MockMvcBuilders.standaloneSetup(controller).build();var request=mvc.perform(post("/paiflow/legacy/workflow/chat").header("Authorization","Bearer fixture_debug").header("space-id",Long.toString(space)).contentType("application/json").content(body.toJSONString(JSONWriter.Feature.WriteMapNullValue))).andExpect(request().asyncStarted()).andExpect(header().string("X-PaiFlow-Workflow-Version-Id",body.getJSONObject("debugReceipt").getString("workflowVersionId"))).andExpect(header().string("Access-Control-Expose-Headers","X-PaiFlow-Run-Id,X-PaiFlow-Workflow-Version-Id")).andReturn();String runId=request.getResponse().getHeader("X-PaiFlow-Run-Id");assertNotNull(runId);assertEquals("CANCELLED",runs.cancel(runId).state());var tick=ArgumentCaptor.forClass(Runnable.class);verify(scheduler).scheduleWithFixedDelay(tick.capture(),eq(0L),eq(250L),eq(TimeUnit.MILLISECONDS));tick.getValue().run();var response=mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn().getResponse();String wire=response.getContentAsString(StandardCharsets.UTF_8);assertTrue(wire.contains("id:"+runId+":1"));assertTrue(wire.contains("\"code\":499"));assertTrue(wire.contains("\"finish_reason\":\"stop\""));verify(future).cancel(false);assertEquals(1,rows("paiflow_run"));Files.writeString(Path.of("target/debug-stream-actual-response.json"),JSONObject.of("scope","actual isolated H2 MockMvc SSE; controlled scheduler and token resolver, no live HTTP or browser","request",body,"headers",JSONObject.of("X-PaiFlow-Run-Id",runId,"X-PaiFlow-Workflow-Version-Id",response.getHeader("X-PaiFlow-Workflow-Version-Id")),"bodyUTF8Base64",Base64.getEncoder().encodeToString(response.getContentAsByteArray()),"wire",wire).toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
    @Test void replyOnlyHttpStopAcknowledgesActualCheckpointWithoutOpeningStreamOrCompletingRun() throws Exception {
        var run=start();var task=leases.claim(worker);assertTrue(leases.beginExecution(run.runId(),worker,task.getString("leaseId"),task.getIntValue("attempt")).getBooleanValue("begun"));var cp=checkpoint(task);var mvc=MockMvcBuilders.standaloneSetup(new LegacyDebugController(bridge,streams)).build();var reply=replyBody(run,cp);var result=mvc.perform(post("/paiflow/legacy/workflow/resume").header("Authorization","Bearer fixture_debug").header("space-id",Long.toString(space)).header("X-PaiFlow-Reply-Only","true").contentType("application/json").content(reply.toJSONString(JSONWriter.Feature.WriteMapNullValue))).andExpect(status().isOk()).andExpect(request().asyncNotStarted()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.runId").value(run.runId())).andExpect(jsonPath("$.data.state").value("RESUMED")).andExpect(jsonPath("$.data.stopped").value(false)).andReturn();assertEquals("RUNNING",runMapper.find(run.runId()).getState());verifyNoInteractions(scheduler,io);assertEquals(1,rows("paiflow_run"));Files.writeString(Path.of("target/debug-reply-actual-response.json"),JSONObject.of("scope","actual isolated H2 MockMvc stop acknowledgement, no Worker completion","request",reply,"response",JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8))).toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
}
