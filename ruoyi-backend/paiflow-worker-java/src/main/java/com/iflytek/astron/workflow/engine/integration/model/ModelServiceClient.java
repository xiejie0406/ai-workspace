package com.iflytek.astron.workflow.engine.integration.model;

import com.iflytek.astron.workflow.engine.integration.model.bo.LlmCallback;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmReqBo;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmResVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Model service client.
 * Calls console-hub's model API to execute LLM inference.
 * 
 * @author 二哥编程星球&Java进阶之路（沉默王二&一灰）
 * @version 1.0.0
 */
@Slf4j
@Service
public class ModelServiceClient {

    @Autowired
    private OpenAiStyleLlmIntegration llmIntergration;


    /**
     * Call LLM for chat completion.
     *      
     * @param req model ID from database
     */
    public LlmResVo chatCompletion(LlmReqBo req, LlmCallback callback) {
        return com.ruoyi.paiflow.worker.service.ExecutionScope.current().modelCompletion(req, callback);
    }
}
