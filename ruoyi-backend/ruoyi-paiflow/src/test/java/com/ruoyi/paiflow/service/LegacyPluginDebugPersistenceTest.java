package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.*;
import com.ruoyi.paiflow.mapper.*;
import com.ruoyi.system.secret.ManagedSecretService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际固定版本→调试回执→Run/租约→隔离工具账本→持久事件→原页投影；非Provider或页面验收。 */
class LegacyPluginDebugPersistenceTest extends WorkflowDebugTestFixture {
    ToolMapper tools;
    ToolService toolService;
    ToolRuntimeCatalog catalog;
    ToolRuntimeProperties properties;
    ToolOperationService toolOperations;
    CompletedOutputSources completedSources;
    LegacyDebugBridgeService bridge;
    JSONObject original;
    final AtomicInteger sends=new AtomicInteger();

    static <T>org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
        var factory=new StaticListableBeanFactory();factory.addBean("existing",bean);
        return factory.getBeanProvider((Class<T>)bean.getClass());
    }
    @BeforeEach void toolSetup() throws Exception {
        try(var statement=schema.createStatement()) {
            for(String file:List.of("027-paiflow-dynamic-model-codec.sql","030-paiflow-tool.sql")) {
                String ddl=Files.readString(Path.of("sql",file)).replaceAll("(?m)^--.*$","")
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET ascii","")
                    .replace(" COLLATE ascii_bin","").replace("resource_id(191)","resource_id")
                    .replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace("DROP CHECK","DROP CONSTRAINT");
                for(String sql:ddl.split(";"))if(!sql.isBlank())statement.execute(sql);
            }
            int id=450;
            for(String permission:List.of("paiflow:tool:add","paiflow:tool:query","paiflow:tool:edit","paiflow:tool:invoke")) {
                statement.execute("INSERT INTO sys_menu VALUES("+(++id)+",'"+permission+"','0')");
                statement.execute("INSERT INTO sys_role_menu VALUES(88,"+id+")");
            }
        }
        var config=sessions.getSqlSessionFactory().getConfiguration();
        for(String name:List.of("ToolMapper","ModelOperationMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();
        }
        tools=sessions.getMapper(ToolMapper.class);properties=new ToolRuntimeProperties();
        catalog=new ToolRuntimeCatalog(tools,spaces,users,permissions,properties,mock(ManagedSecretService.class),sessions);
        var target=new ToolService(tools,access,identities);target.useDefaultSpaces(defaults);toolService=proxy(target);
        workflows.useToolCatalogs(provider(catalog));
        var operations=new ToolOperationService(tools,runMapper,spaces,leases,catalog);toolOperations=proxy(operations);
        ToolOperationService.Producer producer=(fixed,approval,parameters)->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            sends.incrementAndGet();return new ToolOperationService.Outcome(
                ManagedToolProducer.envelope(fixed,0,"Success",JSONObject.of("answer","隔离工具实际输出","count",0)),null,true);
        };
        operations.usePorts(provider(toolOperations),provider(producer));
        completedSources=new CompletedOutputSources(tools,sessions.getMapper(ModelOperationMapper.class),checkpointMapper);
        operations.useCompletedSources(completedSources);
        runs.useToolOperations(provider(toolOperations));
        bridge=proxy(new LegacyDebugBridgeService(workflows,defaults,access,runs,runMapper,checkpoints,checkpointMapper));
        try(var input=getClass().getResourceAsStream("/fixtures/tool-actual-consumer-source.json")) {
            assertNotNull(input);original=JSON.parseObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    JSONObject api() {
        var output=JSONObject.of("type","object","properties",JSONObject.of("answer",JSONObject.of("type","string"),"count",JSONObject.of("type","integer")),"required",JSONArray.of("answer","count"));
        var operation=JSONObject.of("operationId","lookup","parameters",JSONArray.of(
            JSONObject.of("in","query","name","question","schema",JSONObject.of("type","string","x-from",0)),
            JSONObject.of("in","path","name","business","schema",JSONObject.of("type","string","x-from",1))),
            "requestBody",JSONObject.of("content",JSONObject.of("application/json",JSONObject.of("schema",JSONObject.of("type","object","properties",JSONObject.of("defaulted",JSONObject.of("type","string","default","保留默认")))))),
            "responses",JSONObject.of("200",JSONObject.of("content",JSONObject.of("application/json",JSONObject.of("schema",output)))));
        return JSONObject.of("openapi","3.0.0","info",JSONObject.of("title","本地隔离工具","version","1.0.0"),
            "servers",JSONArray.of(JSONObject.of("url","https://provider.invalid")),"paths",JSONObject.of("/fixed",JSONObject.of("post",operation)));
    }
    JSONObject graph(JSONObject tool) {
        var graph=copy(original.getJSONObject("dsl"));
        var params=DataWorkerOperationService.find(graph,"plugin::cap").getJSONObject("data").getJSONObject("nodeParam");
        params.remove("toolResourceId");params.put("pluginId",tool.get("toolId"));
        for(Object raw:graph.getJSONArray("edges")) {
            var edge=(JSONObject)raw;edge.put("source",edge.remove("sourceNodeId"));edge.put("target",edge.remove("targetNodeId"));
        }
        return graph;
    }
    void approve(JSONObject tool) {
        var fixed=tools.byVersion(tool.getLongValue("id"),"V1.0");var p=new ToolRuntimeProperties.Profile();
        p.setEnabled(true);p.setToolId(tool.getLongValue("id"));p.setConfigurationHash(fixed.get("configurationHash").toString());
        p.setOperationId("lookup");p.setEffectClass("read");p.setEndpoint("https://provider.invalid/fixed");p.setPathPattern("/[^/]+");
        p.setAllowedUserIds(List.of(42L));p.setAllowedSpaceIds(List.of(space));p.setMaxCalls(1);
        p.setMaxRequestBytes(2*1024*1024);p.setMaxResponseBytes(2*1024*1024);p.setTimeoutSeconds(60);properties.setProfiles(List.of(p));
    }
    JSONObject frame(JSONObject task,String id,String finish,Object inputs,Object outputs) {
        var node=JSONObject.of("id",id,"alias_name",id,"finish_reason",finish,"inputs",inputs,"outputs",outputs);
        if(id.startsWith("node-end"))node.put("ext",JSONObject.of("answer_mode",0));
        return JSONObject.of("id",task.get("runId"),"code",0,"message","Success","workflow_step",JSONObject.of("node",node),
            "choices",JSONArray.of(JSONObject.of("index",0,"finish_reason",null,"delta",JSONObject.of("role","assistant","content","","reasoning_content",""))));
    }
    void append(JSONObject task,WorkerIdentityService.Principal worker,String id,String type,JSONObject frame) {
        if("java".equals(task.get("engineProfile")) && "node.completed".equals(type)) {
            var node=frame.getJSONObject("workflow_step").getJSONObject("node");
            long seq=runMapper.find(task.getString("runId")).getLastEventSeq()+1;
            leases.append(task.getString("runId"),worker,JSONObject.of("schemaVersion","paiflow.events.v1","runId",task.get("runId"),
                "leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"seq",seq,"eventType",type,"nodeId",id,
                "timestamp",clock.instant().toString(),"payload",JSONObject.of("nodeId",id,"result",JSONObject.of("inputs",node.get("inputs"),"outputs",node.get("outputs"),"status","SUCCESS","error",null))));
            type="text.delta";
        }
        long sequence=runMapper.find(task.getString("runId")).getLastEventSeq()+1;
        leases.append(task.getString("runId"),worker,JSONObject.of("schemaVersion","paiflow.events.v1","runId",task.get("runId"),
            "leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"seq",sequence,"eventType",type,"nodeId",id,
            "timestamp",clock.instant().toString(),"payload",JSONObject.of("legacyFrame",frame)));
    }
    @ParameterizedTest @ValueSource(strings={"python","java"})
    void actualDebugReceiptToolRunAndPersistedPageFrames(String engine) {
        var tool=toolService.create(Long.toString(space),JSONObject.of("name","基础工具 "+engine,"appId","legacy-label","effectClass","read","openApiSchema",api()),false);approve(tool);
        var canvas=graph(tool);var agent=createAgent("basic_"+engine,canvas);
        if("java".equals(engine)) {
            var row=workflowMapper.find(agent.getLongValue("workflowId"));
            workflows.save(row.getId(),space,row.getRevision(),JSONObject.of("engineProfile","java"));
        }
        var built=ordinary(agent,canvas,"basic_build_"+engine);
        assertEquals(engine,receipt(built).get("engineProfile"));assertEquals("python".equals(engine)?DslCodec.V4:DslCodec.V2,receipt(built).get("executionCodec"));
        var body=JSONObject.of("flow_id",agent.get("flowId"),"inputs",original.get("input"),"chatId","basic_chat_"+engine,
            "regen",false,"debugReceipt",receipt(built),"requestKey","basic_request_"+engine);
        var attachment=assertDoesNotThrow(()->bridge.chat(body,Long.toString(space),null));
        var selected=new WorkerIdentityService.Principal("basic-worker-"+engine,engine);var task=leases.claim(selected);
        assertEquals(attachment.runId(),task.get("runId"));assertEquals(engine,task.get("engineProfile"));
        assertEquals(receipt(built).get("workflowVersionId"),task.get("workflowVersionId"));
        assertTrue(leases.beginExecution(attachment.runId(),selected,task.getString("leaseId"),task.getIntValue("attempt")).getBooleanValue("begun"));
        append(task,selected,"node-start::s","node.completed",frame(task,"node-start::s","stop",
            "java".equals(engine)?JavaToolSemantics.startPool(task.getJSONObject("input")):new JSONObject(),"java".equals(engine)?new JSONObject():task.get("input")));
        var node=DataWorkerOperationService.find(task.getJSONObject("dsl"),"plugin::cap");
        String resource=node.getJSONObject("data").getJSONObject("nodeParam").getString("toolResourceId");var descriptor=task.getJSONObject("resourceSnapshots").getJSONObject(resource);
        var accepted=new JSONArray(leasesEvents(attachment.runId()));
        var values=completedSources.values(runMapper.find(attachment.runId()),task.getJSONObject("dsl"),node,accepted);
        var payload="java".equals(engine)?JavaToolSemantics.expected(node,values,descriptor):ToolSchema.expected(node,values,descriptor);
        var request=JSONObject.of("leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"nodeId","plugin::cap","iterationId",null,
            "operation","tools.read","idempotencyKey","basic_tool_operation","parameters",JSONObject.of("resourceId",resource,"resourceVersion",descriptor.get("version"),
            "kind","tool","provider","spark-link","resourceBindings",new JSONObject(),"legacyPayload",payload));
        assertEquals("COMPLETED",toolOperations.start(attachment.runId(),selected,request).get("state"));assertEquals(1,sends.get());
        append(task,selected,"plugin::cap","node.completed",frame(task,"plugin::cap","stop",new JSONObject(values),JSONObject.of("answer","隔离工具实际输出","count",0)));
        append(task,selected,"node-end::e","node.completed",frame(task,"node-end::e","stop","java".equals(engine)?new JSONObject():JSONObject.of("answer","隔离工具实际输出"),JSONObject.of("answer","隔离工具实际输出")));
        leases.result(attachment.runId(),selected,result(task,"COMPLETED",JSONObject.of("answer","隔离工具实际输出")));
        var page=bridge.page(attachment,0);assertTrue(page.terminal());assertEquals(4,page.frames().size());
        assertEquals(List.of("node-start::s","plugin::cap","node-end::e","flow_obj"),page.frames().stream().map(f->f.data().getJSONObject("workflow_step").getJSONObject("node").getString("id")).toList());
        for(int i=0;i<3;i++)assertNull(page.frames().get(i).data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));
        assertEquals("stop",page.frames().get(3).data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));
        assertEquals("隔离工具实际输出",page.frames().get(2).data().getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs").get("answer"));
    }
    List<JSONObject> leasesEvents(String id){return runMapper.events(id,0,100).stream().map(JSON::parseObject).toList();}
    @org.junit.jupiter.api.Test void fixedToolGateRejectsChangedIdentityMissingUnusedResourceAndBadEngineWithoutRun() {
        var tool=toolService.create(Long.toString(space),JSONObject.of("name","工具流绑定反例","appId","legacy-label","effectClass","read","openApiSchema",api()),false);approve(tool);
        var canvas=graph(tool);var agent=createAgent("tool_gate",canvas);var built=ordinary(agent,canvas,"tool_gate_build");
        var fixed=workflows.requireDebugReceipt(receipt(built),space).snapshot();
        var execution=fixed.execution();var resources=fixed.resourceSnapshots();
        assertDoesNotThrow(()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,resources,original.getJSONObject("input"),"python"));
        for(String field:List.of("pluginId","operationId","appId","version","toolResourceId")) {
            var changed=copy(execution);DataWorkerOperationService.find(changed,"plugin::cap").getJSONObject("data").getJSONObject("nodeParam").put(field,"forged");
            assertThrows(com.ruoyi.common.exception.ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(changed,resources,original.getJSONObject("input"),"python"),field);
        }
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,new JSONObject(),original.getJSONObject("input"),"python"));
        var unused=copy(resources);unused.put("unused-tool",resources.values().iterator().next());
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,unused,original.getJSONObject("input"),"python"));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(execution,resources,original.getJSONObject("input"),"worker-selected"));
        var unsupported=copy(execution);unsupported.getJSONArray("nodes").add(JSONObject.of("id","tts::unsupported"));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->LegacyWorkflowStreamCodec.requireSupportedExecution(unsupported,resources,original.getJSONObject("input"),"python"));
        assertEquals(0,sends.get());
    }
}
