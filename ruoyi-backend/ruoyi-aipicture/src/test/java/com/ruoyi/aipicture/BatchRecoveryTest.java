package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.service.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;

class BatchRecoveryTest {
    AiPictureRepository db;RunService runs;BatchExportEvaluationService business;ToolRegistry registry;TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {db=mock(AiPictureRepository.class);when(db.one(anyString(),anyMap())).thenReturn(null);registry=new ToolRegistry(new AiPictureProperties());var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());tx=new TransactionTemplate(manager);runs=new RunService(db,mock(SessionService.class),mock(AssetService.class),registry,mock(AiPictureStorage.class),new AiPictureProperties(),tx);business=new BatchExportEvaluationService(db,runs,mock(AssetService.class),registry,mock(AiPictureStorage.class),tx,new CommandIdempotency(db,tx));}
    Map<String,Object> metadata() {return Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey",Json.id());}
    @Test void twentyOneInputAndNonWhitelistedOperationAreRejectedBeforeWrite() {
        var p=metadata();p.put("asset_ids",java.util.stream.IntStream.range(0,21).mapToObj(i->Json.id()).toList());p.put("operations",List.of(Json.of("tool","remove_background","params",Map.of())));assertThrows(AiPictureException.class,()->business.createBatch(5,p));
        p.put("asset_ids",List.of(Json.id()));p.put("operations",List.of(Json.of("tool","split_layers","params",Map.of())));assertThrows(AiPictureException.class,()->business.createBatch(5,p));verify(db,never()).execute(anyString(),anyMap());
    }
    @Test void successfulItemCannotBeRetried() {
        String batch=Json.id(),item=Json.id();when(db.owned("aip_batch",batch,5,true)).thenReturn(Json.of("id",batch));when(db.one(contains("SELECT * FROM aip_batch_item"),anyMap())).thenReturn(Json.of("status","succeeded"));assertThrows(AiPictureException.class,()->business.retryItem(5,batch,item,metadata()));verify(db,never()).execute(startsWith("INSERT INTO aip_run"),anyMap());
    }
    @Test void uncertainPaidOutcomeCannotCreateAnotherBatchCharge() {
        String batch=Json.id(),item=Json.id();when(db.owned("aip_batch",batch,5,true)).thenReturn(Json.of("id",batch));when(db.one(contains("SELECT * FROM aip_batch_item"),anyMap())).thenReturn(Json.of("status","external_result_unknown"));var error=assertThrows(AiPictureException.class,()->business.retryItem(5,batch,item,metadata()));assertEquals("BATCH_ITEM_STATE",error.errorCode());verify(db,never()).execute(startsWith("INSERT INTO aip_run"),anyMap());
    }
    @Test void samePublicRetryKeyReturnsPriorOutcomeEvenIfItemHasFailedAgain() {
        String batch=Json.id(),item=Json.id();var request=metadata();var prior=Json.of("id",batch,"items",List.of(Json.of("id",item,"status","queued")));when(db.one(contains("SELECT * FROM aip_command"),anyMap())).thenReturn(Json.of("request_hash",RunService.hash(request),"result",prior));
        assertEquals(prior,business.retryItem(5,batch,item,request));verify(db,never()).owned(eq("aip_batch"),anyString(),anyLong(),anyBoolean());verify(db,never()).execute(anyString(),anyMap());
    }
    @Test void partialSuccessDoesNotReportAllSuccessfulAndKeepsSuccessfulOutputIds() {
        String batch=Json.id(),output=Json.id();when(db.query(contains("SELECT id,status,output_ids"),anyMap())).thenReturn(List.of(Json.of("id",Json.id(),"status","succeeded","output_ids",List.of(output)),Json.of("id",Json.id(),"status","failed","output_ids",List.of())));when(db.owned("aip_run",batch,5,false)).thenReturn(Json.of("id",batch,"user_id",5,"result",Map.of(),"event_seq",1,"params",Map.of()));
        when(db.owned("aip_run",batch,5,true)).thenReturn(Json.of("id",batch,"user_id",5));
        runs.refreshBatch(batch,5);var params=ArgumentCaptor.forClass(Map.class);verify(db).execute(startsWith("UPDATE aip_run SET status="),params.capture());assertEquals("partial_success",params.getValue().get("status"));assertEquals(100,((Number)params.getValue().get("progress")).intValue());assertTrue(Json.string(params.getValue().get("result")).contains(output));
    }
    @Test void expiredPaidEditEntersUnknownResultInsteadOfRequeue() {assertRecovery("replace_background","external_result_unknown",1);}
    @Test void expiredDeterministicEditCanBeRequeuedWithinAttemptBudget() {assertRecovery("adjust_image","queued",1);}
    void assertRecovery(String tool,String expected,int attempt) {
        String id=Json.id();var row=Json.of("id",id,"user_id",5,"tool",tool,"attempt",attempt,"params",Map.of(),"result",Map.of(),"event_seq",1);when(db.query(contains("WHERE status='running' AND lease_expires_at"),anyMap())).thenReturn(List.of(row));when(db.owned("aip_run",id,5,false)).thenReturn(row);when(db.one(contains("SELECT r.* FROM aip_dispatch"),anyMap())).thenReturn(null);runs.claim("worker-test");
        var p=ArgumentCaptor.forClass(Map.class);verify(db).execute(startsWith("UPDATE aip_run SET status=:status,error_code='LEASE_EXPIRED'"),p.capture());assertEquals(expected,p.getValue().get("status"));
    }
}
