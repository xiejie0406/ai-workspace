package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.*;
import com.ruoyi.paiflow.adapter.legacy.LegacyPublishApiController;
import com.ruoyi.paiflow.controller.ApplicationController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实中心Service/Mapper/H2事务验证；不执行真实MySQL、外部发布或供应商。 */
class ApplicationPersistenceTest {
    private JdbcDataSource data;private Connection schema;private JdbcTemplate jdbc;
    private ApplicationMapper mapper;private WorkflowMapper versionMapper;private AgentMapper agentMapper;private SpaceMapper spaces;private RunMapper runMapper;
    private ApplicationService applications;private AgentService agents;private WorkflowService workflows;private DefaultPersonalSpaceService defaults;
    private long space,other;private final ThreadLocal<Long> actor=ThreadLocal.withInitial(()->42L);
    private final Set<String> denied=ConcurrentHashMap.newKeySet();
    private <T> T transaction(T target,Class<T> type){var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));return type.cast(proxy.getProxy());}
    /** 与既有Agent夹具相同，仅适配MySQL生成列IF语法。 */
    private static String h2If(String text){int start=text.indexOf("IF(");if(start<0)return text;int depth=1,end=start+3,part=end;boolean quoted=false;var pieces=new ArrayList<String>();for(;end<text.length();end++){char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;if(c=='(')depth++;else if(c==')'){if(--depth==0){pieces.add(text.substring(part,end));break;}}else if(c==',' && depth==1){pieces.add(text.substring(part,end));part=end+1;}}if(pieces.size()!=3 || depth!=0)throw new IllegalArgumentException("H2 IF adaptation");return h2If(text.substring(0,start)+"CASE WHEN "+h2If(pieces.get(0))+" THEN "+h2If(pieces.get(1))+" ELSE "+h2If(pieces.get(2))+" END"+text.substring(end+1));}
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");schema=data.getConnection();jdbc=new JdbcTemplate(data);
        try(var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.PersistenceContractTest.utcNow'");
            for(String name:List.of("001-paiflow-schema.sql","002-paiflow-workflow.sql","003-paiflow-run.sql","004-paiflow-membership.sql","006-paiflow-execution-start.sql","007-paiflow-agent.sql","010-paiflow-default-personal.sql","011-paiflow-agent-owner-guard.sql","012-paiflow-workflow-model-snapshots.sql","013-paiflow-model-operation.sql","015-paiflow-execution-codec.sql","017-paiflow-run-json-codec.sql","018-paiflow-workflow-debug-snapshot.sql","028-paiflow-application.sql")){
                String ddl=Files.readString(Path.of("sql",name)).replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET ascii","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE")
                    .replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:h2If(ddl).split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var config=new Configuration(new Environment("application-isolated",new SpringManagedTransactionFactory(),data));
        for(String file:List.of("SpaceMapper","WorkflowMapper","AgentMapper","ApplicationMapper","RunMapper"))try(var input=getClass().getResourceAsStream("/mapper/paiflow/"+file+".xml")){assertNotNull(input);new XMLMapperBuilder(input,config,file,config.getSqlFragments()).parse();}
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));spaces=session.getMapper(SpaceMapper.class);versionMapper=session.getMapper(WorkflowMapper.class);agentMapper=session.getMapper(AgentMapper.class);mapper=session.getMapper(ApplicationMapper.class);runMapper=session.getMapper(RunMapper.class);
        var identities=mock(IdentityMappingService.class);when(identities.current()).thenAnswer(call->{var user=new SysUser();user.setUserId(actor.get());user.setUserName("fixture-"+actor.get());return new LoginUser(actor.get(),8L,user,Set.of());});
        var permissions=mock(PermissionService.class);when(permissions.hasPermi(anyString())).thenAnswer(call->!denied.contains(call.<String>getArgument(0)));
        var access=new ResourceAccessService(identities,permissions,spaces);access.useCurrentSession(session);
        workflows=transaction(new WorkflowService(versionMapper,access,identities),WorkflowService.class);
        defaults=transaction(new DefaultPersonalSpaceService(spaces,identities,access),DefaultPersonalSpaceService.class);
        var agentTarget=new AgentService(agentMapper,access,identities,workflows);agentTarget.useDefaultSpaces(defaults);agents=transaction(agentTarget,AgentService.class);
        var bindings=mock(ObjectProvider.class);when(bindings.getIfAvailable()).thenReturn(agents);workflows.useAgentBindings(bindings);
        var runs=transaction(new RunService(workflows,runMapper,access,identities),RunService.class);
        applications=transaction(new ApplicationService(mapper,agentMapper,versionMapper,workflows,runs,access,identities,defaults),ApplicationService.class);
        space=space("应用夹具空间");other=space("另一应用夹具空间");spaces.insertMember(space,43,3,"fixture");spaces.insertMember(other,43,3,"fixture");
    }
    @AfterEach void close() throws Exception {if(schema!=null)schema.close();}
    private long space(String name){var row=new SpaceRecord();row.setName(name);row.setKind("personal");row.setOwnerUserId(42L);row.setCreateBy("fixture");row.setUpdateBy("fixture");assertEquals(1,spaces.insert(row));spaces.insertOwner(row.getId(),42,"fixture");return row.getId();}
    private JSONObject create(){return applications.create(space,JSONObject.of("appName","原表单应用","appDescribe","保留原描述"));}
    private JSONObject workflow(long selected,String name){return agents.create(selected,3,JSONObject.of("name",name,"botType",0));}
    private JSONObject bind(){var app=create();var agent=workflow(space,"固定版本工作流");var version=workflows.createVersion(agent.getLongValue("workflowId"),space,0L,JSONObject.of("name","v1"));return applications.bind(app.getString("id"),0L,agent.getLongValue("botId"),version.getLongValue("id"));}
    private long runs(){return jdbc.queryForObject("SELECT COUNT(*) FROM paiflow_run",Long.class);}
    private static int code(Executable action){return assertThrows(ServiceException.class,action::run).getCode();}
    @FunctionalInterface private interface Executable {void run() throws Exception;}
    @Test void originalAppFormPersistsWithoutTenantOrPlainCredentials(){var app=create();var read=applications.detail(app.getString("id"));assertEquals("原表单应用",read.getString("appName"));assertEquals("保留原描述",read.getString("appDescribe"));assertEquals("UNBOUND",read.getString("state"));assertEquals("unpublished",read.getString("publicationStatus"));assertNull(read.get("appKey"));assertNull(read.get("appSecret"));assertEquals(1,applications.page(space,1,15).getLongValue("total"));assertEquals(0,runs());}
    @Test void standardHttpPageUsesActualRuoYiTableDataInfoWhileLegacyKeepsItsArray() throws Exception {create();var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ApplicationController(applications),new LegacyPublishApiController(applications,agents)).build();mvc.perform(get("/paiflow/applications").param("spaceId",Long.toString(space)).param("pageNum","1").param("pageSize","1")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].appName").value("原表单应用")).andExpect(jsonPath("$.data").doesNotExist());mvc.perform(get("/paiflow/legacy/publish-api/app-list").header("space-id",space)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].appName").value("原表单应用")).andExpect(jsonPath("$.rows").doesNotExist());}
    @Test void originalGlobalActiveNameUniquenessAndSoftDeletedReuseArePreserved(){var first=create();assertEquals(409,code(this::create));actor.set(43L);assertEquals(409,code(()->applications.create(other,JSONObject.of("appName","原表单应用"))));var second=applications.create(other,JSONObject.of("name","其他应用"));assertEquals(409,code(()->applications.update(second.getString("id"),0L,JSONObject.of("name","原表单应用"))));assertEquals("其他应用",mapper.find(second.getString("id")).getName());actor.set(42L);applications.delete(first.getString("id"),0L);actor.set(43L);var reused=applications.create(other,JSONObject.of("appName","原表单应用"));assertNotEquals(first.getString("id"),reused.getString("id"));}
    @Test void concurrentSameNameAcrossDifferentSpacesHasExactlyOneWinner() throws Exception {var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->{latch.await();try{applications.create(space,JSONObject.of("name","并发应用名"));return 200;}catch(ServiceException error){return error.getCode();}});var b=pool.submit(()->{actor.set(43L);latch.await();try{applications.create(other,JSONObject.of("name","并发应用名"));return 200;}catch(ServiceException error){return error.getCode();}});latch.countDown();assertEquals(Set.of(200,409),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paiflow_application WHERE name='并发应用名'",Integer.class));}finally{pool.shutdownNow();}}
    @Test void controlledFieldsAndInvalidMetadataNeverCreateRows(){for(var body:List.of(JSONObject.of("appName","bad","appSecret","private"),JSONObject.of("name","bad","ownerUserId",43),JSONObject.of("name","bad","state","READY"),JSONObject.of("name","bad","publicationStatus","active"),JSONObject.of("name","a","appName","b"),JSONObject.of("name",42),JSONObject.of("name"," ")))assertEquals(400,code(()->applications.create(space,body)));assertEquals(0,mapper.count(space,42));}
    @Test void platformAndOwnerScopeRemainRequiredInSharedSpace(){var id=create().getString("id");actor.set(43L);assertEquals(404,code(()->applications.detail(id)));assertEquals(0,applications.page(space,1,15).getLongValue("total"));assertEquals(404,code(()->applications.update(id,0L,JSONObject.of("name","foreign"))));actor.set(42L);denied.add("paiflow:application:query");assertEquals(403,code(()->applications.detail(id)));assertEquals(403,code(()->applications.page(space,1,15)));}
    @Test void bindingKeepsActualImmutableVersionAfterDraftChanges(){var app=bind();long version=app.getLongValue("workflowVersionId"),workflow=app.getLongValue("workflowId");var before=versionMapper.version(version);assertEquals("READY",app.getString("state"));assertEquals("unpublished",app.getString("publicationStatus"));workflows.save(workflow,space,1L,JSONObject.of("name","草稿修改"));var run=applications.invoke(app.getString("id"),1L,new JSONObject(),"same_request",600);assertEquals(version,run.workflowVersionId());assertEquals(before.getDslHash(),runMapper.find(run.runId()).getAssignmentJson()==null?null:JSON.parseObject(runMapper.find(run.runId()).getAssignmentJson()).getString("dslHash"));assertEquals(1,runs());}
    @Test void crossSpaceAndUnrelatedAgentVersionsCannotBind(){var app=create();var a=workflow(space,"a");var b=workflow(space,"b");var v=workflows.createVersion(b.getLongValue("workflowId"),space,0L,JSONObject.of("name","v1"));assertEquals(409,code(()->applications.bind(app.getString("id"),0L,a.getLongValue("botId"),v.getLongValue("id"))));var foreign=workflow(other,"foreign");var otherVersion=workflows.createVersion(foreign.getLongValue("workflowId"),other,0L,JSONObject.of("name","v1"));assertEquals(404,code(()->applications.bind(app.getString("id"),0L,foreign.getLongValue("botId"),otherVersion.getLongValue("id"))));assertEquals("UNBOUND",mapper.find(app.getString("id")).getState());}
    @Test void dedicatedDebugSnapshotIsNotAnApplicationVersion(){var app=create();var agent=workflow(space,"debug");long wf=agent.getLongValue("workflowId");var row=versionMapper.find(wf);var request=JSONObject.of("flowId",row.getFlowId(),"data",JSON.parseObject(row.getCanvasJson()));var build=workflows.debugBuild(wf,space,0L,request,"comparison",UUID.randomUUID().toString(),"fixture-debug-1");long version=build.getJSONObject("debugReceipt").getLongValue("workflowVersionId");assertEquals("comparison",versionMapper.version(version).getDebugMode());assertEquals(404,code(()->applications.bind(app.getString("id"),0L,agent.getLongValue("botId"),version)));assertEquals("UNBOUND",mapper.find(app.getString("id")).getState());}
    @Test void duplicateInvocationPersistsExactlyOneRunAndDifferentBodyConflicts(){var app=bind();String id=app.getString("id");var first=applications.invoke(id,1L,new JSONObject(),"repeat_request",600);assertEquals(first.runId(),applications.invoke(id,1L,new JSONObject(),"repeat_request",600).runId());assertEquals(409,code(()->applications.invoke(id,1L,JSONObject.of("changed",1),"repeat_request",600)));assertEquals(1,runs());assertEquals(first.runId(),mapper.request(id,42,"repeat_request").get("runId"));}
    @Test void appRunBindingFailureRollsBackActualRunInsert(){var app=bind();jdbc.execute("ALTER TABLE paiflow_application_run ADD CONSTRAINT fixture_link_failure CHECK(request_key<>'rollback_request')");var error=assertThrows(RuntimeException.class,()->applications.invoke(app.getString("id"),1L,new JSONObject(),"rollback_request",600));assertTrue(error.toString().toLowerCase().contains("fixture_link_failure"),error.toString());assertEquals(0,runs());assertNull(mapper.request(app.getString("id"),42,"rollback_request"));}
    @Test void disablingAndDeletingPreventNewCallsButKeepRunAudit(){var app=bind();String id=app.getString("id");var run=applications.invoke(id,1L,new JSONObject(),"before_disabled",600);applications.disable(id,1L);assertEquals(409,code(()->applications.invoke(id,2L,new JSONObject(),"after_disabled",600)));assertEquals("QUEUED",runMapper.find(run.runId()).getState());applications.delete(id,2L);assertEquals(404,code(()->applications.detail(id)));assertNotNull(mapper.request(id,42,"before_disabled"));assertNotNull(versionMapper.version(run.workflowVersionId()));assertEquals(1,runs());}
    @Test void removedWorkflowAndRevokedRunPermissionDoNotDispatch(){var app=bind();String id=app.getString("id");denied.add("paiflow:run:create");assertEquals(403,code(()->applications.invoke(id,1L,new JSONObject(),"denied_request",600)));assertEquals(0,runs());denied.clear();var agent=agentMapper.find(app.getLongValue("botId"));agents.delete(agent.getId(),space,agent.getRevision(),versionMapper.find(agent.getWorkflowId()).getRevision());assertEquals(409,code(()->applications.invoke(id,1L,new JSONObject(),"deleted_binding",600)));assertEquals(0,runs());}
    @Test void staleOrAbsentRevisionCannotChangeBindingOrDispatch(){var app=bind();String id=app.getString("id");assertEquals(409,code(()->applications.invoke(id,null,new JSONObject(),"missing_revision",600)));assertEquals(409,code(()->applications.invoke(id,0L,new JSONObject(),"stale_revision",600)));assertEquals(409,code(()->applications.disable(id,0L)));assertEquals(409,code(()->applications.update(id,null,JSONObject.of("name","stale"))));assertEquals(1L,mapper.find(id).getRevision());assertEquals(0,runs());}
    @Test void concurrentSameRequestHasOneRunAndOneBinding() throws Exception {var app=bind();var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{Callable<String> invoke=()->{latch.await();return applications.invoke(app.getString("id"),1L,new JSONObject(),"concurrent_request",600).runId();};var a=pool.submit(invoke);var b=pool.submit(invoke);latch.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,runs());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paiflow_application_run",Integer.class));}finally{pool.shutdownNow();}}
    @Test void legacyEmptySpaceReadDoesNotMaterializeAndWriteUsesOwnedPrivateDomain(){assertTrue(applications.legacyList(null).isEmpty());assertNull(spaces.defaultPersonal(42));var app=applications.legacyCreate(null,JSONObject.of("appName","原个人应用","appDescribe",""));assertTrue(spaces.find(app.getLongValue("spaceId")).isDefaultPersonal());assertEquals(app.getString("id"),applications.legacyList("").getFirst().getString("appId"));actor.set(43L);assertTrue(applications.legacyList(null).isEmpty());assertEquals(404,code(()->applications.detail(app.getString("id"))));}
    @Test void originalPublishApiRoutesReturnRealRegistrationAndRejectUnconfiguredPublication(){var controller=new LegacyPublishApiController(applications,agents);assertTrue(controller.create(String.valueOf(space),JSONObject.of("appName","原App界面")).data());var app=applications.legacyList(String.valueOf(space)).getFirst();var agent=workflow(space,"原API页");assertEquals(503,code(()->controller.publish(String.valueOf(space),JSONObject.of("botId",agent.getLong("botId"),"appId",app.getString("id")))));assertEquals("UNBOUND",mapper.find(app.getString("id")).getState());assertEquals(0,runs());}
}
