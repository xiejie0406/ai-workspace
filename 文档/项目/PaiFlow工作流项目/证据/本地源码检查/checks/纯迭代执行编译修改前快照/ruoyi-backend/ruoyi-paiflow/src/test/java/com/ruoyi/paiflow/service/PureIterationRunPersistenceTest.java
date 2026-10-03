package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实Run/Space Mapper与RC事务；固定Workflow快照、用户权限是隔离夹具，不代表页面或Provider联机。 */
public class PureIterationRunPersistenceTest {
    private JdbcDataSource db;private java.sql.Connection schema;private RunMapper mapper;private SpaceMapper spaces;
    private WorkflowService workflows;private RunService runs;private RunLeaseService leases;private ModelOperationService modelOperations;
    private JSONObject fixture,graph,input;private long space;
    private final WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("isolated-pure-iteration","python");
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    @SuppressWarnings("unchecked") private <T>T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    @BeforeEach void setup() throws Exception {
        fixture=JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/pure-iteration.json"),StandardCharsets.UTF_8));
        graph=copy(fixture.getJSONObject("baseline").getJSONObject("dsl"));input=copy(fixture.getJSONObject("baseline").getJSONObject("input"));
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE");schema=db.getConnection();
        // H2 2.4.240 CHECK常量集合持有DDL session；保留连接至每项结束，不删除真实约束。
        try(var sql=schema.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.PureIterationRunPersistenceTest.utcNow'");
            for(String file:List.of("001-paiflow-schema.sql","003-paiflow-run.sql","006-paiflow-execution-start.sql","013-paiflow-model-operation.sql")) {
                String ddl=Files.readString(Path.of("sql",file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET ascii","")
                    .replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END")
                    .replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END");
                for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var config=new Configuration(new Environment("pure-iteration-isolated",new SpringManagedTransactionFactory(),db));
        for(String name:List.of("SpaceMapper","RunMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){assertNotNull(xml);new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();}
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=session.getMapper(RunMapper.class);spaces=session.getMapper(SpaceMapper.class);
        var s=new SpaceRecord();s.setName("pure iteration source");s.setKind("personal");s.setOwnerUserId(42L);s.setCreateBy("fixture");s.setUpdateBy("fixture");assertEquals(1,spaces.insert(s));space=s.getId();assertEquals(1,spaces.insertOwner(space,42,"fixture"));
        var user=new SysUser();user.setUserId(42L);user.setUserName("fixture");user.setStatus("0");user.setDelFlag("0");
        var identities=mock(IdentityMappingService.class);when(identities.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));
        var platform=mock(PermissionService.class);when(platform.hasPermi(anyString())).thenReturn(true);var access=new ResourceAccessService(identities,platform,spaces);access.useCurrentSession(session);
        workflows=mock(WorkflowService.class);modelOperations=mock(ModelOperationService.class);var target=new RunService(workflows,mapper,access,identities);
        @SuppressWarnings("unchecked") ObjectProvider<ModelOperationService> models=mock(ObjectProvider.class);when(models.getIfAvailable()).thenReturn(modelOperations);target.useModelOperations(models);runs=proxy(target);
        var users=mock(ISysUserService.class);when(users.selectUserById(42L)).thenReturn(user);var menus=mock(SysPermissionService.class);when(menus.getMenuPermission(user)).thenReturn(Set.of("paiflow:run:create"));
        var lease=new RunLeaseService(mapper,spaces,users,menus,Clock.systemUTC());lease.useCurrentSession(session);leases=proxy(lease);snapshot(graph);
    }
    @AfterEach void close() throws Exception {if(schema!=null){try(var sql=schema.createStatement()){sql.execute("SHUTDOWN");}finally{schema.close();}}}
    private static JSONObject copy(JSONObject value){return JSON.parseObject(value.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));}
    private void snapshot(JSONObject value){var n=new DslCodec().normalize(value);when(workflows.runtimeSnapshot(2)).thenReturn(new WorkflowService.RuntimeSnapshot(1,2,space,42,"fixed-original-flow","python",n.executionHash(),DslCodec.canonicalDslBase64(n.execution()),n.execution(),n.original(),new JSONObject(),new JSONObject(),new JSONObject()));}
    private RunService.View create(JSONObject value,JSONObject actual){snapshot(value);return runs.create(2,"python",actual,"iteration_"+UUID.randomUUID().toString().replace("-",""),300);}
    private long rows(String table)throws Exception{try(var c=db.getConnection();var sql=c.createStatement();var r=sql.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}}
    private void rejected(JSONObject value,JSONObject actual,int code)throws Exception {
        long before=rows("paiflow_run");assertEquals(code,assertThrows(ServiceException.class,()->create(value,actual)).getCode());assertEquals(before,rows("paiflow_run"));
        assertEquals(0,rows("paiflow_run_event"));assertEquals(0,rows("paiflow_model_operation"));verifyNoInteractions(modelOperations);
    }
    private static JSONObject node(JSONObject dsl,String id){return dsl.getJSONArray("nodes").stream().map(o->(JSONObject)o).filter(n->id.equals(n.get("id"))).findFirst().orElseThrow();}
    private static JSONObject data(JSONObject dsl,String id){return node(dsl,id).getJSONObject("data");}
    private static void edge(JSONObject dsl,String from,String to){dsl.getJSONArray("edges").add(JSONObject.of("sourceNodeId",from,"targetNodeId",to));}
    private static JSONObject pure(String id){return JSONObject.of("id",id,"data",JSONObject.of("nodeMeta",JSONObject.of("nodeType",id.split("::")[0],"aliasName",id),"inputs",new JSONArray(),"outputs",new JSONArray(),"nodeParam",JSONObject.of("mode",0,"prompt","")));}
    private static JSONArray batch(int size){var array=new JSONArray();for(int i=0;i<size;i++)array.add("item"+i);return array;}
    private static JSONObject startOutput(JSONObject dsl){return data(dsl,"node-start::s").getJSONArray("outputs").getJSONObject(0);}
    private static JSONObject ref(JSONObject dsl,String id){return data(dsl,id).getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content");}
    private void innerChain(JSONObject dsl,int added){dsl.getJSONArray("edges").removeIf(raw->"text-joiner::j".equals(((JSONObject)raw).get("sourceNodeId")));String previous="text-joiner::j";for(int i=0;i<added;i++){String id="text-joiner::chain"+i;dsl.getJSONArray("nodes").add(pure(id));edge(dsl,previous,id);previous=id;}edge(dsl,previous,"iteration-node-end::ie");}

    @Test void sharedFactorySampleQueuesPinnedReadOnlyTaskAndObtainsOneExecutionPermit() throws Exception {
        var expected=fixture.getJSONObject("baseline").getJSONObject("expectedStats");var metrics=PureIterationPolicy.validate(graph,input);
        assertEquals(expected.getIntValue("expandedNodes"),metrics.expandedNodes());assertEquals(expected.getIntValue("pathCount"),metrics.pathCount());assertEquals(expected.getIntValue("maxPathLength"),metrics.maxPathLength());
        var created=create(graph,input);assertEquals("QUEUED",created.state());var saved=mapper.find(created.runId());var assignment=JSON.parseObject(saved.getAssignmentJson());
        assertEquals(input,assignment.getJSONObject("input"));assertEquals(graph,assignment.getJSONObject("dsl"));assertEquals("read_only",assignment.getJSONObject("effectPolicy").get("mode"));assertTrue(assignment.getJSONObject("resourceSnapshots").isEmpty());assertNull(saved.getModelPermitJson());
        var task=leases.claim(worker);assertEquals(created.runId(),task.getString("runId"));assertEquals(1,task.getIntValue("attempt"));assertTrue(leases.beginExecution(created.runId(),worker,task.getString("leaseId"),1).getBooleanValue("begun"));
        assertEquals(409,assertThrows(ServiceException.class,()->leases.beginExecution(created.runId(),worker,task.getString("leaseId"),1)).getCode());assertNull(leases.claim(worker));assertEquals(1,rows("paiflow_run"));verifyNoInteractions(modelOperations);
    }
    @Test void allFifteenOriginalFactoryCasesUseSharedAdmissionWithoutInventingFactorySuccess() throws Exception {
        int admitted=0;for(Object raw:fixture.getJSONArray("cases")){var c=(JSONObject)raw;if(c.getBooleanValue("expectedAdmission")){create(c.getJSONObject("dsl"),c.getJSONObject("input"));admitted++;}else rejected(c.getJSONObject("dsl"),c.getJSONObject("input"),400);}
        assertEquals(15,fixture.getJSONArray("cases").size());assertEquals(admitted,rows("paiflow_run"));assertEquals(12,admitted);verifyNoInteractions(modelOperations);
    }
    @Test void batchSixtyFourAcceptedAndSixtyFiveRejectedWithZeroExtraWrites() throws Exception {assertEquals(195,PureIterationPolicy.validate(graph,JSONObject.of("items",batch(64))).expandedNodes());create(graph,JSONObject.of("items",batch(64)));rejected(graph,JSONObject.of("items",batch(65)),413);}
    @Test void defaultArrayLengthCountsAndRequiredStillRejectsMissingInput() throws Exception {
        var optional=copy(graph);startOutput(optional).put("required",false);startOutput(optional).getJSONObject("schema").put("default",batch(65));rejected(optional,new JSONObject(),413);
        startOutput(optional).getJSONObject("schema").put("default",batch(64));assertEquals(64,PureIterationPolicy.validate(optional,new JSONObject()).batchSizes().get("iteration::i"));create(optional,new JSONObject());
        startOutput(optional).put("required",true);rejected(optional,new JSONObject(),400);
    }
    @Test void duplicateEdgesDoNotMultiplyOriginalChainsPaths() throws Exception {edge(graph,"iteration-node-start::is","text-joiner::j");edge(graph,"node-start::s","iteration::i");assertEquals(2,PureIterationPolicy.validate(graph,input).pathCount());create(graph,input);}
    @Test void crossContainerEdgesCannotTurnInnerNodesIntoMainGraph() throws Exception {edge(graph,"iteration::i","iteration-node-start::is");rejected(graph,input,400);}
    @Test void orphanPureNodeIsNotAcceptedByPassingOrdinaryDagValidation() throws Exception {graph.getJSONArray("nodes").add(pure("text-joiner::orphan"));assertTrue(new DslValidationService().validate(graph,"python").valid());rejected(graph,input,400);}
    @Test void ordinaryCycleNeverCreatesRunOrEvent() throws Exception {edge(graph,"iteration-node-end::ie","text-joiner::j");rejected(graph,input,400);}
    @Test void uniqueInternalStartAndEndAreActualScopeRequirements() throws Exception {
        var missing=copy(graph);data(missing,"iteration::i").getJSONObject("nodeParam").put("IterationStartNodeId","iteration-node-start::missing");rejected(missing,input,400);
        var duplicate=copy(graph);duplicate.getJSONArray("nodes").add(pure("iteration-node-end::second"));edge(duplicate,"text-joiner::j","iteration-node-end::second");rejected(duplicate,input,400);
    }
    @Test void detachedIterationCannotOwnAnUnreachableInnerGraph() throws Exception {graph.getJSONArray("edges").removeIf(raw->"iteration::i".equals(((JSONObject)raw).get("targetNodeId")));edge(graph,"node-start::s","node-end::e");rejected(graph,input,400);}
    @Test void nestedIterationAndAnyResourceOrGlobalVariableStayUnavailable() throws Exception {
        for(String type:List.of("iteration","spark-llm","plugin","node-variable","question-answer")){var changed=copy(graph);var j=node(changed,"text-joiner::j");String target=type+"::j";j.put("id",target);changed.getJSONArray("edges").forEach(raw->{var e=(JSONObject)raw;if("text-joiner::j".equals(e.get("sourceNodeId")))e.put("sourceNodeId",target);if("text-joiner::j".equals(e.get("targetNodeId")))e.put("targetNodeId",target);});ref(changed,"iteration-node-end::ie").put("nodeId",target);rejected(changed,input,503);}
    }
    @Test void innerReferencesCannotReadCopiedMainOrUnknownGlobalVariables() throws Exception {var main=copy(graph);ref(main,"text-joiner::j").put("nodeId","node-start::s");rejected(main,input,503);var unknown=copy(graph);ref(unknown,"text-joiner::j").put("nodeId","node-variable::global");rejected(unknown,input,503);}
    @Test void sameScopeForwardAndUndeclaredOutputReferencesAreRejected() throws Exception {var forward=copy(graph);ref(forward,"text-joiner::j").put("nodeId","iteration-node-end::ie");ref(forward,"text-joiner::j").put("name","value");rejected(forward,input,400);var missing=copy(graph);ref(missing,"text-joiner::j").put("name","notDeclared");rejected(missing,input,400);}
    @Test void batchMustBeOnlyInputAndDirectStartArrayInsteadOfDerivedOrLiteralValue() throws Exception {
        var derived=copy(graph);ref(derived,"iteration::i").put("name","items.child");rejected(derived,input,503);
        var literal=copy(graph);data(literal,"iteration::i").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").put("value",JSONObject.of("type","literal","content","[]"));rejected(literal,input,400);
        var extra=copy(graph);data(extra,"iteration::i").getJSONArray("inputs").add(copy(data(extra,"iteration::i").getJSONArray("inputs").getJSONObject(0)));rejected(extra,input,503);
    }
    @Test void disabledRetryUsesOriginalBooleanCoercionAndActiveRetryIsNotHiddenFromBudget() throws Exception {
        data(graph,"text-joiner::j").put("retryConfig",JSONObject.of("shouldRetry","OFF","maxRetries",0));create(graph,input);data(graph,"text-joiner::j").put("retryConfig",JSONObject.of("shouldRetry",true,"maxRetries",2));rejected(graph,input,503);
    }
    @Test void schemaRemoteReferenceAndNestedResourceShadowNeverQueue() throws Exception {var schemaRef=copy(graph);startOutput(schemaRef).getJSONObject("schema").put("$ref","https://schema.invalid/items.json");rejected(schemaRef,input,503);var nested=copy(graph);data(nested,"text-joiner::j").put("opaque",JSONObject.of("id","spark-llm::hidden","data",new JSONObject()));rejected(nested,input,503);}
    @Test void graphNodeBudgetRejectsManyEvenWhenEmptyBatchWouldSkipThem() throws Exception {innerChain(graph,123);rejected(graph,JSONObject.of("items",new JSONArray()),413);}
    @Test void expandedBudgetAndPathLengthAreIndependentlyEnforced() throws Exception {
        var expanded=copy(graph);innerChain(expanded,61);assertEquals(64,PureIterationPolicy.validate(expanded,JSONObject.of("items",batch(1))).maxPathLength());rejected(expanded,JSONObject.of("items",batch(64)),413);
        var longPath=copy(graph);innerChain(longPath,62);rejected(longPath,JSONObject.of("items",new JSONArray()),413);
    }
    @Test void exponentialDagIsCountedBySaturatingDpWithoutPathEnumeration() throws Exception {
        graph.getJSONArray("edges").removeIf(raw->"text-joiner::j".equals(((JSONObject)raw).get("sourceNodeId")));List<String> previous=List.of("text-joiner::j");
        for(int level=0;level<7;level++){var layer=new java.util.ArrayList<String>();for(int side=0;side<2;side++){String id="text-joiner::layer"+level+"-"+side;graph.getJSONArray("nodes").add(pure(id));layer.add(id);for(String before:previous)edge(graph,before,id);}previous=layer;}for(String before:previous)edge(graph,before,"iteration-node-end::ie");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->assertEquals(413,assertThrows(ServiceException.class,()->PureIterationPolicy.validate(graph,input)).getCode()));rejected(graph,input,413);
    }
    @Test void originalVariablePoolCompositeKeysCannotAliasAnotherNodeOutput() throws Exception {
        var collision=pure("text-joiner::j-value");data(graph,"text-joiner::j").getJSONArray("outputs").getJSONObject(0).put("name","value-x");collision.getJSONObject("data").getJSONArray("outputs").add(JSONObject.of("name","x","schema",JSONObject.of("type","string")));graph.getJSONArray("nodes").add(collision);edge(graph,"text-joiner::j",collision.getString("id"));edge(graph,collision.getString("id"),"iteration-node-end::ie");rejected(graph,input,400);
    }
    @Test void staleWorkflowSnapshotHashAndJavaEngineCannotUseIterationAdmission() throws Exception {
        var n=new DslCodec().normalize(graph);when(workflows.runtimeSnapshot(2)).thenReturn(new WorkflowService.RuntimeSnapshot(1,2,space,42,"fixed-original-flow","python","f".repeat(64),DslCodec.canonicalDslBase64(n.execution()),n.execution(),n.original(),new JSONObject(),new JSONObject()));assertEquals(409,assertThrows(ServiceException.class,()->runs.create(2,"python",input,"stale_snapshot_key",300)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->runs.create(2,"java",input,"wrong_engine_key",300)).getCode());assertEquals(0,rows("paiflow_run"));verifyNoInteractions(modelOperations);
    }
}
