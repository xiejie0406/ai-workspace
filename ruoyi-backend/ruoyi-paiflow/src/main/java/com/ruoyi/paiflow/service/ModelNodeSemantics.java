package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;

/** 原四类模型节点的模式与有效参数；只处理固定节点，不签发权限。 */
public final class ModelNodeSemantics {
    private ModelNodeSemantics() {}
    public static String type(JSONObject node){return node.getString("id").split("::",2)[0];}
    static String providerUid(JSONObject node){var p=node.getJSONObject("data").getJSONObject("nodeParam");Object value=p.getOrDefault("uid","");if(!(value instanceof String uid))throw error("原模型uid必须为字符串",422);return uid;}
    public static boolean isModelNode(JSONObject node){String t=type(node);if(Set.of("spark-llm","extractor-parameter","decision-making").contains(t))return true;
        var d=node.getJSONObject("data");var p=d==null?null:d.getJSONObject("nodeParam");var direct=p==null?null:p.getJSONObject("directAnswer");return "question-answer".equals(t) && p!=null && "direct".equals(p.getString("answerType")) && direct!=null && ModelNodeOptions.bool(direct.get("handleResponse"),"directAnswer.handleResponse");}
    public static String mode(JSONObject node){var p=node.getJSONObject("data").getJSONObject("nodeParam");String t=type(node);
        if("extractor-parameter".equals(t))return ModelNodeOptions.integer(p.getOrDefault("reasonMode",0),"reasonMode").intValueExact()==1?"chat":"function_call";
        if("decision-making".equals(t))return ModelNodeOptions.integer(p.getOrDefault("reasonMode",0),"reasonMode").intValueExact()==1 || !ModelNodeOptions.bool(p.getOrDefault("useFunctionCall",true),"useFunctionCall")?"chat":"function_call";
        if("spark-llm".equals(t) || isModelNode(node))return "chat";throw error("节点没有正式模型调用模式",503);}
    private static String string(JSONObject p,String key,String fallback){Object v=p.getOrDefault(key,fallback);if(!(v instanceof String s))throw error(key+"必须为字符串",422);return s;}
    public static void normalize(JSONObject node){var p=node.getJSONObject("data").getJSONObject("nodeParam");String t=type(node);
        if("extractor-parameter".equals(t) || "decision-making".equals(t)){p.put("reasonMode",ModelNodeOptions.integer(p.getOrDefault("reasonMode",0),"reasonMode").intValueExact());p.put("question_type",string(p,"question_type","not_knowledge"));}
        if("extractor-parameter".equals(t)){p.put("instruction",string(p,"instruction",""));if(p.containsKey("fc_schema_params") && !(p.get("fc_schema_params") instanceof JSONObject))throw error("fc_schema_params必须为固定对象",422);}
        if("decision-making".equals(t)){p.put("useFunctionCall",ModelNodeOptions.bool(p.getOrDefault("useFunctionCall",true),"useFunctionCall"));p.put("promptPrefix",string(p,"promptPrefix",""));
            if(!(p.get("intentChains") instanceof JSONArray chains) || chains.isEmpty())throw error("决策必须配置原意图分支",422);for(Object raw:chains){if(!(raw instanceof JSONObject chain) || !string(chain,"id","").matches("intent-one-of::[0-9a-zA-Z-]+.*") || string(chain,"name","").isEmpty() || string(chain,"description","").isEmpty())throw error("决策原意图分支无效",422);int kind=ModelNodeOptions.integer(chain.get("intentType"),"intentType").intValueExact();if(kind<1 || kind>2)throw error("intentType须为1或2",422);chain.put("intentType",kind);}}
        if("question-answer".equals(t)){var original=copy(p);var direct=original.getJSONObject("directAnswer");if(direct==null)throw error("动态问答缺少directAnswer",422);boolean active=ModelNodeOptions.bool(direct.get("handleResponse"),"handleResponse");
            original.remove("modelResourceId");original.put("directAnswer",JSONObject.of("handleResponse",false,"maxRetryCounts",direct.get("maxRetryCounts")));p.putAll(StaticQuestionAnswerOptions.effective(original));p.put("directAnswer",JSONObject.of("handleResponse",active,"maxRetryCounts",p.getJSONObject("directAnswer").get("maxRetryCounts")));}
        if("function_call".equals(mode(node)) && !"xinghuo".equals(p.getString("source")))throw error("原FunctionCall仅支持Spark，不转换供应商别名",503);
    }
}
