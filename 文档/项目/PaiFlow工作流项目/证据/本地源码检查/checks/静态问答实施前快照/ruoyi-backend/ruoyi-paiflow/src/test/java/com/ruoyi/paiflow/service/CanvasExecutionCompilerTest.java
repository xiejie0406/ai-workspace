package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原Toolkit纯字段转换事实；预期来自保存的原函数/DTO，不代表Provider或页面已验收。 */
class CanvasExecutionCompilerTest {
    private static JSONObject graph(JSONObject data){return JSONObject.of("nodes",new JSONArray(List.of(JSONObject.of("id","node-start::s","position",JSONObject.of("x",12,"y",34),"data",data))),"edges",new JSONArray(),"futureCanvas","保全");}
    private static JSONObject data(){return JSONObject.of("label","原页面开始","nodeMeta",JSONObject.of("nodeType","node-start","aliasName","旧别名"),"nodeParam",JSONObject.of("configs",JSONObject.of("legacy","opaque")),"inputs",new JSONArray(),"outputs",new JSONArray());}
    private static JSONObject input(String name,String type,Object content){return JSONObject.of("name",name,"schema",JSONObject.of("type",type,"value",JSONObject.of("type","literal","content",content)));}
    private static JSONObject output(String name,JSONObject schema){return JSONObject.of("name",name,"schema",schema);}
    private static JSONObject compiled(JSONObject graph){return new DslCodec().normalize(graph).execution().getJSONArray("nodes").getJSONObject(0).getJSONObject("data");}
    @Test void originalAliasAndTypedArrayTimeConversionOnlyChangeExecutionCopy(){
        var d=data();d.getJSONArray("inputs").add(input("date","TIME","2026-10-03"));d.getJSONArray("inputs").add(input("batch","array-string","['A']"));d.getJSONArray("outputs").add(output("result",JSONObject.of("type","array-integer")));d.getJSONArray("outputs").add(output("timeOutput",JSONObject.of("type","time")));
        var source=graph(d);String before=source.toJSONString();var normalized=new DslCodec().normalize(source);var result=normalized.execution().getJSONArray("nodes").getJSONObject(0).getJSONObject("data");
        assertEquals(before,source.toJSONString());assertEquals(source,normalized.original());assertEquals("原页面开始",result.getJSONObject("nodeMeta").get("aliasName"));assertFalse(result.containsKey("label"));assertFalse(result.getJSONObject("nodeParam").containsKey("configs"));
        assertEquals("string",result.getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").get("type"));assertEquals("array",result.getJSONArray("inputs").getJSONObject(1).getJSONObject("schema").get("type"));assertEquals("string",result.getJSONArray("inputs").getJSONObject(1).getJSONObject("schema").getJSONObject("items").get("type"));
        assertEquals("integer",result.getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").getJSONObject("items").get("type"));assertEquals("time",result.getJSONArray("outputs").getJSONObject(1).getJSONObject("schema").get("type"));
        assertEquals(normalized.execution(),new DslCodec().normalize(normalized.execution()).execution());assertEquals(new DslCodec().normalizeV1(source).execution().getJSONArray("nodes").getJSONObject(0).get("data"),source.getJSONArray("nodes").getJSONObject(0).get("data"));
    }
    @Test void inputContentEmptyMatchesOriginalInsteadOfBooleanTruthiness(){
        var d=data();var inputs=d.getJSONArray("inputs");inputs.add(input("nullValue","string",null));inputs.add(input("blank","string"," "));inputs.add(input("emptyArray","array-string",new JSONArray()));inputs.add(input("emptyItems","array-string",new JSONArray(java.util.Arrays.asList(null," "))));inputs.add(input("nestedOnly","object",JSONObject.of("opaque",JSONObject.of("x","v"))));
        inputs.add(input("falseValue","boolean",false));inputs.add(input("zero","integer",0));inputs.add(input("falseArray","array-boolean",new JSONArray(List.of(false))));inputs.add(input("directString","object",JSONObject.of("opaque","v")));
        var names=compiled(graph(d)).getJSONArray("inputs").stream().map(raw->((JSONObject)raw).getString("name")).toList();assertEquals(List.of("falseValue","zero","falseArray","directString"),names);
    }
    @Test void objectPropertiesUseOnlyOriginalTypeItemsAndFirstArrayObjectRequired(){
        var child=JSONObject.of("id","ui-child","name","inside","type","integer","required",true,"default","7","description","UI描述");
        var nested=JSONObject.of("id","ui-parent","name","nested","type","object","required",true,"properties",new JSONArray(List.of(child)));
        var d=data();d.getJSONArray("outputs").add(output("batch",JSONObject.of("type","array-object","properties",new JSONArray(List.of(nested)))));d.getJSONArray("inputs").add(JSONObject.of("name","objects","schema",JSONObject.of("type","array-object","properties",new JSONArray(List.of(nested)),"value",JSONObject.of("type","literal","content","[]"))));
        var result=compiled(graph(d));var schema=result.getJSONArray("outputs").getJSONObject(0).getJSONObject("schema");var items=schema.getJSONObject("items");assertEquals(new JSONArray(List.of("nested")),items.getJSONArray("required"));
        var object=items.getJSONObject("properties").getJSONObject("nested");assertEquals(JSONObject.of("type","object","properties",JSONObject.of("inside",JSONObject.of("type","integer"))),object);assertFalse(object.containsKey("required"));assertFalse(object.getJSONObject("properties").getJSONObject("inside").containsKey("default"));
        var inputItems=result.getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("items");assertEquals(JSONObject.of("type","object"),inputItems);
    }
    @Test void nonArrayObjectNestedArrayAndDuplicatePropertyNamePreserveOriginalProjection(){
        var props=JSONArray.of(JSONObject.of("name","same","type","string"),JSONObject.of("name","same","type","array-object","properties",JSONArray.of(JSONObject.of("name","v","type","string","required",true))));
        var d=data();d.getJSONArray("outputs").add(output("object",JSONObject.of("type","object","properties",props)));var s=compiled(graph(d)).getJSONArray("outputs").getJSONObject(0).getJSONObject("schema");assertEquals(JSONObject.of("same",JSONObject.of("type","array","items",JSONObject.of("type","object","properties",JSONObject.of("v",JSONObject.of("type","string"))))),s.getJSONObject("properties"));
    }
    @Test void defaultBecomesDescriptionOnlyWhenOriginalDescriptionIsNullIncludingStart(){
        var d=data();d.getJSONArray("outputs").add(output("missing",JSONObject.of("type","array-string","default",new JSONArray(List.of("D","E")))));d.getJSONArray("outputs").add(output("empty",JSONObject.of("type","string","description","","default","保持默认")));d.getJSONArray("outputs").add(output("existing",JSONObject.of("type","string","description","既有描述","default","保留")));
        var output=compiled(graph(d)).getJSONArray("outputs");assertEquals("[\"D\",\"E\"]",output.getJSONObject(0).getJSONObject("schema").get("description"));assertFalse(output.getJSONObject(0).getJSONObject("schema").containsKey("default"));assertEquals("保持默认",output.getJSONObject(1).getJSONObject("schema").get("default"));assertEquals("保留",output.getJSONObject(2).getJSONObject("schema").get("default"));
    }
    @Test void alreadyExecutableDefaultsAreNotReinterpretedAsCanvasDescription(){
        var d=data();d.remove("label");d.getJSONArray("outputs").add(output("batch",JSONObject.of("type","array","items",JSONObject.of("type","string"),"default",new JSONArray(List.of("D")))));var source=graph(d);source.getJSONArray("nodes").getJSONObject(0).remove("position");
        assertEquals(new JSONArray(List.of("D")),compiled(source).getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").get("default"));
    }
    @Test void branchHandleContainsMatchingTermAndTargetHandlePreserveOriginalWithoutDoublePrefix(){
        var source=graph(data());for(String handle:List.of("prefix:branch_one_of::b:suffix","intent-one-of::b","fail_one_of::b","ordinary"))source.getJSONArray("edges").add(JSONObject.of("source","node-start::s","target","node-end::e","sourceHandle",handle,"targetHandle","wire-target"));
        var result=new DslCodec().normalize(source).execution();var edges=result.getJSONArray("edges");assertEquals("intent_chain|prefix:branch_one_of::b:suffix",edges.getJSONObject(0).get("sourceHandle"));assertEquals("intent_chain|intent-one-of::b",edges.getJSONObject(1).get("sourceHandle"));assertEquals("intent_chain|fail_one_of::b",edges.getJSONObject(2).get("sourceHandle"));assertEquals("ordinary",edges.getJSONObject(3).get("sourceHandle"));assertEquals("wire-target",edges.getJSONObject(0).get("targetHandle"));assertEquals(result,new DslCodec().normalize(result).execution());
    }
    @Test void retryCustomOutputObjectStringBlankAndMalformedUseOriginalCases(){
        var d=data();d.put("retryConfig",JSONObject.of("shouldRetry",false,"customOutput","{\"value\":\"原输出\"}"));assertEquals(JSONObject.of("value","原输出"),compiled(graph(d)).getJSONObject("retryConfig").get("customOutput"));
        d.getJSONObject("retryConfig").put("customOutput"," ");assertEquals(" ",compiled(graph(d)).getJSONObject("retryConfig").get("customOutput"));d.getJSONObject("retryConfig").put("customOutput","malformed");assertEquals(422,assertThrows(ServiceException.class,()->compiled(graph(d))).getCode());
    }
    @Test void v1BytesAndUnknownCodecCannotAccidentallyUseCurrentConverter(){
        var d=data();d.getJSONArray("outputs").add(output("batch",JSONObject.of("type","array-string","default","D")));var source=graph(d);var old=new DslCodec().normalizeV1(source);assertEquals(d,old.execution().getJSONArray("nodes").getJSONObject(0).get("data"));assertEquals(old.execution(),new DslCodec().normalize(source,null).execution());assertNotEquals(old.executionHash(),new DslCodec().normalize(source).executionHash());assertThrows(IllegalArgumentException.class,()->new DslCodec().normalize(source,"future-unrecognized"));
    }
    @Test void v2ExplicitNullCloneCanonicalAndSecondNormalizationAreConsistent(){
        var d=data();d.getJSONObject("nodeParam").put("explicitNull",null);d.getJSONArray("outputs").add(output("value",JSONObject.of("type","string","description","","default",null)));var source=graph(d);source.put("futureNull",null);var normalized=new DslCodec().normalize(source);
        assertTrue(normalized.original().containsKey("futureNull"));var result=normalized.execution().getJSONArray("nodes").getJSONObject(0).getJSONObject("data");assertTrue(result.getJSONObject("nodeParam").containsKey("explicitNull"));assertNull(result.getJSONObject("nodeParam").get("explicitNull"));assertTrue(result.getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").containsKey("default"));
        byte[] bytes=java.util.Base64.getDecoder().decode(DslCodec.canonicalDslBase64(normalized.execution()));assertEquals(normalized.execution(),JSON.parseObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));assertEquals(normalized.execution(),new DslCodec().normalize(normalized.execution()).execution());assertEquals(normalized.executionHash(),DslCodec.fixedExecutionHash(normalized.execution(),DslCodec.V2));
        assertFalse(new DslCodec().normalizeV1(source).execution().getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONObject("nodeParam").containsKey("explicitNull"));
    }
    @Test void topLevelArrayFallbackDoesNotInventMissingNestedElementType(){
        var d=data();d.getJSONArray("outputs").add(output("top",JSONObject.of("type","array-")));assertEquals("object",compiled(graph(d)).getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").getJSONObject("items").get("type"));
        d.getJSONArray("outputs").add(output("nested",JSONObject.of("type","object","properties",JSONArray.of(JSONObject.of("name","broken","type","array-")))));assertEquals(422,assertThrows(ServiceException.class,()->compiled(graph(d))).getCode());
    }
}
