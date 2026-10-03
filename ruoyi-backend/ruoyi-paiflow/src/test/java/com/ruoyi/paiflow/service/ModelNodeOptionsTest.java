package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 对比独立原Pydantic实际结果；不运行模型节点、SDK、网络或数据库。 */
class ModelNodeOptionsTest {
    @Test void effectiveValuesAgreeWithRecordedOriginalPydanticCases() throws Exception {
        var record=JSON.parseObject(Files.readString(Path.of("..","..","python","paiflow-runtime","verification","pydantic-model-options-probe.json"),StandardCharsets.UTF_8));
        assertEquals(126,record.getIntValue("count"));int checked=0;
        for(Object raw:record.getJSONArray("cases")){
            var c=(JSONObject)raw;String field=c.getString("field");var input=new JSONObject();input.put(field,c.get("input"));String label=field+" / "+c.getString("inputType")+" / "+c.get("input");
            if(c.getBooleanValue("accepted")){
                Object actual=ModelNodeOptions.effective(input).get(field);var expected=new JSONObject();expected.put("value",c.get("effective"));var result=new JSONObject();result.put("value",actual);
                assertEquals(ModelOperationJson.canonical(expected,8192),ModelOperationJson.canonical(result,8192),label);
                if(field.equals("temperature"))assertInstanceOf(Double.class,actual,label);
            }else assertThrows(ServiceException.class,()->ModelNodeOptions.effective(input),label);
            checked++;
        }
        assertEquals(126,checked);
    }
    @Test void missingDefaultsAndExplicitNullKeepOriginalDifferentSemantics(){
        var defaults=ModelNodeOptions.effective(new JSONObject());assertEquals(1.0,defaults.get("temperature"));assertEquals(2048,defaults.getIntValue("maxTokens"));assertEquals(3,defaults.getIntValue("topK"));assertEquals(0,defaults.getIntValue("respFormat"));assertTrue(defaults.getBooleanValue("searchDisable"));assertFalse(defaults.getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"));
        for(String key:defaults.keySet()){var input=new JSONObject();input.put(key,null);assertThrows(ServiceException.class,()->ModelNodeOptions.effective(input),key);}
    }
    @Test void responseFormatCannotUseFloatBooleanOrStringCoercion(){
        for(Object invalid:new Object[]{true,false,"0","1",1.0,2.0})assertThrows(ServiceException.class,()->ModelNodeOptions.effective(JSONObject.of("respFormat",invalid)));
        assertEquals(1,ModelNodeOptions.effective(JSONObject.of("respFormat",1)).getIntValue("respFormat"));
    }
    @Test void ignoredInternalHistoryNameNeverEnablesExternalHistoryAlias(){
        assertFalse(ModelNodeOptions.effective(JSONObject.of("enableChatHistoryV2",JSONObject.of("is_enabled",true))).getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"));
        assertTrue(ModelNodeOptions.effective(JSONObject.of("enableChatHistoryV2",JSONObject.of("isEnabled","yes"))).getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"));
        assertEquals(503,assertThrows(ServiceException.class,()->ModelOperationOptions.fromNode(JSONObject.of("enableChatHistoryV2",JSONObject.of("isEnabled","yes")))).getCode());
    }
    @Test void originalIntegerSyntaxAndFloatSyntaxRemainDistinct(){
        assertEquals(30,ModelNodeOptions.effective(JSONObject.of("topK","3_0.0")).getIntValue("topK"));assertEquals(3,ModelNodeOptions.effective(JSONObject.of("topK","\t3\n")).getIntValue("topK"));
        for(String invalid:new String[]{"3.","3e0","3.5"})assertThrows(ServiceException.class,()->ModelNodeOptions.effective(JSONObject.of("topK",invalid)));
        assertThrows(ServiceException.class,()->ModelNodeOptions.effective(JSONObject.of("temperature","0x1.0p-1")));
    }
    @Test void keyBudgetCountsUnicodeCharactersAndRejectsMalformedUnicode(){
        String key="\uD83D\uDE00".repeat(4096);assertEquals(key,ModelOperationJson.text(key,4096,"idempotencyKey"));assertEquals(16384,key.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(ServiceException.class,()->ModelOperationJson.text(key+"x",4096,"idempotencyKey"));for(String invalid:new String[]{"\u0080","\u009F","\uD800","\uDC00"})assertThrows(ServiceException.class,()->ModelOperationJson.text(invalid,4096,"idempotencyKey"));
    }
}
