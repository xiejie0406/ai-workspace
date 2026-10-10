package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原BaseLLMNode的有效参数语义；供应商选择/调用批准由目录与逐次策略另行核对。 */
public final class ModelOperationOptions {
    private ModelOperationOptions() {}
    private static final Set<String> EXTRA=Set.of("temperature","top_p","max_tokens","presence_penalty","frequency_penalty","seed","stop","n","response_format","stream_options","logprobs","top_logprobs","user","tools","tool_choice","functions","function_call","parallel_tool_calls","max_completion_tokens","reasoning_effort","store","metadata","audio","modalities","prediction","service_tier");
    public static JSONObject fromNode(JSONObject p){
        var effective=ModelNodeOptions.effective(p);if(effective.getBooleanValue("enableChatHistory") || effective.getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"))throw error("模型历史资源尚未接通",503);
        var result=JSONObject.of("temperature",effective.get("temperature"),"maxTokens",effective.get("maxTokens"),"topK",effective.get("topK"),"patchId",effective.get("patch_id"),"respFormat",effective.get("respFormat"),"searchDisable",effective.get("searchDisable"),"extraParams",effective.get("extraParams"));
        ModelOperationJson.canonical(result,ModelOperationJson.REQUEST_BYTES);return ModelOperationJson.copy(result);
    }
    public static void validateRequest(JSONObject parameters,JSONObject node){
        RunJsonBoundary.fields(parameters,Set.of("resourceId","resourceVersion","provider","modelName","mode","messages","options"),Set.of("timeoutSeconds"));
        if(!"openai".equals(parameters.get("provider")) || !"chat".equals(parameters.get("mode")))throw error("当前模型运行profile只接OpenAI chat",503);
        if(!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty())throw error("模型messages不能为空",400);
        for(Object raw:messages){if(!(raw instanceof Map<?,?> message) || !Set.of("system","user").contains(message.get("role")) || !(message.get("content") instanceof String) || !message.keySet().equals(Set.of("role","content")))throw error("历史/工具调用/多模态消息来源尚未接通",503);}
        if(!(parameters.get("options") instanceof JSONObject options))throw error("模型options必须为对象",400);
        RunJsonBoundary.fields(options,Set.of("temperature","maxTokens","topK","patchId","respFormat","searchDisable","extraParams"),Set.of());
        JSONObject expected=fromNode(node.getJSONObject("data").getJSONObject("nodeParam"));
        if(!ModelOperationJson.canonical(options,ModelOperationJson.REQUEST_BYTES).equals(ModelOperationJson.canonical(expected,ModelOperationJson.REQUEST_BYTES)))throw error("模型选项与固定节点不一致",409);
        JSONObject extra=options.getJSONObject("extraParams");if(extra.keySet().stream().anyMatch(k->!EXTRA.contains(k)))throw error("模型额外参数不在受控SDK范围",403);
        if(extra.containsKey("n") && ModelOperationJson.integer(extra.get("n"),1,1,"n")!=1)throw error("模型必须返回单choice",400);
        if(parameters.containsKey("timeoutSeconds")){Object raw=parameters.get("timeoutSeconds");if(!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<=0 || n.doubleValue()>600)throw error("模型timeoutSeconds无效",400);}
    }
}
