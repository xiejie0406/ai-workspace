package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原Pydantic共享探针逐项对应；静态profile授权限制与原typed合法性分别判断。 */
class StaticQuestionAnswerOptionsTest {
    private JSONObject raw(){return JSONObject.of("question","请回答","answerType","direct","timeout",1,"needReply",false,"directAnswer",JSONObject.of("handleResponse",false,"maxRetryCounts",5));}
    @Test void actualOneHundredEighteenPydanticCasesKeepOriginalFactsAndOnlyApprovedMissingStringCompatibility() throws Exception {
        var fixture=JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/static-question-answer.json"),StandardCharsets.UTF_8));int count=0,compatibility=0;
        for(Object item:fixture.getJSONArray("coercionCases")) {
            JSONObject c=(JSONObject)item,p=c.getJSONObject("parameters");String field=c.getString("field"),group=field.split("\\.",2)[0];count++;
            if(!c.getBooleanValue("accepted")){
                if(List.of("appId","domain").contains(field) && !p.containsKey(field)){assertEquals("",StaticQuestionAnswerOptions.effective(p).get(field));assertFalse(p.containsKey(field));compatibility++;}
                else assertEquals(422,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p),c.getString("id")).getCode());continue;
            }
            Object expected=c.get("effectiveValue");boolean blocked=group.equals("enableChatHistory") && Boolean.TRUE.equals(expected) || group.equals("enableChatHistoryV2") && expected instanceof JSONObject h && h.getBooleanValue("is_enabled") || group.equals("directAnswer") && expected instanceof JSONObject d && d.getBooleanValue("handleResponse") && "direct".equals(p.get("answerType"));
            if(blocked){assertEquals(503,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p),c.getString("id")).getCode());continue;}
            var actual=StaticQuestionAnswerOptions.effective(p).get(group);
            if(group.equals("enableChatHistoryV2")){var h=StaticQuestionAnswerTestFixture.copy((JSONObject)expected);h.put("isEnabled",h.remove("is_enabled"));expected=h;}
            assertEquals(expected,actual,c.getString("id"));
        }
        assertEquals(118,count);
        assertEquals(2,compatibility); // 原输入确实拒缺字段；V3只补执行副本空str，不宣告App/Provider关系。
    }
    @Test void baseFieldsAndCompatibilityEmptyStringsAreTypedOnlyInExecutionCopy(){var p=raw();p.put("remarkVisible","OFF");p.put("remark","说明");p.put("modelId",17);p.put("llmId","old-extra");var effective=StaticQuestionAnswerOptions.effective(p);assertEquals(false,effective.get("remarkVisible"));assertEquals("说明",effective.get("remark"));assertEquals("",effective.get("appId"));assertEquals("",effective.get("domain"));assertFalse(p.containsKey("appId"));p.put("remark",null);assertEquals(422,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p)).getCode());}
    @Test void optionOrderDefaultAndTrueHandleRemainStaticAndNoGuessing(){var p=raw();p.put("answerType","option");p.getJSONObject("directAnswer").put("handleResponse",true);var options=new JSONArray(List.of(option("B"),option("default"),option("A")));p.put("optionAnswer",options);var result=StaticQuestionAnswerOptions.effective(p);assertEquals(options,result.get("optionAnswer"));assertTrue(result.getJSONObject("directAnswer").getBooleanValue("handleResponse"));}
    private JSONObject option(String name){return JSONObject.of("id","uuid-"+name,"name",name,"type",2,"content_type","text","content",name);}
    @Test void duplicateDefaultTypeOnlyRejectsEffectiveOptionBranch(){var p=raw();var a=option("A");var b=option("B");a.put("type",1);b.put("type",1);p.put("optionAnswer",new JSONArray(List.of(a,b)));assertEquals(2,StaticQuestionAnswerOptions.effective(p).getJSONArray("optionAnswer").size());p.put("answerType","option");assertEquals(422,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p)).getCode());}
    @Test void emptyHistoryStillRejectedWhenEffectiveFlagsAreEnabled(){for(Object flag:List.of(true,1,"true")){var p=raw();p.put("enableChatHistory",flag);assertEquals(503,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p)).getCode());}var p=raw();p.put("enableChatHistoryV2",JSONObject.of("is_enabled",true));assertFalse(StaticQuestionAnswerOptions.effective(p).getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"));p.put("enableChatHistoryV2",JSONObject.of("isEnabled",true));assertEquals(503,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p)).getCode());}
    @Test void explicitNullDoesNotBecomeMissingDefault(){for(String key:List.of("temperature","timeout","needReply","optionAnswer","appId","domain","remarkVisible","modelResourceId")){var p=raw();p.put(key,null);assertEquals(422,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p)).getCode(),key);}}
    @Test void runtimeStateAndPrivateProviderMaterialHaveNoDefaultPort(){for(String key:List.of("apiKey","apiSecret","url","modelResourceId","credentialRef","endpoint","extractor_params","default_outputs")){var p=raw();p.put(key,"fixture-private");assertEquals(503,assertThrows(ServiceException.class,()->StaticQuestionAnswerOptions.effective(p)).getCode(),key);}}
    @Test void sharedCanonicalGraphUsesSamePoolAndTopologicalBudgets() throws Exception {var f=JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/static-question-answer.json"),StandardCharsets.UTF_8));var metrics=StaticQuestionAnswerPolicy.validate(f.getJSONObject("execution"));assertEquals(3,metrics.expandedNodes());assertEquals(1,metrics.pathCount());assertEquals(3,metrics.maxPathLength());}
}
