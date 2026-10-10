package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 固定节点到中心请求边界，与原SDK捕获比较；不授予调用或历史许可。 */
class JavaModelOperationOptionsTest {
    private static JSONArray golden() throws Exception {
        try(var in=JavaModelOperationOptionsTest.class.getResourceAsStream("/fixtures/java-model-wire/original-sdk.json")) {
            assertNotNull(in);return JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static JSONObject node(int variant) {
        var p=JSONObject.of("template","问题","systemTemplate","系统","maxTokens",variant==0?null:variant==1?12:32);
        if(variant==1)p.put("extraParams",JSONObject.of("temperature","0","topP","0","presencePenalty","0","frequencyPenalty","0","n","1"));
        if(variant==2)p.put("extraParams",JSONObject.of("temperature","bad","max_tokens",99));
        if(variant==3)p.put("extraParams",JSONObject.of("n","2"));
        return JSONObject.of("id","spark-llm::m","data",JSONObject.of("nodeParam",p,"inputs",new JSONArray(),"outputs",new JSONArray()));
    }
    @Test void originalFivePublicRequestsMatchFixedJavaOptionsIncludingNullAndTwoChoices() throws Exception {
        for(Object raw:golden()) {
            var sample=(JSONObject)raw;var n=node(sample.getIntValue("variant"));var params=sample.getJSONObject("actualRequest").getJSONObject("parameters");
            assertEquals(params.getJSONObject("options"),ModelOperationOptions.forNode(n,"java"));
            assertDoesNotThrow(()->ModelOperationOptions.validateRequest(params,n,"java"));
            assertThrows(ServiceException.class,()->ModelOperationOptions.validateRequest(params,n));
        }
    }
    @Test void privateProtocolSelectionAndHistoryRemainOutsideWorkerControl() throws Exception {
        var n=node(3);var params=golden().getJSONObject(3).getJSONObject("actualRequest").getJSONObject("parameters");
        params.put("engineProfile","java");assertThrows(ServiceException.class,()->ModelOperationOptions.validateRequest(params,n,"java"));params.remove("engineProfile");
        params.getJSONObject("options").put("n",1);assertEquals(409,assertThrows(ServiceException.class,()->ModelOperationOptions.validateRequest(params,n,"java")).getCode());
        var p=n.getJSONObject("data").getJSONObject("nodeParam");p.put("enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds",2));
        var effective=assertDoesNotThrow(()->ModelOperationOptions.forNode(n,"java"));assertEquals(8,effective.size());assertEquals(JavaModelSemantics.options(n),effective);assertEquals(2,JavaModelSemantics.historyLimit(n));
        // options解析不授予历史许可；缺真实轮次/伪历史在JavaModelHistoryOperationTest中走prepare/policy反例。
        p.put("enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds",null));
        assertEquals(400,assertThrows(ServiceException.class,()->ModelOperationOptions.forNode(n,"java")).getCode());
    }
    @Test void nonFiniteOriginalConversionsCannotBeCanonicalizedAsApprovedNull() {
        var n=node(0);n.getJSONObject("data").getJSONObject("nodeParam").put("extraParams",JSONObject.of("temperature","NaN"));
        assertTrue(Double.isNaN(JavaModelSemantics.options(n).getDoubleValue("temperature")));
        assertEquals(400,assertThrows(ServiceException.class,()->ModelOperationOptions.forNode(n,"java")).getCode());
    }
}
