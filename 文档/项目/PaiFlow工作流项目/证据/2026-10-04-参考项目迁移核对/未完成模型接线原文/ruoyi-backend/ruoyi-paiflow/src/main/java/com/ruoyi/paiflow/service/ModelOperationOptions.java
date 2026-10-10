package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原BaseLLMNode的有效参数语义；供应商选择/调用批准由目录与逐次策略另行核对。 */
public final class ModelOperationOptions {
    private ModelOperationOptions() {}
    private static final Set<String> EXTRA=Set.of("temperature","top_p","max_tokens","presence_penalty","frequency_penalty","seed","stop","n","response_format","stream_options","logprobs","top_logprobs","user","tools","tool_choice","functions","function_call","parallel_tool_calls","max_completion_tokens","reasoning_effort","store","metadata","audio","modalities","prediction","service_tier");
    public static JSONObject fromNode(JSONObject p){
        var effective=ModelNodeOptions.effective(p);
        var result=JSONObject.of("temperature",effective.get("temperature"),"maxTokens",effective.get("maxTokens"),"topK",effective.get("topK"),"patchId",effective.get("patch_id"),"respFormat",effective.get("respFormat"),"searchDisable",effective.get("searchDisable"),"extraParams",effective.get("extraParams"));
        ModelOperationJson.canonical(result,ModelOperationJson.REQUEST_BYTES);return ModelOperationJson.copy(result);
    }
    public static String mode(JSONObject node){
        String kind=node.getString("id").split("::",2)[0];var p=node.getJSONObject("data").getJSONObject("nodeParam");
        if(!WorkflowModelCompiler.requiresModel(node))throw error("节点实际分支不使用模型",403);
        if("extractor-parameter".equals(kind) && !ModelNodeOptions.integer(p.getOrDefault("reasonMode",0),"reasonMode").equals(java.math.BigInteger.ONE)
            || "decision-making".equals(kind) && !ModelNodeOptions.integer(p.getOrDefault("reasonMode",0),"reasonMode").equals(java.math.BigInteger.ONE) && ModelNodeOptions.bool(p.getOrDefault("useFunctionCall",true),"useFunctionCall"))return "function_call";
        return "chat";
    }
    public static JSONObject fromNode(JSONObject node,boolean compiled){
        var p=node.getJSONObject("data").getJSONObject("nodeParam");var result=fromNode(p);
        if("function_call".equals(mode(node)))result.putAll(JSONObject.of("questionType",p.getOrDefault("question_type","not_knowledge"),"functionChoice",node.getString("id").startsWith("extractor-parameter::")?"extractor_parameter":""));
        return result;
    }
    public static JSONObject fromFixedNode(JSONObject node){return fromNode(node,true);}
    public static void validateRequest(JSONObject parameters,JSONObject node){
        String mode=mode(node);RunJsonBoundary.fields(parameters,Set.of("resourceId","resourceVersion","provider","modelName","mode","messages","options"),"function_call".equals(mode)?Set.of("timeoutSeconds","functions"):Set.of("timeoutSeconds"));
        if(!Set.of("openai","xinghuo").contains(parameters.get("provider")) || !mode.equals(parameters.get("mode")) || "function_call".equals(mode) && !"xinghuo".equals(parameters.get("provider")))throw error("模型请求与原实际分支或SDK不匹配",403);
        if(!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty())throw error("模型messages不能为空",400);
        for(Object raw:messages){if(!(raw instanceof Map<?,?> message) || !Set.of("system","user","assistant").contains(message.get("role")) || !(message.get("content") instanceof String) || !(message.keySet().equals(Set.of("role","content")) || message.keySet().equals(Set.of("role","content","content_type")) && "text".equals(message.get("content_type"))))throw error("模型消息须为已绑定原文本消息",403);}
        if(!(parameters.get("options") instanceof JSONObject options))throw error("模型options必须为对象",400);
        JSONObject expected=fromFixedNode(node);RunJsonBoundary.fields(options,expected.keySet(),Set.of());
        if(!ModelOperationJson.canonical(options,ModelOperationJson.REQUEST_BYTES).equals(ModelOperationJson.canonical(expected,ModelOperationJson.REQUEST_BYTES)))throw error("模型选项与固定节点不一致",409);
        JSONObject extra=options.getJSONObject("extraParams");if(extra.keySet().stream().anyMatch(k->!EXTRA.contains(k)))throw error("模型额外参数不在受控SDK范围",403);
        if(extra.containsKey("n") && ModelOperationJson.integer(extra.get("n"),1,1,"n")!=1)throw error("模型必须返回单choice",400);
        if(parameters.containsKey("timeoutSeconds")){Object raw=parameters.get("timeoutSeconds");if(!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<=0 || n.doubleValue()>600)throw error("模型timeoutSeconds无效",400);}
    }
}
