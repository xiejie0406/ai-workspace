package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.domain.ModelRecord;
import com.ruoyi.paiflow.domain.ModelVersionRecord;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.ModelMapper;
import com.ruoyi.paiflow.mapper.ModelOperationMapper;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 原始中心Mapper/RC事务＋内存批准/Producer回调；无HTTP、真实DB、秘密、SDK或默认运行装配。 */
public class ModelOperationPersistenceTest {
    private JdbcDataSource db;private java.sql.Connection schema;private SqlSessionTemplate session;private ModelMapper models;private ModelOperationMapper operations;private RunMapper runMapper;private SpaceMapper spaces;private SysPermissionService permissions;
    private ModelOperationService service;private RunService runService;private RunLeaseService leases;private ModelRuntimeCatalog catalog;private WorkflowService workflows;
    private long space,modelId,modelVersionId;private ModelRuntimeCatalog.FixedModel fixed;private JSONObject execution,resources;
    private JSONObject runResourcesExpectation;
    private final WorkerIdentityService.Principal worker=new WorkerIdentityService.Principal("isolated-python","python");
    private final MutableClock clock=new MutableClock();
    private final AtomicReference<InvocationPolicy> policy=new AtomicReference<>();
    private final AtomicReference<CredentialMaterialPort> credentials=new AtomicReference<>();
    private final AtomicReference<ProducerBridge> bridge=new AtomicReference<>();
    private final AtomicReference<Runnable> currentCallHook=new AtomicReference<>();
    private final AtomicReference<Runnable> currentRunHook=new AtomicReference<>();
    private final AtomicReference<Runnable> approveRunHook=new AtomicReference<>();
    private final AtomicReference<PrivateGrant> lastGrant=new AtomicReference<>();
    private final AtomicReference<StopEvidence> stopEvidence=new AtomicReference<>();
    private final AtomicInteger submits=new AtomicInteger(),resolves=new AtomicInteger(),stops=new AtomicInteger();
    private final String fixtureKey="isolated-private-material-never-real";
    private final AtomicLong callSeconds=new AtomicLong(90);
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value=new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return value.get();}
        void advance(long seconds){value.updateAndGet(i->i.plusSeconds(seconds));}
    }
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(java.util.function.Supplier<T> value){var p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenAnswer(c->value.get());return p;}
    @SuppressWarnings("unchecked") private <T> T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    @BeforeEach void setup() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");
        schema=db.getConnection();
        // H2 2.4.240的IN常量CHECK持有创建session；保留DDL连接，不放宽原约束。
        try(var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.ModelOperationPersistenceTest.utcNow'");
            for(String file:List.of("001-paiflow-schema.sql","003-paiflow-run.sql","006-paiflow-execution-start.sql","009-paiflow-model.sql","013-paiflow-model-operation.sql","017-paiflow-run-json-codec.sql")){
                String ddl=Files.readString(Path.of("sql",file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET ascii","")
                    .replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END").replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END");
                for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            }
            sql.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(128),status VARCHAR(1))");sql.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");sql.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");sql.execute("CREATE TABLE sys_role(role_id BIGINT,status VARCHAR(1))");
            sql.execute("INSERT INTO sys_menu VALUES(1,'paiflow:run:create','0'),(2,'paiflow:model:query','0')");sql.execute("INSERT INTO sys_role_menu VALUES(88,1),(88,2)");sql.execute("INSERT INTO sys_user_role VALUES(42,88)");sql.execute("INSERT INTO sys_role VALUES(88,'0')");
        }
        var config=new Configuration(new Environment("model-operation-isolated",new SpringManagedTransactionFactory(),db));
        config.getTypeAliasRegistry().registerAlias("SysMenu",SysMenu.class);
        for(String name:List.of("SpaceMapper","ModelMapper","RunMapper","ModelOperationMapper","IdentityMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){assertNotNull(xml);new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();}
        try(var xml=Files.newInputStream(Path.of(System.getProperty("paiflow.systemSource","../ruoyi-system")).resolve("src/main/resources/mapper/system/SysMenuMapper.xml"))){new XMLMapperBuilder(xml,config,"SysMenuMapper",config.getSqlFragments()).parse();}
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));spaces=session.getMapper(SpaceMapper.class);models=session.getMapper(ModelMapper.class);runMapper=session.getMapper(RunMapper.class);operations=session.getMapper(ModelOperationMapper.class);
        var s=new SpaceRecord();s.setName("operation source fixture");s.setKind("personal");s.setOwnerUserId(42L);s.setCreateBy("fixture");s.setUpdateBy("fixture");assertEquals(1,spaces.insert(s));space=s.getId();assertEquals(1,spaces.insertOwner(space,42,"fixture"));
        var users=mock(ISysUserService.class);var user=new SysUser();user.setUserId(42L);user.setUserName("fixture");user.setStatus("0");user.setDelFlag("0");when(users.selectUserById(42L)).thenReturn(user);
        var menuService=new SysMenuServiceImpl();org.springframework.test.util.ReflectionTestUtils.setField(menuService,"menuMapper",session.getMapper(SysMenuMapper.class));permissions=new SysPermissionService();org.springframework.test.util.ReflectionTestUtils.setField(permissions,"menuService",menuService);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));var principal=new LoginUser(42L,8L,user,Set.of("paiflow:run:create","paiflow:model:query"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        var identities=new IdentityMappingService(session.getMapper(IdentityMapper.class),users);identities.useCurrentSession(session);identities.useCurrentPermissions(permissions);var access=new ResourceAccessService(identities,new PermissionService(),spaces);access.useCurrentSession(session);
        catalog=new ModelRuntimeCatalog(models,spaces,access,users,permissions);catalog.useCurrentSession(session);catalog.useProfiles(provider(()->source->new ModelRuntimeCatalog.RuntimeProfile(source.sourceFingerprint(),"openai",Set.of("chat"))));
        seedModel();fixed=catalog.bindCurrent(space,modelId,"openai","actual-domain");resources=JSONObject.of(fixed.resourceId(),fixed.publicDescriptor());
        var canvas=JSON.parseObject("{\"nodes\":[{\"id\":\"node-start::s\",\"data\":{}},{\"id\":\"spark-llm::m\",\"data\":{\"inputs\":[{\"name\":\"q\",\"type\":\"string\"}],\"outputs\":[{\"name\":\"answer\",\"type\":\"string\"}],\"nodeParam\":{\"modelId\":"+modelId+",\"source\":\"openai\",\"domain\":\"actual-domain\",\"template\":\"{{q}}\"}}},{\"id\":\"node-end::e\",\"data\":{}}],\"edges\":[{\"source\":\"node-start::s\",\"target\":\"spark-llm::m\"},{\"source\":\"spark-llm::m\",\"target\":\"node-end::e\"}]}");
        execution=WorkflowModelCompiler.compile(canvas,(id,source,domain)->fixed).execution();var normalized=new DslCodec().normalize(execution);
        workflows=mock(WorkflowService.class);when(workflows.runtimeSnapshot(2)).thenReturn(new WorkflowService.RuntimeSnapshot(1,2,space,42,"actual-flow","python",normalized.executionHash(),DslCodec.canonicalDslBase64(normalized.execution()),normalized.execution(),canvas,new JSONObject(),new JSONObject(),resources));
        var leaseTarget=new RunLeaseService(runMapper,spaces,users,permissions,clock);leaseTarget.useCurrentSession(session);leases=proxy(leaseTarget);var target=new ModelOperationService(operations,runMapper,spaces,leases,catalog,clock);target.useCurrentSession(session);target.useCompletedSources(new CompletedOutputSources(null,operations,null));service=proxy(target);target.usePorts(provider(policy::get),provider(credentials::get),provider(bridge::get),provider(()->service));
        var runTarget=new RunService(workflows,runMapper,access,identities);runService=proxy(runTarget);runTarget.useModelOperations(provider(()->service));ports();
    }
    @AfterEach void close() throws Exception {SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();if(schema!=null)schema.close();}
    private void seedModel(){var m=new ModelRecord();m.setSpaceId(space);m.setOwnerUserId(42L);m.setName("model fixture");m.setType(1);m.setState("VALIDATED");m.setEnabled(1);m.setRevision(0L);m.setConfigurationJson(JSONObject.of("domain","actual-domain","provider","source-provider-label","endpoint","https://private-provider.invalid/v1/chat/completions","credentialRef","env:ISOLATED_PRIVATE_REFERENCE").toJSONString());m.setCandidateHash("a".repeat(64));m.setCredentialVersion("isolated-ref-v1");m.setReceiptJson("{\"testFixture\":true}");m.setCreateBy("fixture");m.setUpdateBy("fixture");assertEquals(1,models.insert(m));modelId=m.getId();var v=new ModelVersionRecord();v.setModelId(modelId);v.setSpaceId(space);v.setOwnerUserId(42L);v.setRevision(0L);v.setType(1);v.setState("VALIDATED");v.setEnabled(1);v.setConfigurationJson(m.getConfigurationJson());v.setCandidateHash(m.getCandidateHash());v.setCredentialVersion(m.getCredentialVersion());v.setReceiptJson(m.getReceiptJson());v.setCreateBy("fixture");assertEquals(1,models.insertVersion(v));modelVersionId=v.getId();assertEquals(1,models.selectVersion(modelId,0,modelVersionId));}
    private void ports(){
        policy.set(new InvocationPolicy(){
            public RunPermit approveRun(RunCandidate c){assertEquals(42,c.ownerUserId());assertEquals(space,c.spaceId());assertEquals("实际中心输入",JSON.parseObject(c.inputJson()).getString("q"));assertEquals(runResourcesExpectation==null?resources:runResourcesExpectation,JSON.parseObject(c.resourcesJson()));Runnable hook=approveRunHook.getAndSet(null);if(hook!=null)hook.run();return new RunPermit(c.fingerprint(),"isolated-run-approval",clock.instant().plusSeconds(120));}
            public boolean currentRun(RunCandidate c,RunPermit p){Runnable hook=currentRunHook.getAndSet(null);if(hook!=null)hook.run();return Objects.equals(c.fingerprint(),p.fingerprint());}
            public CallPermit approveCall(CallCandidate c){assertEquals("spark-llm::m",c.nodeId());assertEquals(fixed.resourceId(),c.resourceId());assertEquals(String.valueOf(modelVersionId),c.resourceVersion());assertEquals("{{q}}",JSON.parseObject(c.nodeJson()).getJSONObject("data").getJSONObject("nodeParam").getString("template"));JSONObject params=JSON.parseObject(c.parametersJson());String input=JSON.parseObject(c.inputJson()).getString("q");if(!params.getJSONArray("messages").equals(new JSONArray(List.of(JSONObject.of("role","user","content",input)))))throw new ServiceException("isolated policy: messages来源与中心输入不匹配",403);return new CallPermit(c.fingerprint(),"isolated-call-approval",clock.instant().plusSeconds(callSeconds.get()));}
            public boolean currentCall(CallCandidate c,CallPermit p){Runnable hook=currentCallHook.getAndSet(null);if(hook!=null)hook.run();return Objects.equals(c.fingerprint(),p.fingerprint());}
        });
        credentials.set(new CredentialMaterialPort(){public boolean available(ModelRuntimeCatalog.FixedModel f){return f.modelVersionId()==modelVersionId;}public PrivateMaterial resolve(ModelRuntimeCatalog.FixedModel f){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());resolves.incrementAndGet();return new PrivateMaterial(f.credentialRef(),f.credentialVersion(),f.privateBaseUrl(),fixtureKey);}});
        bridge.set(new ProducerBridge(){public boolean available(ModelRuntimeCatalog.FixedModel f){return "openai".equals(f.provider());}public DispatchAck submit(PrivateGrant g,ProducerCallbacks callbacks){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());submits.incrementAndGet();lastGrant.set(g);assertTrue(callbacks.assertActive(g).getBooleanValue("active"));return new DispatchAck(g.operationId(),g.grantId(),true);}public StopEvidence stop(String operation,String grant,String reason){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals("worker_cancel",reason);stops.incrementAndGet();return stopEvidence.get();}});
    }
    @Test void modelAdmissionSeparatesPluginFromModelResourcesWithoutGrantingToolEffects() throws Exception {
        var mixed=JSON.parseObject(execution.toJSONString());
        mixed.getJSONArray("nodes").add(JSONObject.of("id","plugin::tool","data",JSONObject.of("nodeParam",
            JSONObject.of("toolResourceId","tool:8:version:9:operation:bG9va3Vw"))));
        var bindings=JSON.parseObject(resources.toJSONString());
        bindings.put("tool:8:version:9:operation:bG9va3Vw",JSONObject.of("kind","tool","provider","spark-link","version","9"));
        runResourcesExpectation=bindings;
        var permit=service.admit(UUID.randomUUID().toString(),42,space,2,"a".repeat(64),
            JSONObject.of("q","实际中心输入"),bindings,mixed,clock.instant().plusSeconds(300));
        assertNotNull(permit.get("approvalRef"));
        assertEquals(0,submits.get());assertEquals(0,resolves.get());assertEquals(0,rows("paiflow_model_operation"));
        // 此方法只签模型运行许可；RunService还须独立调用真实ToolOperationService.admit。
        mixed.getJSONArray("nodes").getJSONObject(mixed.getJSONArray("nodes").size()-1).put("id","agent::unconnected");
        assertEquals(503,assertThrows(ServiceException.class,()->service.admit(UUID.randomUUID().toString(),42,space,2,
            "a".repeat(64),JSONObject.of("q","实际中心输入"),bindings,mixed,clock.instant().plusSeconds(60))).getCode());
    }
    @Test void pluginWithoutAnyModelCannotAcquireModelRunPermit() {
        var mixed=JSON.parseObject(execution.toJSONString());
        mixed.getJSONArray("nodes").removeIf(raw->((JSONObject)raw).getString("id").startsWith("spark-llm::"));
        mixed.getJSONArray("nodes").add(JSONObject.of("id","plugin::tool","data",JSONObject.of("nodeParam",new JSONObject())));
        assertThrows(ServiceException.class,()->service.admit(UUID.randomUUID().toString(),42,space,2,"a".repeat(64),
            new JSONObject(),new JSONObject(),mixed,clock.instant().plusSeconds(60)));
        assertEquals(0,submits.get());assertEquals(0,resolves.get());
    }
    private JSONObject claim(){var run=runService.create(2,"python",JSONObject.of("q","实际中心输入"),"fixture_"+UUID.randomUUID().toString().replace("-",""),300);var task=leases.claim(worker);assertEquals(run.runId(),task.getString("runId"));leases.beginExecution(run.runId(),worker,task.getString("leaseId"),task.getIntValue("attempt"));return task;}
    private static JSONObject node(JSONObject execution){return execution.getJSONArray("nodes").getJSONObject(1);}
    private JSONObject request(JSONObject task,String key){var params=JSONObject.of("resourceId",fixed.resourceId(),"resourceVersion",String.valueOf(modelVersionId),"provider","openai","modelName","actual-domain","mode","chat","messages",new JSONArray(List.of(JSONObject.of("role","user","content","实际中心输入"))),"options",ModelOperationOptions.fromNode(node(execution).getJSONObject("data").getJSONObject("nodeParam")));return JSONObject.of("leaseId",task.getString("leaseId"),"attempt",task.getIntValue("attempt"),"nodeId","spark-llm::m","iterationId",null,"operation","model.invoke","parameters",params,"idempotencyKey",key);}
    private JSONObject started(JSONObject task){return service.start(task.getString("runId"),worker,request(task,"fixture_call"));}
    private JSONObject frames(JSONObject task,String operation,long after,int limit){return service.frames(task.getString("runId"),worker,operation,JSONObject.of("leaseId",task.getString("leaseId"),"attempt",task.getIntValue("attempt"),"afterCursor",after,"limit",limit));}
    private static JSONObject raw(String text,String finish){return JSON.parseObject("{\"id\":\"actual-provider-frame\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}").fluentPut("choices",new JSONArray(List.of(JSONObject.of("index",0,"delta",JSONObject.of("content",text),"finish_reason",finish))));}
    private static JSONObject usage(){return JSONObject.of("id","actual-provider-frame","choices",new JSONArray(),"usage",JSONObject.of("prompt_tokens",3,"completion_tokens",2,"total_tokens",5),"optional",null);}
    private long rows(String table) throws Exception {try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}}
    private void sql(String statement) throws Exception {try(var c=db.getConnection();var s=c.createStatement()){s.execute(statement);}}
    private int fixtureUpdate(String statement) throws Exception {try(var c=db.getConnection();var s=c.createStatement()){return s.executeUpdate(statement);}}
    private void menuStatus(String permission,String status){try{assertEquals(1,fixtureUpdate("UPDATE sys_menu SET status='"+status+"' WHERE perms='"+permission+"'"));}catch(Exception ex){throw new IllegalStateException(ex);}}
    @Test void oldCandidateFingerprintWithoutPrivateInputFieldStillReadsAndReplays() throws Exception {var task=claim();var request=request(task,"legacy_candidate");var ack=service.start(task.getString("runId"),worker,request);var row=operations.find(ack.getString("operationId"));var approval=JSON.parseObject(row.getApprovalJson());var facts=approval.getJSONObject("candidate");assertNotNull(facts.remove("verifiedInputsJson"));String fingerprint=ModelOperationJson.sha(ModelOperationJson.canonical(facts,ModelOperationJson.REQUEST_BYTES));approval.put("fingerprint",fingerprint);try(var connection=db.getConnection();var statement=connection.prepareStatement("UPDATE paiflow_model_operation SET approval_json=? WHERE id=?")){statement.setString(1,ModelOperationJson.canonical(approval,ModelOperationJson.REQUEST_BYTES));statement.setString(2,row.getId());assertEquals(1,statement.executeUpdate());}session.clearCache();assertEquals(ack,service.start(task.getString("runId"),worker,request));assertEquals(1,submits.get());assertEquals(1,resolves.get());}

    @Test void actualRunAdmissionPersistsBoundApprovalAndNeverPublicPrivateMaterial() throws Exception {
        var task=claim();var stored=runMapper.find(task.getString("runId"));assertNotNull(stored.getModelPermitJson());assertEquals("approved_write",task.getJSONObject("effectPolicy").getString("mode"));assertEquals(resources,task.getJSONObject("resourceSnapshots"));assertFalse(stored.getAssignmentJson().contains("private-provider.invalid"));assertFalse(stored.getAssignmentJson().contains("ISOLATED_PRIVATE_REFERENCE"));assertFalse(stored.getAssignmentJson().contains(fixtureKey));assertEquals(0,rows("paiflow_model_operation"));assertEquals(0,submits.get());assertEquals(0,resolves.get());
    }
    @Test void missingAnyRequiredPortProducesZeroRunAndZeroOperationWrites() throws Exception {
        for(String missing:List.of("policy","credential","bridge")){ports();if(missing.equals("policy"))policy.set(null);if(missing.equals("credential"))credentials.set(null);if(missing.equals("bridge"))bridge.set(null);assertEquals(503,assertThrows(ServiceException.class,()->runService.create(2,"python",JSONObject.of("q","实际中心输入"),"missing_"+missing,300)).getCode());assertEquals(0,rows("paiflow_run"));assertEquals(0,rows("paiflow_model_operation"));assertEquals(0,submits.get());}
    }
    @Test void actualRunMenuRevokedDuringApproveRunCannotCreateRunOrOperation() throws Exception {
        assertTrue(new PermissionService().hasPermi("paiflow:run:create"));approveRunHook.set(()->menuStatus("paiflow:run:create","1"));
        assertEquals(403,assertThrows(ServiceException.class,()->runService.create(2,"python",JSONObject.of("q","实际中心输入"),"approve_run_revoke",300)).getCode());
        var user=new SysUser();user.setUserId(42L);assertTrue(permissions.getMenuPermission(user).contains("paiflow:model:query"));assertFalse(permissions.getMenuPermission(user).contains("paiflow:run:create"));assertEquals(0,rows("paiflow_run"));assertEquals(0,rows("paiflow_model_operation"));assertEquals(0,submits.get());assertEquals(0,resolves.get());
    }
    @Test void sameActualKeyReplaysReceiptButDifferentRequestCannotReuseItsApproval() throws Exception {
        var task=claim();var req=request(task,"实际幂等ID:"+"x".repeat(300));var first=service.start(task.getString("runId"),worker,req);var replay=service.start(task.getString("runId"),worker,ModelOperationJson.copy(req));assertEquals(first,replay);assertEquals(1,submits.get());assertEquals(1,rows("paiflow_model_operation"));
        var conflict=ModelOperationJson.copy(req);conflict.getJSONObject("parameters").getJSONArray("messages").getJSONObject(0).put("content","不同来源请求");assertEquals(409,assertThrows(ServiceException.class,()->service.start(task.getString("runId"),worker,conflict)).getCode());assertEquals(1,submits.get());assertEquals(1,rows("paiflow_model_operation"));
    }
    @Test void twoConcurrentCallersDispatchOneActualOperationAndNoAutomaticRetry() throws Exception {
        var task=claim();var req=request(task,"parallel-call");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{Callable<JSONObject> call=()->{start.await();return service.start(task.getString("runId"),worker,ModelOperationJson.copy(req));};var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,submits.get());assertEquals(1,rows("paiflow_model_operation"));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void workerMessagesNeedActualInputAndFixedNodeApprovalInsteadOfSelfReportedHash() throws Exception {
        var task=claim();var req=request(task,"forged-messages");req.getJSONObject("parameters").getJSONArray("messages").getJSONObject(0).put("content","伪造输入");assertEquals(403,assertThrows(ServiceException.class,()->service.start(task.getString("runId"),worker,req)).getCode());assertEquals(0,rows("paiflow_model_operation"));assertEquals(0,submits.get());
    }
    @Test void originalFramesKeepNullUsageTailAndOnlyActualStreamEndCompletes() {
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();service.commitFrame(grant,1,raw("原文本",null));service.commitFrame(grant,2,raw(null,"stop"));assertEquals("RUNNING",operations.find(operation).getState());service.commitFrame(grant,3,usage());assertEquals("RUNNING",operations.find(operation).getState());assertEquals(2L,operations.find(operation).getFinishSeq());assertTrue(operations.frame(operation,2).getFrameJson().contains("\"content\":null"));assertTrue(operations.frame(operation,3).getFrameJson().contains("\"optional\":null"));
        var first=frames(task,operation,0,1);assertEquals(1,first.getJSONArray("frames").size());assertEquals(raw("原文本",null),first.getJSONArray("frames").getJSONObject(0));assertFalse(first.getBooleanValue("done"));assertEquals(1L,first.getLongValue("nextCursor"));
        service.finishStream(grant,3,2,"stop");var tail=frames(task,operation,1,64);assertEquals("COMPLETED",tail.getString("state"));assertTrue(tail.getBooleanValue("done"));assertEquals(new JSONArray(List.of(raw(null,"stop"),usage())),tail.getJSONArray("frames"));assertEquals(3L,tail.getLongValue("nextCursor"));
        var empty=frames(task,operation,3,64);assertTrue(empty.getJSONArray("frames").isEmpty());assertEquals(3L,empty.getLongValue("nextCursor"));assertTrue(empty.getBooleanValue("done"));assertEquals("COMPLETED",empty.getString("state"));assertEquals(1,submits.get());
    }
    @Test void exactFrameReplayIsIdempotentButChangedFramesAndGapsAreRejected() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();var frame=raw("文本",null);service.commitFrame(grant,1,frame);service.commitFrame(grant,1,ModelOperationJson.copy(frame));assertEquals(1,rows("paiflow_model_frame"));assertEquals(1L,operations.find(operation).getLastSeq());assertEquals(409,assertThrows(ServiceException.class,()->service.commitFrame(grant,1,raw("不同",null))).getCode());assertEquals(409,assertThrows(ServiceException.class,()->service.commitFrame(grant,3,frame)).getCode());
    }
    @Test void frameInsertAndSequenceCasRollBackTogetherOnActualConstraintFailure() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");sql("ALTER TABLE paiflow_model_operation ADD CONSTRAINT isolated_fail_sequence CHECK(last_seq=0)");assertThrows(RuntimeException.class,()->service.commitFrame(lastGrant.get(),1,raw("待回滚",null)));assertEquals(0,rows("paiflow_model_frame"));assertEquals(0L,operations.find(operation).getLastSeq());assertEquals("RUNNING",operations.find(operation).getState());
    }
    @Test void bareEofAndUnknownStopPreserveEffectUnknownAndNeverRedispatch() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();service.commitFrame(grant,1,raw("已产生部分费用事实",null));service.recordFailure(grant,new FailureFacts("provider","STREAM_EOF_WITHOUT_FINISH",true,true,false));assertEquals("EFFECT_UNKNOWN",operations.find(operation).getState());assertNull(operations.find(operation).getFinishSeq());started(task);assertEquals(1,submits.get());
        var cancel=service.cancel(task.getString("runId"),worker,operation,JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1));assertEquals("EFFECT_UNKNOWN",cancel.getString("state"));assertFalse(cancel.getBooleanValue("stopped"));assertEquals(1,stops.get());assertEquals(1,rows("paiflow_model_frame"));
    }
    @Test void confirmedStopUsesExactGrantProofAndDoesNotClaimUndeliveredOutput() {
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();stopEvidence.set(new StopEvidence(operation,grant.grantId(),true,"isolated-actual-stop-proof"));var ack=service.cancel(task.getString("runId"),worker,operation,JSONObject.of("leaseId",task.getString("leaseId"),"attempt",1));assertEquals("CANCELLED",ack.getString("state"));assertTrue(ack.getBooleanValue("stopped"));assertEquals("isolated-actual-stop-proof",ack.getString("proofId"));assertEquals(0L,operations.find(operation).getLastSeq());assertEquals(410,assertThrows(ServiceException.class,()->service.commitFrame(grant,1,raw("late",null))).getCode());assertEquals(1,submits.get());
    }
    @Test void revokedRunMenuWithModelQueryStillPresentCannotReadExistingFramesAfterPolicyWait(){
        var task=claim();String operation=started(task).getString("operationId");service.commitFrame(lastGrant.get(),1,raw("已保存事实",null));currentCallHook.set(()->menuStatus("paiflow:run:create","1"));assertEquals(403,assertThrows(ServiceException.class,()->frames(task,operation,0,64)).getCode());var user=new SysUser();user.setUserId(42L);assertTrue(permissions.getMenuPermission(user).contains("paiflow:model:query"));assertEquals(1L,operations.find(operation).getLastSeq());
    }
    @Test void expiryDuringLocalCallPolicyPreventsDispatchAndSdkSubmit(){
        callSeconds.set(5);var task=claim();var prepared=service.prepare(task.getString("runId"),worker,request(task,"expiry-window"));currentCallHook.set(()->clock.advance(6));assertEquals(403,assertThrows(ServiceException.class,()->service.claimDispatch(prepared.getId(),worker,"private-capability-token","new-grant")).getCode());assertEquals(0,operations.find(prepared.getId()).getDispatchStarted());assertEquals("PREPARED",operations.find(prepared.getId()).getState());assertEquals(0,submits.get());
    }
    @Test void privateGrantDeadlineIsMinimumApprovalDeadlineAndExpiredFramesAreRefused(){
        callSeconds.set(5);var task=claim();String operation=started(task).getString("operationId");var stored=operations.find(operation);assertEquals(stored.getApprovalExpiresAt().toInstant(ZoneOffset.UTC),lastGrant.get().deadline());clock.advance(6);assertEquals(403,assertThrows(ServiceException.class,()->service.commitFrame(lastGrant.get(),1,raw("expired",null))).getCode());assertEquals(0L,operations.find(operation).getLastSeq());
    }
    @Test void providerFrameCannotPersistKnownPrivateKeyBaseOrReferenceEvenInsideText() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();for(String secret:List.of(grant.privateMaterial().apiKey(),grant.privateMaterial().baseUrl(),grant.privateMaterial().credentialRef())){assertThrows(ServiceException.class,()->service.commitFrame(grant,1,raw("echo:"+secret,null)));assertEquals(0,rows("paiflow_model_frame"));assertEquals(0L,operations.find(operation).getLastSeq());}
    }
    @Test void finishEvidenceCannotBeInventedAndKnownProviderNonStopReasonIsFailure(){
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();assertEquals(409,assertThrows(ServiceException.class,()->service.finishStream(grant,1,1,"stop")).getCode());assertEquals("RUNNING",operations.find(operation).getState());service.commitFrame(grant,1,raw("受限输出","length"));service.finishStream(grant,1,1,"length");assertEquals("FAILED",operations.find(operation).getState());assertEquals("provider",operations.find(operation).getFailureKind());assertEquals("length",operations.find(operation).getFinishReason());
    }
    @Test void wrongAttemptNodeScopeOrModelVersionNeverCreatesAnotherOperation() throws Exception {
        var task=claim();for(String field:List.of("attempt","nodeId","resourceVersion")){var req=request(task,"bad-"+field);if(field.equals("attempt"))req.put("attempt",2);else if(field.equals("nodeId"))req.put("nodeId","spark-llm::other");else req.getJSONObject("parameters").put("resourceVersion","99999");assertEquals(409,assertThrows(ServiceException.class,()->service.start(task.getString("runId"),worker,req)).getCode());}assertEquals(0,rows("paiflow_model_operation"));assertEquals(0,submits.get());
    }
    @Test void modelDisableAndMembershipRevocationRejectFurtherStreamWritesButPreserveFacts() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");service.commitFrame(lastGrant.get(),1,raw("原事实",null));sql("UPDATE paiflow_model SET enabled=0 WHERE id="+modelId);assertEquals(503,assertThrows(ServiceException.class,()->service.commitFrame(lastGrant.get(),2,raw("forbidden",null))).getCode());assertEquals(1L,operations.find(operation).getLastSeq());sql("UPDATE paiflow_model SET enabled=1 WHERE id="+modelId);assertEquals(1,fixtureUpdate("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+space+" AND user_id=42"));assertEquals(403,assertThrows(ServiceException.class,()->service.commitFrame(lastGrant.get(),2,raw("forbidden",null))).getCode());assertEquals(1,rows("paiflow_model_frame"));
    }
    @Test void actualCurrentCallWaitCannotDeliverFramesAfterModelMenuRevocationOrAppendAfterDisable() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");service.commitFrame(lastGrant.get(),1,raw("已经确认的原始事实",null));
        // 实际平台菜单SQL在本地许可等待中提交，保留run:create，租约核对不能替代模型权限核对。
        currentCallHook.set(()->menuStatus("paiflow:model:query","1"));
        assertEquals(403,assertThrows(ServiceException.class,()->frames(task,operation,0,64)).getCode());
        var user=new SysUser();user.setUserId(42L);assertTrue(permissions.getMenuPermission(user).contains("paiflow:run:create"));assertEquals(1,rows("paiflow_model_frame"));assertEquals(1L,operations.find(operation).getLastSeq());
        menuStatus("paiflow:model:query","0");
        // 隔离维护写入核对实际影响行；已存事实保留，等待后停用不能继续追加。
        currentCallHook.set(()->{try{assertEquals(1,fixtureUpdate("UPDATE paiflow_model SET enabled=0 WHERE id="+modelId));}catch(Exception ex){throw new IllegalStateException(ex);}});
        assertEquals(503,assertThrows(ServiceException.class,()->service.commitFrame(lastGrant.get(),2,raw("不得追加",null))).getCode());
        assertEquals(1,rows("paiflow_model_frame"));assertEquals(1L,operations.find(operation).getLastSeq());assertEquals("RUNNING",operations.find(operation).getState());assertEquals(1,submits.get());
    }
    @Test void oversizedFrameIsRejectedBeforeAnyFrameOrSequenceWrite() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");assertEquals(413,assertThrows(ServiceException.class,()->service.commitFrame(lastGrant.get(),1,raw("x".repeat(65536),null))).getCode());assertEquals(0,rows("paiflow_model_frame"));assertEquals(0L,operations.find(operation).getLastSeq());
    }
    @Test void invalidUsageTailCannotOverwriteFactsOrInventCompletion() throws Exception {
        var task=claim();String operation=started(task).getString("operationId");var grant=lastGrant.get();service.commitFrame(grant,1,raw(null,"stop"));
        for(JSONObject malformed:List.of(new JSONObject(),JSONObject.of("prompt_tokens",true,"completion_tokens",0,"total_tokens",0),JSONObject.of("prompt_tokens",-1,"completion_tokens",0,"total_tokens",0))){assertThrows(ServiceException.class,()->service.commitFrame(grant,2,JSONObject.of("choices",new JSONArray(),"usage",malformed)));assertEquals(1,rows("paiflow_model_frame"));assertEquals(1L,operations.find(operation).getLastSeq());assertEquals("RUNNING",operations.find(operation).getState());assertNull(operations.find(operation).getUsageJson());}
        service.commitFrame(grant,2,usage());assertEquals("RUNNING",operations.find(operation).getState());assertEquals(5,JSON.parseObject(operations.find(operation).getUsageJson()).getIntValue("total_tokens"));service.finishStream(grant,2,1,"stop");assertEquals("COMPLETED",operations.find(operation).getState());assertEquals(2,rows("paiflow_model_frame"));
    }
}
