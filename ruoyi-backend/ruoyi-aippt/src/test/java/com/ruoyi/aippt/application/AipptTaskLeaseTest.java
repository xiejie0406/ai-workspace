package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.persistence.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AipptTaskLeaseTest  {
    private final AipptRepository db=mock(AipptRepository.class);
    private final AipptService service=mock(AipptService.class);
    private JSONObject task,request;
    private TaskLeaseService leases;
    private final AipptProperties props=new AipptProperties();
    @BeforeEach void setup()  {
        TransactionTemplate transactions=mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation->((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        leases=new TaskLeaseService(db,mock(AipptMapper.class),transactions,service,props);
        task=JSONObject.of("id","task","project_id","project","owner_user_id",1,"type","generate_slide","status","running","revision",1,"attempt",1,"lease_token","token","fencing_token",2,"lease_until",Instant.now().plusSeconds(60).toString(),"deadline",Instant.now().plusSeconds(300).toString(),"cancel_requested",true);
        request=JSONObject.of("attempt",1,"leaseToken","token","fencingToken",2,"output",JSONObject.of("slide",new JSONObject()),"assets",new JSONArray());
        when(db.get("task","task")).thenAnswer(i->task);
        when(db.lock("task","task")).thenAnswer(i->task);
        when(service.workerProject("project")).thenReturn(JSONObject.of("id","project","owner_user_id",1));
    }
    @Test void softCancelledInFlightLeaseCanCommitExactlyOnce()  {
        assertEquals("succeeded",leases.complete("task",request).getString("status"));
        assertEquals("succeeded",leases.complete("task",request).getString("status"));
        verify(service,times(1)).completeOutput(any(),any(),any());
        verify(service,times(1)).audit(any(),eq("task_completed"),any());
    }
    @Test void leaseRenewedAfterExpiredScanIsNotRequeuedOrFenced()  {
        props.getWorker().setEnabled(true);
        JSONObject stale=com.ruoyi.aippt.domain.AipptRules.copy(task);
        stale.put("lease_until",Instant.now().minusSeconds(2).toString());
        when(db.expired(anyString())).thenReturn(java.util.List.of(stale));
        when(db.get("project","project")).thenReturn(JSONObject.of("id","project","owner_user_id",1));
        leases.recoverExpired();
        assertEquals("running",task.getString("status"));
        assertEquals(2,task.getLongValue("fencing_token"));
        verify(db,never()).save(eq("task"),any(),anyLong());
    }
    @Test void executionAuditPreservesActualUsageAndRejectsCredentials()  {
        JSONObject summary=TaskLeaseService.executionSummary(JSONObject.of("model","fixture-model","elapsed_ms",12,"input_tokens",8,"usage_status","partial","usage_missing_reason","Provider未返回完整用量"));
        assertEquals(8,summary.getIntValue("input_tokens"));
        assertFalse(summary.containsKey("output_tokens"));
        assertThrows(AipptException.class,()->TaskLeaseService.executionSummary(JSONObject.of("api_key","secret")));
        assertThrows(AipptException.class,()->TaskLeaseService.executionSummary(JSONObject.of("input_tokens",1.5)));
        assertThrows(AipptException.class,()->TaskLeaseService.executionSummary(JSONObject.of("usage_status","invented")));
    }
    @Test void queuedCancelledAndExpiredLeaseCannotWriteOutput()  {
        task.put("status","cancelled");
        assertThrows(AipptException.class,()->leases.complete("task",request));
        task.put("status","running");
        task.put("deadline",Instant.now().minusSeconds(1).toString());
        assertThrows(AipptException.class,()->leases.complete("task",request));
        verify(service,never()).completeOutput(any(),any(),any());
    }
    @Test void retryableFailureKeepsThisAttemptsActualExecutionAudit()  {
        task.put("cancel_requested",false);
        request.put("message","算法失败");
        request.put("retryable",true);
        request.put("execution",JSONObject.of("model_calls",1,"usage_status","unavailable","usage_missing_reason","provider_usage_omitted"));
        assertEquals("queued",leases.fail("task",request).getString("status"));
        assertEquals(1,task.getJSONObject("execution").getIntValue("model_calls"));
        verify(service,times(1)).audit(any(),eq("task_attempt_failed"),any());
    }
    @Test void deletedProjectAndChangedFenceCannotWriteOutput()  {
        request.put("fencingToken",1);
        assertThrows(AipptException.class,()->leases.complete("task",request));
        request.put("fencingToken",2);
        when(service.workerProject("project")).thenThrow(new AipptException(409,"PROJECT_DELETED","已删除"));
        assertThrows(AipptException.class,()->leases.complete("task",request));
        verify(service,never()).completeOutput(any(),any(),any());
    }
}
