package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.ModelRecord;
import com.ruoyi.paiflow.domain.ModelVersionRecord;
import com.ruoyi.paiflow.mapper.ModelMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysUserService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原Java编译语义与真实Catalog代码边界；Mapper/profile仅隔离夹具，不派发或访问库/Provider。 */
class JavaModelCompilationPolicyTest {
    private static JSONObject graph() {
        var start=JSONObject.of("id","node-start::s","data",JSONObject.of("nodeParam",new JSONObject(),"inputs",new JSONArray(),"outputs",new JSONArray()));
        var model=JSONObject.of("id","spark-llm::m","data",JSONObject.of("nodeParam",JSONObject.of("modelId",1,"domain","java-domain","template","原问题：{{question}}","maxTokens",null),
            "inputs",new JSONArray(List.of(ref("question","node-start::s","question"))),"outputs",new JSONArray(List.of(JSONObject.of("name","answer","schema",JSONObject.of("type","string"))))));
        var end=JSONObject.of("id","node-end::e","data",JSONObject.of("nodeParam",JSONObject.of("template","结果：{{answer}}"),"inputs",new JSONArray(List.of(ref("answer","spark-llm::m","answer"))),"outputs",new JSONArray()));
        return JSONObject.of("nodes",new JSONArray(List.of(start,model,end)),"edges",new JSONArray(List.of(
            JSONObject.of("sourceNodeId","node-start::s","targetNodeId","spark-llm::m","sourceHandle","normal_one_of"),
            JSONObject.of("sourceNodeId","spark-llm::m","targetNodeId","node-end::e","sourceHandle",""))));
    }
    private static JSONObject ref(String name,String node,String field) {return JSONObject.of("name",name,"schema",JSONObject.of("type","string","value",JSONObject.of("type","ref","content",JSONObject.of("nodeId",node,"name",field))));}
    private static JSONObject params(JSONObject graph){return node(graph).getJSONObject("data").getJSONObject("nodeParam");}
    private static JSONObject node(JSONObject graph){return graph.getJSONArray("nodes").getJSONObject(1);}
    private static String json(Object value){return JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue,JSONWriter.Feature.MapSortField);}
    private record Fixture(ModelRuntimeCatalog catalog,ModelMapper mapper,ResourceAccessService access,ModelRecord current) {}
    @SuppressWarnings("unchecked") private static Fixture fixture(String profile) {
        var mapper=mock(ModelMapper.class);var access=mock(ResourceAccessService.class);
        var current=new ModelRecord();current.setId(1L);current.setSpaceId(1L);current.setDeleted(0);current.setType(1);current.setState("VALIDATED");current.setEnabled(1);current.setLatestVersionId(7L);
        var version=new ModelVersionRecord();version.setId(7L);version.setModelId(1L);version.setSpaceId(1L);version.setOwnerUserId(42L);version.setRevision(0L);version.setType(1);version.setState("VALIDATED");version.setEnabled(1);
        version.setCredentialVersion("fixture-ref-v1");version.setCandidateHash("a".repeat(64));
        version.setConfigurationJson(JSONObject.of("provider","legacy-vendor","domain","java-domain","endpoint","https://isolated.invalid/v1/chat/completions","credentialRef","fixture:private").toJSONString());
        when(mapper.find(1L)).thenReturn(current);when(mapper.version(7L)).thenReturn(version);
        var catalog=new ModelRuntimeCatalog(mapper,mock(SpaceMapper.class),access,mock(ISysUserService.class),mock(SysPermissionService.class));
        if(profile!=null){ObjectProvider<ModelRuntimeCatalog.ProfilePort> port=mock(ObjectProvider.class);when(port.getIfAvailable()).thenReturn(source->new ModelRuntimeCatalog.RuntimeProfile(source.sourceFingerprint(),profile,Set.of("chat"),"https://isolated.invalid/v1"));catalog.useProfiles(port);}
        return new Fixture(catalog,mapper,access,current);
    }
    private static WorkflowModelCompiler.Compiled compile(JSONObject graph,Fixture fixture){return WorkflowModelCompiler.compile(graph,(id,source,domain)->fixture.catalog.bindCurrent(1L,id,source,domain),null,null,"java");}

    @Test void defaultSourcePinsActualCatalogWithoutPythonDefaults() {
        var graph=graph();String before=json(graph);var f=fixture("openai");
        var compiled=assertDoesNotThrow(()->compile(graph,f));assertEquals(DslCodec.V2,WorkflowModelCompiler.executionCodecFor(graph,"java"));
        assertEquals(before,json(graph));assertFalse(params(graph).containsKey("source"));
        var p=params(compiled.execution());assertEquals("openai",p.get("source"));assertEquals("model:1:version:7",p.get("modelResourceId"));assertTrue(p.containsKey("maxTokens"));assertNull(p.get("maxTokens"));
        for(String absent:List.of("temperature","topK","respFormat","enableChatHistory","enableChatHistoryV2","extraParams","patch_id","appId","systemTemplate"))assertFalse(p.containsKey(absent),absent);
        assertEquals(JSONObject.of("kind","model","provider","openai","version","7","modelName","java-domain","enabled",true,"capabilities",new JSONArray(List.of("chat"))),compiled.resourceSnapshots().getJSONObject("model:1:version:7"));
        verify(f.access,times(2)).requireSpace(1L,"paiflow:model:query",3,false);verify(f.mapper,times(2)).find(1L);verify(f.mapper).version(7L);
    }
    @Test void legacyVendorSourceIsIgnoredOnlyInExecutionCopy() {
        for(Object source:Arrays.asList("xinghuo","deepseek",null,"")){var graph=graph();params(graph).put("source",source);String before=json(graph);var compiled=compile(graph,fixture("openai"));assertEquals("openai",params(compiled.execution()).get("source"));assertEquals(before,json(graph));}
    }
    @Test void missingProfileAndActualSparkProfileCannotGainJavaPermission() {
        assertEquals(503,assertThrows(ServiceException.class,()->compile(graph(),fixture(null))).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->compile(graph(),fixture("xinghuo"))).getCode());
    }
    @Test void originalNullableOptionsZeroExtraOverrideAndMultipleRepliesArePreserved() {
        var graph=graph();params(graph).put("maxTokens",12);params(graph).put("extraParams",JSONObject.of("temperature","0","topP","bad","presencePenalty",0,"frequencyPenalty",null,"maxTokens","99","n","2"));
        var compiled=compile(graph,fixture("openai"));assertEquals(json(params(graph).get("extraParams")),json(params(compiled.execution()).get("extraParams")));
        var options=JavaModelSemantics.options(node(compiled.execution()));assertEquals(8,options.size());assertEquals(99,options.get("maxTokens"));assertEquals(0.0,options.get("temperature"));assertNull(options.get("topP"));assertNull(options.get("frequencyPenalty"));assertEquals(2,options.get("n"));assertEquals(true,options.get("streamUsage"));assertEquals(true,options.get("enableThinking"));
    }
    @Test void scalarOrObjectTemplateIsNotRewrittenAsPythonTextDefaults() {
        for(Object value:List.of(0,false,JSONObject.of("business","原值"))){var graph=graph();params(graph).put("template",value);var compiled=compile(graph,fixture("openai"));assertEquals(json(value),json(params(compiled.execution()).get("template")));}
    }
    @Test void unconsumedHistoryFlagDoesNotInventHistoryAndEffectiveV2MetadataIsPreserved() {
        var graph=graph();params(graph).put("enableChatHistory",true);assertDoesNotThrow(()->compile(graph,fixture("openai")));
        params(graph).put("enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds",2));var f=fixture("openai");String before=json(graph);var compiled=assertDoesNotThrow(()->compile(graph,f));assertEquals(before,json(graph));assertEquals(params(graph).getJSONObject("enableChatHistoryV2"),params(compiled.execution()).getJSONObject("enableChatHistoryV2"));assertEquals(2,JavaModelSemantics.historyLimit(node(compiled.execution())));verify(f.mapper,times(2)).find(1L);
        // 编译只保全固定历史配置；真实历史proof准入由JavaModelHistoryOperationTest核对。
        params(graph).put("enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds","2"));assertDoesNotThrow(()->compile(graph,fixture("openai")));
    }
    @Test void enabledNullOrMissingRoundsRejectsBeforeCatalogWhileOriginalDisabledConfigurationsPass() {
        for(JSONObject history:List.of(JSONObject.of("isEnabled",true,"rounds",null),JSONObject.of("isEnabled",true))){var graph=graph();params(graph).put("enableChatHistoryV2",history);var f=fixture("openai");assertEquals(422,assertThrows(ServiceException.class,()->compile(graph,f)).getCode());verifyNoInteractions(f.mapper);}
        for(Object history:Arrays.asList(null,JSONObject.of("isEnabled",false,"rounds",null),JSONObject.of("isEnabled",null,"rounds",null),JSONObject.of("isEnabled",true,"rounds",0),JSONObject.of("isEnabled",true,"rounds",2.5),JSONObject.of("isEnabled",true,"rounds",2147483648L))){var graph=graph();params(graph).put("enableChatHistoryV2",history);assertDoesNotThrow(()->compile(graph,fixture("openai")));}
    }
    @Test void privateBindingsAndUnconnectedMultimodalAreRejectedBeforeCatalog() {
        for(String key:List.of("credentialRef","credentialVersion","endpoint","multiMode")){var graph=graph();params(graph).put(key,key.equals("multiMode")?true:"fixture-private");var f=fixture("openai");assertThrows(ServiceException.class,()->compile(graph,f));verifyNoInteractions(f.mapper);}
    }
    @Test void historyReconstructionUsesOnlyStoredApprovedResourceAndRejectsExtraOrWrongProvider() {
        var graph=graph();params(graph).put("source","deepseek");var compiled=compile(graph,fixture("openai"));assertEquals(json(compiled.execution()),json(WorkflowModelCompiler.reconstruct(graph,compiled.resourceSnapshots(),DslCodec.V2,"java")));
        var resources=JSON.parseObject(json(compiled.resourceSnapshots()));resources.getJSONObject("model:1:version:7").put("provider","xinghuo");assertEquals(409,assertThrows(ServiceException.class,()->WorkflowModelCompiler.reconstruct(graph,resources,DslCodec.V2,"java")).getCode());
        resources.clear();resources.putAll(compiled.resourceSnapshots());resources.put("model:2:version:8",JSONObject.of("kind","model","provider","openai","version","8","modelName","other","enabled",true,"capabilities",new JSONArray(List.of("chat"))));assertEquals(409,assertThrows(ServiceException.class,()->WorkflowModelCompiler.reconstruct(graph,resources,DslCodec.V2,"java")).getCode());
    }
    @Test void unchangedGraphGuardsRejectFutureReferencesRetryAndFailureRoutingBeforeCatalog() {
        for(String mode:List.of("future","retry","failure","orphan")){var graph=graph();if(mode.equals("future"))node(graph).getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content").put("nodeId","node-end::e");if(mode.equals("retry"))node(graph).getJSONObject("data").put("retryConfig",JSONObject.of("shouldRetry",true));if(mode.equals("failure"))graph.getJSONArray("edges").getJSONObject(0).put("sourceHandle","intent_chain|fail_one_of");if(mode.equals("orphan"))graph.getJSONArray("edges").remove(0);var f=fixture("openai");assertThrows(ServiceException.class,()->compile(graph,f));verifyNoInteractions(f.mapper);}
    }
    @Test void currentCatalogVersionRaceStillRejectsBeforeReturningBinding() {
        var f=fixture("openai");var changed=new ModelRecord();changed.setId(1L);changed.setSpaceId(1L);changed.setDeleted(0);changed.setType(1);changed.setState("VALIDATED");changed.setEnabled(1);changed.setLatestVersionId(8L);when(f.mapper.find(1L)).thenReturn(f.current,changed);
        assertEquals(409,assertThrows(ServiceException.class,()->compile(graph(),f)).getCode());
    }
    @Test void nonFiniteOriginalExtraNumbersCannotBecomeNullableAuthorizedOptions() {
        for(String value:List.of("NaN","Infinity","-Infinity")){var graph=graph();params(graph).put("extraParams",JSONObject.of("temperature",value));var f=fixture("openai");assertEquals(422,assertThrows(ServiceException.class,()->compile(graph,f)).getCode());verifyNoInteractions(f.mapper);}
    }
    @Test void javaModelMissingSnapshotOrNonObjectParametersRemainExplicitlyRejected() {
        assertEquals(409,assertThrows(ServiceException.class,()->WorkflowModelCompiler.reconstruct(graph(),new JSONObject(),DslCodec.V2,"java")).getCode());
        var graph=graph();node(graph).getJSONObject("data").put("nodeParam",null);var f=fixture("openai");assertEquals(422,assertThrows(ServiceException.class,()->compile(graph,f)).getCode());verifyNoInteractions(f.mapper);
    }
}
