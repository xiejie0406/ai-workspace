package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.domain.CheckpointRecord;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.CheckpointMapper;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import static org.mockito.Mockito.*;

/** 实际中心编译类的纯内存只读审查；不调用Controller、不连接数据库。 */
public final class CheckpointShapeProbe {
    public static void main(String[] args) throws Exception {
        JSONObject params = JSONObject.of("answerType", "option", "optionAnswer", JSON.parseArray("[{\"name\":\"A\",\"content_type\":\"text\"}]"));
        JSONObject question = JSONObject.of("type", "option", "content", "fixture", "option",
                "[{\"id\":\"A\",\"text\":\"fixture\",\"content_type\":\"text\"}]");
        var questionMethod = CheckpointService.class.getDeclaredMethod("question", JSONObject.class, JSONObject.class);
        questionMethod.setAccessible(true);
        boolean accepted;
        try { questionMethod.invoke(null, question, params); accepted = true; }
        catch (InvocationTargetException error) { accepted = false; System.out.println("shapeRejection=" + error.getCause().getClass().getSimpleName()); }
        System.out.println("stringEncodedOptionAccepted=" + accepted + ",storedOptionType=" + question.get("option").getClass().getSimpleName());

        CheckpointMapper mapper = mock(CheckpointMapper.class);
        CheckpointRecord foreign = new CheckpointRecord();foreign.setRunId("run-a");foreign.setLeaseId("lease-a");foreign.setAttempt(1);
        when(mapper.find("checkpoint-a")).thenReturn(foreign);when(mapper.lock("checkpoint-a")).thenReturn(foreign);
        CheckpointService service = new CheckpointService(mapper, null, null, null, null, null, Clock.systemUTC());
        RunRecord run = new RunRecord();run.setId("run-b");run.setLeaseId("lease-b");run.setAttempt(1);
        var scopeMethod = CheckpointService.class.getDeclaredMethod("scoped", RunRecord.class, String.class);scopeMethod.setAccessible(true);
        try { scopeMethod.invoke(service, run, "checkpoint-a"); throw new AssertionError("必须拒绝跨运行"); }
        catch (InvocationTargetException error) { System.out.println("scopeRejection=" + error.getCause().getClass().getSimpleName()); }
        long locks = mockingDetails(mapper).getInvocations().stream().filter(invocation -> invocation.getMethod().getName().equals("lock")).count();
        System.out.println("foreignCheckpointLockAttempts=" + locks);
    }
}
