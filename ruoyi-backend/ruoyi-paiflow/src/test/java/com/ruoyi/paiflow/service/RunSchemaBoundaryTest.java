package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunSchemaBoundaryTest {
    private JSONObject schema() {return JSONObject.of("type","object","properties",JSONObject.of("token",JSONObject.of("type","string","description","业务标记"),"password",JSONObject.of("type","string","description","需提取的字段名称")),"required",JSONArray.of("token"));}
    private JSONObject graph(Object schema) {return JSONObject.of("nodes",JSONArray.of(JSONObject.of("data",JSONObject.of("nodeMeta",JSONObject.of("nodeType","extractor-parameter"),"nodeParam",JSONObject.of("fc_schema_params",schema)))));}
    @Test void fixedFunctionSchemaPropertyNamesAreMetadataAndDoNotDisappearFromRequestBytes() {
        var request=JSONObject.of("functions",JSONArray.of(JSONObject.of("name","extractor_parameter","parameters",schema())));
        assertDoesNotThrow(()->RunJsonBoundary.checkModel(request,8192));
        assertThrows(ServiceException.class,()->RunJsonBoundary.check(request,8192));
        var decoded=com.alibaba.fastjson2.JSON.parseObject(ModelOperationJson.canonical(request,8192));assertEquals(request,decoded);assertEquals("业务标记",decoded.getJSONArray("functions").getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").getJSONObject("token").get("description"));
    }
    @Test void originalWorkflowCustomSchemaIncludingEncodedJsonKeepsBusinessPropertyNames() {
        var graph=graph(schema());graph.getJSONArray("nodes").add(JSONObject.of("data",graph.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").toJSONString()));String before=graph.toJSONString();
        assertDoesNotThrow(()->WorkflowJsonBoundary.check(graph,"data",8192));assertEquals(before,graph.toJSONString());
    }
    @Test void nestedSchemaDefaultsAndExamplesAreBusinessValuesWhileAnnotationsAreStillPublic() {
        var definition=JSONObject.of("type","object","properties",JSONObject.of("apiKey",JSONObject.of("type","string"),"nested",JSONObject.of("type","object","properties",JSONObject.of("password",true))),"default",JSONObject.of("apiKey","示例业务字段","nested",JSONObject.of("password","示例值")),"examples",JSONArray.of(JSONObject.of("token","示例标记")));
        var full=JSONObject.of("type","object","properties",JSONObject.of("token",definition));
        assertDoesNotThrow(()->RunJsonBoundary.checkSchema(full,8192));assertDoesNotThrow(()->WorkflowJsonBoundary.check(graph(full),"data",8192));assertEquals(full,ModelOperationJson.copySchema(full));assertThrows(ServiceException.class,()->RunJsonBoundary.check(full,8192));
        var forged=JSONObject.of("type","object","properties",JSONObject.of("token",JSONObject.of("apiKey","actual-secret","default",JSONObject.of("token","示例"))));
        assertThrows(ServiceException.class,()->RunJsonBoundary.checkSchema(forged,8192));assertThrows(ServiceException.class,()->WorkflowJsonBoundary.check(graph(forged),"data",8192));
        assertThrows(ServiceException.class,()->RunJsonBoundary.check(JSONObject.of("default",JSONObject.of("token","actual-secret")),8192));
    }
    @Test void unknownExtensionsCannotDeclareTheirOwnSchemaDomain() {
        for(JSONObject fake:java.util.List.of(JSONObject.of("ext",schema()),JSONObject.of("future",schema().toJSONString()),JSONObject.of("ext",JSONObject.of("nodes",JSONArray.of(JSONObject.of("data",JSONObject.of("nodeParam",JSONObject.of("future",schema())))))))) {
            assertThrows(ServiceException.class,()->WorkflowJsonBoundary.check(fake,"data",8192));if(!fake.containsKey("future"))assertThrows(ServiceException.class,()->RunJsonBoundary.checkModel(fake,8192));
        }
        assertThrows(ServiceException.class,()->WorkflowJsonBoundary.check(JSONObject.of("ext",JSONObject.of("functions",JSONArray.of(JSONObject.of("name","extractor_parameter","parameters",schema())))),"data",8192));
        assertThrows(ServiceException.class,()->WorkflowJsonBoundary.check(JSONObject.of("ext",graph(schema())),"data",8192));
        assertThrows(ServiceException.class,()->RunJsonBoundary.checkSchemaAware(JSONObject.of("ext",graph(schema())),8192));
        var unknown=graph(schema());unknown.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONObject("nodeMeta").put("nodeType","unknown");
        assertThrows(ServiceException.class,()->WorkflowJsonBoundary.check(unknown,"data",8192));assertThrows(ServiceException.class,()->RunJsonBoundary.checkSchemaAware(unknown,8192));
    }
    @Test void sparkFunctionWireHasItsOwnExactSchemaPathAndRetainsHeaderCredentialGuard() {
        var packet=JSONObject.of("header",JSONObject.of("app_id","provider-label"),"payload",JSONObject.of("functions",JSONObject.of("text",JSONArray.of(JSONObject.of("name","extractor_parameter","parameters",schema())))));
        assertDoesNotThrow(()->ModelOperationJson.canonicalSparkWire(packet,8192));assertThrows(ServiceException.class,()->ModelOperationJson.canonical(packet,8192));
        var wrong=JSONObject.of("ext",packet.get("payload"));assertThrows(ServiceException.class,()->ModelOperationJson.canonicalSparkWire(wrong,8192));
        packet.getJSONObject("header").put("apiKey","actual-secret");assertThrows(ServiceException.class,()->ModelOperationJson.canonicalSparkWire(packet,8192));
    }
    @Test void actualCredentialFieldsAndMalformedSchemaValuesCannotUseMetadataExemption() {
        for(JSONObject bad:java.util.List.of(JSONObject.of("token","actual-secret"),JSONObject.of("properties",JSONObject.of("token",JSONObject.of("value","actual-secret"))),JSONObject.of("type","object","properties",JSONObject.of("token","actual-secret")),JSONObject.of("type","object","properties",JSONObject.of("token",JSONObject.of("type","string","apiKey","actual-secret"))))) {
            assertEquals(400,assertThrows(ServiceException.class,()->RunJsonBoundary.check(bad,8192)).getCode());assertEquals(400,assertThrows(ServiceException.class,()->RunJsonBoundary.checkSchemaAware(bad,8192)).getCode());assertEquals(400,assertThrows(ServiceException.class,()->WorkflowJsonBoundary.check(bad,"data",8192)).getCode());
        }
    }
}
