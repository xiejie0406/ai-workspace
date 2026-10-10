package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.ModelRecord;
import com.ruoyi.paiflow.domain.ModelVersionRecord;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.domain.WorkflowVersionRecord;
import com.ruoyi.paiflow.mapper.ModelMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import com.ruoyi.system.service.ISysUserService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真Mapper/事务＋显式本地批准fixture。无网络、真实库、真实凭据或默认ProfilePort。 */
public class RuntimeModelCompilationTest {
    private JdbcDataSource db;private java.sql.Connection schema;private ModelMapper models;private SpaceMapper spaces;private WorkflowMapper workflows;private SqlSessionTemplate session;private IdentityMappingService identities;
    private ModelRuntimeCatalog catalog;private WorkflowService service;private WorkflowService target;private ResourceAccessService access;
    private ISysUserService users;private SysPermissionService permissions;private long space,other;
    private RunHistoryService javaHistory;
    private final AtomicReference<ModelRuntimeCatalog.ProfilePort> profile=new AtomicReference<>();
    private final java.util.Map<String,JSONObject> v4CheckpointAcks=new java.util.LinkedHashMap<>();
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    public static Timestamp utcNow(int precision){return Timestamp.from(Instant.now().truncatedTo(precision==3?java.time.temporal.ChronoUnit.MILLIS:java.time.temporal.ChronoUnit.MICROS));}
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(java.util.function.Supplier<T> value){var p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenAnswer(c->value.get());return p;}
    @BeforeEach void setup() throws Exception {
        v4CheckpointAcks.clear();
        javaHistory=null;
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE");
        schema=db.getConnection();
        // H2 2.4.240的IN常量CHECK持有创建session；保留DDL连接，不放宽原约束。
        try(var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.RuntimeModelCompilationTest.utcNow'");
            for(String file:List.of("001-paiflow-schema.sql","002-paiflow-workflow.sql","003-paiflow-run.sql","005-paiflow-checkpoint.sql","006-paiflow-execution-start.sql","007-paiflow-agent.sql","009-paiflow-model.sql","010-paiflow-default-personal.sql","011-paiflow-agent-owner-guard.sql","012-paiflow-workflow-model-snapshots.sql","013-paiflow-model-operation.sql","015-paiflow-execution-codec.sql","016-paiflow-static-question-answer-codec.sql","027-paiflow-dynamic-model-codec.sql","017-paiflow-run-json-codec.sql","018-paiflow-workflow-debug-snapshot.sql","021-paiflow-run-history.sql","033-paiflow-java-model-history.sql","034-paiflow-python-history-snapshot.sql")){
                String ddl=Files.readString(Path.of("sql",file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END").replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END")
                    .replace(" COLLATE=utf8mb4_bin","").replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var conf=new Configuration(new Environment("runtime-model-isolated",new SpringManagedTransactionFactory(),db));
        for(String name:List.of("SpaceMapper","ModelMapper","WorkflowMapper","RunMapper","ModelOperationMapper","CheckpointMapper","AgentMapper","RunHistoryMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){assertNotNull(xml);new XMLMapperBuilder(xml,conf,name,conf.getSqlFragments()).parse();}
        var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(conf));session=template;models=template.getMapper(ModelMapper.class);spaces=template.getMapper(SpaceMapper.class);workflows=template.getMapper(WorkflowMapper.class);
        access=mock(ResourceAccessService.class);when(access.requireSpace(anyLong(),anyString(),anyInt(),anyBoolean())).thenReturn(new SpaceRecord());
        identities=mock(IdentityMappingService.class);var actor=user(42);when(identities.current()).thenReturn(new LoginUser(42L,8L,actor,Set.of()));when(access.requirePermission(anyString())).thenReturn(42L);
        users=mock(ISysUserService.class);when(users.selectUserById(anyLong())).thenAnswer(c->user(c.getArgument(0)));permissions=mock(SysPermissionService.class);when(permissions.getMenuPermission(any())).thenReturn(Set.of("paiflow:model:query","paiflow:run:create","paiflow:run:resume"));
        catalog=new ModelRuntimeCatalog(models,spaces,access,users,permissions);catalog.useCurrentSession(template);catalog.useProfiles(provider(profile::get));approved();
        target=new WorkflowService(workflows,access,identities);target.useModelCatalogs(provider(()->catalog));var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));service=(WorkflowService)proxy.getProxy();
        space=createSpace("actual scope","personal");other=createSpace("other scope","personal");spaces.insertMember(space,43,3,"fixture");
    }
    @AfterEach void close() throws Exception {if(schema!=null)schema.close();}
    private static SysUser user(long id){var u=new SysUser();u.setUserId(id);u.setUserName("fixture-"+id);u.setStatus("0");u.setDelFlag("0");return u;}
    private void approved(){profile.set(s->new ModelRuntimeCatalog.RuntimeProfile(s.sourceFingerprint(),"openai",Set.of("chat")));}
    private long createSpace(String name,String kind){var s=new SpaceRecord();s.setName(name);s.setOwnerUserId(42L);s.setKind(kind);s.setCreateBy("fixture");s.setUpdateBy("fixture");assertEquals(1,spaces.insert(s));assertEquals(1,spaces.insertOwner(s.getId(),42,"fixture"));return s.getId();}
    private ModelRecord model(long scope,String domain,String endpoint){var m=new ModelRecord();m.setSpaceId(scope);m.setOwnerUserId(42L);m.setName(domain+UUID.randomUUID());m.setRevision(0L);m.setType(1);m.setEnabled(1);m.setState("VALIDATED");m.setConfigurationJson(JSONObject.of("modelName","展示名称","domain",domain,"provider","openai-compatible","endpoint",endpoint,"credentialRef","env:ISOLATED_REFERENCE").toJSONString());m.setCandidateHash("a".repeat(64));m.setCredentialVersion("fixture-ref-v1");m.setReceiptJson("{\"testFixture\":true}");m.setCreateBy("fixture");m.setUpdateBy("fixture");assertEquals(1,models.insert(m));pin(m);return models.find(m.getId());}
    private long pin(ModelRecord m){var v=new ModelVersionRecord();v.setModelId(m.getId());v.setSpaceId(m.getSpaceId());v.setOwnerUserId(m.getOwnerUserId());v.setRevision(m.getRevision());v.setType(m.getType());v.setEnabled(m.getEnabled());v.setState(m.getState());v.setConfigurationJson(m.getConfigurationJson());v.setCandidateHash(m.getCandidateHash());v.setCredentialVersion(m.getCredentialVersion());v.setReceiptJson(m.getReceiptJson());v.setCreateBy("fixture");assertEquals(1,models.insertVersion(v));assertEquals(1,models.selectVersion(m.getId(),m.getRevision(),v.getId()));return v.getId();}
    private static JSONObject graph(long model,String domain){return JSON.parseObject("{\"nodes\":[{\"id\":\"node-start::s\",\"data\":{}},{\"id\":\"spark-llm::m\",\"data\":{\"inputs\":[],\"nodeParam\":{\"modelId\":"+model+",\"source\":\"openai\",\"domain\":\""+domain+"\",\"url\":\"https://client-untrusted.invalid/ignored\",\"template\":\"{{value}}\"}}},{\"id\":\"node-end::e\",\"data\":{}}],\"edges\":[{\"source\":\"node-start::s\",\"target\":\"spark-llm::m\"},{\"source\":\"spark-llm::m\",\"target\":\"node-end::e\"}],\"futureOpaque\":\"原画布\"}");}
    private JSONObject version(ModelRecord m){var c=service.create(m.getSpaceId(),JSONObject.of("name","workflow"+UUID.randomUUID(),"data",graph(m.getId(),JSON.parseObject(m.getConfigurationJson()).getString("domain"))));return service.createVersion(c.getLongValue("id"),m.getSpaceId(),0L,JSONObject.of("name","v1"));}
    private ModelRuntimeCatalog.FixedModel fixed(ModelRecord m){return catalog.bindCurrent(m.getSpaceId(),m.getId(),"openai",JSON.parseObject(m.getConfigurationJson()).getString("domain"));}
    private static JSONObject param(JSONObject graph){return graph.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam");}
    private void sql(String statement) throws Exception {try(var c=db.getConnection();var s=c.createStatement()){s.execute(statement);}}
    @SuppressWarnings("unchecked") private <T> T tx(T value){var p=new ProxyFactory(value);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    private record V4Fixture(com.ruoyi.paiflow.mapper.RunMapper runs,com.ruoyi.paiflow.mapper.ModelOperationMapper operations,RunLeaseService leases,RunService runService,ModelOperationService calls,CheckpointService checkpoints,AtomicReference<ModelOperationPorts.PrivateGrant> grant,WorkerIdentityService.Principal worker,ManagedModelRuntime runtime,ModelOperationPorts.InvocationPolicy policy,ModelOperationPorts.CredentialMaterialPort credentials) {}
    private V4Fixture v4Fixture() {
        profile.set(s->new ModelRuntimeCatalog.RuntimeProfile(s.sourceFingerprint(),s.privateEndpoint().startsWith("wss:")?"xinghuo":"openai",s.privateEndpoint().startsWith("wss:")?Set.of("chat","function_call"):Set.of("chat")));
        var runs=session.getMapper(com.ruoyi.paiflow.mapper.RunMapper.class);var operations=session.getMapper(com.ruoyi.paiflow.mapper.ModelOperationMapper.class);var cp=session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class);var clock=java.time.Clock.systemUTC();
        var leaseTarget=new RunLeaseService(runs,spaces,users,permissions,clock);leaseTarget.useCurrentSession(session);var leases=tx(leaseTarget);
        var runtime=mock(ManagedModelRuntime.class);when(runtime.profile(any(ModelRuntimeCatalog.FixedModel.class))).thenAnswer(c->{var f=(ModelRuntimeCatalog.FixedModel)c.getArgument(0);var p=new ModelRuntimeProperties.Profile();p.setEnabled(true);p.setInvocationAllowed(true);p.setSourceProvider(f.sourceProvider());p.setRuntimeProvider(f.provider());p.setDomain(f.domain());p.setEndpoint(f.privateEndpoint());p.setCredentialRef(f.credentialRef());p.setSecretAlias("isolated-managed");p.setSecretProvider(f.provider());p.setCapability("chat");p.setAuthMode("isolated");p.setFunctionCall("xinghuo".equals(f.provider()));p.setAllowedUserIds(List.of(42L));p.setAllowedSpaceIds(List.of(space));p.setMaxCalls(20);p.setMaxTokens(1000000);p.setMaxRequestBytes(ModelOperationJson.REQUEST_BYTES);p.setTimeoutSeconds(300);return p;});
        var policy=new ManagedModelInvocationPolicy(runtime,catalog,operations);var grant=new AtomicReference<ModelOperationPorts.PrivateGrant>();
        var credentials=new ModelOperationPorts.CredentialMaterialPort(){public boolean available(ModelRuntimeCatalog.FixedModel f){return true;}public ModelOperationPorts.PrivateMaterial resolve(ModelRuntimeCatalog.FixedModel f){return new ModelOperationPorts.PrivateMaterial(f.credentialRef(),f.credentialVersion(),f.privateBaseUrl(),"isolated-key","isolated-app","isolated-secret");}};
        var bridge=new ModelOperationPorts.ProducerBridge(){public boolean available(ModelRuntimeCatalog.FixedModel f){return true;}public ModelOperationPorts.DispatchAck submit(ModelOperationPorts.PrivateGrant g,ModelOperationPorts.ProducerCallbacks callbacks){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertTrue(callbacks.assertActive(g).getBooleanValue("active"));grant.set(g);return new ModelOperationPorts.DispatchAck(g.operationId(),g.grantId(),true);}public ModelOperationPorts.StopEvidence stop(String operation,String id,String reason){return null;}};
        var callTarget=new ModelOperationService(operations,runs,spaces,leases,catalog,clock);callTarget.useCurrentSession(session);callTarget.useCheckpoints(cp);callTarget.useCompletedSources(new CompletedOutputSources(null,operations,cp));var calls=tx(callTarget);callTarget.usePorts(provider(()->policy),provider(()->credentials),provider(()->bridge),provider(()->calls));
        var checkpoints=tx(new CheckpointService(cp,runs,leases,access,identities,permissions,clock));var runTarget=new RunService(service,runs,access,identities);runTarget.useModelOperations(provider(()->calls));runTarget.useCheckpoints(provider(()->checkpoints));
        return new V4Fixture(runs,operations,leases,tx(runTarget),calls,checkpoints,grant,new WorkerIdentityService.Principal("v4-fixture-worker","python"),runtime,policy,credentials);
    }
    private JSONObject persistedV4(JSONObject item) {
        var canvas=JSON.parseObject(ModelOperationJson.canonicalWorkflow(item.getJSONObject("assignment").getJSONObject("dsl"),ModelOperationJson.REQUEST_BYTES));var node=canvas.getJSONArray("nodes").stream().map(v->(JSONObject)v).filter(ModelNodeSemantics::isModelNode).findFirst().orElseThrow();var p=node.getJSONObject("data").getJSONObject("nodeParam");
        var model=model(space,p.getString("domain"),"xinghuo".equals(p.getString("source"))?"wss://isolated-spark.invalid/v3/chat":"https://isolated-openai.invalid/v1/chat/completions");p.remove("modelResourceId");p.put("modelId",model.getId());
        var flow=service.create(space,JSONObject.of("name","v4-flow-"+UUID.randomUUID(),"engineProfile","python","data",canvas));var version=service.createVersion(flow.getLongValue("id"),space,0L,JSONObject.of("name","v4"));var stored=workflows.version(version.getLongValue("id"));assertEquals("spark-llm".equals(ModelNodeSemantics.type(node))?DslCodec.V2:DslCodec.V4,stored.getExecutionCodec());var snapshot=service.runtimeSnapshot(stored.getId());assertEquals(stored.getExecutionCodec(),snapshot.executionCodec());assertEquals(JSON.parseObject(stored.getResourceSnapshotsJson()),snapshot.resourceSnapshots());return version;
    }
    private JSONObject claimedV4(V4Fixture f,JSONObject item) {
        var version=persistedV4(item);var run=f.runService.create(version.getLongValue("id"),"python",item.getJSONObject("assignment").getJSONObject("input"),"v4_"+UUID.randomUUID().toString().replace("-",""),300);var task=f.leases.claim(f.worker);assertEquals(run.runId(),task.getString("runId"));assertEquals(DslCodec.V2.equals(service.runtimeSnapshot(version.getLongValue("id")).executionCodec())?null:service.runtimeSnapshot(version.getLongValue("id")).executionCodec(),task.getString("executionCodec"));f.leases.beginExecution(run.runId(),f.worker,task.getString("leaseId"),task.getIntValue("attempt"));assertNotNull(f.runs.find(run.runId()).getExecutionStartedAt());return task;
    }
    private static JSONObject v4Request(JSONObject task,JSONObject original,String key) {
        var node=task.getJSONObject("dsl").getJSONArray("nodes").stream().map(v->(JSONObject)v).filter(ModelNodeSemantics::isModelNode).findFirst().orElseThrow();var p=node.getJSONObject("data").getJSONObject("nodeParam");var params=ModelOperationJson.copy(original);String resource=p.getString("modelResourceId");var descriptor=task.getJSONObject("resourceSnapshots").getJSONObject(resource);params.put("resourceId",resource);params.put("resourceVersion",descriptor.getString("version"));return JSONObject.of("leaseId",task.getString("leaseId"),"attempt",task.getIntValue("attempt"),"nodeId",node.getString("id"),"iterationId",null,"operation","model.invoke","parameters",params,"idempotencyKey",key);
    }
    private void finishV4(V4Fixture f,String text) {
        var g=f.grant.get();JSONObject frame;
        if("xinghuo".equals(g.binding().getString("provider")))frame=JSONObject.of("header",JSONObject.of("code",0,"status",2,"sid","fixture"),"payload",JSONObject.of("choices",JSONObject.of("status",2,"seq",0,"text",new JSONArray(List.of(JSONObject.of("role","assistant","content",text))))));
        else frame=JSONObject.of("id","fixture","choices",new JSONArray(List.of(JSONObject.of("index",0,"delta",JSONObject.of("content",text),"finish_reason","stop"))));
        f.calls.commitFrame(g,1,frame);f.calls.finishStream(g,1,1,"stop");assertEquals("COMPLETED",f.operations.find(g.operationId()).getState());
    }

    private JSONObject modelBranchTask(V4Fixture f,boolean missingPredecessor,boolean forgedJoin) throws Exception {
        var branch=JSON.parseObject(new String(getClass().getResourceAsStream("/fixtures/branch-default-source-golden.json").readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases").getJSONObject(0);
        var llm=JSON.parseObject(new String(getClass().getResourceAsStream("/fixtures/llm-output-projection-golden.json").readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases").getJSONObject(0);
        var canvas=ModelOperationJson.copy(branch.getJSONObject("assignment").getJSONObject("dsl"));var nodes=canvas.getJSONArray("nodes");nodes.removeIf(raw->((JSONObject)raw).getString("id").startsWith("node-end::"));
        var modelNode=ModelOperationJson.copy(llm.getJSONObject("nodeJson"));var p=modelNode.getJSONObject("data").getJSONObject("nodeParam");var model=model(space,p.getString("domain"),"wss://isolated-spark.invalid/v3/chat");p.remove("modelResourceId");p.put("modelId",model.getId());
        modelNode.getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content").putAll(JSONObject.of("nodeId",forgedJoin?"text-joiner::yes":"text-joiner::no","name","value"));nodes.add(modelNode);nodes.add(JSONObject.of("id","node-end::e","data",JSONObject.of("inputs",new JSONArray(),"outputs",new JSONArray(),"nodeParam",new JSONObject())));
        for(Object raw:canvas.getJSONArray("edges")){var edge=(JSONObject)raw;if("node-end::e".equals(edge.get("targetNodeId")))edge.put("targetNodeId",modelNode.get("id"));}canvas.getJSONArray("edges").add(JSONObject.of("sourceNodeId",modelNode.get("id"),"targetNodeId","node-end::e","sourceHandle",""));
        var flow=service.create(space,JSONObject.of("name","model-branch-"+UUID.randomUUID(),"engineProfile","python","data",canvas));var version=service.createVersion(flow.getLongValue("id"),space,0L,new JSONObject());var created=f.runService.create(version.getLongValue("id"),"python",branch.getJSONObject("assignment").getJSONObject("input"),"branch_"+UUID.randomUUID(),300);var task=f.leases.claim(f.worker);assertEquals(created.runId(),task.get("runId"));f.leases.beginExecution(created.runId(),f.worker,task.getString("leaseId"),task.getIntValue("attempt"));
        for(Object raw:branch.getJSONArray("actualEvents")){var original=(JSONObject)raw;if(!"node.completed".equals(original.get("eventType"))||!Set.of("if-else::c","text-joiner::yes").contains(original.get("nodeId"))||missingPredecessor&&"text-joiner::yes".equals(original.get("nodeId")))continue;var event=ModelOperationJson.copy(original);event.putAll(JSONObject.of("runId",task.get("runId"),"leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"seq",f.runs.find(created.runId()).getLastEventSeq()+1,"timestamp",Instant.now().toString()));event.getJSONObject("payload").getJSONObject("legacyFrame").put("id",created.runId());if(forgedJoin&&"text-joiner::yes".equals(event.get("nodeId")))event.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("outputs").put("value","FORGED");assertTrue(f.leases.append(created.runId(),f.worker,event).getBooleanValue("accepted"));}return task;
    }
    private JSONObject modelBranchRequest(JSONObject task,String value) throws Exception {
        var llm=JSON.parseObject(new String(getClass().getResourceAsStream("/fixtures/llm-output-projection-golden.json").readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases").getJSONObject(0);var params=ModelOperationJson.copy(llm.getJSONArray("actualOperations").getJSONArray(0).getJSONObject(5));params.getJSONArray("messages").getJSONObject(1).put("content","ask="+value);return v4Request(task,params,"branch_model_"+UUID.randomUUID());
    }
    @Test void modelConsumerBranchDefaultUsesFixedSourceInsteadOfMissingEvent() throws Exception {var f=v4Fixture();var task=modelBranchTask(f,false,false);var ack=f.calls.start(task.getString("runId"),f.worker,modelBranchRequest(task,"no-default"));assertEquals("RUNNING",ack.get("state"));assertNotNull(f.grant.get());}
    @Test void modelConsumerRejectsForgedIntermediateBeforeProducer() throws Exception {var f=v4Fixture();var task=modelBranchTask(f,false,true);assertEquals(409,assertThrows(ServiceException.class,()->f.calls.start(task.getString("runId"),f.worker,modelBranchRequest(task,"FORGED"))).getCode());assertNull(f.grant.get());assertEquals(0,f.operations.forRunResource(task.getString("runId"),DataWorkerOperationService.find(task.getJSONObject("dsl"),"spark-llm::model").getJSONObject("data").getJSONObject("nodeParam").getString("modelResourceId")).size());}
    @Test void modelConsumerWaitsForEveryActivePredecessor() throws Exception {var f=v4Fixture();var task=modelBranchTask(f,true,false);assertEquals(403,assertThrows(ServiceException.class,()->f.calls.start(task.getString("runId"),f.worker,modelBranchRequest(task,"no-default"))).getCode());assertNull(f.grant.get());}

    @Test void modelTrustedMapActualFactoryMessagesPassRealVersionPrepareAndPolicy() throws Exception {var f=v4Fixture();for(Object raw:ModelTrustedInputMapTest.cases()){var c=(JSONObject)raw;var task=claimedV4(f,c);var request=v4Request(task,c.getJSONArray("actualOperations").getJSONArray(0).getJSONObject(5),"map_golden_"+UUID.randomUUID());var ack=f.calls.start(task.getString("runId"),f.worker,request);var facts=JSON.parseObject(f.operations.find(ack.getString("operationId")).getApprovalJson()).getJSONObject("candidate");assertNotNull(facts.getString("verifiedInputsJson"));assertFalse(request.containsKey("verifiedInputsJson"));assertFalse(request.getJSONObject("parameters").containsKey("verifiedInputsJson"));finishV4(f,"captured");}}

    private record DataModelChain(V4Fixture model, JSONObject task, JSONObject request,
        com.ruoyi.paiflow.mapper.DataMapper data, long tableId, JSONObject sourceNode) {}

    /** 同一空间、账号、固定版本和租约中的真实Data入口；不借用其他夹具的成功回执。 */
    private DataModelChain dataModelChain(boolean completeData, boolean forgedEvent) throws Exception {
        try (var ddl=schema.createStatement()) {
            for(String file:List.of("026-paiflow-data-resource.sql","031-paiflow-data-completion.sql")) {
                String text=Files.readString(Path.of("sql",file),StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","")
                    .replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4(?: COLLATE=utf8mb4_bin)?","");
                for(String statement:text.split(";")) if(!statement.isBlank()) ddl.execute(statement);
            }
        }
        var configuration=session.getSqlSessionFactory().getConfiguration();
        try(var xml=getClass().getResourceAsStream("/mapper/paiflow/DataMapper.xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"DataMapper",configuration.getSqlFragments()).parse();
        }
        var data=session.getMapper(com.ruoyi.paiflow.mapper.DataMapper.class);
        var database=new com.ruoyi.paiflow.domain.DataRecord();database.setSpaceId(space);database.setOwnerUserId(42L);
        database.setName("同域数据源");database.setDescription("");database.setRevision(0L);database.setDeleted(0);
        database.setCreateTime(LocalDateTime.now());database.setUpdateTime(LocalDateTime.now());assertEquals(1,data.insertDatabase(database));
        var table=new com.ruoyi.paiflow.domain.DataRecord();table.setDbId(database.getId());table.setName("people");table.setDescription("");
        table.setFieldsJson("[{\"name\":\"name\",\"type\":\"String\"}]");table.setRevision(0L);table.setDeleted(0);
        table.setCreateTime(LocalDateTime.now());table.setUpdateTime(LocalDateTime.now());assertEquals(1,data.insertTable(table));
        var testRow=new java.util.HashMap<String,Object>();testRow.put("table",table.getId());testRow.put("env",1);testRow.put("json","{\"name\":\"测试环境的值\"}");assertEquals(1,data.insertRow(testRow));
        var row=new java.util.HashMap<String,Object>();row.put("table",table.getId());row.put("env",2);row.put("json","{\"name\":\"完成时的值\"}");assertEquals(1,data.insertRow(row));
        when(permissions.getMenuPermission(any())).thenReturn(Set.of("paiflow:model:query","paiflow:run:create","paiflow:run:resume",DataResourceService.QUERY));
        var dataCatalog=new DataRuntimeCatalog(data,spaces,access,users,permissions);dataCatalog.useCurrentSession(session);
        var dataRows=new DataResourceService(data,mock(DefaultPersonalSpaceService.class),access);
        var dataOperations=tx(new DataOperationService(dataCatalog,dataRows,data));target.useDataCatalogs(provider(()->dataCatalog));
        var f=v4Fixture();f.runService.useDataOperations(provider(()->dataOperations));
        var sources=new CompletedOutputSources(null,f.operations,session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class));
        sources.useDataSources(provider(()->dataOperations));f.calls.useCompletedSources(sources);
        var dataWorker=tx(new DataWorkerOperationService(f.runs,spaces,f.leases,dataCatalog,dataOperations,sources));
        var model=model(space,"data-model","https://isolated-openai.invalid/v1/chat/completions");var canvas=graph(model.getId(),"data-model");
        var sourceNode=JSONObject.of("id","database::actual","data",JSONObject.of("inputs",new JSONArray(),"outputs",new JSONArray(List.of(
            JSONObject.of("name","isSuccess","schema",JSONObject.of("type","boolean")),
            JSONObject.of("name","message","schema",JSONObject.of("type","string")),
            JSONObject.of("name","outputList","schema",JSONObject.of("type","array","items",JSONObject.of("type","object","properties",JSONObject.of("name",JSONObject.of("type","string")),"required",new JSONArray(List.of("name"))))))),
            "nodeParam",JSONObject.of("dbId",database.getId(),"mode",3,"tableName","people","assignmentList",new JSONArray(List.of("name")),"orderData",new JSONArray(),"cases",new JSONArray(),"limit",10)));
        canvas.getJSONArray("nodes").add(1,sourceNode);var modelNode=DataWorkerOperationService.find(canvas,"spark-llm::m");
        modelNode.getJSONObject("data").put("inputs",new JSONArray(List.of(JSONObject.of("name","rows","schema",JSONObject.of("type","array","items",JSONObject.of("type","object"),"value",JSONObject.of("type","ref","content",JSONObject.of("nodeId","database::actual","name","outputList")))))));
        modelNode.getJSONObject("data").getJSONObject("nodeParam").put("template","Rows={{rows}}");
        canvas.put("edges",new JSONArray(List.of(JSONObject.of("source","node-start::s","target","database::actual"),JSONObject.of("source","database::actual","target","spark-llm::m"),JSONObject.of("source","spark-llm::m","target","node-end::e"))));
        var flow=service.create(space,JSONObject.of("name","actual-data-model-"+UUID.randomUUID(),"engineProfile","python","data",canvas));
        var version=service.createVersion(flow.getLongValue("id"),space,0L,new JSONObject());
        var created=f.runService.create(version.getLongValue("id"),"python",new JSONObject(),"data_model_"+UUID.randomUUID(),300);
        var task=f.leases.claim(f.worker);assertEquals(created.runId(),task.get("runId"));assertEquals(2,task.getIntValue("dataEnvironment"));f.leases.beginExecution(created.runId(),f.worker,task.getString("leaseId"),task.getIntValue("attempt"));
        sourceNode=DataWorkerOperationService.find(task.getJSONObject("dsl"),"database::actual");var resource=sourceNode.getJSONObject("data").getJSONObject("nodeParam").getString("dataResourceId");
        var descriptor=task.getJSONObject("resourceSnapshots").getJSONObject(resource);
        if(completeData){var payload=DataInputPayload.expected(sourceNode,new java.util.LinkedHashMap<>(),task.getIntValue("dataEnvironment"));
            var parameters=JSONObject.of("resourceId",resource,"resourceVersion",descriptor.get("version"),"kind","database","provider","center-data","legacyPayload",payload,"resourceBindings",new JSONObject());
            var ack=dataWorker.start(created.runId(),f.worker,JSONObject.of("leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"nodeId","database::actual","operation","data.query","parameters",parameters,"idempotencyKey","data_source_once"));
            assertEquals("COMPLETED",ack.get("state"));assertEquals(1,data.completedSources(created.runId(),task.getString("leaseId"),task.getIntValue("attempt"),"database::actual","").size());
            assertEquals("完成时的值",ack.getJSONObject("legacyResponse").getJSONObject("data").getJSONArray("exec_success").getJSONObject(0).get("name"));
            assertEquals(1,data.updateRow(table.getId(),2,((Number)row.get("id")).longValue(),"{\"name\":\"后来修改的值\"}"));
        }
        var output=JSONObject.of("isSuccess",true,"message","success","outputList",new JSONArray(List.of(JSONObject.of("name",forgedEvent?"伪造的值":"完成时的值"))));
        var event=JSONObject.of("schemaVersion","paiflow.events.v1","runId",task.get("runId"),"leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"seq",1,"timestamp",Instant.now().toString(),"eventType","node.completed","nodeId","database::actual","payload",JSONObject.of("legacyFrame",JSONObject.of("id",task.get("runId"),"workflow_step",JSONObject.of("node",JSONObject.of("id","database::actual","outputs",output)))));
        assertTrue(f.leases.append(created.runId(),f.worker,event).getBooleanValue("accepted"));
        var fixedNode=DataWorkerOperationService.find(task.getJSONObject("dsl"),"spark-llm::m");var p=fixedNode.getJSONObject("data").getJSONObject("nodeParam");var modelDescriptor=task.getJSONObject("resourceSnapshots").getJSONObject(p.getString("modelResourceId"));
        var params=JSONObject.of("resourceId",p.get("modelResourceId"),"resourceVersion",modelDescriptor.get("version"),"provider",modelDescriptor.get("provider"),"modelName",modelDescriptor.get("modelName"),"mode","chat","messages",new JSONArray(List.of(JSONObject.of("role","user","content","Rows=[{'name': '完成时的值'}]"))),"options",ModelOperationOptions.forNode(fixedNode));
        var request=JSONObject.of("leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"nodeId","spark-llm::m","iterationId",null,"operation","model.invoke","parameters",params,"idempotencyKey","data_model_once");
        return new DataModelChain(f,task,request,data,table.getId(),sourceNode);
    }
    @Test void actualDataCompletionFeedsModelAfterBusinessRowChanges() throws Exception {
        var chain=dataModelChain(true,false);var f=chain.model;var ack=f.calls.start(chain.task.getString("runId"),f.worker,chain.request);
        assertEquals("RUNNING",ack.get("state"));assertNotNull(f.grant.get());finishV4(f,"数据模型完成");
        assertEquals("COMPLETED",f.operations.find(ack.getString("operationId")).getState());
        assertEquals("后来修改的值",ModelMessageSources.sourceObject(String.valueOf(chain.data.rows(chain.tableId,2,10).getFirst().get("rowJson"))).get("name"));
        assertEquals("测试环境的值",ModelMessageSources.sourceObject(String.valueOf(chain.data.rows(chain.tableId,1,10).getFirst().get("rowJson"))).get("name"));
    }
    @Test void acceptedDataOutputWithoutPersistentCompletionCannotDispatchModel() throws Exception {
        var chain=dataModelChain(false,false);var f=chain.model;assertEquals(403,assertThrows(ServiceException.class,()->f.calls.start(chain.task.getString("runId"),f.worker,chain.request)).getCode());assertNull(f.grant.get());
    }
    @Test void acceptedForgedDataOutputCannotReplaceActualCompletionForModel() throws Exception {
        var chain=dataModelChain(true,true);var f=chain.model;assertEquals(409,assertThrows(ServiceException.class,()->f.calls.start(chain.task.getString("runId"),f.worker,chain.request)).getCode());assertNull(f.grant.get());
    }

    private String consumedV4(V4Fixture f,JSONObject task,int ordinal,String question,String reply) {
        String run=task.getString("runId"),lease=task.getString("leaseId");int attempt=task.getIntValue("attempt");var created=f.checkpoints.create(run,f.worker,JSONObject.of("leaseId",lease,"attempt",attempt,"nodeId","question-answer::qa","ordinal",ordinal,"question",JSONObject.of("type","direct","content",question),"needReply",true,"timeoutSeconds",60,"idempotencyKey","checkpoint_fixture_"+ordinal));String id=created.getString("checkpointId");
        var waiting=f.checkpoints.poll(run,id,f.worker,JSONObject.of("leaseId",lease,"attempt",attempt,"afterRevision",created.getLongValue("revision")));var replyRequest=JSONObject.of("expectedRevision",created.getLongValue("revision"),"idempotencyKey","reply_fixture_"+ordinal,"event_type","resume","content",reply);var resumed=f.checkpoints.reply(run,id,replyRequest);var polled=f.checkpoints.poll(run,id,f.worker,JSONObject.of("leaseId",lease,"attempt",attempt,"afterRevision",created.getLongValue("revision")));var consumeRequest=JSONObject.of("leaseId",lease,"attempt",attempt,"expectedRevision",resumed.getLongValue("revision"),"idempotencyKey","consume_fixture_"+ordinal);var consumed=f.checkpoints.consume(run,id,f.worker,consumeRequest);assertTrue(consumed.getBooleanValue("consumed"));assertEquals("CONSUMED",consumed.getString("state"));for(var ack:List.of(waiting,resumed,polled,consumed))assertEquals(created.getString("deadline"),ack.getString("deadline"),"真实Mapper重读后完整checkpoint期限绑定不变");v4CheckpointAcks.put(id,JSONObject.of("createAck",created,"waitingPollAck",waiting,"replyRequest",replyRequest,"replyAck",resumed,"pollAck",polled,"consumeRequest",consumeRequest,"consumeAck",consumed));return id;
    }
    @Test void actualV4MapperVersionsRunLeaseAndManagedPolicyAcceptOriginalPeDecisionVariants() throws Exception {
        var f=v4Fixture();int checked=0;
        for(Object raw:ModelV4ContractTest.cases()) {var item=(JSONObject)raw;if(item.getString("name").startsWith("dynamic-qa"))continue;var task=claimedV4(f,item);var request=v4Request(task,item.getJSONArray("parameters").getJSONObject(0),"original_"+checked);var ack=f.calls.start(task.getString("runId"),f.worker,request);assertEquals(task.getString("workflowVersionId"),ack.getString("workflowVersionId"));assertEquals(task.getString("dslHash"),ack.getString("dslHash"));assertEquals(request.getJSONObject("parameters").getString("resourceId"),ack.getString("resourceId"));assertEquals(request.getString("nodeId"),ack.getString("nodeId"));finishV4(f,"原Provider文本");
            var forged=v4Request(task,item.getJSONArray("parameters").getJSONObject(0),"forged_"+checked);forged.getJSONObject("parameters").getJSONArray("messages").getJSONObject(0).put("content","Worker伪造来源");assertEquals(403,assertThrows(ServiceException.class,()->f.calls.start(task.getString("runId"),f.worker,forged)).getCode());checked++;
        }
        assertEquals(16,checked);
    }
    @Test void actualV4DynamicQaUsesConsumedRepliesAndPersistedPreviousModelFrames() throws Exception {
        var item=ModelV4ContractTest.cases().stream().map(v->(JSONObject)v).filter(v->v.getString("name").startsWith("dynamic-qa")).findFirst().orElseThrow();var f=v4Fixture();var task=claimedV4(f,item);
        String cp1=consumedV4(f,task,1,"请回答中心检查点问题","还没年龄");var first=v4Request(task,item.getJSONArray("parameters").getJSONObject(0),"qa_first");first.getJSONObject("parameters").put("checkpointId",cp1);var ack=f.calls.start(task.getString("runId"),f.worker,first);finishV4(f,"{\"role\":\"assistant\",\"content\":\"请补充年龄\",\"completed\":{},\"incomplete\":{\"age\":null}}");assertEquals(ack,f.calls.start(task.getString("runId"),f.worker,ModelOperationJson.copy(first)));
        var duplicate=ModelOperationJson.copy(first);duplicate.put("idempotencyKey","qa_repeat_new_key");assertEquals(409,assertThrows(ServiceException.class,()->f.calls.start(task.getString("runId"),f.worker,duplicate)).getCode());
        String cp2=consumedV4(f,task,2,"请补充年龄","年龄8");var second=v4Request(task,item.getJSONArray("parameters").getJSONObject(1),"qa_second");second.getJSONObject("parameters").put("checkpointId",cp2);var forged=ModelOperationJson.copy(second);forged.put("idempotencyKey","qa_forged_history");forged.getJSONObject("parameters").getJSONArray("messages").getJSONObject(0).put("content","伪造历史及回复");assertEquals(403,assertThrows(ServiceException.class,()->f.calls.start(task.getString("runId"),f.worker,forged)).getCode());
        var secondAck=f.calls.start(task.getString("runId"),f.worker,second);finishV4(f,"{\"role\":\"assistant\",\"content\":\"\",\"completed\":{\"age\":8},\"incomplete\":{}}");assertEquals(2,f.operations.forRunResource(task.getString("runId"),second.getJSONObject("parameters").getString("resourceId")).size());
        String export=System.getProperty("paiflow.qaAssignmentExport");if(export!=null){var frames=new JSONArray();for(var receipt:List.of(ack,secondAck))frames.add(f.calls.frames(task.getString("runId"),f.worker,receipt.getString("operationId"),JSONObject.of("leaseId",task.getString("leaseId"),"attempt",task.getIntValue("attempt"),"afterCursor",0,"limit",64)));var cp=session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class);Files.writeString(Path.of(export),JSONObject.of("scope","actual fixed version Mapper/Run/claim/checkpoint consumption/model operations; isolated producer callback","serializedAssignment",f.runs.find(task.getString("runId")).getAssignmentJson(),"claimedAssignment",task,"requests",List.of(first,second),"starts",List.of(ack,secondAck),"frames",frames,"checkpoints",List.of(JSONObject.of("checkpointId",cp1,"actualAcks",v4CheckpointAcks.get(cp1),"question",JSON.parseObject(cp.find(cp1).getQuestionJson()),"resumeData",JSON.parseObject(cp.find(cp1).getResumeDataJson())),JSONObject.of("checkpointId",cp2,"actualAcks",v4CheckpointAcks.get(cp2),"question",JSON.parseObject(cp.find(cp2).getQuestionJson()),"resumeData",JSON.parseObject(cp.find(cp2).getResumeDataJson())))).toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);}
    }
    @Test void actualV4ScalarRunPersistenceExportsRealClaimAssignments() throws Exception {
        var f=v4Fixture();var captures=new JSONArray();var failures=new java.util.ArrayList<Throwable>();var base=ModelV4ContractTest.cases().stream().map(v->(JSONObject)v).filter(v->"pe-prompt-xinghuo".equals(v.getString("name"))).findFirst().orElseThrow();
        try(var in=getClass().getResourceAsStream("/fixtures/model-scalar-actual-source.json")) {
            var scalar=ModelMessageSources.sourceObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            for(Object raw:scalar.getJSONArray("cases")) {
                var source=(JSONObject)raw;var item=ModelOperationJson.copy(base);var assignment=item.getJSONObject("assignment");assignment.put("input",source.getJSONObject("input"));var nodes=assignment.getJSONObject("dsl").getJSONArray("nodes");var model=nodes.stream().map(v->(JSONObject)v).filter(ModelNodeSemantics::isModelNode).findFirst().orElseThrow();model.put("data",source.getJSONObject("nodeData"));var start=nodes.stream().map(v->(JSONObject)v).filter(v->v.getString("id").startsWith("node-start::")).findFirst().orElseThrow();start.getJSONObject("data").getJSONArray("outputs").getJSONObject(0).getJSONObject("schema").put("type",source.getJSONObject("nodeData").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getString("type"));
                var task=claimedV4(f,item);var saved=f.runs.find(task.getString("runId"));captures.add(JSONObject.of("name",source.getString("name"),"serializedAssignment",saved.getAssignmentJson(),"claimedAssignment",task,"expectedParameters",source.getJSONObject("parameters")));
                if("pe-prompt-negative-zero".equals(source.getString("name")))try{assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(((Number)task.getJSONObject("input").get("question")).doubleValue()),"真实claim负零符号不得丢失");var verified=f.leases.verify(task.getString("runId"),f.worker,task.getString("leaseId"),task.getIntValue("attempt"));assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(((Number)verified.getJSONObject("assignment").getJSONObject("input").get("question")).doubleValue()),"真实verify负零符号不得丢失");}catch(Throwable failed){failures.add(failed);}
                var params=ModelOperationJson.copy(source.getJSONObject("parameters"));params.put("resourceVersion","unused");var request=v4Request(task,params,"scalar_"+captures.size());try{f.calls.start(task.getString("runId"),f.worker,request);finishV4(f,"{\"answer\":9}");}catch(Throwable failed){failures.add(failed);}
            }
        } finally {
            String output=System.getProperty("paiflow.modelAssignmentExport");if(output!=null)Files.writeString(Path.of(output),JSONObject.of("scope","actual WorkflowVersion mapper and persisted Run/claim fixture; no provider","cases",captures).toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
        }
        assertEquals(5,captures.size());
        assertAll("原数值输入真实中心逐次授权",failures.stream().map(failed->(org.junit.jupiter.api.function.Executable)()->{throw failed;}));
    }
    @Test void newCodecConstraintKeepsAllHistoricalValuesAndRefusesUnapprovedVersion() throws Exception {
        var item=ModelV4ContractTest.cases().stream().map(v->(JSONObject)v).filter(v->"pe-prompt-xinghuo".equals(v.getString("name"))).findFirst().orElseThrow();v4Fixture();long id=persistedV4(item).getLongValue("id");
        for(String codec:List.of(DslCodec.V1,DslCodec.V2,DslCodec.V3,DslCodec.V4)){sql("UPDATE paiflow_workflow_version SET execution_codec='"+codec+"' WHERE id="+id);assertEquals(codec,workflows.version(id).getExecutionCodec());}
        sql("UPDATE paiflow_workflow_version SET execution_codec=NULL WHERE id="+id);assertNull(workflows.version(id).getExecutionCodec());
        assertThrows(java.sql.SQLException.class,()->sql("UPDATE paiflow_workflow_version SET execution_codec='paiflow.execution.v5' WHERE id="+id));
    }
    @Test void actualV4CustomTokenSchemaReachesManagedWebsocketProducerAndRealMapperFinish() throws Exception {
        JSONObject item;try(var in=getClass().getResourceAsStream("/fixtures/model-token-schema-actual-source.json")){assertNotNull(in);item=JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases").getJSONObject(0);}
        ModelV4ContractTest.node(item).getJSONObject("data").getJSONObject("nodeParam").put("uid","fixed-business-user");
        var f=v4Fixture();var done=new java.util.concurrent.CountDownLatch(1);var failure=new AtomicReference<ModelOperationPorts.FailureFacts>();var received=new AtomicReference<JSONObject>();
        try(var server=new okhttp3.mockwebserver.MockWebServer()) {
            var providerFrame=JSONObject.of("header",JSONObject.of("code",0,"status",2,"sid","fixture-sid-0"),"payload",JSONObject.of("choices",JSONObject.of("status",2,"seq",0,"text",List.of(JSONObject.of("role","assistant","content","","function_call",JSONObject.of("name","extractor_parameter","arguments","{\"answer\":9,\"token\":\"business-slot\"}")))),"usage",JSONObject.of("text",JSONObject.of("question_tokens",4,"prompt_tokens",4,"completion_tokens",3,"total_tokens",7))));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener(){@Override public void onClosing(okhttp3.WebSocket socket,int code,String reason){socket.close(code,reason);}@Override public void onMessage(okhttp3.WebSocket socket,String text){received.set(JSON.parseObject(text));socket.send(providerFrame.toJSONString());}}));server.start(java.net.InetAddress.getLoopbackAddress(),0);
            when(f.runtime.profileByGrant(any())).thenAnswer(c->{var g=(ModelOperationPorts.PrivateGrant)c.getArgument(0);var p=JSON.parseObject(g.parametersJson());var desc=JSONObject.of("kind","model","provider","xinghuo","version",p.getString("resourceVersion"),"modelName",p.getString("modelName"),"enabled",true,"capabilities",List.of("chat","function_call"));return f.runtime.profile(catalog.resolvePinned(space,42,p.getString("resourceId"),desc));});
            var transport=new ModelPrivateTransport(){@Override public okhttp3.OkHttpClient client(java.net.URI uri,int seconds,List<String> addresses){return new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v3/chat")).build())).build();}};
            var producer=new ManagedModelProducer(f.runtime,transport);
            var bridge=new ModelOperationPorts.ProducerBridge(){public boolean available(ModelRuntimeCatalog.FixedModel model){return producer.available(model);}public ModelOperationPorts.DispatchAck submit(ModelOperationPorts.PrivateGrant grant,ModelOperationPorts.ProducerCallbacks callbacks){f.grant.set(grant);return producer.submit(grant,new ModelOperationPorts.ProducerCallbacks(){public JSONObject assertActive(ModelOperationPorts.PrivateGrant g){return callbacks.assertActive(g);}public JSONObject commitFrame(ModelOperationPorts.PrivateGrant g,long seq,JSONObject raw){return callbacks.commitFrame(g,seq,raw);}public JSONObject finishStream(ModelOperationPorts.PrivateGrant g,long last,long finish,String reason){var ack=callbacks.finishStream(g,last,finish,reason);done.countDown();return ack;}public JSONObject confirmStop(ModelOperationPorts.PrivateGrant g,ModelOperationPorts.StopEvidence evidence){return callbacks.confirmStop(g,evidence);}public void recordFailure(ModelOperationPorts.PrivateGrant g,ModelOperationPorts.FailureFacts facts){failure.set(facts);callbacks.recordFailure(g,facts);done.countDown();}});}public ModelOperationPorts.StopEvidence stop(String operation,String grant,String reason){return producer.stop(operation,grant,reason);}};
            f.calls.usePorts(provider(()->f.policy),provider(()->f.credentials),provider(()->bridge),provider(()->f.calls));
            try {
                var task=claimedV4(f,item);var req=v4Request(task,item.getJSONObject("parameters"),"original_token_schema");var started=f.calls.start(task.getString("runId"),f.worker,req);assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertNull(failure.get());assertEquals("COMPLETED",f.operations.find(started.getString("operationId")).getState());var wire=received.get();assertNotNull(wire);var actualFunctions=wire.getJSONObject("payload").getJSONObject("functions").getJSONArray("text");assertEquals(req.getJSONObject("parameters").getJSONArray("functions"),actualFunctions);assertEquals("string",actualFunctions.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").getJSONObject("token").getString("type"));assertEquals(item.getJSONObject("parameters").getJSONArray("functions").getJSONObject(0).getJSONObject("parameters").getJSONObject("default"),actualFunctions.getJSONObject(0).getJSONObject("parameters").getJSONObject("default"));
                var frames=f.calls.frames(task.getString("runId"),f.worker,started.getString("operationId"),JSONObject.of("leaseId",task.getString("leaseId"),"attempt",task.getIntValue("attempt"),"afterCursor",0,"limit",64));assertTrue(frames.getBooleanValue("done"));assertEquals("COMPLETED",frames.getString("state"));assertEquals("{\"answer\":9,\"token\":\"business-slot\"}",frames.getJSONArray("frames").getJSONObject(0).getJSONObject("payload").getJSONObject("choices").getJSONArray("text").getJSONObject(0).getJSONObject("function_call").getString("arguments"));
                assertEquals(providerFrame,frames.getJSONArray("frames").getJSONObject(0));
                assertEquals("fixed-business-user",wire.getJSONObject("header").getString("uid"));assertEquals("fixed-business-user",f.grant.get().providerUid());assertFalse(started.containsKey("uid"));assertFalse(started.containsKey("providerUid"));assertFalse(f.grant.get().binding().containsKey("uid"));
                String export=System.getProperty("paiflow.functionAssignmentExport");if(export!=null)Files.writeString(Path.of(export),JSONObject.of("scope","actual fixed version and run/lease permission to managed websocket producer; local fake material fixture","serializedAssignment",f.runs.find(task.getString("runId")).getAssignmentJson(),"claimedAssignment",task,"request",req,"start",started,"frames",frames,"wirePayload",wire.getJSONObject("payload"),"wireParameters",wire.getJSONObject("parameter")).toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
            } finally {producer.close();}
        }
    }
    @Test void originalUidComesOnlyFromApprovedNodeWithoutChangingPublicOperationFields() throws Exception {
        var base=ModelV4ContractTest.cases().getJSONObject(0);var f=v4Fixture();Set<String> factKeys=null;
        for(String uid:List.of("","business-user")){
            var item=ModelOperationJson.copy(base);var p=ModelV4ContractTest.node(item).getJSONObject("data").getJSONObject("nodeParam");if(uid.isEmpty())p.remove("uid");else p.put("uid",uid);
            var task=claimedV4(f,item);var request=v4Request(task,item.getJSONArray("parameters").getJSONObject(0),"uid_"+UUID.randomUUID());var injected=ModelOperationJson.copy(request);injected.getJSONObject("parameters").put("uid","worker-spoof");assertEquals(400,assertThrows(ServiceException.class,()->f.calls.start(task.getString("runId"),f.worker,injected)).getCode());
            var ack=f.calls.start(task.getString("runId"),f.worker,request);var grant=f.grant.get();assertEquals(uid,grant.providerUid());assertFalse(grant.binding().containsKey("uid"));assertFalse(grant.binding().containsKey("providerUid"));assertFalse(ack.containsKey("uid"));assertFalse(ack.containsKey("providerUid"));assertFalse(JSON.parseObject(grant.parametersJson()).containsKey("uid"));assertEquals(ModelOperationJson.sha(grant.parametersJson()),grant.parametersHash());assertEquals(task.getString("dslHash"),ack.getString("dslHash"));
            var row=f.operations.find(grant.operationId());var facts=JSON.parseObject(row.getApprovalJson()).getJSONObject("candidate");if(factKeys==null)factKeys=Set.copyOf(facts.keySet());else assertEquals(factKeys,facts.keySet());assertFalse(facts.containsKey("providerUid"));assertEquals(uid,ModelNodeSemantics.providerUid(JSON.parseObject(facts.getString("nodeJson"))));
            var forged=new ModelOperationPorts.PrivateGrant(grant.operationId(),grant.grantId(),grant.token(),grant.binding(),grant.parametersJson(),grant.parametersHash(),grant.optionsJson(),grant.deadline(),grant.privateMaterial(),"worker-spoof");assertEquals(403,assertThrows(ServiceException.class,()->f.calls.assertActive(forged)).getCode());
            var wrongEngine=new ModelOperationPorts.PrivateGrant(grant.operationId(),grant.grantId(),grant.token(),grant.binding(),grant.parametersJson(),grant.parametersHash(),grant.optionsJson(),grant.deadline(),grant.privateMaterial(),grant.providerUid(),"java");
            var before=f.operations.find(grant.operationId());
            assertEquals(403,assertThrows(ServiceException.class,()->f.calls.assertActive(wrongEngine)).getCode());
            assertEquals(403,assertThrows(ServiceException.class,()->f.calls.commitFrame(wrongEngine,1,JSONObject.of("choices",new JSONArray()))).getCode());
            assertEquals(before.getRevision(),f.operations.find(grant.operationId()).getRevision());assertEquals(0,f.operations.frames(grant.operationId(),0,10).size());
            assertTrue(f.calls.assertActive(grant).getBooleanValue("active"));finishV4(f,"原合法结果");
        }
    }
    @Test void defaultPersonalDataNullSurvivesCompilerPersistedVersionAndWorkerResource() {
        long hidden=createSpace("default data owner",SpaceRecord.DEFAULT_PERSONAL);
        var dataMapper=mock(com.ruoyi.paiflow.mapper.DataMapper.class);var record=new com.ruoyi.paiflow.domain.DataRecord();record.setId(5L);record.setSpaceId(hidden);record.setRevision(2L);record.setDeleted(0);
        var table=new com.ruoyi.paiflow.domain.DataRecord();table.setId(7L);table.setDbId(5L);table.setName("people");table.setRevision(1L);table.setFieldsJson("[{\"id\":1,\"name\":\"id\",\"type\":\"Integer\"},{\"id\":4,\"name\":\"name\",\"type\":\"String\"}]");
        when(dataMapper.database(5)).thenReturn(record);when(dataMapper.tables(5)).thenReturn(List.of(table));when(permissions.getMenuPermission(any())).thenReturn(Set.of(DataResourceService.QUERY));
        var dataCatalog=new DataRuntimeCatalog(dataMapper,spaces,access,users,permissions);target.useDataCatalogs(provider(()->dataCatalog));
        var graph=JSON.parseObject("{\"nodes\":[{\"id\":\"node-start::s\",\"data\":{}},{\"id\":\"database::d\",\"data\":{\"inputs\":[],\"nodeParam\":{\"dbId\":5,\"mode\":3,\"tableName\":\"people\",\"assignmentList\":[\"name\"]}}},{\"id\":\"node-end::e\",\"data\":{}}],\"edges\":[{\"source\":\"node-start::s\",\"target\":\"database::d\"},{\"source\":\"database::d\",\"target\":\"node-end::e\"}]}");
        var flow=service.create(hidden,JSONObject.of("name","actual-null-data","engineProfile","python","data",graph));var version=service.createVersion(flow.getLongValue("id"),hidden,0L,JSONObject.of("name","v1"));
        var stored=workflows.version(version.getLongValue("id"));var raw=JSON.parseObject(stored.getResourceSnapshotsJson()).getJSONObject("data:5:revision:2");assertTrue(raw.containsKey("legacySpaceId"));assertNull(raw.get("legacySpaceId"));
        var snapshot=service.runtimeSnapshot(stored.getId());assertEquals(RunJsonBoundary.canonicalFull(raw),RunJsonBoundary.canonicalFull(dataCatalog.resolvePinned(hidden,42,"data:5:revision:2",snapshot.resourceSnapshots().getJSONObject("data:5:revision:2")).descriptor()));assertTrue(param(snapshot.execution()).containsKey("spaceId"));assertNull(param(snapshot.execution()).get("spaceId"));
        var run=new com.ruoyi.paiflow.domain.RunRecord();run.setId("33333333-3333-3333-3333-333333333333");run.setSpaceId(hidden);run.setOwnerUserId(42L);run.setEngineProfile("python");run.setState("RUNNING");run.setExecutionStartedAt(LocalDateTime.now());run.setAssignmentJson(JSONObject.of("dsl",snapshot.execution(),"resourceSnapshots",snapshot.resourceSnapshots()).toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        var runMapper=mock(com.ruoyi.paiflow.mapper.RunMapper.class);var leases=mock(RunLeaseService.class);var worker=new WorkerIdentityService.Principal("fixture-python","python");when(runMapper.find(run.getId())).thenReturn(run);when(leases.requireActiveLease(run.getId(),worker,"lease",1)).thenReturn(run);
        var bridge=new DataWorkerOperationService(runMapper,spaces,leases,dataCatalog,mock(DataOperationService.class),new CompletedOutputSources(null,null,null));var receipt=bridge.resource(run.getId(),worker,"data:5:revision:2",JSONObject.of("leaseId","lease","attempt",1));assertEquals(raw,receipt);assertTrue(receipt.containsKey("legacySpaceId"));assertNull(receipt.get("legacySpaceId"));
    }
    @Test void realVersionStoresAuthoritativePublicDescriptorAndExecutionOnlyAdaptation(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var v=version(m);var stored=workflows.version(v.getLongValue("id"));var resource=ModelRuntimeCatalog.resourceIdOf(m.getId(),m.getLatestVersionId());
        assertEquals(graph(m.getId(),"domain-model"),JSON.parseObject(stored.getCanvasJson()));assertEquals("原画布",JSON.parseObject(stored.getCanvasJson()).getString("futureOpaque"));
        var snapshot=service.runtimeSnapshot(stored.getId());var p=param(snapshot.execution());assertEquals(resource,p.getString("modelResourceId"));assertFalse(p.containsKey("url"));assertFalse(p.containsKey("apiKey"));assertEquals("",p.getString("appId"));assertEquals(1.0,p.getDoubleValue("temperature"));assertEquals(2048,p.getIntValue("maxTokens"));assertEquals(3,p.getIntValue("topK"));assertEquals(0,p.getIntValue("respFormat"));assertTrue(p.getBooleanValue("searchDisable"));
        var d=snapshot.resourceSnapshots().getJSONObject(resource);assertEquals("domain-model",d.getString("modelName"));assertEquals(String.valueOf(m.getLatestVersionId()),d.getString("version"));assertEquals(Set.of("kind","provider","version","modelName","enabled","capabilities"),d.keySet());assertFalse(snapshot.resourceSnapshots().toJSONString().contains("provider.invalid"));assertFalse(snapshot.resourceSnapshots().toJSONString().contains("ISOLATED_REFERENCE"));assertEquals(snapshot.resourceSnapshots(),v.getJSONObject("resourceSnapshots"));assertNotEquals(new DslCodec().normalize(snapshot.canvas()).executionHash(),snapshot.dslHash());
    }
    @Test void missingApprovedProfileRollsBackVersionSelectionAndWritesNoResources(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");profile.set(null);var c=service.create(space,JSONObject.of("name","unavailable","data",graph(m.getId(),"domain-model")));long id=c.getLongValue("id");
        assertEquals(503,assertThrows(ServiceException.class,()->service.createVersion(id,space,0L,new JSONObject())).getCode());assertEquals(0,workflows.countVersions(id));assertEquals(0L,workflows.find(id).getRevision());assertNull(workflows.find(id).getLatestVersionId());
    }
    @Test void profileCannotGuessFamilyOrReuseAnotherSourceApproval(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");
        for(var bad:List.of(new ModelRuntimeCatalog.RuntimeProfile("other","openai",Set.of("chat")),new ModelRuntimeCatalog.RuntimeProfile("unused","xinghuo",Set.of("chat")),new ModelRuntimeCatalog.RuntimeProfile("unused","openai",Set.of("chat","function-call")))){profile.set(s->new ModelRuntimeCatalog.RuntimeProfile(bad.sourceFingerprint().equals("other")?"other":s.sourceFingerprint(),bad.provider(),bad.capabilities()));assertEquals(503,assertThrows(ServiceException.class,()->fixed(m)).getCode());}
    }
    @Test void currentModelCanChangeWithoutRetargetingPinnedOldVersion(){
        var m=model(space,"first-domain","https://first.invalid/v1/chat/completions");var old=fixed(m);var v=version(m);m.setConfigurationJson(JSONObject.of("domain","second-domain","provider","openai-compatible","endpoint","https://second.invalid/v2/chat/completions","credentialRef","env:SECOND_REFERENCE").toJSONString());m.setCredentialVersion("fixture-ref-v2");assertEquals(1,models.update(m));m=models.find(m.getId());pin(m);
        var runtime=catalog.resolvePinned(space,43,old.resourceId(),old.publicDescriptor());assertEquals("first-domain",runtime.domain());assertEquals("https://first.invalid/v1",runtime.privateBaseUrl());assertEquals("fixture-ref-v1",runtime.credentialVersion());assertEquals(old.publicDescriptor(),service.runtimeSnapshot(v.getLongValue("id")).resourceSnapshots().getJSONObject(old.resourceId()));
    }
    @Test void disabledCurrentModelRefusesNewRuntimeButHistoricalVersionRemainsReadable() throws Exception {
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var old=fixed(m);var v=version(m);sql("UPDATE paiflow_model SET enabled=0 WHERE id="+m.getId());
        assertEquals(503,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,42,old.resourceId(),old.publicDescriptor())).getCode());assertNotNull(service.versionDetail(v.getLongValue("id")));assertEquals(old.publicDescriptor(),service.runtimeSnapshot(v.getLongValue("id")).resourceSnapshots().getJSONObject(old.resourceId()));
    }
    @Test void runtimeUsesExplicitCurrentHumanAndFreshMenuNotWorkerLoginContext(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);clearInvocations(access);assertEquals(f.resourceId(),catalog.resolvePinned(space,43,f.resourceId(),f.publicDescriptor()).resourceId());verifyNoInteractions(access);
        when(permissions.getMenuPermission(any())).thenReturn(Set.of());assertEquals(403,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,43,f.resourceId(),f.publicDescriptor())).getCode());
    }
    @Test void revokedMembershipOrDisabledActorCannotUseImmutableSnapshot() throws Exception {
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);spaces.removeMember(space,43,"fixture");assertEquals(403,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,43,f.resourceId(),f.publicDescriptor())).getCode());
        var disabled=user(42);disabled.setStatus("1");when(users.selectUserById(42L)).thenReturn(disabled);assertEquals(403,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,42,f.resourceId(),f.publicDescriptor())).getCode());
    }
    @Test void dirtyDefaultMemberNeverAuthorizesAnotherOwner(){
        long hidden=createSpace("hidden","default_personal");spaces.insertMember(hidden,43,1,"dirty fixture");var m=model(hidden,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);assertEquals(403,assertThrows(ServiceException.class,()->catalog.resolvePinned(hidden,43,f.resourceId(),f.publicDescriptor())).getCode());assertEquals(f.resourceId(),catalog.resolvePinned(hidden,42,f.resourceId(),f.publicDescriptor()).resourceId());
    }
    @Test void modelAndVersionCannotCrossActualScopeOrBeReplacedByDescriptor(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);assertEquals(404,assertThrows(ServiceException.class,()->catalog.resolvePinned(other,42,f.resourceId(),f.publicDescriptor())).getCode());
        var badVersion=f.publicDescriptor();badVersion.put("version","9999");assertEquals(409,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,42,f.resourceId(),badVersion)).getCode());var badEndpoint=f.publicDescriptor();badEndpoint.put("endpoint","https://injected.invalid");assertEquals(409,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,42,f.resourceId(),badEndpoint)).getCode());
    }
    @Test void profileWaitCannotCarryRevokedPermissionOrEnabledStateAcrossReturn() throws Exception {
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);profile.set(s->{when(permissions.getMenuPermission(any())).thenReturn(Set.of());return new ModelRuntimeCatalog.RuntimeProfile(s.sourceFingerprint(),"openai",Set.of("chat"));});assertEquals(403,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,42,f.resourceId(),f.publicDescriptor())).getCode());
        when(permissions.getMenuPermission(any())).thenReturn(Set.of("paiflow:model:query"));profile.set(s->{try{sql("UPDATE paiflow_model SET enabled=0 WHERE id="+m.getId());}catch(Exception e){throw new AssertionError(e);}return new ModelRuntimeCatalog.RuntimeProfile(s.sourceFingerprint(),"openai",Set.of("chat"));});assertEquals(503,assertThrows(ServiceException.class,()->catalog.resolvePinned(space,42,f.resourceId(),f.publicDescriptor())).getCode());
    }
    @Test void baseUrlDerivationIsExactAndRejectsUnapprovedTransportsAndRewrites(){
        assertEquals("https://provider.invalid/v1",ModelRuntimeCatalog.openAiBase("https://provider.invalid/v1/chat/completions"));assertEquals("https://provider.invalid:443",ModelRuntimeCatalog.openAiBase("https://provider.invalid:443/chat/completions"));
        for(String uri:List.of("http://provider.invalid/v1/chat/completions","https://provider.invalid:8443/v1/chat/completions","https://u:p@provider.invalid/v1/chat/completions","https://provider.invalid/v1/chat/completions?x=1","https://provider.invalid/v1/chat/completions#f","https://provider.invalid/v1/../v2/chat/completions","https://provider.invalid/v1//chat/completions","https://provider.invalid/v1/%2e/chat/completions","https://provider.invalid/v1","https://provider.invalid/v1/chat/completions/"))assertEquals(503,assertThrows(ServiceException.class,()->ModelRuntimeCatalog.openAiBase(uri)).getCode(),uri);
    }
    @Test void appIdCompatibilityPreservesRealOriginalAndNeverCreatesAppBinding(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var canvas=graph(m.getId(),"domain-model");param(canvas).put("appId","actual-original-label");var result=WorkflowModelCompiler.compile(canvas,(id,s,d)->fixed(m));assertEquals("actual-original-label",param(result.execution()).getString("appId"));assertEquals("actual-original-label",param(canvas).getString("appId"));assertFalse(result.resourceSnapshots().containsKey("app"));
    }
    @Test void originalTemplateObjectAndLegacyHistoryRemainWhileV2MultimodalAndOverridesAreRejected(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var canvas=graph(m.getId(),"domain-model");canvas.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").put("inputs",new JSONArray(List.of(JSONObject.of("name","value","type","object"))));assertNotNull(WorkflowModelCompiler.compile(canvas,(id,s,d)->fixed(m)));
        var legacy=graph(m.getId(),"domain-model");param(legacy).put("enableChatHistory",true);var compiled=WorkflowModelCompiler.compile(legacy,(id,s,d)->fixed(m));assertEquals(true,param(compiled.execution()).get("enableChatHistory"));assertEquals(true,param(legacy).get("enableChatHistory"));assertNotNull(param(compiled.execution()).get("modelResourceId"));
        for(JSONObject patch:List.of(JSONObject.of("enableChatHistoryV2",JSONObject.of("isEnabled",true)),JSONObject.of("multiMode",true),JSONObject.of("extraParams",JSONObject.of("extra_headers",JSONObject.of("x","y"))),JSONObject.of("extraParams",JSONObject.of("n",2)))){var g=graph(m.getId(),"domain-model");param(g).putAll(patch);assertThrows(ServiceException.class,()->WorkflowModelCompiler.compile(g,(id,s,d)->{fail("preflight must reject before model read");return null;}));}
    }
    @Test void forgedClientBindingAndMalformedTypedOptionsFailBeforeVersionInsert(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");for(JSONObject patch:List.of(JSONObject.of("modelResourceId","model:99:version:99"),JSONObject.of("maxTokens",1.5),JSONObject.of("temperature",0),JSONObject.of("searchDisable","  true "),JSONObject.of("respFormat",3),JSONObject.of("credentialRef","env:FORGED"))){var g=graph(m.getId(),"domain-model");param(g).putAll(patch);assertThrows(ServiceException.class,()->WorkflowModelCompiler.compile(g,(id,s,d)->fixed(m)));}
    }
    @Test void originalPydanticCoercionIsSavedOnlyInExecutionWithoutChangingRawCanvas(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var g=graph(m.getId(),"domain-model");param(g).putAll(JSONObject.of("temperature",true,"maxTokens","20.0","topK",true,"searchDisable","TRUE","enableChatHistory",0));
        var created=service.create(space,JSONObject.of("name","coercion fixture","data",g));var v=service.createVersion(created.getLongValue("id"),space,0L,new JSONObject());var stored=workflows.version(v.getLongValue("id"));var p=param(service.runtimeSnapshot(stored.getId()).execution());
        assertEquals(g,JSON.parseObject(stored.getCanvasJson()));assertTrue(param(JSON.parseObject(stored.getCanvasJson())).get("temperature") instanceof Boolean);assertEquals("20.0",param(JSON.parseObject(stored.getCanvasJson())).get("maxTokens"));assertEquals(1.0,p.getDoubleValue("temperature"));assertEquals(20,p.getIntValue("maxTokens"));assertEquals(1,p.getIntValue("topK"));assertTrue(p.getBooleanValue("searchDisable"));assertFalse(p.getBooleanValue("enableChatHistory"));
    }
    @Test void publicDescriptorCopiesCannotMutatePrivatePinnedTuple(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);var d=f.publicDescriptor();d.put("modelName","forged");assertEquals("domain-model",f.domain());assertEquals("domain-model",f.publicDescriptor().getString("modelName"));String wire=JSON.toJSONString(f);assertFalse(wire.contains("provider.invalid"));assertFalse(wire.contains("ISOLATED_REFERENCE"));assertFalse(f.toString().contains("ISOLATED_REFERENCE"));
    }
    @Test void legacyNullPureVersionKeepsOriginalHashAndEmptyBindings() throws Exception {
        var c=service.create(space,JSONObject.of("name","pure"));var v=service.createVersion(c.getLongValue("id"),space,0L,new JSONObject());sql("UPDATE paiflow_workflow_version SET resource_snapshots_json=NULL WHERE id="+v.getLongValue("id"));var snapshot=service.runtimeSnapshot(v.getLongValue("id"));assertTrue(snapshot.resourceSnapshots().isEmpty());assertEquals(new DslCodec().normalize(snapshot.canvas()).executionHash(),snapshot.dslHash());assertEquals("legacy_uncompiled",service.versionDetail(v.getLongValue("id")).getString("resourceCompilationStatus"));
    }
    @Test void legacyNullResourceVersionRemainsHistoricalButCannotRun(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var c=service.create(space,JSONObject.of("name","legacy resource","data",graph(m.getId(),"domain-model")));var row=workflows.find(c.getLongValue("id"));var n=new DslCodec().normalizeV1(JSON.parseObject(row.getCanvasJson()));var v=new WorkflowVersionRecord();v.setWorkflowId(row.getId());v.setSpaceId(space);v.setOwnerUserId(42L);v.setSnapshotRevision(0L);v.setFlowId(row.getFlowId());v.setVersionNum("legacy");v.setName("legacy");v.setCanvasJson(row.getCanvasJson());v.setExecutionJson(n.execution().toJSONString());v.setDslHash(n.executionHash());v.setCanonicalDslBase64(DslCodec.canonicalDslBase64(n.execution(),DslCodec.V1));v.setMetadataJson("{}");v.setAdvancedConfigJson("{}");v.setFlowConfigJson("{}");v.setEngineProfile("python");v.setCreateBy("fixture");assertEquals(1,workflows.insertVersion(v));assertNotNull(service.versionDetail(v.getId()));assertEquals(503,assertThrows(ServiceException.class,()->service.runtimeSnapshot(v.getId())).getCode());
    }
    @Test void resourceColumnIsIndependentOfOpaqueMetadataAndTamperingBreaksIntegrity() throws Exception {
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var c=service.create(space,JSONObject.of("name","opaque metadata","data",graph(m.getId(),"domain-model"),"resourceSnapshots",JSONObject.of("fake",JSONObject.of("enabled",true))));var v=service.createVersion(c.getLongValue("id"),space,0L,new JSONObject());var stored=workflows.version(v.getLongValue("id"));assertTrue(JSON.parseObject(stored.getMetadataJson()).getJSONObject("resourceSnapshots").containsKey("fake"));assertFalse(v.getJSONObject("resourceSnapshots").containsKey("fake"));sql("UPDATE paiflow_workflow_version SET resource_snapshots_json='{}' WHERE id="+stored.getId());assertEquals(409,assertThrows(ServiceException.class,()->service.runtimeSnapshot(stored.getId())).getCode());
    }
    @Test void javaModelUsesPersistedRunPermissionAndOriginalFramesBeforeEndSource() throws Exception {
        for(int variant:List.of(0,2,3,4))javaModelLedgerVariant(variant);
    }
    private void javaModelLedgerVariant(int variant) throws Exception {
        var f=v4Fixture();JSONObject gold,wireCase;
        try(var in=getClass().getResourceAsStream("/fixtures/java-model/original-java-model-graph-golden.json")){assertNotNull(in);gold=JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        try(var in=getClass().getResourceAsStream("/fixtures/java-model-wire/original-sdk.json")){assertNotNull(in);wireCase=JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONObject(variant);}
        var m=model(space,"original-model","https://provider.invalid/v1/chat/completions");
        var canvas=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("dsl"));
        var modelNode=canvas.getJSONArray("nodes").getJSONObject(1);var params=modelNode.getJSONObject("data").getJSONObject("nodeParam");params.remove("modelResourceId");params.put("modelId",m.getId());
        if(variant==0){params.remove("maxTokens");var approvedProfile=f.runtime.profile(fixed(m));approvedProfile.setProviderDefaultMaxTokens(128);when(f.runtime.profile(any(ModelRuntimeCatalog.FixedModel.class))).thenReturn(approvedProfile);}
        if(variant==3)params.put("extraParams",JSONObject.of("n",2));
        var flow=service.create(space,JSONObject.of("name","java model current "+variant,"engineProfile","java","data",canvas));
        var version=service.createVersion(flow.getLongValue("id"),space,0L,new JSONObject());
        var input=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("input"));
        var created=f.runService.create(version.getLongValue("id"),"java",input,"java_model_"+UUID.randomUUID(),300);
        var worker=new WorkerIdentityService.Principal("java-model-fixture","java");var task=f.leases.claim(worker);assertEquals(created.runId(),task.get("runId"));
        assertEquals("java",task.get("engineProfile"));f.leases.beginExecution(created.runId(),worker,task.getString("leaseId"),task.getIntValue("attempt"));
        var start=javaModelEvent(f,task,gold,"node-start::1");assertTrue(f.leases.append(created.runId(),worker,start).getBooleanValue("accepted"));
        var request=v4Request(task,gold.getJSONObject("actualRequest").getJSONObject("parameters"),"java_model_call");
        request.getJSONObject("parameters").put("options",wireCase.getJSONObject("actualRequest").getJSONObject("parameters").getJSONObject("options"));
        var forged=ModelOperationJson.copy(request);forged.getJSONObject("parameters").getJSONArray("messages").getJSONObject(1).put("content","worker fabricated prompt");
        assertEquals(403,assertThrows(ServiceException.class,()->f.calls.start(created.runId(),worker,forged)).getCode());assertNull(f.grant.get());
        String resource=request.getJSONObject("parameters").getString("resourceId");assertEquals(0,f.operations.forRunResource(created.runId(),resource).size());
        var ack=f.calls.start(created.runId(),worker,request);var grant=f.grant.get();assertNotNull(grant);assertEquals("java",grant.engineProfile());assertEquals("RUNNING",ack.get("state"));
        var row=f.operations.find(grant.operationId());var candidate=JSON.parseObject(row.getApprovalJson()).getJSONObject("candidate");assertEquals("java",candidate.get("engineProfile"));
        var changedOptions=JSON.parseObject(grant.optionsJson());changedOptions.put("n",10);
        var forgedGrant=new ModelOperationPorts.PrivateGrant(grant.operationId(),grant.grantId(),grant.token(),grant.binding(),grant.parametersJson(),grant.parametersHash(),ModelOperationJson.canonical(changedOptions,ModelOperationJson.REQUEST_BYTES),grant.deadline(),grant.privateMaterial(),grant.providerUid(),grant.engineProfile());
        assertEquals(403,assertThrows(ServiceException.class,()->f.calls.assertActive(forgedGrant)).getCode());assertEquals(0,f.operations.frames(grant.operationId(),0,100).size());assertEquals(row.getRevision(),f.operations.find(grant.operationId()).getRevision());
        assertEquals(input,JSON.parseObject(f.runs.find(created.runId()).getAssignmentJson()).getJSONObject("input"));
        var raw=wireCase.getJSONArray("rawFrames");String finish=raw.getJSONObject(2).getJSONArray("choices").getJSONObject(0).getString("finish_reason");
        for(int i=0;i<raw.size();i++)assertTrue(f.calls.commitFrame(grant,i+1,raw.getJSONObject(i)).getBooleanValue("accepted"));
        assertEquals("stop".equals(finish)?"COMPLETED":"FAILED",f.calls.finishStream(grant,4,3,finish).get("state"));
        assertEquals(4,f.operations.frames(grant.operationId(),0,100).size());assertEquals(7,JSON.parseObject(f.operations.find(grant.operationId()).getUsageJson()).getIntValue("total_tokens"));
        assertEquals(ack,f.calls.start(created.runId(),worker,request));assertSame(grant,f.grant.get());assertEquals(1,f.operations.forRunResource(created.runId(),resource).size());
        var completed=javaModelEvent(f,task,gold,"spark-llm::2");assertTrue(f.leases.append(created.runId(),worker,completed).getBooleanValue("accepted"));
        var events=new JSONArray(List.of(start,completed));var run=f.runs.find(created.runId());var end=task.getJSONObject("dsl").getJSONArray("nodes").getJSONObject(2);
        var sources=new CompletedOutputSources(null,f.operations,null);assertEquals("原文本",sources.values(run,task.getJSONObject("dsl"),end,events).get("answer"));
        completed.getJSONObject("payload").getJSONObject("result").put("rawOutput","fabricated display");
        assertEquals(409,assertThrows(ServiceException.class,()->sources.values(run,task.getJSONObject("dsl"),end,events)).getCode());
    }
    private JSONObject javaModelEvent(V4Fixture f,JSONObject task,JSONObject gold,String nodeId){
        var source=gold.getJSONArray("actualEvents").stream().map(raw->(JSONObject)raw).filter(event->nodeId.equals(event.get("nodeId"))&&"node.completed".equals(event.get("eventType"))).findFirst().orElseThrow();
        var event=ModelOperationJson.copy(source);event.putAll(JSONObject.of("runId",task.get("runId"),"leaseId",task.get("leaseId"),"attempt",task.get("attempt"),"seq",f.runs.find(task.getString("runId")).getLastEventSeq()+1,"timestamp",Instant.now().toString()));return event;
    }
    @Test void javaManagedProducerAndOriginalWorkerCompleteTheSamePersistedRun() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("paiflow.model.pipeCommandFile")!=null,"需要显式私有原Worker测试桥，不自动安装或启动项目服务");
        for(int variant:List.of(0,3,4))javaManagedModelVariant(variant);
    }
    @Test void javaManagedModelPreservesOriginalFramesThroughFixedDebugBuildChatAndPage() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("paiflow.model.pipeCommandFile")!=null,"需要显式私有原Worker测试桥，不自动安装或启动项目服务");
        for(int variant:List.of(0,3,4))javaManagedModelVariant(variant,true);
    }
    @Test void javaHistoryUsesTheSameCentreConversationAcrossOriginalWorkerRunsAndFailure() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("paiflow.model.pipeCommandFile")!=null,"需要显式原Worker私有桥");
        var f=v4Fixture();var mapper=session.getMapper(com.ruoyi.paiflow.mapper.RunHistoryMapper.class);
        var sources=new CompletedOutputSources(null,f.operations,session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class));
        var historyTarget=new RunHistoryService(mapper,f.runs,f.leases,access);historyTarget.useJavaSources(provider(()->sources),spaces,f.operations);javaHistory=tx(historyTarget);
        sources.useHistory(provider(()->javaHistory));f.calls.useCompletedSources(sources);f.calls.useHistory(provider(()->javaHistory));
        ((ManagedModelInvocationPolicy)f.policy).useHistory(provider(()->javaHistory),f.runs);f.leases.useHistories(provider(()->javaHistory));
        JSONObject gold;JSONArray wireCases;
        try(var in=getClass().getResourceAsStream("/fixtures/java-model/original-java-model-graph-golden.json")){assertNotNull(in);gold=JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        try(var in=getClass().getResourceAsStream("/fixtures/java-model-wire/original-sdk.json")){assertNotNull(in);wireCases=JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        var m=model(space,"original-model","https://provider.invalid/v1/chat/completions");var canvas=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("dsl"));
        var p=canvas.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam");p.remove("modelResourceId");p.put("modelId",m.getId());p.put("enableChatHistoryV2",JSONObject.of("isEnabled",true,"rounds",2));
        var menu=mock(com.ruoyi.framework.web.service.PermissionService.class);when(menu.hasPermi(anyString())).thenReturn(true);var authority=new ResourceAccessService(identities,menu,spaces);authority.useCurrentSession(session);
        doAnswer(c->authority.requirePermission(c.getArgument(0))).when(access).requirePermission(anyString());doAnswer(c->authority.requireSpace(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3))).when(access).requireSpace(anyLong(),anyString(),anyInt(),anyBoolean());
        var agents=tx(new AgentService(session.getMapper(com.ruoyi.paiflow.mapper.AgentMapper.class),access,identities,service));target.useAgentBindings(provider(()->agents));
        var agent=agents.create(space,3,JSONObject.of("name","原历史连续调试","engineProfile","java","data",canvas));var flow=service.detail(agent.getLongValue("workflowId"),space);
        var build=service.debugBuild(flow.getLongValue("id"),space,0L,JSONObject.of("flowId",flow.get("flowId"),"data",canvas),"ordinary",null,"history-build_"+UUID.randomUUID());
        var defaults=tx(new DefaultPersonalSpaceService(spaces,identities,access));var debug=tx(new com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService(service,defaults,access,f.runService,f.runs,f.checkpoints,session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class)));debug.useHistories(provider(()->javaHistory));
        var profile=f.runtime.profile(fixed(m));when(f.runtime.profile(any(ModelRuntimeCatalog.FixedModel.class))).thenReturn(profile);when(f.runtime.profileByGrant(any())).thenReturn(profile);
        try(var server=new okhttp3.mockwebserver.MockWebServer()) {
            server.start(java.net.InetAddress.getLoopbackAddress(),0);
            var transport=new ModelPrivateTransport(){@Override public okhttp3.OkHttpClient client(java.net.URI uri,int seconds,List<String> addresses){return new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())).build();}};
            var producer=new ManagedModelProducer(f.runtime,transport);var captures=new JSONArray();var oldUsers=new java.util.ArrayList<String>();var oldAnswers=new java.util.ArrayList<String>();
            try {
                f.calls.usePorts(provider(()->f.policy),provider(()->f.credentials),provider(()->producer),provider(()->f.calls));
                for(int round=0;round<5;round++) {
                    boolean failed=round==1,isolated=round==4;String value="第"+(round+1)+"轮";var raw=wireCases.getJSONObject(round==3?4:0).getJSONArray("rawFrames");
                    if(failed)server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("isolated provider failure"));
                    else {var sse=new StringBuilder();for(Object frame:raw)sse.append("data: ").append(ModelOperationJson.canonical(frame,ModelOperationJson.FRAME_BYTES)).append("\n\n");sse.append("data: [DONE]\n\n");server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse.toString()));}
                    var body=JSONObject.of("flow_id",flow.get("flowId"),"inputs",JSONObject.of("x",value),"chatId",isolated?"different-chat":"original-history-chat","regen",false,"debugReceipt",build.getJSONObject("debugReceipt"),"requestKey","history-run_"+UUID.randomUUID());
                    var attachment=debug.chat(body,Long.toString(space),null);assertEquals(attachment.runId(),debug.chat(body,Long.toString(space),null).runId());
                    var worker=new WorkerIdentityService.Principal("java-managed-history","java");var task=f.leases.claim(worker);assertEquals(attachment.runId(),task.get("runId"));assertFalse(task.containsKey("executionContext"),"正式claim不把会话投影加入固定任务");
                    var captured=javaModelWorkerBridge(f,worker,task,failed?"EFFECT_UNKNOWN":"COMPLETED");var run=f.runs.find(attachment.runId());assertEquals(failed?"EFFECT_UNKNOWN":"COMPLETED",run.getState());
                    var verification=captured.getJSONArray("transcript").stream().map(v->(JSONObject)v).filter(v->v.getJSONObject("request").getString("path").endsWith("/lease/verify")).findFirst().orElseThrow().getJSONObject("response").getJSONObject("body").getJSONObject("data");
                    assertEquals(body.get("chatId"),verification.getJSONObject("executionContext").get("chatId"));assertFalse(verification.getJSONObject("assignment").containsKey("executionContext"));
                    var opens=captured.getJSONArray("transcript").stream().map(v->(JSONObject)v).filter(v->v.getJSONObject("request").getString("path").endsWith("/history/java/open")).toList();assertEquals(1,opens.size(),"本轮只开一次固定历史窗口");
                    var historyAck=opens.getFirst().getJSONObject("response").getJSONObject("body").getJSONObject("data");assertEquals(isolated?0:Math.min(2,round),historyAck.getJSONArray("history").size());
                    var expected=new JSONArray();expected.add(JSONObject.of("role","system","content","系统："+value));
                    if(!isolated)for(int old=Math.max(0,oldUsers.size()-2);old<oldUsers.size();old++){expected.add(JSONObject.of("role","user","content",oldUsers.get(old)));if(oldAnswers.get(old)!=null)expected.add(JSONObject.of("role","assistant","content",oldAnswers.get(old)));}
                    expected.add(JSONObject.of("role","user","content","问题："+value+"/{{missing}}"));
                    assertTrue(ToolSchema.same(expected,historyAck.getJSONObject("snapshot").get("messages")),"当前SYSTEM、旧问题/首回答、当前USER保留原顺序");
                    var request=server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(request);String actualWire=request.getBody().readUtf8();assertTrue(ToolSchema.same(expected,JSON.parseObject(actualWire).get("messages")));assertEquals(round+1,server.getRequestCount(),"同次调试回用与历史回读不重复供应商调用");
                    var node=DataWorkerOperationService.find(task.getJSONObject("dsl"),"spark-llm::2");String resource=node.getJSONObject("data").getJSONObject("nodeParam").getString("modelResourceId");var operations=f.operations.forRunResource(run.getId(),resource);assertEquals(1,operations.size());var operation=operations.getFirst();
                    assertEquals(failed?"EFFECT_UNKNOWN":round==3?"FAILED":"COMPLETED",operation.getState());assertEquals(failed?"cancelled":round==3?"provider":null,operation.getFailureKind());
                    if(failed){
                        var reads=captured.getJSONArray("transcript").stream().map(v->(JSONObject)v).filter(v->v.getJSONObject("request").getString("path").endsWith("/frames")).toList();
                        assertTrue(reads.stream().anyMatch(v->"transport".equals(v.getJSONObject("response").getJSONObject("body").getJSONObject("data").get("failureKind"))),"原HTTP故障已由实际读帧保存transport事实");
                        var stops=captured.getJSONArray("transcript").stream().map(v->(JSONObject)v).filter(v->v.getJSONObject("request").getString("path").endsWith("/cancel")).toList();assertFalse(stops.isEmpty());
                        for(Object valueStop:stops){var stop=((JSONObject)valueStop).getJSONObject("response").getJSONObject("body").getJSONObject("data");assertEquals("EFFECT_UNKNOWN",stop.get("state"));assertFalse(stop.getBooleanValue("stopped"));assertNull(stop.get("proofId"));}
                        assertEquals(0,operation.getStopConfirmed());assertNull(operation.getStopProofId());
                    }
                    var privateCandidate=JSON.parseObject(operation.getApprovalJson()).getJSONObject("candidate");assertNotNull(privateCandidate.get("javaHistoryJson"));
                    var historyRow=mapper.javaRound(run.getId(),run.getAttempt(),node.getString("id"));assertEquals(failed?"OPEN":"COMPLETED",historyRow.get("state"));var item=JSON.parseObject((String)historyRow.get("itemJson"));assertEquals(failed?0:1,item.getJSONArray("llmResponses").size());
                    assertEquals("问题："+value+"/{{missing}}",item.getJSONArray("userInputs").getJSONObject(1).get("content"));
                    var accepted=new JSONArray();for(var event:f.runs.eventsThrough(run.getId(),0,run.getLastEventSeq(),1000))accepted.add(ModelMessageSources.sourceObject(event.eventJson()));
                    var capture=JSONObject.of("scope","实际Agent/debugBuild/chat及五次原Worker/中心历史/ManagedProducer/Mapper；临时回环SSE，不是真实Provider或浏览器验收","round",round,"assignment",task,"executionContext",verification.get("executionContext"),"historyAck",historyAck,"historyRow",historyRow,"privateCandidate",privateCandidate,"providerWireRaw",actualWire,"actualProviderRequestCount",server.getRequestCount(),"modelState",operation.getState(),"modelFailureKind",operation.getFailureKind(),"worker",captured,"savedResult",ModelMessageSources.sourceObject(run.getResultJson()),"acceptedEvents",accepted);
                    if(!failed){assertEquals("原文本",sources.values(run,task.getJSONObject("dsl"),DataWorkerOperationService.find(task.getJSONObject("dsl"),"node-end::3"),accepted).get("answer"));capture.put("actualLegacyPage",javaModelLegacyPage(f,debug,attachment,build,task,accepted));}
                    else {assertNull(historyRow.get("modelOperationId"));assertNull(operation.getFinishSeq());assertTrue(debug.page(attachment,0).terminal());}
                    assertEquals(round+1,server.getRequestCount());captures.add(capture);
                    Files.writeString(Path.of(System.getProperty("paiflow.center.javaModelCaptureDir"),"java-model-history-five-runs.json"),JSON.toJSONString(captures,com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
                    oldUsers.add("问题："+value+"/{{missing}}");oldAnswers.add(failed?null:"原文本");
                }
                Files.writeString(Path.of(System.getProperty("paiflow.center.javaModelCaptureDir"),"java-model-history-five-runs.json"),JSON.toJSONString(captures,com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
            } finally {producer.close();}
        }
    }
    @Test void pythonFixedVersionRetainsRawHistoryFlagsWithOriginalCompletionTruthiness() throws Exception {
        var f=v4Fixture();JSONObject gold;
        try(var in=getClass().getResourceAsStream("/fixtures/llm-output-projection-golden.json")){assertNotNull(in);gold=JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases").getJSONObject(0);}
        var m=model(space,"original-model","https://provider.invalid/v1/chat/completions");
        for(String raw:List.of("false","0")){
            var canvas=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("dsl"));var p=DataWorkerOperationService.find(canvas,"spark-llm::model").getJSONObject("data").getJSONObject("nodeParam");p.remove("modelResourceId");p.put("modelId",m.getId());p.put("source","openai");p.put("domain","original-model");p.put("enableChatHistory",raw);
            var flow=service.create(space,JSONObject.of("name","raw-history-"+raw,"engineProfile","python","data",canvas));var version=service.createVersion(flow.getLongValue("id"),space,0L,new JSONObject());var fixed=DataWorkerOperationService.find(service.runtimeSnapshot(version.getLongValue("id")).execution(),"spark-llm::model");
            assertEquals(raw,fixed.getJSONObject("data").getJSONObject("nodeParam").get("enableChatHistory"));assertFalse(PythonModelHistorySemantics.readsLegacy(fixed));assertTrue(PythonModelHistorySemantics.appendsLegacy(fixed));
            assertNotNull(f.runService.create(version.getLongValue("id"),"python",JSONObject.of("question","原问题"),"raw-history-run-"+raw,300));
        }
    }
    @Test void pythonLegacyHistoryPreservesOriginalAppendAndDisplayAcrossActualWorkerRuns() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("paiflow.python.model.pipeCommandFile")!=null,"需要显式私有原Python Worker桥，不启动项目服务");
        var f=v4Fixture();var mapper=session.getMapper(com.ruoyi.paiflow.mapper.RunHistoryMapper.class);
        var sources=new CompletedOutputSources(null,f.operations,session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class));
        var historyTarget=new RunHistoryService(mapper,f.runs,f.leases,access);historyTarget.useJavaSources(provider(()->sources),spaces,f.operations);javaHistory=tx(historyTarget);
        sources.useHistory(provider(()->javaHistory));f.calls.useCompletedSources(sources);f.calls.useHistory(provider(()->javaHistory));
        ((ManagedModelInvocationPolicy)f.policy).useHistory(provider(()->javaHistory),f.runs);f.leases.useHistories(provider(()->javaHistory));
        JSONObject gold;JSONArray wires;
        try(var in=getClass().getResourceAsStream("/fixtures/llm-output-projection-golden.json")){assertNotNull(in);gold=JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases").getJSONObject(0);}
        try(var in=getClass().getResourceAsStream("/fixtures/java-model-wire/original-sdk.json")){assertNotNull(in);wires=JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        var m=model(space,"original-model","https://provider.invalid/v1/chat/completions");var canvas=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("dsl"));
        var p=DataWorkerOperationService.find(canvas,"spark-llm::model").getJSONObject("data").getJSONObject("nodeParam");p.remove("modelResourceId");p.put("modelId",m.getId());p.put("source","openai");p.put("domain","original-model");p.put("enableChatHistory",true);p.put("enableChatHistoryV2",JSONObject.of("isEnabled",false,"rounds",2));
        var menu=mock(com.ruoyi.framework.web.service.PermissionService.class);when(menu.hasPermi(anyString())).thenReturn(true);var authority=new ResourceAccessService(identities,menu,spaces);authority.useCurrentSession(session);
        doAnswer(c->authority.requirePermission(c.getArgument(0))).when(access).requirePermission(anyString());doAnswer(c->authority.requireSpace(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3))).when(access).requireSpace(anyLong(),anyString(),anyInt(),anyBoolean());
        var agents=tx(new AgentService(session.getMapper(com.ruoyi.paiflow.mapper.AgentMapper.class),access,identities,service));target.useAgentBindings(provider(()->agents));
        var agent=agents.create(space,3,JSONObject.of("name","原Python旧历史连续调试","engineProfile","python","data",canvas));var flow=service.detail(agent.getLongValue("workflowId"),space);
        var build=service.debugBuild(flow.getLongValue("id"),space,0L,JSONObject.of("flowId",flow.get("flowId"),"data",canvas),"ordinary",null,"python-history-build_"+UUID.randomUUID());
        var defaults=tx(new DefaultPersonalSpaceService(spaces,identities,access));var debug=tx(new com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService(service,defaults,access,f.runService,f.runs,f.checkpoints,session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class)));debug.useHistories(provider(()->javaHistory));
        var profile=f.runtime.profile(fixed(m));when(f.runtime.profile(any(ModelRuntimeCatalog.FixedModel.class))).thenReturn(profile);when(f.runtime.profileByGrant(any())).thenReturn(profile);
        var captureRoot=Path.of(System.getProperty("paiflow.center.pythonModelCaptureDir"));Files.createDirectories(captureRoot);var captures=new JSONArray();var expectedOld=new JSONArray();
        try(var server=new okhttp3.mockwebserver.MockWebServer()){
            server.start(java.net.InetAddress.getLoopbackAddress(),0);
            var transport=new ModelPrivateTransport(){@Override public okhttp3.OkHttpClient client(java.net.URI uri,int seconds,List<String> addresses){return new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())).build();}};
            var producer=new ManagedModelProducer(f.runtime,transport);
            try {
                f.calls.usePorts(provider(()->f.policy),provider(()->f.credentials),provider(()->producer),provider(()->f.calls));
                for(int round=0;round<5;round++){
                    boolean transportFailure=round==1,length=round==2,differentChat=round==4;
                    if(transportFailure)server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("isolated provider failure"));
                    else {
                        var raw=ModelOperationJson.copy(JSONObject.of("frames",wires.getJSONObject(length?4:0).getJSONArray("rawFrames"))).getJSONArray("frames");
                        // 原Provider帧保留省略usage；结束delta={}由原SDK补content:null的语义在消费边界恢复。
                        for(Object frame:raw){var item=(JSONObject)frame;var choices=item.getJSONArray("choices");if(choices!=null&&!choices.isEmpty()&&choices.getJSONObject(0).get("finish_reason")!=null)choices.getJSONObject(0).put("delta",new JSONObject());}
                        var sse=new StringBuilder();for(Object frame:raw)sse.append("data: ").append(ModelOperationJson.canonical(frame,ModelOperationJson.FRAME_BYTES)).append("\n\n");sse.append("data: [DONE]\n\n");server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse.toString()));
                    }
                    String question="原Python第"+(round+1)+"问";var body=JSONObject.of("flow_id",flow.get("flowId"),"inputs",JSONObject.of("question",question),"chatId",differentChat?"different-chat":"original-python-chat","regen",false,"debugReceipt",build.getJSONObject("debugReceipt"),"requestKey","python-history-run_"+UUID.randomUUID());
                    var attachment=debug.chat(body,Long.toString(space),null);assertEquals(attachment.runId(),debug.chat(body,Long.toString(space),null).runId());var worker=new WorkerIdentityService.Principal("python-managed-history","python");var task=f.leases.claim(worker);assertEquals(attachment.runId(),task.get("runId"));assertFalse(task.containsKey("executionContext"));
                    String expectedState=transportFailure?"EFFECT_UNKNOWN":length?"FAILED":"COMPLETED";var captured=modelWorkerBridge(f,worker,task,expectedState,true);var run=f.runs.find(attachment.runId());assertEquals(expectedState,run.getState());
                    var transcript=captured.getJSONArray("transcript");var reads=transcript.stream().map(v->(JSONObject)v).filter(v->v.getJSONObject("request").getString("path").endsWith("/history/python/read")).toList();assertEquals(1,reads.size());var ack=reads.getFirst().getJSONObject("response").getJSONObject("body").getJSONObject("data");assertEquals(15,ack.getJSONObject("snapshot").size());assertEquals(RunJsonBoundary.hashFull(ack.getJSONObject("snapshot")),ack.get("historyHash"));
                    var window=ack.getJSONArray("history");if(differentChat||expectedOld.isEmpty())assertTrue(window.isEmpty());else {assertEquals(1,window.size());assertEquals("spark-llm::model",window.getJSONObject(0).get("nodeID"));assertTrue(ToolSchema.same(expectedOld,window.getJSONObject(0).get("chat_history")));}
                    var wireRequest=server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(wireRequest);String actualWire=wireRequest.getBody().readUtf8();var expectedMessages=JSONArray.of(JSONObject.of("role","system","content","original system"),JSONObject.of("role","user","content","ask="+question));assertTrue(ToolSchema.same(expectedMessages,JSON.parseObject(actualWire).get("messages")),"旧历史仅展示，不添到Provider消息");assertEquals(round+1,server.getRequestCount());
                    var node=DataWorkerOperationService.find(task.getJSONObject("dsl"),"spark-llm::model");var operations=f.operations.forRunResource(run.getId(),node.getJSONObject("data").getJSONObject("nodeParam").getString("modelResourceId"));assertEquals(1,operations.size());var operation=operations.getFirst();assertEquals(transportFailure?"EFFECT_UNKNOWN":length?"FAILED":"COMPLETED",operation.getState());var privateCandidate=JSON.parseObject(operation.getApprovalJson()).getJSONObject("candidate");assertNotNull(privateCandidate.get("pythonHistoryJson"));assertNull(privateCandidate.get("javaHistoryJson"));
                    var appends=transcript.stream().map(v->(JSONObject)v).filter(v->v.getJSONObject("request").getString("path").endsWith("/history")).toList();assertEquals(transportFailure||length?0:1,appends.size(),"失败不写问答半轮");
                    var accepted=new JSONArray();for(var event:f.runs.eventsThrough(run.getId(),0,run.getLastEventSeq(),1000))accepted.add(ModelMessageSources.sourceObject(event.eventJson()));
                    if(!transportFailure&&!length){var request=appends.getFirst().getJSONObject("request");var q=request.getJSONObject("body").getJSONObject("question");var a=request.getJSONObject("body").getJSONObject("answer");assertEquals("ask="+question,q.get("content"));assertEquals(" 原文本",a.get("content"));assertEquals("assistant",a.get("role"));assertTrue(ToolSchema.same(JSONObject.of("stored",true,"revision",1),appends.getFirst().getJSONObject("response").getJSONObject("body").get("data")));
                        int appendIndex=transcript.indexOf(appends.getFirst());var completed=transcript.stream().map(v->(JSONObject)v).filter(v->"node.completed".equals(v.getJSONObject("request").getJSONObject("body").get("eventType"))&&"spark-llm::model".equals(v.getJSONObject("request").getJSONObject("body").get("nodeId"))).findFirst().orElseThrow();assertTrue(appendIndex<transcript.indexOf(completed));assertNotNull(mapper.pythonEvidence(run.getId(),run.getAttempt(),ModelOperationJson.sha(request.getJSONObject("body").getString("idempotencyKey"))));
                        var modelEvent=accepted.stream().map(v->(JSONObject)v).filter(v->"spark-llm::model".equals(v.get("nodeId"))&&"node.completed".equals(v.get("eventType"))).findFirst().orElseThrow();var display=modelEvent.getJSONObject("payload").getJSONObject("legacyFrame").getJSONObject("workflow_step").getJSONObject("node").getJSONObject("inputs");
                        if(!differentChat&&!expectedOld.isEmpty()){
                            var expectedDisplay=new JSONArray();for(Object old:expectedOld){var message=ModelOperationJson.copy((JSONObject)old);message.put("content_type","text");expectedDisplay.add(message);}
                            assertTrue(ToolSchema.same(expectedDisplay,display.get("chatHistory")),"原调试展示保留ChatMessage默认content_type=text");
                        }else assertFalse(display.containsKey("chatHistory"));
                        if(!differentChat){expectedOld.add(q);expectedOld.add(a);}assertTrue(debug.page(attachment,0).terminal());
                    }
                    var ledger=JSONObject.of("id",operation.getId(),"runId",operation.getRunId(),"leaseId",operation.getLeaseId(),"attempt",operation.getAttempt(),"nodeId",operation.getNodeId(),"workflowVersionId",operation.getWorkflowVersionId(),"dslHash",operation.getDslHash(),"resourceId",operation.getResourceId(),"resourceVersion",operation.getResourceVersion(),"provider",operation.getProvider(),"state",operation.getState(),"failureKind",operation.getFailureKind(),"parametersJson",operation.getParametersJson(),"approvalJson",operation.getApprovalJson(),"lastSeq",operation.getLastSeq(),"finishSeq",operation.getFinishSeq(),"stopConfirmed",operation.getStopConfirmed(),"stopProofId",operation.getStopProofId(),"responseBytes",operation.getResponseBytes(),"usageJson",operation.getUsageJson());
                    var evidence=appends.isEmpty()?null:mapper.pythonEvidence(run.getId(),run.getAttempt(),ModelOperationJson.sha(appends.getFirst().getJSONObject("request").getJSONObject("body").getString("idempotencyKey")));
                    captures.add(JSONObject.of("scope","实际Agent/debugBuild/chat/原Python Worker/中心Service与Mapper；回环SSE，不算真实Provider或浏览器验收","round",round,"assignment",task,"historyAck",ack,"storedSnapshot",mapper.pythonSnapshot(run.getId(),run.getAttempt()),"storedEvidence",evidence,"privateCandidate",privateCandidate,"modelLedger",ledger,"storedModelFrames",f.operations.frames(operation.getId(),0,ModelOperationJson.MAX_FRAMES),"providerWireRaw",actualWire,"actualProviderRequestCount",server.getRequestCount(),"modelState",operation.getState(),"modelFailureKind",operation.getFailureKind(),"worker",captured,"savedResult",ModelMessageSources.sourceObject(run.getResultJson()),"acceptedEvents",accepted));
                    Files.writeString(captureRoot.resolve("python-history-five-runs.json"),JSON.toJSONString(captures,com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
                }
            }finally{producer.close();}
        }
    }
    private void javaManagedModelVariant(int variant) throws Exception {
        javaManagedModelVariant(variant,false);
    }
    private void javaManagedModelVariant(int variant,boolean debugPage) throws Exception {
        var f=v4Fixture();JSONObject gold,wireCase;
        try(var in=getClass().getResourceAsStream("/fixtures/java-model/original-java-model-graph-golden.json")){assertNotNull(in);gold=JSON.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        try(var in=getClass().getResourceAsStream("/fixtures/java-model-wire/original-sdk.json")){assertNotNull(in);wireCase=JSON.parseArray(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONObject(variant);}
        var m=model(space,"original-model","https://provider.invalid/v1/chat/completions");
        var canvas=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("dsl"));var p=canvas.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam");p.remove("modelResourceId");p.put("modelId",m.getId());
        var profile=f.runtime.profile(fixed(m));if(variant==0){p.remove("maxTokens");profile.setProviderDefaultMaxTokens(128);}if(variant==3)p.put("extraParams",JSONObject.of("n",2));
        when(f.runtime.profile(any(ModelRuntimeCatalog.FixedModel.class))).thenReturn(profile);when(f.runtime.profileByGrant(any())).thenReturn(profile);
        try(var server=new okhttp3.mockwebserver.MockWebServer()){
            server.start(java.net.InetAddress.getLoopbackAddress(),0);var raw=wireCase.getJSONArray("rawFrames");var sse=new StringBuilder();for(Object frame:raw)sse.append("data: ").append(ModelOperationJson.canonical(frame,ModelOperationJson.FRAME_BYTES)).append("\n\n");sse.append("data: [DONE]\n\n");server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse.toString()));
            var transport=new ModelPrivateTransport(){@Override public okhttp3.OkHttpClient client(java.net.URI uri,int seconds,List<String> addresses){return new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())).build();}};
            var producer=new ManagedModelProducer(f.runtime,transport);
            try{
                f.calls.usePorts(provider(()->f.policy),provider(()->f.credentials),provider(()->producer),provider(()->f.calls));
                JSONObject flow;
                if(debugPage){
                    var menu=mock(com.ruoyi.framework.web.service.PermissionService.class);when(menu.hasPermi(anyString())).thenReturn(true);var authority=new ResourceAccessService(identities,menu,spaces);authority.useCurrentSession(session);
                    doAnswer(c->authority.requirePermission(c.getArgument(0))).when(access).requirePermission(anyString());doAnswer(c->authority.requireSpace(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3))).when(access).requireSpace(anyLong(),anyString(),anyInt(),anyBoolean());
                    var agents=tx(new AgentService(session.getMapper(com.ruoyi.paiflow.mapper.AgentMapper.class),access,identities,service));target.useAgentBindings(provider(()->agents));var agent=agents.create(space,3,JSONObject.of("name","java model page "+variant,"engineProfile","java","data",canvas));flow=service.detail(agent.getLongValue("workflowId"),space);assertEquals(agent.get("id"),flow.get("botId"));
                }else flow=service.create(space,JSONObject.of("name","java live bridge "+variant,"engineProfile","java","data",canvas));
                var input=ModelOperationJson.copy(gold.getJSONObject("assignment").getJSONObject("input"));JSONObject build=null;com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService debug=null;com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService.Attachment attachment=null;String runId;
                if(debugPage){
                    build=service.debugBuild(flow.getLongValue("id"),space,0L,JSONObject.of("flowId",flow.get("flowId"),"data",canvas),"ordinary",null,"java-model-build_"+UUID.randomUUID());var receipt=build.getJSONObject("debugReceipt");assertEquals(DslCodec.V2,receipt.get("executionCodec"));assertEquals("java",receipt.get("engineProfile"));
                    assertEquals(flow.getLong("botId"),workflows.version(receipt.getLongValue("workflowVersionId")).getDebugAgentId());
                    var defaults=tx(new DefaultPersonalSpaceService(spaces,identities,access));debug=tx(new com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService(service,defaults,access,f.runService,f.runs,f.checkpoints,session.getMapper(com.ruoyi.paiflow.mapper.CheckpointMapper.class)));
                    var body=JSONObject.of("flow_id",flow.get("flowId"),"inputs",input,"chatId","java-model-page-chat","regen",false,"debugReceipt",receipt,"requestKey","java-model-run_"+UUID.randomUUID());attachment=debug.chat(body,Long.toString(space),null);assertEquals(attachment.runId(),debug.chat(body,Long.toString(space),null).runId());runId=attachment.runId();assertEquals(receipt.getLongValue("workflowVersionId"),attachment.workflowVersionId());
                }else{var version=service.createVersion(flow.getLongValue("id"),space,0L,new JSONObject());runId=f.runService.create(version.getLongValue("id"),"java",input,"java_managed_"+UUID.randomUUID(),300).runId();}
                var worker=new WorkerIdentityService.Principal("java-managed-model","java");var task=f.leases.claim(worker);assertEquals(runId,task.get("runId"));
                var captured=javaModelWorkerBridge(f,worker,task);var run=f.runs.find(runId);assertEquals("COMPLETED",run.getState());
                JSONObject submitted=null;for(Object trace:captured.getJSONArray("transcript")){var request=((JSONObject)trace).getJSONObject("request");if(request.getString("path").endsWith("/result")){assertNull(submitted);submitted=request.getJSONObject("body");}}
                assertNotNull(submitted);assertTrue(ToolSchema.same(submitted,ModelMessageSources.sourceObject(run.getResultJson())));
                var workerResult=captured.getJSONObject("result");for(String key:List.of("leaseId","attempt","state","output"))assertTrue(ToolSchema.same(workerResult.get(key),submitted.get(key)),key);assertNull(workerResult.get("error"));assertFalse(submitted.containsKey("error"));
                var modelNode=DataWorkerOperationService.find(task.getJSONObject("dsl"),"spark-llm::2");String resource=modelNode.getJSONObject("data").getJSONObject("nodeParam").getString("modelResourceId");var rows=f.operations.forRunResource(runId,resource);assertEquals(1,rows.size());var row=rows.getFirst();
                assertEquals(variant==4?"FAILED":"COMPLETED",row.getState());assertEquals(variant==4?"provider":null,row.getFailureKind());assertEquals(4,row.getLastSeq());assertEquals(3,row.getFinishSeq());
                var stored=new JSONArray();for(var frame:f.operations.frames(row.getId(),0,100))stored.add(ModelMessageSources.sourceObject(frame.getFrameJson()));assertTrue(ToolSchema.same(raw,stored));
                var accepted=new JSONArray();for(var event:f.runs.eventsThrough(run.getId(),0,run.getLastEventSeq(),1000))accepted.add(ModelMessageSources.sourceObject(event.eventJson()));var sources=new CompletedOutputSources(null,f.operations,null);assertEquals("原文本",sources.values(run,task.getJSONObject("dsl"),DataWorkerOperationService.find(task.getJSONObject("dsl"),"node-end::3"),accepted).get("answer"));
                var request=server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(request);String actualWire=request.getBody().readUtf8();var expected=ModelOperationJson.copy(wireCase.getJSONObject("originalWire"));expected.put("messages",gold.getJSONObject("actualRequest").getJSONObject("parameters").getJSONArray("messages"));assertTrue(ToolSchema.same(expected,JSON.parseObject(actualWire)));assertEquals(1,server.getRequestCount());
                var capture=JSONObject.of("scope","同次真实中心Service/Mapper/ManagedProducer/临时回环SSE/原Worker实时stdio；无项目服务、真实Provider或页面验收","variant",variant,"assignment",task,"worker",captured,"modelOperation",JSONObject.of("operationId",row.getId(),"state",row.getState(),"failureKind",row.getFailureKind(),"lastSeq",row.getLastSeq(),"finishSeq",row.getFinishSeq()),"providerWire",JSON.parseObject(actualWire),"providerWireRaw",actualWire,"actualProviderRequestCount",server.getRequestCount(),"providerFrames",stored,"acceptedEvents",accepted,"savedResult",ModelMessageSources.sourceObject(run.getResultJson()));
                if(debugPage){capture.put("scope","同次固定debugBuild/chat/原Worker/ManagedProducer/Mapper及原页page回读；无项目服务、真实Provider或浏览器验收");capture.put("actualLegacyPage",javaModelLegacyPage(f,debug,attachment,build,task,accepted));assertEquals(1,server.getRequestCount(),"回读/续读不得再次调用供应商");}
                Files.writeString(Path.of(System.getProperty("paiflow.center.javaModelCaptureDir"),"java-model-center-"+run.getId()+".json"),JSON.toJSONString(capture,com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
            }finally{producer.close();}
        }
    }
    private JSONObject javaModelLegacyPage(V4Fixture f,com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService debug,com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService.Attachment attachment,JSONObject build,JSONObject task,JSONArray accepted){
        var page=debug.page(attachment,0);assertTrue(page.terminal());assertFalse(page.waiting());assertEquals("COMPLETED",page.state());assertEquals(f.runs.find(attachment.runId()).getLastEventSeq(),page.watermark());assertEquals(page.watermark(),page.scannedThrough());
        var expected=new java.util.LinkedHashMap<String,JSONObject>();for(Object item:accepted){var event=(JSONObject)item;var raw=event.getJSONObject("payload").getJSONObject("legacyFrame");if(raw!=null){var copy=ModelOperationJson.copy(raw);copy.put("id",attachment.runId());copy.getJSONArray("choices").getJSONObject(0).put("finish_reason",null);expected.put(attachment.runId()+":"+event.getLongValue("seq"),copy);}}
        assertEquals(9,expected.size(),"原三个节点开始/完成与LLM中间流帧");assertEquals(10,page.frames().size());int terminal=0;var frames=new JSONArray();
        for(var item:page.frames()){var node=item.data().getJSONObject("workflow_step").getJSONObject("node");if("flow_obj".equals(node.get("id"))){terminal++;assertEquals("stop",item.data().getJSONArray("choices").getJSONObject(0).get("finish_reason"));}else assertTrue(ToolSchema.same(expected.remove(item.id()),item.data()),"原帧及节点输入输出/推理/usage必须逐值保真");frames.add(JSONObject.of("eventId",item.id(),"frame",item.data()));}
        assertEquals(1,terminal);assertTrue(expected.isEmpty());assertEquals("flow_obj",page.frames().getLast().data().getJSONObject("workflow_step").getJSONObject("node").get("id"));
        long cursor=com.ruoyi.paiflow.adapter.legacy.LegacyDebugBridgeService.cursor(page.frames().get(3).id(),attachment.runId(),page.watermark());var resumed=debug.page(attachment,cursor);assertEquals(page.frames().subList(4,page.frames().size()),resumed.frames());assertTrue(resumed.terminal());var drained=debug.page(attachment,page.scannedThrough());assertTrue(drained.frames().isEmpty());assertTrue(drained.terminal());
        return JSONObject.of("debugBuild",build,"runId",attachment.runId(),"workflowVersionId",attachment.workflowVersionId(),"spaceId",attachment.spaceId(),"watermark",page.watermark(),"scannedThrough",page.scannedThrough(),"terminal",page.terminal(),"state",page.state(),"frames",frames,"resumedFrom",cursor,"resumedFrameCount",resumed.frames().size(),"drainedFrameCount",drained.frames().size());
    }
    private JSONObject javaModelWorkerBridge(V4Fixture f,WorkerIdentityService.Principal worker,JSONObject task) throws Exception {
        return javaModelWorkerBridge(f,worker,task,"COMPLETED");
    }
    private JSONObject javaModelWorkerBridge(V4Fixture f,WorkerIdentityService.Principal worker,JSONObject task,String expectedState) throws Exception {
        return modelWorkerBridge(f,worker,task,expectedState,false);
    }
    private JSONObject modelWorkerBridge(V4Fixture f,WorkerIdentityService.Principal worker,JSONObject task,String expectedState,boolean python) throws Exception {
        String commandProperty=python?"paiflow.python.model.pipeCommandFile":"paiflow.model.pipeCommandFile";
        String captureProperty=python?"paiflow.center.pythonModelCaptureDir":"paiflow.center.javaModelCaptureDir";
        var command=new java.util.ArrayList<>(JSON.parseArray(Files.readString(Path.of(System.getProperty(commandProperty)),StandardCharsets.UTF_8)).toJavaList(String.class));var root=Path.of(System.getProperty(captureProperty));Files.createDirectories(root);var workerOutput=root.resolve("worker-model-"+task.getString("runId")+".json");command.add(Instant.now().toString());command.add(workerOutput.toAbsolutePath().toString());command.add(expectedState);var process=new ProcessBuilder(command).redirectError(root.resolve("worker-model-"+task.getString("runId")+".stderr.log").toFile()).start();var reader=java.util.concurrent.Executors.newSingleThreadExecutor();var transcript=new JSONArray();JSONObject result=null;int claims=0,begins=0,invocations=0;
        try(var input=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));var output=new java.io.BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8))){
            for(int i=0;i<256;i++){
                var pending=reader.submit(()->input.readLine());String line;try{line=pending.get(15,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){pending.cancel(true);process.destroyForcibly();throw failure;}if(line==null)break;var message=JSON.parseObject(line);if("result".equals(message.get("type"))){result=message.getJSONObject("result");break;}var trace=JSONObject.of("request",message);transcript.add(trace);assertEquals("request",message.get("type"));assertEquals("POST",message.get("method"));var body=message.getJSONObject("body");String path=message.getString("path");JSONObject value,envelope;int status=200;
                try{
                    if("/internal/paiflow/v1/runs/claim".equals(path)){assertEquals(0,claims++);if(python)assertTrue(ToolSchema.same(JSONObject.of("engineProfile","python"),body));else assertTrue(body.isEmpty());value=task;}
                    else{
                        String prefix="/internal/paiflow/v1/runs/"+task.getString("runId")+"/";assertTrue(path.startsWith(prefix));String action=path.substring(prefix.length());String id=task.getString("runId");
                        value=switch(action){
                            case "lease/verify" -> f.leases.verify(id,worker,body.getString("leaseId"),RunLeaseService.attempt(body));
                            case "execution/begin" -> {assertEquals(0,begins++);yield f.leases.beginExecution(id,worker,body.getString("leaseId"),RunLeaseService.attempt(body));}
                            case "heartbeat" -> f.leases.heartbeat(id,worker,body.getString("leaseId"),RunLeaseService.attempt(body));
                            case "events" -> f.leases.append(id,worker,body);
                            case "history/java/open" -> {assertNotNull(javaHistory,"历史接线须显式装配正式服务");yield javaHistory.openJava(id,worker,body);}
                            case "history/python/read" -> {assertTrue(python);assertNotNull(javaHistory);yield javaHistory.readPython(id,worker,body);}
                            case "history" -> {assertTrue(python);assertNotNull(javaHistory);yield javaHistory.append(id,worker,body);}
                            case "operations" -> {assertEquals(0,invocations++);yield f.calls.start(id,worker,body);}
                            case "result" -> f.leases.result(id,worker,body);
                            default -> {
                                if(action.startsWith("resources/"))yield f.calls.resource(id,worker,java.net.URLDecoder.decode(action.substring(10),StandardCharsets.UTF_8),body);
                                var parts=action.split("/");if(parts.length==3&&"operations".equals(parts[0])&&"frames".equals(parts[2]))yield f.calls.frames(id,worker,parts[1],body);
                                if(parts.length==3&&"operations".equals(parts[0])&&"cancel".equals(parts[2]))yield f.calls.cancel(id,worker,parts[1],body);
                                throw new AssertionError("未接实际Worker模型入口："+action);
                            }
                        };
                    }
                    envelope=JSONObject.of("code",200,"msg","操作成功","data",value);
                }catch(ServiceException denied){status=denied.getCode();envelope=JSONObject.of("code",status,"msg",denied.getMessage());}
                var reply=JSONObject.of("id",message.get("id"),"status",status,"body",envelope);trace.put("response",reply);output.write(JSON.toJSONString(reply,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));output.newLine();output.flush();
            }
            assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"原Worker失败，详见同次stderr/transcript");assertNotNull(result);assertEquals(expectedState,result.get("state"));assertEquals(1,claims);assertEquals(1,begins);assertEquals(1,invocations);var capture=JSON.parseObject(Files.readString(workerOutput,StandardCharsets.UTF_8));assertTrue(ToolSchema.same(result,capture.get("result")));return JSONObject.of("result",result,"transcript",transcript,"capture",capture);
        }finally{reader.shutdownNow();if(process.isAlive())process.destroyForcibly();Files.writeString(root.resolve("transcript-model-"+task.getString("runId")+".json"),JSON.toJSONString(transcript,com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);}
    }
    @Test void javaNullableVersionPreservesOriginalCanvasButRejectsRunWithoutProviderDefaultBudget() throws Exception {
        var f=v4Fixture();var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var canvas=graph(m.getId(),"domain-model");var c=service.create(space,JSONObject.of("name","java model","engineProfile","java","data",canvas));
        var version=service.createVersion(c.getLongValue("id"),space,0L,new JSONObject());var stored=workflows.version(version.getLongValue("id"));assertEquals(1,workflows.countVersions(c.getLongValue("id")));
        assertEquals("java",stored.getEngineProfile());assertEquals(DslCodec.V2,stored.getExecutionCodec());assertEquals(canvas,JSON.parseObject(stored.getCanvasJson()));
        var snapshot=service.runtimeSnapshot(stored.getId());var p=param(snapshot.execution());assertFalse(p.containsKey("maxTokens"));assertFalse(p.containsKey("temperature"));assertFalse(p.containsKey("topK"));assertFalse(p.containsKey("url"));assertEquals("openai",p.get("source"));
        assertEquals(fixed(m).publicDescriptor(),snapshot.resourceSnapshots().getJSONObject(fixed(m).resourceId()));
        assertEquals(503,assertThrows(ServiceException.class,()->f.runService.create(stored.getId(),"java",new JSONObject(),"java_model_pending",300)).getCode());assertNull(f.grant.get());
        try(var connection=db.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM paiflow_run")){assertTrue(result.next());assertEquals(0,result.getInt(1));}
        var g=graph(m.getId(),"domain-model");g.getJSONArray("nodes").getJSONObject(1).put("id","iteration::i");g.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").put("nested",graph(m.getId(),"domain-model"));assertEquals(503,assertThrows(ServiceException.class,()->WorkflowModelCompiler.compile(g,(id,s,d)->fixed(m))).getCode());
    }
    @Test void immutableTupleCopiesCachedMutableDtoBeforeLocalProfileRead(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var mutable=models.version(m.getLatestVersionId());var modelStub=mock(ModelMapper.class);when(modelStub.find(m.getId())).thenReturn(m);when(modelStub.version(m.getLatestVersionId())).thenReturn(mutable);var local=new ModelRuntimeCatalog(modelStub,spaces,access,users,permissions);local.useProfiles(provider(()->s->{String fingerprint=s.sourceFingerprint();mutable.setCredentialVersion("mutated");mutable.setConfigurationJson("{}");mutable.setCandidateHash("b".repeat(64));assertEquals("fixture-ref-v1",s.credentialVersion());return new ModelRuntimeCatalog.RuntimeProfile(fingerprint,"openai",Set.of("chat"));}));var f=local.bindCurrent(space,m.getId(),"openai","domain-model");assertEquals("fixture-ref-v1",f.credentialVersion());assertEquals("env:ISOLATED_REFERENCE",f.credentialRef());assertEquals("a".repeat(64),f.candidateHash());
    }
    @Test void localProfileFailureNeverReturnsItsPrivateEndpointOrCredentialMessage(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");profile.set(s->{throw new ServiceException(s.privateEndpoint()+" "+s.credentialRef(),403);});
        var rejected=assertThrows(ServiceException.class,()->fixed(m));assertEquals(503,rejected.getCode());assertFalse(rejected.getMessage().contains("provider.invalid"));assertFalse(rejected.getMessage().contains("ISOLATED_REFERENCE"));assertEquals(1,models.countVersions(m.getId()));assertEquals(0,workflows.count(space,null,null));
    }
    @Test void v2ExplicitNullModelOptionsCannotDisappearIntoDefaultOrWriteVersion(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var g=graph(m.getId(),"domain-model");param(g).put("temperature",null);var row=service.create(space,JSONObject.of("name","explicit-null","data",g));long id=row.getLongValue("id");
        assertTrue(JSON.parseObject(workflows.find(id).getCanvasJson()).getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam").containsKey("temperature"));assertEquals(422,assertThrows(ServiceException.class,()->service.createVersion(id,space,0L,new JSONObject())).getCode());assertEquals(0,workflows.countVersions(id));assertEquals(0L,workflows.find(id).getRevision());assertNull(workflows.find(id).getLatestVersionId());
    }
    @Test void nullCodecModelResourceReconstructionUsesOriginalV1DataAndBytes(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var f=fixed(m);var g=graph(m.getId(),"domain-model");var d=g.getJSONArray("nodes").getJSONObject(1).getJSONObject("data");d.put("label","新编译才使用的页面别名");d.put("nodeMeta",JSONObject.of("nodeType","spark-llm","aliasName","旧V1别名"));param(g).put("configs",JSONObject.of("legacy","opaque"));
        var resources=JSONObject.of(f.resourceId(),f.publicDescriptor());var old=WorkflowModelCompiler.reconstruct(g,resources,null);assertEquals("旧V1别名",old.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeMeta").get("aliasName"));assertTrue(param(old).containsKey("configs"));var n=new DslCodec().normalizeV1(old);
        var created=service.create(space,JSONObject.of("name","historical-v1-model","data",g));var row=workflows.find(created.getLongValue("id"));var v=new WorkflowVersionRecord();v.setWorkflowId(row.getId());v.setSpaceId(space);v.setOwnerUserId(42L);v.setSnapshotRevision(0L);v.setFlowId(row.getFlowId());v.setVersionNum("source-v1-resource");v.setName("source-v1-resource");v.setCanvasJson(row.getCanvasJson());v.setExecutionJson(n.execution().toJSONString());v.setResourceSnapshotsJson(resources.toJSONString());v.setDslHash(n.executionHash());v.setCanonicalDslBase64(DslCodec.canonicalDslBase64(n.execution(),DslCodec.V1));v.setMetadataJson("{}");v.setAdvancedConfigJson("{}");v.setFlowConfigJson("{}");v.setEngineProfile("python");v.setCreateBy("fixture");assertEquals(1,workflows.insertVersion(v));
        profile.set(null);assertEquals(n.execution(),service.runtimeSnapshot(v.getId()).execution());assertEquals(v.getCanonicalDslBase64(),workflows.version(v.getId()).getCanonicalDslBase64());assertNull(workflows.version(v.getId()).getExecutionCodec());assertEquals(DslCodec.V1,service.versionDetail(v.getId()).get("executionCodec"));assertNotEquals(DslCodec.fixedExecutionHash(old,DslCodec.V1),DslCodec.fixedExecutionHash(WorkflowModelCompiler.reconstruct(g,resources,DslCodec.V2),DslCodec.V2));
    }
}
