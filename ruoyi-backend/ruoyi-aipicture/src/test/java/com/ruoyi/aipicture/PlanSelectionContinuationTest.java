package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;

class PlanSelectionContinuationTest {
    AiPictureRepository db;RunService runs;String turn,session,mask,current;
    Map<String,Object> state;
    @BeforeEach void setup() throws Exception {
        db=mock(AiPictureRepository.class);when(db.one(anyString(),anyMap())).thenReturn(null);var sessions=mock(SessionService.class);var assets=mock(AssetService.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        runs=new RunService(db,sessions,assets,new ToolRegistry(new AiPictureProperties()),mock(AiPictureStorage.class),new AiPictureProperties(),new TransactionTemplate(manager));turn=Json.id();session=Json.id();mask=Json.id();current=Json.id();
        state=Json.of("id",session,"current_asset_id",current,"row_version",9,"revision",6,"document",DocumentCommandService.documentOf(Json.of("id",current,"width",400,"height",300)));
        when(db.one(contains("SELECT * FROM aip_agent_turn"),anyMap())).thenReturn(Json.of("id",turn,"session_id",session,"status","executing","base_revision",2,"mask_asset_id",mask,"mask_width",400,"mask_height",300));
        when(db.query(contains("SELECT * FROM aip_plan_step"),anyMap())).thenReturn(List.of(Json.of("step_id","first","status","succeeded"),Json.of("step_id","second","status","pending","tool","adjust_image","params",Json.of("brightness",0.1),"attempt",0)));
        when(db.owned("aip_session",session,11,true)).thenReturn(state);when(sessions.lock(eq(11L),eq(session),anyLong())).thenReturn(state);when(sessions.selection(anyLong(),anyString())).thenReturn(null);
        when(assets.record(eq(11L),anyString())).thenAnswer(call->Json.of("id",call.getArgument(1),"width",400,"height",300,"format","png","sha256","hash"));
        when(db.owned(eq("aip_run"),anyString(),eq(11L),eq(false))).thenAnswer(call->Json.of("id",call.getArgument(1),"user_id",11,"status","queued","event_seq",1,"params",Map.of(),"result",Map.of()));
    }
    @Test void secondLayerOperationKeepsPinnedMaskAcrossOrdinaryRevisionChanges() {
        runs.continuePlan(turn,11);var inserted=ArgumentCaptor.forClass(Map.class);verify(db).execute(startsWith("INSERT INTO aip_run("),inserted.capture());var p=inserted.getValue();
        assertEquals(mask,Json.map(p.get("params")).get("mask_asset_id"));assertEquals(mask,Json.map(p.get("snapshot")).get("selection_asset_id"));assertEquals(6,Json.number(p.get("revision")));assertEquals(9,Json.number(p.get("version")));
        assertTrue(Json.list(Json.map(p.get("snapshot")).get("assets")).stream().anyMatch(ref->mask.equals(Json.map(ref).get("id"))));verify(db,never()).execute(contains("error_code='STALE_SELECTION'"),anyMap());
    }
    @Test void cropChangingCanvasRejectsTheOldPinnedMaskBeforeSubmittingAnotherTool() {
        Json.map(state.get("document")).put("width",399);runs.continuePlan(turn,11);verify(db).execute(contains("error_code='STALE_SELECTION'"),anyMap());verify(db,never()).execute(startsWith("INSERT INTO aip_run("),anyMap());
    }
    @Test void planRetryCannotBypassRevokedGenerationPermission() throws Exception {
        var runner=mock(RunService.class);var commands=mock(CommandIdempotency.class);when(commands.execute(anyLong(),anyString(),anyMap(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(3)).get());
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());var service=new PlanService(db,runner,mock(SessionService.class),new ToolRegistry(new AiPictureProperties()),mock(com.ruoyi.aipicture.worker.AiPictureWorkerClient.class),new TransactionTemplate(manager),commands);
        when(db.one(contains(" AND session_id="),anyMap())).thenReturn(Json.of("id",turn,"status","failed","steps",List.of(Json.of("tool","generate_image"))));
        var user=new LoginUser();user.setUserId(11L);user.setPermissions(Set.of("aipicture:plan:retry"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        try {var error=assertThrows(AiPictureException.class,()->service.action(11,session,turn,"retry",Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey",Json.id(),"expectedVersion",9)));assertEquals("TOOL_PERMISSION_DENIED",error.errorCode());verifyNoInteractions(runner);}
        finally {SecurityContextHolder.clearContext();}
    }
    @Test void planBatchResumeOnlyRetriesExistingFailedItemsAndKeepsSuccessfulItems() {
        String batch=Json.id(),failed=Json.id(),success=Json.id();var business=mock(BatchExportEvaluationService.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(business);runs.batchProvider(provider);
        when(db.query(contains("tool='batch_process'"),anyMap())).thenReturn(List.of(Json.of("step_id","batch-step","run_id",batch)));when(db.owned("aip_run",batch,11,true)).thenReturn(Json.of("id",batch,"tool","batch_process"));when(db.query(contains("SELECT id,status FROM aip_batch_item"),anyMap())).thenReturn(List.of(Json.of("id",success,"status","succeeded"),Json.of("id",failed,"status","failed")));
        runs.resumePlanBatches(turn,11,Json.of("idempotencyKey",Json.id()),state);verify(business).retryItem(eq(11L),eq(batch),eq(failed),anyMap());verify(business,never()).retryItem(eq(11L),eq(batch),eq(success),anyMap());verify(db).execute(contains("SET status='running',attempt=attempt+1"),anyMap());verify(db,never()).execute(startsWith("INSERT INTO aip_batch("),anyMap());
    }
    @Test void planBatchResumeRejectsUnknownPaidItemsBeforeAnyRetry() {
        String batch=Json.id();var business=mock(BatchExportEvaluationService.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(business);runs.batchProvider(provider);
        when(db.query(contains("tool='batch_process'"),anyMap())).thenReturn(List.of(Json.of("step_id","batch-step","run_id",batch)));when(db.owned("aip_run",batch,11,true)).thenReturn(Json.of("id",batch,"tool","batch_process"));when(db.query(contains("SELECT id,status FROM aip_batch_item"),anyMap())).thenReturn(List.of(Json.of("id",Json.id(),"status","external_result_unknown"),Json.of("id",Json.id(),"status","failed")));
        var error=assertThrows(AiPictureException.class,()->runs.resumePlanBatches(turn,11,Json.of("idempotencyKey",Json.id()),state));assertEquals("EXTERNAL_RESULT_UNKNOWN",error.errorCode());verifyNoInteractions(business);verify(db,never()).execute(anyString(),anyMap());
    }
}
