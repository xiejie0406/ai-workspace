package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.adapter.legacy.dto.LegacyWorkflowInputTypeDto;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原纯DTO已执行观察与Maas源码推导分别比较；不把源码推导说成原业务Service运行。 */
class WorkflowInputTypesPolicyTest {
    static JSONObject fixture() throws Exception {return JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/workflow-input-types.json")));}
    static JSONObject graph(JSONArray outputs){return JSONObject.of("nodes",JSONArray.of(JSONObject.of("id","node-start::first","data",JSONObject.of("outputs",outputs))),"edges",new JSONArray(),"opaqueCanvas",null);}
    @Test void fifteenOriginalTypedCasesAndSourceDerivedMetadataMatchWithoutMutatingRaw() throws Exception {
        var cases=fixture().getJSONArray("cases");assertEquals(15,cases.size());int rejected=0;
        for(Object value:cases){var c=(JSONObject)value;var raw=c.getJSONArray("rawStartOutputs");String before=raw.toJSONString(JSONWriter.Feature.WriteMapNullValue);var expected=c.getJSONObject("independentExpected").getJSONObject("maasLocalMetadataProjection");
            if(!expected.getBooleanValue("metadataWriteReachable")){assertEquals(400,assertThrows(ServiceException.class,()->WorkflowInputTypesPolicy.project(graph(raw))).getCode());rejected++;}
            else{var p=WorkflowInputTypesPolicy.project(graph(raw));assertEquals(c.getJSONObject("actualOriginalPureObservations").get("actualGetInputsInfoData"),p.responseFields(),c.getString("id"));assertEquals(expected.get("extraInputsConfigStoredJsonValue"),JSON.parseArray(p.extraInputsConfig()),c.getString("id"));assertEquals(expected.get("extraInputsStoredJsonValue"),JSON.parseObject(p.extraInputs()),c.getString("id"));assertEquals(expected.getIntValue("supportUpload"),p.supportUpload(),c.getString("id"));
                var dtos=p.responseFields().toJavaList(LegacyWorkflowInputTypeDto.class);var fields=JSON.parseArray(JSON.toJSONString(dtos,JSONWriter.Feature.WriteMapNullValue));assertEquals(c.getJSONObject("independentExpected").get("returnedDtoFieldState"),fields,c.getString("id"));}
            assertEquals(before,raw.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        }assertEquals(2,rejected);
    }
    @Test void typedWhitelistStopsAtOpenObjectsAndOnlySingularItemIsOriginalField(){
        var open=JSONObject.of("unknown",7,"nullValue",null);var schema=JSONObject.of("type","string","default",open,"item",open,"items",JSONObject.of("mustDrop",true),"futureSchema",true,"value",JSONObject.of("type","literal","content",open,"contentErrMsg","fixture","futureValue",true));
        var row=JSONObject.of("name","text","schema",schema,"refId",open,"disabled",open,"futureRoot",true);var projected=WorkflowInputTypesPolicy.projectOutputs(JSONArray.of(row)).responseFields().getJSONObject(0);var result=projected.getJSONObject("schema");
        assertFalse(result.containsKey("items"));assertFalse(result.containsKey("futureSchema"));assertFalse(projected.containsKey("futureRoot"));assertFalse(result.getJSONObject("value").containsKey("futureValue"));
        for(JSONObject object:List.of(result.getJSONObject("default"),result.getJSONObject("item"),result.getJSONObject("value").getJSONObject("content"),projected.getJSONObject("refId"),projected.getJSONObject("disabled"))) {assertEquals(7,object.getIntValue("unknown"));assertFalse(object.containsKey("nullValue"));}
        assertTrue(open.containsKey("nullValue"));
    }
    @Test void responseKeepsAgentAndArrayFileOrderAndOriginalFirstStart(){
        var fields=JSONArray.of(JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string")),JSONObject.of("name","files","fileType","file","allowedFileType",JSONArray.of("PDF","image"),"schema",JSONObject.of("type","array-string")));
        var graph=graph(fields);graph.getJSONArray("nodes").add(JSONObject.of("id","node-start::second","data",JSONObject.of("outputs",new JSONArray())));var p=WorkflowInputTypesPolicy.project(graph);
        assertEquals(2,p.responseFields().size());assertEquals("AGENT_USER_INPUT",p.responseFields().getJSONObject(0).get("name"));assertEquals("array-string",p.responseFields().getJSONObject(1).getJSONObject("schema").get("type"));assertEquals("file",p.responseFields().getJSONObject(1).get("fileType"));assertEquals(1,JSON.parseArray(p.extraInputsConfig()).size());assertEquals("{}",p.extraInputs());assertEquals(0,p.supportUpload());
    }
    @Test void emptyOrNullFirstFileTypeAndMissingFileSchemaRefuseBeforeProjectionWrites(){
        for(JSONArray allowed:List.of(new JSONArray(),JSONArray.of(null,"image"))){var row=JSONObject.of("name","file","allowedFileType",allowed,"schema",JSONObject.of("type","string"));assertEquals(400,assertThrows(ServiceException.class,()->WorkflowInputTypesPolicy.projectOutputs(JSONArray.of(row))).getCode());}
        assertEquals(400,assertThrows(ServiceException.class,()->WorkflowInputTypesPolicy.projectOutputs(JSONArray.of(JSONObject.of("name","file","allowedFileType",JSONArray.of("image"),"schema",null)))).getCode());
    }
    @Test void reservedAgentInputIsReturnedButSkippedBeforeMetadataSchemaOrFileChecks(){
        var reserved=List.of(JSONObject.of("name","AGENT_USER_INPUT","schema",null),JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string"),"allowedFileType",new JSONArray()),JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of(null,"pdf")));
        for(JSONObject row:reserved){var raw=JSONArray.of(row,JSONObject.of("name","file","schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of("PDF")));String before=raw.toJSONString(JSONWriter.Feature.WriteMapNullValue);var projection=WorkflowInputTypesPolicy.projectOutputs(raw);assertEquals(2,projection.responseFields().size());assertEquals("AGENT_USER_INPUT",projection.responseFields().getJSONObject(0).get("name"));assertEquals(1,JSON.parseArray(projection.extraInputsConfig()).size());assertEquals("file",JSON.parseObject(projection.extraInputs()).get("name"));assertEquals(1,projection.supportUpload());var returned=JSON.parseArray(JSON.toJSONString(projection.responseFields().toJavaList(LegacyWorkflowInputTypeDto.class),JSONWriter.Feature.WriteMapNullValue)).getJSONObject(0);assertEquals(12,returned.size());assertEquals(row.get("schema"),returned.get("schema"));assertEquals(row.get("allowedFileType"),returned.get("allowedFileType"));assertEquals(before,raw.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    }
    @Test void projectionBudgetAndDerivedDynamicCredentialNamesKeepExistingBoundary(){
        assertEquals(413,assertThrows(ServiceException.class,()->WorkflowInputTypesPolicy.projectOutputs(JSONArray.of(JSONObject.of("name","field","schema",JSONObject.of("type","string","default","x".repeat(1024*1024)))))).getCode());
        for(String name:List.of("apiKey","password","token"))assertEquals(400,assertThrows(ServiceException.class,()->WorkflowInputTypesPolicy.projectOutputs(JSONArray.of(JSONObject.of("name",name,"schema",JSONObject.of("type","string"),"allowedFileType",JSONArray.of("pdf"))))).getCode());
    }
}
