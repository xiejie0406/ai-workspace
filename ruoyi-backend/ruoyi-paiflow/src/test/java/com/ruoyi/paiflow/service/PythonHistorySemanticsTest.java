package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原Pydantic读取、raw完成追加及格式化成功边界；不验证真实Provider或页面。 */
class PythonHistorySemanticsTest {
    JSONObject node(Object flag,int format,JSONArray fields){
        return JSONObject.of("id","spark-llm::m","data",JSONObject.of("nodeMeta",JSONObject.of("nodeType","spark-llm"),"inputs",new JSONArray(),"outputs",fields,"nodeParam",JSONObject.of("source","openai","domain","ordinary","enableChatHistory",flag,"enableChatHistoryV2",JSONObject.of("isEnabled",false,"rounds",1),"respFormat",format)));
    }
    JSONArray frames(String text,String reasoning){return JSONArray.of(JSONObject.of("choices",JSONArray.of(JSONObject.of("delta",JSONObject.of("content",text,"reasoning_content",reasoning),"finish_reason","stop"))));}
    @Test void originalFalseStringsDisableReadsButKeepCompletionAppend(){
        for(String raw:List.of("false","0","off","f","no","n")){
            var n=node(raw,0,new JSONArray());assertFalse(PythonModelHistorySemantics.readsLegacy(n));assertTrue(PythonModelHistorySemantics.appendsLegacy(n));assertTrue(PythonModelHistorySemantics.legacy(n));assertDoesNotThrow(()->PythonModelHistorySemantics.requireLegacy(n));
        }
        for(Object raw:List.of(false,0,0.0)){
            var n=node(raw,0,new JSONArray());assertFalse(PythonModelHistorySemantics.readsLegacy(n));assertFalse(PythonModelHistorySemantics.appendsLegacy(n));assertFalse(PythonModelHistorySemantics.legacy(n));
        }
        for(Object raw:List.of(true,1,1.0,"true","1")){
            var n=node(raw,0,new JSONArray());assertTrue(PythonModelHistorySemantics.readsLegacy(n));assertTrue(PythonModelHistorySemantics.appendsLegacy(n));
        }
        assertThrows(ServiceException.class,()->PythonModelHistorySemantics.appendsLegacy(node("",0,new JSONArray())));
        assertThrows(ServiceException.class,()->PythonModelHistorySemantics.appendsLegacy(node(new JSONArray(),0,new JSONArray())));
    }
    @Test void textNeedsFirstOutputUnlessOriginalReasoningLoopAlreadyProducesAnObject(){
        for(int format:List.of(0,1))assertEquals(403,assertThrows(ServiceException.class,()->CompletedModelOutputs.project(node(true,format,new JSONArray()),frames("raw",""))).getCode());
        assertTrue(CompletedModelOutputs.project(node(true,0,new JSONArray()),frames("raw","think")).isEmpty());
        assertThrows(ServiceException.class,()->CompletedModelOutputs.project(node(true,1,new JSONArray()),frames("raw","think")));
    }
    @Test void jsonObjectOrInvalidJsonCanSucceedWithNoOutputsButNonObjectFallbackCanFail(){
        for(String text:List.of("{}","not valid json"))assertTrue(CompletedModelOutputs.project(node(true,2,new JSONArray()),frames(text,"think")).isEmpty());
        for(String text:List.of("[]","null","0","\"text\""))assertEquals(403,assertThrows(ServiceException.class,()->CompletedModelOutputs.project(node(true,2,new JSONArray()),frames(text,"think"))).getCode());
        var fields=JSONArray.of(JSONObject.of("name","answer","required",true,"schema",JSONObject.of("type","string")));
        assertEquals("[]",CompletedModelOutputs.project(node(true,2,fields),frames("[]","think")).get("answer"));
    }
    @Test void oldHistoryPathDoesNotOpenDecisionOrV2History(){
        var decision=node(true,0,new JSONArray());decision.put("id","decision-making::m");assertEquals(503,assertThrows(ServiceException.class,()->PythonModelHistorySemantics.requireLegacy(decision)).getCode());
        var v2=node(true,0,new JSONArray());v2.getJSONObject("data").getJSONObject("nodeParam").getJSONObject("enableChatHistoryV2").put("isEnabled",true);assertEquals(503,assertThrows(ServiceException.class,()->PythonModelHistorySemantics.requireLegacy(v2)).getCode());
    }
}
