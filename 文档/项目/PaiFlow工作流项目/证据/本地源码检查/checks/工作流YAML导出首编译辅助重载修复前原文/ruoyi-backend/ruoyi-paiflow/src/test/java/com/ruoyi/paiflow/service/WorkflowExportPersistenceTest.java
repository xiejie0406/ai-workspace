package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowExportController;
import com.ruoyi.paiflow.domain.WorkflowRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 随机H2真实平台菜单/Mapper/事务；不执行Provider、导入、编译或派发。 */
class WorkflowExportPersistenceTest extends WorkflowDebugTestFixture {
    private WorkflowExportService exports;
    private static final int MIB=1024*1024;
    @BeforeEach void setupExport(){exports=proxy(new WorkflowExportService(workflows,defaults,access));}
    private String header(){return Long.toString(space);}
    private long create(JSONObject graph){return workflows.create(space,JSONObject.of("name","export_"+java.util.UUID.randomUUID(),"data",graph)).getLongValue("id");}
    private static JSONObject empty(){return JSONObject.of("nodes",new JSONArray(),"edges",new JSONArray());}
    private static JSONObject node(JSONObject graph){return graph.getJSONArray("nodes").getJSONObject(0);}
    private static JSONObject parsed(byte[] bytes) {
        var options=new LoaderOptions();options.setCodePointLimit(40*MIB);
        Object value=new Yaml(new SafeConstructor(options)).load(new String(bytes,StandardCharsets.UTF_8));
        assertInstanceOf(Map.class,value);return JSON.parseObject(JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue));
    }
    private JSONObject exported(long id){return parsed(exports.export(id,header()).bytes());}
    private static int denied(Runnable call){return assertThrows(ServiceException.class,call::run).getCode();}
    private JSONObject truth(long id)throws Exception {
        var row=workflowMapper.find(id);return JSONObject.of("canvas",row.getCanvasJson(),"metadata",row.getMetadataJson(),"advanced",row.getAdvancedConfigJson(),
                "revision",row.getRevision(),"latestVersionId",row.getLatestVersionId(),"workflowCount",rows("paiflow_workflow"),"versionCount",rows("paiflow_workflow_version"),
                "runCount",rows("paiflow_run"),"eventCount",rows("paiflow_run_event"),"checkpointCount",rows("paiflow_run_checkpoint"),"spaces",rows("paiflow_space"));
    }
    private void stored(long id,String field,String value)throws Exception {
        assertTrue(Set.of("canvas_json","metadata_json","advanced_config_json").contains(field));
        try(var connection=db.getConnection();var statement=connection.prepareStatement("UPDATE paiflow_workflow SET "+field+"=? WHERE id=?")) {
            statement.setString(1,value);statement.setLong(2,id);assertEquals(1,statement.executeUpdate());
        }sessions.clearCache();
    }
    private WorkflowExportService afterRead(Runnable action) {
        var reads=new AtomicInteger();
        var real=new WorkflowService(workflowMapper,access,identities) {
            @Override public WorkflowRecord require(long id,Long requestedSpace,String permission,int role,boolean lock) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                WorkflowRecord row=super.require(id,requestedSpace,permission,role,lock);
                if(reads.incrementAndGet()==1)action.run();return row;
            }
        };
        return proxy(new WorkflowExportService(real,defaults,access));
    }
    private void checkedSql(String sql) {try {assertEquals(1,sql(sql));}catch(Exception ex){throw new AssertionError(ex);}}

    @Test void actualCurrentDraftExportsSevenMetadataFieldsAndNeverSelectsLatestOrWritesRuntime()throws Exception {
        var original=canvas();var created=workflows.create(space,JSONObject.of("name","中文😀工作流","description","原说明","data",original,
                "avatarIcon","原图标","avatarColor","#0a0a0a","edgeType","bezier","category","3","advancedConfig",JSONObject.of("flag",false),"futureMetadata","不得出包"));
        long id=created.getLongValue("id");var version=workflows.createVersion(id,space,0L,new JSONObject());
        var changed=copy(original);changed.put("viewport",JSONObject.of("x",0,"y",false,"future",null));node(changed).getJSONObject("data").put("label","当前草稿");
        long revision=workflowMapper.find(id).getRevision();workflows.save(id,space,revision,JSONObject.of("data",changed));var before=truth(id);
        var value=exported(id);var meta=value.getJSONObject("flowMeta");
        assertEquals(Set.of("name","description","avatarIcon","avatarColor","edgeType","category","advancedConfig","dslVersion"),meta.keySet());
        assertEquals("v1",meta.get("dslVersion"));assertEquals(3,meta.get("category"));assertEquals("中文😀工作流",meta.get("name"));assertEquals("{\"flag\":false}",meta.get("advancedConfig"));
        assertEquals(changed,value.getJSONObject("flowData"));assertFalse(new String(exports.export(id,header()).bytes(),StandardCharsets.UTF_8).contains("futureMetadata"));
        assertEquals(version.getLongValue("id"),workflowMapper.find(id).getLatestVersionId());assertEquals(before,truth(id));
        var converter=new JacksonJsonHttpMessageConverter(JsonMapper.builder().findAndAddModules().build());
        var mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowExportController(exports)).setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(converter).build();
        var response=mvc.perform(get("/paiflow/legacy/workflow/export/"+id).header("space-id",header()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM)).andReturn().getResponse();
        byte[] actual=response.getContentAsByteArray();assertEquals(value,parsed(actual));assertEquals("UTF-8",response.getCharacterEncoding());
        assertEquals(Integer.toString(actual.length),response.getHeader("Content-Length"));assertEquals("attachment; filename*=UTF-8''workflow-"+id+".yaml",response.getHeader("Content-Disposition"));
        assertEquals("Content-Disposition",response.getHeader("Access-Control-Expose-Headers"));assertTrue(response.isCommitted());var after=truth(id);assertEquals(before,after);
        var headers=new JSONObject();for(String field:response.getHeaderNames())headers.put(field,new JSONArray(response.getHeaders(field)));
        var artifact=JSONObject.of("scope","isolated H2 + actual Workflow/Space Mapper, platform Menu/User reads, real WorkflowExportService and MockMVC Controller; no live Centre/Provider/browser",
                "authenticationScope","existing fixture loginAs/login controls the principal; no full JWT/Redis/filter authentication chain",
                "originalGolden","8e7b99afc4e99781d7d9c9bfeb4786f4339203623eabd854d7ff9fb3bb0e97c8",
                "actualRequest",JSONObject.of("method","GET","path","/paiflow/legacy/workflow/export/"+id,"spaceHeader",header()),
                "actualStatus",response.getStatus(),"actualContentType",response.getContentType(),"actualCharacterEncoding",response.getCharacterEncoding(),"actualHeaders",headers,
                "actualBodyUTF8",response.getContentAsString(StandardCharsets.UTF_8),"bodyUTF8Base64",Base64.getEncoder().encodeToString(actual),
                "bodySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)),"bodyUTF8Bytes",actual.length,
                "workflowId",id,"spaceId",space,"actorId",identities.current().getUserId(),"currentRevision",workflowMapper.find(id).getRevision(),"selectedVersionId",workflowMapper.find(id).getLatestVersionId(),
                "beforeTruth",before,"afterTruth",after,"zeroBusinessWrites",true,"businessVerified",false);
        Path destination=Path.of("target/workflow-export-actual-response.json");Files.createDirectories(destination.getParent());Files.writeString(destination,artifact.toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
    @Test void actualOriginalGoldenKnownFieldsRetainTypedNumbersBooleansAndPropertyDefaultStrings()throws Exception {
        var golden=JSON.parseObject(Files.readString(Path.of("../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/工作流原YAML导出合同金样.json"),StandardCharsets.UTF_8));
        Set<String> cases=Set.of("empty-arrays-seven-metadata","all-null-metadata-and-empty-dto-lists","typed-node-width-boolean-string-coercion","typed-property-default-coercion");int checked=0;
        for(Object raw:golden.getJSONArray("fixtures")) {
            var fixture=(JSONObject)raw;if(!cases.contains(fixture.getString("id")))continue;
            var graph=fixture.getJSONObject("canvas");long id=create(graph);var before=truth(id);
            JSONObject expected=golden.getJSONArray("actualOriginalProjection").stream().map(v->(JSONObject)v).filter(v->fixture.get("id").equals(v.get("id"))).findFirst().orElseThrow().getJSONObject("parsedYaml").getJSONObject("flowData");
            assertEquals(expected,exported(id).getJSONObject("flowData"),fixture.getString("id"));assertEquals(before,truth(id));checked++;
        }assertEquals(4,checked);
    }
    @Test void approvedUnknownLayoutNullableAndOpenObjectExtensionsArePreservedWithoutChangingStoredBytes()throws Exception {
        var graph=canvas();graph.put("viewport",JSONObject.of("x",0,"future",null));graph.put("futureRoot",JSONObject.of("nil",null,"flag",false));
        var n=node(graph);n.put("futureNode",null);n.put("position",JSONObject.of("x",0,"y",false,"nil",null));var data=n.getJSONObject("data");data.put("futureData",JSONObject.of("future",null));
        var input=data.getJSONArray("outputs").getJSONObject(0);input.put("futureInput",null);var schema=input.getJSONObject("schema");schema.put("default",JSONObject.of("uid","business UID","userId",42,"runId","business Run","nil",null,"false",false,"zero",0));
        schema.put("item",JSONObject.of("open",null));schema.put("futureSchema",null);schema.put("value",JSONObject.of("type","literal","content",JSONObject.of("unknown",null,"value",0),"futureValue",false));
        schema.put("properties",JSONArray.of(JSONObject.of("name","p","type","string","default",123,"required","false","futureProperty",null)));
        long id=create(graph);String raw=workflowMapper.find(id).getCanvasJson();var value=exported(id).getJSONObject("flowData");
        var expected=copy(graph);node(expected).getJSONObject("data").getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").getJSONArray("properties").getJSONObject(0).put("default","123");
        node(expected).getJSONObject("data").getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").getJSONArray("properties").getJSONObject(0).put("required",false);
        assertEquals(expected,value);assertEquals(raw,workflowMapper.find(id).getCanvasJson());assertTrue(value.getJSONObject("futureRoot").containsKey("nil"));
    }
    @Test void onlyExactInitiatorUidIsClearedInExportCopyWithChineseSafePathComments()throws Exception {
        var graph=canvas();var parameters=node(graph).getJSONObject("data").getJSONObject("nodeParam");parameters.put("uid","fixture-secret-identity-never-in-package");parameters.put("appId","nonsecret-app-reference");
        parameters.put("business",JSONObject.of("uid","business uid","userId",42,"runId","business-run"));
        graph.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam").put("uid","  ");graph.getJSONArray("nodes").getJSONObject(2).getJSONObject("data").getJSONObject("nodeParam").put("uid",null);
        long id=create(graph);var before=truth(id);byte[] bytes=exports.export(id,header()).bytes();String yaml=new String(bytes,StandardCharsets.UTF_8);var value=parsed(bytes).getJSONObject("flowData");
        assertTrue(yaml.startsWith("# 已清空节点发起者UID，导入后由当前中心身份确定\n# flowData/nodes/0/data/nodeParam/uid\n"));assertFalse(yaml.contains("fixture-secret-identity"));
        assertEquals("",node(value).getJSONObject("data").getJSONObject("nodeParam").get("uid"));assertEquals(parameters.get("business"),node(value).getJSONObject("data").getJSONObject("nodeParam").get("business"));
        assertEquals("nonsecret-app-reference",node(value).getJSONObject("data").getJSONObject("nodeParam").get("appId"));assertEquals("",value.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam").get("uid"));
        assertTrue(yaml.contains("# flowData/nodes/1/data/nodeParam/uid\n"));
        assertTrue(value.getJSONArray("nodes").getJSONObject(2).getJSONObject("data").getJSONObject("nodeParam").containsKey("uid"));assertNull(value.getJSONArray("nodes").getJSONObject(2).getJSONObject("data").getJSONObject("nodeParam").get("uid"));assertEquals(before,truth(id));
        parameters.put("uid",17);stored(id,"canvas_json",graph.toJSONString(JSONWriter.Feature.WriteMapNullValue));var ex=assertThrows(ServiceException.class,()->exports.export(id,header()));assertEquals(400,ex.getCode());assertTrue(ex.getMessage().contains("flowData/nodes/0/data/nodeParam/uid"));
    }
    @Test void resourceReferencesAndOpaqueBusinessIdsDoNotRequireAProviderOrCreateAnAppRelation()throws Exception {
        var graph=canvas();var p=node(graph).getJSONObject("data").getJSONObject("nodeParam");p.put("appId","not-an-authorized-app");p.put("modelId",99);p.put("llmId",77);p.put("repoId","fixture-repo");p.put("serviceId","fixture-service");
        p.put("httpInput",JSONObject.of("uid","business-body","runId",0,"userId",null));long id=create(graph);var before=truth(id);
        assertEquals(graph,exported(id).getJSONObject("flowData"));assertEquals(before,truth(id));assertEquals(0,rows("paiflow_workflow_version"));assertEquals(0,rows("paiflow_run"));
    }
    @Test void malformedStoredCanvasAndTypedFieldsRejectWithoutLeakingValuesOrWritingAnything()throws Exception {
        long id=create(empty());
        for(String raw:List.of("null","{\"nodes\":null,\"edges\":[]}","{\"nodes\":[null],\"edges\":[]}","{\"nodes\":[{\"id\":\"x\",\"width\":\"not-a-width\"}],\"edges\":[]}","[]","{broken")) {
            stored(id,"canvas_json",raw);var before=truth(id);assertEquals(400,denied(()->exports.export(id,header())));assertEquals(before,truth(id));
        }
        stored(id,"canvas_json",empty().toJSONString());stored(id,"metadata_json","[]");assertEquals(400,denied(()->exports.export(id,header())));assertEquals(400,denied(()->exports.export(0,header())));
    }
    @Test void credentialsInCanvasMetadataAndEncodedAdvancedConfigAreRejectedWithOnlyPaths()throws Exception {
        long id=create(empty());
        for(String field:List.of("canvas_json","metadata_json","advanced_config_json")) {
            String original=switch(field){case "canvas_json"->workflowMapper.find(id).getCanvasJson();case "metadata_json"->workflowMapper.find(id).getMetadataJson();default->workflowMapper.find(id).getAdvancedConfigJson();};
            var bad=field.equals("canvas_json")?empty():new JSONObject();bad.put("nested",JSONObject.of("apiKey","fixture-credential-must-never-echo"));stored(id,field,bad.toJSONString());var before=truth(id);
            var ex=assertThrows(ServiceException.class,()->exports.export(id,header()));assertEquals(400,ex.getCode());assertTrue(ex.getMessage().contains("/nested/apiKey"));assertFalse(ex.getMessage().contains("fixture-credential"));assertEquals(before,truth(id));stored(id,field,original);
        }
    }
    @Test void defaultScopeReadsNeverMaterializeOrFallBackToNamedAndExistingDefaultExportsNormally()throws Exception {
        long id=create(empty()),count=rows("paiflow_space");assertEquals(404,denied(()->exports.export(id,"")));assertEquals(404,denied(()->exports.export(id,null)));assertEquals(count,rows("paiflow_space"));
        var agent=agents.legacyCreate("",3,JSONObject.of("name","default_export","data",canvas()));long defaultWorkflow=agent.getLongValue("workflowId");
        assertNotEquals(space,workflowMapper.find(defaultWorkflow).getSpaceId());long initialized=rows("paiflow_space");assertNotNull(parsed(exports.export(defaultWorkflow,"").bytes()).getJSONObject("flowData"));assertEquals(initialized,rows("paiflow_space"));
        assertEquals(404,denied(()->exports.export(defaultWorkflow,header())));assertEquals(404,denied(()->exports.export(id,Long.toString(space+1000))));
    }
    @Test void queryOnlyMemberCanReadButCurrentPermissionAndMembershipAreStillRequired()throws Exception {
        long id=create(empty());assertEquals(1,spaces.insertMember(space,43,3,"fixture"));checkedSql("INSERT INTO sys_user_role VALUES(43,88)");loginAs(43);
        revoke("paiflow:workflow:edit");revoke("paiflow:workflow:create");assertNotNull(exports.export(id,header()));
        revoke("paiflow:workflow:query");assertEquals(403,denied(()->exports.export(id,header())));checkedSql("UPDATE sys_menu SET status='0' WHERE perms='paiflow:workflow:query'");
        checkedSql("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+space+" AND user_id=43");assertEquals(404,denied(()->exports.export(id,header())));assertEquals(0,rows("paiflow_run"));
    }
    @Test void finalCurrentSqlAuthorityAndActorChecksRejectChangesAfterTheActualRead()throws Exception {
        long id=create(empty());var before=truth(id);
        var permissionRace=afterRead(()->checkedSql("UPDATE sys_menu SET status='1' WHERE perms='paiflow:workflow:query'"));assertEquals(403,denied(()->permissionRace.export(id,header())));assertEquals(before,truth(id));checkedSql("UPDATE sys_menu SET status='0' WHERE perms='paiflow:workflow:query'");
        var memberRace=afterRead(()->checkedSql("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+space+" AND user_id=42"));assertEquals(404,denied(()->memberRace.export(id,header())));checkedSql("UPDATE paiflow_space_member SET status='active' WHERE space_id="+space+" AND user_id=42");
        assertEquals(1,spaces.insertMember(space,43,3,"fixture"));checkedSql("INSERT INTO sys_user_role VALUES(43,88)");
        var actorRace=afterRead(()->loginAs(43));assertEquals(403,denied(()->actorRace.export(id,header())));assertEquals(before,truth(id));
    }
    @Test void finalWorkflowDeletionOrDomainChangeRejectsSnapshotDespitePreviouslyVisibleColumns()throws Exception {
        long id=create(empty());var deletion=afterRead(()->checkedSql("UPDATE paiflow_workflow SET deleted=1 WHERE id="+id));assertEquals(404,denied(()->deletion.export(id,header())));checkedSql("UPDATE paiflow_workflow SET deleted=0 WHERE id="+id);
        var foreign=afterRead(()->checkedSql("UPDATE paiflow_workflow SET space_id="+(space+1000)+" WHERE id="+id));assertEquals(404,denied(()->foreign.export(id,header())));assertEquals(0,rows("paiflow_run"));assertEquals(0,rows("paiflow_workflow_version"));
    }
    @Test void boundedUtf8YamlStopsIndentationAmplificationAndWrapperOverflowWithoutChangingDraft()throws Exception {
        long id=create(empty());var graph=empty();JSONObject tail=graph;
        for(int i=0;i<40;i++){var next=new JSONObject();tail.put("nested",next);tail=next;}tail.put("text","a\n".repeat(450_000));
        assertTrue(graph.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8).length<16*MIB);stored(id,"canvas_json",graph.toJSONString(JSONWriter.Feature.WriteMapNullValue));var before=truth(id);
        assertEquals(413,denied(()->exports.export(id,header())));assertEquals(before,truth(id));
        var oversized=empty();oversized.put("future","x".repeat(15*MIB));var advanced=JSONObject.of("text","\"".repeat(5*MIB));
        assertTrue(oversized.toJSONString().getBytes(StandardCharsets.UTF_8).length<16*MIB);assertTrue(advanced.toJSONString().getBytes(StandardCharsets.UTF_8).length<16*MIB);
        stored(id,"canvas_json",oversized.toJSONString());stored(id,"advanced_config_json",advanced.toJSONString());before=truth(id);assertEquals(413,denied(()->exports.export(id,header())));assertEquals(before,truth(id));
        stored(id,"canvas_json",empty().toJSONString());stored(id,"advanced_config_json","{}");var bytes=exports.export(id,header()).bytes();var copy=new WorkflowExportService.Export(bytes);bytes[0]='!';assertNotEquals('!',copy.bytes()[0]);var leaked=copy.bytes();leaked[0]='?';assertNotEquals('?',copy.bytes()[0]);
    }
}
