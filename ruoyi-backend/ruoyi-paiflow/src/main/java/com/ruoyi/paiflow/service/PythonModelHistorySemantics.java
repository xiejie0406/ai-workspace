package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原Python旧节点历史与V2会话历史分别准入，不套用Java半轮规则。 */
final class PythonModelHistorySemantics {
    private PythonModelHistorySemantics() {}
    static boolean legacy(JSONObject node) {
        return readsLegacy(node) || appendsLegacy(node);
    }
    static boolean readsLegacy(JSONObject node) {
        return ModelNodeOptions.effective(node.getJSONObject("data").getJSONObject("nodeParam")).getBooleanValue("enableChatHistory");
    }
    /** 原完成模板对未转换的nodeParam用Python真值；非空"false"字符串仍追加。 */
    static boolean appendsLegacy(JSONObject node) {
        var p=node.getJSONObject("data").getJSONObject("nodeParam");
        // 先按原Pydantic允许值校验，不能凭真值放行任意容器或非法字符串。
        ModelNodeOptions.effective(p);
        Object raw=p.getOrDefault("enableChatHistory",false);
        if(raw instanceof Boolean value)return value;
        if(raw instanceof Number value)return new java.math.BigDecimal(value.toString()).signum()!=0;
        return raw instanceof String value && !value.isEmpty();
    }
    static boolean v2(JSONObject node) {
        return ModelNodeOptions.effective(node.getJSONObject("data").getJSONObject("nodeParam")).getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled");
    }
    static void requireLegacy(JSONObject node) {
        if(!"spark-llm".equals(ModelNodeSemantics.type(node)) || !legacy(node) || v2(node))
            throw error("Python该历史分支的中心权威来源尚未接通",503);
    }
}
