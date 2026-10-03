package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.util.Set;

/** 顶层静态问答整图准入；检查点和运行真相仍由既有中心Service持有。 */
public final class StaticQuestionAnswerPolicy {
    private static final Set<String> ALLOWED=Set.of("node-start","node-end","if-else","text-joiner","message","question-answer");
    private StaticQuestionAnswerPolicy() {}
    public static PureIterationPolicy.Budget validate(JSONObject execution) {
        var metrics=PureIterationPolicy.validateTopLevel(execution,ALLOWED);boolean found=false;
        for(Object item:execution.getJSONArray("nodes")){JSONObject node=(JSONObject)item;if("question-answer".equals(NodeCatalog.typeOf(node.getString("id")))){
            found=true;JSONObject data=node.getJSONObject("data");if(!(data.get("nodeParam") instanceof JSONObject params))throw new ServiceException("静态问答参数必须为对象",422);StaticQuestionAnswerOptions.effective(params);
        }}
        if(!found)throw new ServiceException("静态问答图缺少原问答节点",400);return metrics;
    }
}
