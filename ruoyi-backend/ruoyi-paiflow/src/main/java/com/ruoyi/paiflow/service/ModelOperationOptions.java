package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原BaseLLMNode的有效参数语义；供应商选择/调用批准由目录与逐次策略另行核对。 */
public final class ModelOperationOptions {
    private ModelOperationOptions() {}
    private static final Set<String> EXTRA=Set.of("temperature","top_p","max_tokens","presence_penalty","frequency_penalty","seed","stop","n","response_format","stream_options","logprobs","top_logprobs","user","tools","tool_choice","functions","function_call","parallel_tool_calls","max_completion_tokens","reasoning_effort","store","metadata","audio","modalities","prediction","service_tier");
    public static JSONObject fromNode(JSONObject p){
        var effective=ModelNodeOptions.effective(p);if(effective.getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"))throw error("Python V2会话历史资源尚未接通",503);
        var result=JSONObject.of("temperature",effective.get("temperature"),"maxTokens",effective.get("maxTokens"),"topK",effective.get("topK"),"patchId",effective.get("patch_id"),"respFormat",effective.get("respFormat"),"searchDisable",effective.get("searchDisable"),"extraParams",effective.get("extraParams"));
        ModelOperationJson.canonical(result,ModelOperationJson.REQUEST_BYTES);return ModelOperationJson.copy(result);
    }
    public static JSONObject forNode(JSONObject node){var p=node.getJSONObject("data").getJSONObject("nodeParam");if(PythonModelHistorySemantics.legacy(node))PythonModelHistorySemantics.requireLegacy(node);var result=fromNode(p);if("function_call".equals(ModelNodeSemantics.mode(node))){result.put("extraParams",new JSONObject());result.put("searchDisable",true);result.put("questionType",p.getOrDefault("question_type","not_knowledge"));result.put("functionChoice","decision-making".equals(ModelNodeSemantics.type(node))?"":"extractor_parameter");}return result;}
    public static JSONObject forNode(JSONObject node,String engine){
        if("python".equals(engine))return forNode(node);
        if(!"java".equals(engine) || !"spark-llm".equals(ModelNodeSemantics.type(node)))throw error("模型引擎与固定节点不一致",503);
        Integer history=JavaModelSemantics.historyLimit(node);
        if(history==null)throw error("Java模型启用历史时轮数不能为null",400);
        var data=node.getJSONObject("data");var params=data.getJSONObject("nodeParam");
        if(Boolean.TRUE.equals(params.get("multiMode")))throw error("Java多模态中心来源尚未接通",503);
        if(data.get("inputs") instanceof com.alibaba.fastjson2.JSONArray inputs)for(Object raw:inputs)if(raw instanceof JSONObject input&&"SYSTEM_IMAGE".equals(input.get("name")))throw error("Java多模态中心来源尚未接通",503);
        var options=JavaModelSemantics.options(node);
        for(Object value:options.values())if(value instanceof Number n && !Double.isFinite(n.doubleValue()))throw error("Java模型选项必须是有限数值",400);
        // 先检查原数值，避免JSON把非有限值写成null后误取得批准。
        ModelOperationJson.canonical(options,ModelOperationJson.REQUEST_BYTES);return ModelOperationJson.copy(options);
    }
    public static void validateRequest(JSONObject parameters,JSONObject node,String engine){
        if("python".equals(engine)){validateRequest(parameters,node);return;}
        var expected=forNode(node,engine);
        RunJsonBoundary.fields(parameters,Set.of("resourceId","resourceVersion","provider","modelName","mode","messages","options"),Set.of());
        if(!"openai".equals(parameters.get("provider")) || !"chat".equals(parameters.get("mode")))throw error("原Java模型调用协议不一致",503);
        if(!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty())throw error("Java模型messages不能为空",400);
        for(Object raw:messages)if(!(raw instanceof Map<?,?> message) || !Set.of("system","user","assistant").contains(message.get("role")) || !(message.get("content") instanceof String) || !message.keySet().equals(Set.of("role","content")))throw error("Java模型消息必须为原文本角色消息",503);
        if(!(parameters.get("options") instanceof JSONObject options))throw error("Java模型options必须为对象",400);
        RunJsonBoundary.fields(options,expected.keySet(),Set.of());
        if(!ModelOperationJson.canonical(options,ModelOperationJson.REQUEST_BYTES).equals(ModelOperationJson.canonical(expected,ModelOperationJson.REQUEST_BYTES)))throw error("Java模型选项与原固定节点不一致",409);
    }
    public static void validateRequest(JSONObject parameters,JSONObject node){
        String mode=ModelNodeSemantics.mode(node);boolean fc="function_call".equals(mode),qa="question-answer".equals(ModelNodeSemantics.type(node));var required=new HashSet<>(Set.of("resourceId","resourceVersion","provider","modelName","mode","messages","options"));if(fc)required.add("functions");if(qa)required.add("checkpointId");
        RunJsonBoundary.fields(parameters,required,fc?Set.of():Set.of("timeoutSeconds"));
        if(!("openai".equals(parameters.get("provider")) || "xinghuo".equals(parameters.get("provider"))) || !mode.equals(parameters.get("mode")) || fc && !"xinghuo".equals(parameters.get("provider")))throw error("调用模式与固定原节点不一致",503);
        if(!(parameters.get("messages") instanceof List<?> messages) || messages.isEmpty())throw error("模型messages不能为空",400);
        for(Object raw:messages){if(!(raw instanceof Map<?,?> message) || !Set.of("system","user").contains(message.get("role")) || !(message.get("content") instanceof String) || !message.keySet().equals(Set.of("role","content")))throw error("历史/工具调用/多模态消息来源尚未接通",503);}
        if(!(parameters.get("options") instanceof JSONObject options))throw error("模型options必须为对象",400);
        var optionKeys=new HashSet<>(Set.of("temperature","maxTokens","topK","patchId","respFormat","searchDisable","extraParams"));if(fc){optionKeys.add("questionType");optionKeys.add("functionChoice");}RunJsonBoundary.fields(options,optionKeys,Set.of());
        JSONObject expected=forNode(node);
        if(!ModelOperationJson.canonical(options,ModelOperationJson.REQUEST_BYTES).equals(ModelOperationJson.canonical(expected,ModelOperationJson.REQUEST_BYTES)))throw error("模型选项与固定节点不一致",409);
        JSONObject extra=options.getJSONObject("extraParams");if(extra.keySet().stream().anyMatch(k->!EXTRA.contains(k)))throw error("模型额外参数不在受控SDK范围",403);
        if(extra.containsKey("n") && ModelOperationJson.integer(extra.get("n"),1,1,"n")!=1)throw error("模型必须返回单choice",400);
        if(fc && !ModelOperationJson.canonicalFunctions(ModelMessageSources.functions(node),ModelOperationJson.REQUEST_BYTES).equals(ModelOperationJson.canonicalFunctions(parameters.get("functions"),ModelOperationJson.REQUEST_BYTES)))throw error("函数声明与固定原节点不一致",403);
        if(qa)ModelOperationJson.text(parameters.get("checkpointId"),128,"checkpointId");
        if(parameters.containsKey("timeoutSeconds")){Object raw=parameters.get("timeoutSeconds");if(!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<=0 || n.doubleValue()>600)throw error("模型timeoutSeconds无效",400);}
    }
}
