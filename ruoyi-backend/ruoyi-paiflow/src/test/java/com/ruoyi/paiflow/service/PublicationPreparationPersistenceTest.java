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
class PublicationPreparationPersistenceTest {
    private JdbcDataSource data;private Connection schema;private JdbcTemplate jdbc;
    private ApplicationMapper mapper;private WorkflowMapper versionMapper;private AgentMapper agentMapper;private SpaceMapper spaces;private RunMapper runMapper;
    private PublicationPreparationService preparations;private PublicationPreparationMapper preparationMapper;private RunService runService;private RunLeaseService leases;private ApplicationService applications;private AgentService agents;private WorkflowService workflows;private DefaultPersonalSpaceService defaults;
    private long space,other;private final ThreadLocal<Long> actor=ThreadLocal.withInitial(()->42L);
    private final Set<String> denied=ConcurrentHashMap.newKeySet();
    private <T> T transaction(T target,Class<T> type){var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));return type.cast(proxy.getProxy());}
    /** 与既有Agent夹具相同，仅适配MySQL生成列IF语法。 */
    private static String h2If(String text){int start=text.indexOf("IF(");if(start<0)return text;int depth=1,end=start+3,part=end;boolean quoted=false;var pieces=new ArrayList<String>();for(;end<text.length();end++){char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;if(c=='(')depth++;else if(c==')'){if(--depth==0){pieces.add(text.substring(part,end));break;}}else if(c==',' && depth==1){pieces.add(text.substring(part,end));part=end+1;}}if(pieces.size()!=3 || depth!=0)throw new IllegalArgumentException("H2 IF adaptation");return h2If(text.substring(0,start)+"CASE WHEN "+h2If(pieces.get(0))+" THEN "+h2If(pieces.get(1))+" ELSE "+h2If(pieces.get(2))+" END"+text.substring(end+1));}
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");schema=data.getConnection();jdbc=new JdbcTemplate(data);
        try(var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.PersistenceContractTest.utcNow'");
            for(String name:List.of("001-paiflow-schema.sql","002-paiflow-workflow.sql","003-paiflow-run.sql","004-paiflow-membership.sql","006-paiflow-execution-start.sql","007-paiflow-agent.sql","010-paiflow-default-personal.sql","011-paiflow-agent-owner-guard.sql","012-paiflow-workflow-model-snapshots.sql","013-paiflow-model-operation.sql","015-paiflow-execution-codec.sql","017-paiflow-run-json-codec.sql","018-paiflow-workflow-debug-snapshot.sql","028-paiflow-application.sql","029-paiflow-publication-preparation.sql")){
                String ddl=Files.readString(Path.of("sql",name)).replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" CHARACTER SET ascii","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE")
                    .replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:h2If(ddl).split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var config=new Configuration(new Environment("application-isolated",new SpringManagedTransactionFactory(),data));
        for(String file:List.of("SpaceMapper","WorkflowMapper","AgentMapper","ApplicationMapper","RunMapper","PublicationPreparationMapper"))try(var input=getClass().getResourceAsStream("/mapper/paiflow/"+file+".xml")){assertNotNull(input);new XMLMapperBuilder(input,config,file,config.getSqlFragments()).parse();}
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
        runService=runs;preparationMapper=session.getMapper(PublicationPreparationMapper.class);
        preparations=transaction(new PublicationPreparationService(preparationMapper,mapper,versionMapper,agentMapper,runMapper,workflows,access,identities),PublicationPreparationService.class);
        var users=mock(com.ruoyi.system.service.ISysUserService.class);when(users.selectUserById(anyLong())).thenAnswer(call->{var user=new SysUser();user.setUserId(call.getArgument(0));user.setStatus("0");user.setDelFlag("0");return user;});
        var menu=mock(com.ruoyi.framework.web.service.SysPermissionService.class);when(menu.getMenuPermission(any())).thenReturn(Set.of("*:*:*"));
        var leaseTarget=new RunLeaseService(runMapper,spaces,users,menu);leaseTarget.useCurrentSession(session);leases=transaction(leaseTarget,RunLeaseService.class);
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
    private record Fixture(JSONObject app,JSONObject agent,RunService.View debug) {}
    private Fixture fixture() {
        var app=create();var agent=workflow(space,"发布证明工作流");long wf=agent.getLongValue("workflowId");
        var row=versionMapper.find(wf);var body=JSONObject.of("flowId",row.getFlowId(),"data",JSON.parseObject(row.getCanvasJson()));
        var build=workflows.debugBuild(wf,space,0L,body,"comparison",UUID.randomUUID().toString(),"actual-debug-proof");
        long debugId=build.getJSONObject("debugReceipt").getLongValue("workflowVersionId");
        var fixed=workflows.createVersion(wf,space,0L,JSONObject.of("name","普通发布候选"));
        app=applications.bind(app.getString("id"),0L,agent.getLongValue("botId"),fixed.getLongValue("id"));
        return new Fixture(app,agent,runService.createDebug(debugId,"python",new JSONObject(),"actual_debug_request",600));
    }
    private void finish(Fixture fixture,String state) {
        var worker=new WorkerIdentityService.Principal("publication-fixture-worker","python");var claimed=leases.claim(worker);
        assertEquals(fixture.debug().runId(),claimed.getString("runId"));
        String id=claimed.getString("runId"),lease=claimed.getString("leaseId");int attempt=claimed.getIntValue("attempt");
        leases.beginExecution(id,worker,lease,attempt);
        assertTrue(leases.result(id,worker,JSONObject.of("leaseId",lease,"attempt",attempt,"state",state,"output",JSONObject.of("answer","已完成技术夹具"))).getBooleanValue("accepted"));
    }
    private JSONObject prepare(Fixture fixture,String key) {return preparations.prepare(fixture.app().getString("id"),fixture.app().getLong("revision"),"API",fixture.debug().runId(),key);}
    @Test void actualDebugLeaseResultBindsOrdinaryVersionWithoutPretendingChannelSuccess() throws Exception {
        var f=fixture();finish(f,"COMPLETED");var result=prepare(f,"prepare_actual_proof");
        assertEquals(f.app().getLong("workflowVersionId"),result.getLong("workflowVersionId"));
        assertNotEquals(result.getLong("workflowVersionId"),result.getLong("debugWorkflowVersionId"));
        assertEquals("PENDING",result.getString("state"));assertFalse(result.getBooleanValue("isPublished"));
        assertEquals("unconfigured",result.getString("channelStatus"));assertEquals("requires_current_channel_preflight",result.getString("resourceStatus"));
        assertEquals("unpublished",applications.detail(f.app().getString("id")).getString("publicationStatus"));assertEquals(1L,mapper.find(f.app().getString("id")).getRevision());assertEquals(1,runs());
        assertEquals(result.getString("proofHash"),preparationMapper.find(result.getString("id")).getProofHash());assertFalse(result.containsKey("requestKey"));assertFalse(result.containsKey("output"));
        Files.writeString(Path.of("publication-preparation-isolated-actual-view.json"),result.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
    }
    @Test void repeatedRequestIsSamePreparationAndChangedChannelOrRunConflicts(){
        var f=fixture();finish(f,"COMPLETED");var first=prepare(f,"same_prepare_request");
        assertEquals(first.getString("id"),prepare(f,"same_prepare_request").getString("id"));
        assertEquals(409,code(()->preparations.prepare(f.app().getString("id"),1L,"MCP",f.debug().runId(),"same_prepare_request")));
        assertEquals(1,preparationMapper.count(space,42));assertEquals(1,runs());
    }
    @Test void concurrentSamePreparationCreatesOnlyOneRecord() throws Exception {
        var f=fixture();finish(f,"COMPLETED");var barrier=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {Callable<String> action=()->{barrier.await();return prepare(f,"concurrent_prepare").getString("id");};var a=pool.submit(action);var b=pool.submit(action);barrier.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,preparationMapper.count(space,42));}
        finally {pool.shutdownNow();}
    }
    @Test void queuedFailedCancelledOrUnknownRunsNeverPrepare(){
        var f=fixture();assertEquals(409,code(()->prepare(f,"queued_prepare_key")));finish(f,"FAILED");assertEquals(409,code(()->prepare(f,"failed_prepare_key")));assertEquals(0,preparationMapper.count(space,42));
        for(String state:List.of("CANCELLED","EFFECT_UNKNOWN")){jdbc.update("UPDATE paiflow_run SET state=? WHERE id=?",state,f.debug().runId());assertEquals(409,code(()->prepare(f,"terminal_prepare_key")));}assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void ordinarySuccessfulCallCannotReplaceDedicatedDebugProof(){
        var f=fixture();finish(f,"COMPLETED");var ordinary=applications.invoke(f.app().getString("id"),1L,new JSONObject(),"ordinary_request",600);
        var other=new Fixture(f.app(),f.agent(),ordinary);finish(other,"COMPLETED");
        assertEquals(409,code(()->prepare(other,"ordinary_prepare")));assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void missingStaleRevisionAndUnconfiguredApplicationsHaveNoWrites(){
        var f=fixture();finish(f,"COMPLETED");
        assertEquals(409,code(()->preparations.prepare(f.app().getString("id"),null,"API",f.debug().runId(),"missing_revision")));
        assertEquals(409,code(()->preparations.prepare(f.app().getString("id"),0L,"API",f.debug().runId(),"stale_revision")));
        applications.disable(f.app().getString("id"),1L);assertEquals(409,code(()->prepare(f,"disabled_prepare")));assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void allChannelsKeepPreparationSeparateFromRealPublication(){
        var f=fixture();finish(f,"COMPLETED");for(String channel:PublicationPreparationService.CHANNELS){var result=preparations.prepare(f.app().getString("id"),1L,channel,f.debug().runId(),"channel_"+channel);assertEquals(channel,result.getString("channel"));assertFalse(result.getBooleanValue("isPublished"));}
        assertEquals(7,preparationMapper.count(space,42));assertEquals(400,code(()->preparations.prepare(f.app().getString("id"),1L,"invented",f.debug().runId(),"invalid_channel")));assertEquals(1,runs());
    }
    @Test void ownerPlatformAndCurrentSpaceAreAllRequired(){
        var f=fixture();finish(f,"COMPLETED");var prepared=prepare(f,"owned_prepare_key");
        actor.set(43L);assertEquals(404,code(()->preparations.detail(prepared.getString("id"))));assertEquals(0,preparations.page(space,1,15).getLongValue("total"));assertEquals(404,code(()->prepare(f,"foreign_prepare_key")));
        actor.set(42L);denied.add("paiflow:publication:create");assertEquals(403,code(()->prepare(f,"denied_prepare_key")));denied.clear();denied.add("paiflow:run:query");assertEquals(403,code(()->prepare(f,"denied_debug_key")));denied.clear();
        jdbc.update("UPDATE paiflow_space_member SET status='removed' WHERE space_id=? AND user_id=42",space);
        assertEquals(404,code(()->preparations.detail(prepared.getString("id"))));assertEquals(1,preparationMapper.count(space,42));
    }
    @Test void fixedCandidateKeepsProofWhenOnlyLaterDraftChanges(){
        var f=fixture();finish(f,"COMPLETED");workflows.save(f.app().getLongValue("workflowId"),space,1L,JSONObject.of("name","后来修改的草稿","advancedConfig",JSONObject.of("unknown",true)));
        var result=prepare(f,"historical_candidate");assertEquals(f.app().getLong("workflowVersionId"),result.getLong("workflowVersionId"));assertFalse(result.getBooleanValue("isPublished"));
    }
    @Test void actualSecondVersionWithIdenticalContentAndDifferentRevisionKeepsDebugProof(){
        var f=fixture();finish(f,"COMPLETED");long wf=f.app().getLongValue("workflowId");
        var second=workflows.createVersion(wf,space,1L,JSONObject.of("name","第二份相同内容版本"));
        var app=applications.bind(f.app().getString("id"),1L,f.app().getLongValue("botId"),second.getLongValue("id"));
        var candidate=new Fixture(app,f.agent(),f.debug());var prepared=prepare(candidate,"equivalent_revision");
        assertEquals("PENDING",prepared.getString("state"));assertEquals(second.getLong("id"),prepared.getLong("workflowVersionId"));
        assertEquals(1L,prepared.getLong("fixedSnapshotRevision"));assertEquals(0L,prepared.getLong("debugSnapshotRevision"));
        assertEquals(prepared.getLong("fixedSnapshotRevision"),preparationMapper.find(prepared.getString("id")).getFixedSnapshotRevision());
        assertEquals(1,runs());
    }
    @Test void equalDslHashDoesNotAllowDifferentFixedConfigOrResources(){
        var f=fixture();finish(f,"COMPLETED");long version=f.app().getLongValue("workflowVersionId");String original=versionMapper.version(version).getAdvancedConfigJson();
        jdbc.update("UPDATE paiflow_workflow_version SET advanced_config_json=? WHERE id=?","{\"changed\":true}",version);
        assertEquals(409,code(()->prepare(f,"changed_config_key")));jdbc.update("UPDATE paiflow_workflow_version SET advanced_config_json=?,resource_snapshots_json=? WHERE id=?",original,"{\"changed\":true}",version);
        assertEquals(409,code(()->prepare(f,"changed_resource_key")));assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void existingKeyStillRequiresCurrentRunQueryPermission(){
        var f=fixture();finish(f,"COMPLETED");prepare(f,"revoked_replay_key");denied.add("paiflow:run:query");
        assertEquals(403,code(()->prepare(f,"revoked_replay_key")));assertEquals(1,preparationMapper.count(space,42));
    }
    @Test void changedAssignmentResourcesOrBytesCannotReuseCompletedEvidence(){
        var f=fixture();finish(f,"COMPLETED");String original=runMapper.find(f.debug().runId()).getAssignmentJson();var task=JSON.parseObject(original);
        task.put("resourceSnapshots",JSONObject.of("changed",true));jdbc.update("UPDATE paiflow_run SET assignment_json=? WHERE id=?",task.toJSONString(),f.debug().runId());
        assertEquals(409,code(()->prepare(f,"changed_assignment_key")));task=JSON.parseObject(original);task.put("dslCanonicalBase64","dGFtcGVy");
        jdbc.update("UPDATE paiflow_run SET assignment_json=? WHERE id=?",task.toJSONString(),f.debug().runId());assertEquals(409,code(()->prepare(f,"changed_bytes_key")));assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void terminalEventMustHaveTheActualLeaseAndAttempt(){
        var f=fixture();finish(f,"COMPLETED");var row=runMapper.find(f.debug().runId());String original=runMapper.event(row.getId(),row.getLastEventSeq());var event=JSON.parseObject(original);event.put("leaseId","different-lease");
        jdbc.update("UPDATE paiflow_run_event SET event_json=? WHERE run_id=? AND seq=?",event.toJSONString(),row.getId(),row.getLastEventSeq());assertEquals(409,code(()->prepare(f,"wrong_terminal_lease")));
        event=JSON.parseObject(original);event.put("attempt",row.getAttempt()+1);jdbc.update("UPDATE paiflow_run_event SET event_json=? WHERE run_id=? AND seq=?",event.toJSONString(),row.getId(),row.getLastEventSeq());assertEquals(409,code(()->prepare(f,"wrong_terminal_attempt")));assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void corruptResultOrMissingTerminalEventCannotBeAcceptedAsCompleted(){
        var f=fixture();finish(f,"COMPLETED");String hash=runMapper.find(f.debug().runId()).getResultHash();jdbc.update("UPDATE paiflow_run SET result_hash=? WHERE id=?","0".repeat(64),f.debug().runId());
        assertEquals(409,code(()->prepare(f,"corrupt_result_key")));jdbc.update("UPDATE paiflow_run SET result_hash=? WHERE id=?",hash,f.debug().runId());jdbc.update("DELETE FROM paiflow_run_event WHERE run_id=?",f.debug().runId());
        assertEquals(409,code(()->prepare(f,"missing_terminal_key")));assertEquals(0,preparationMapper.count(space,42));
    }
    @Test void preparationInsertFailureKeepsApplicationVersionAndRunAuditUnchanged(){
        var f=fixture();finish(f,"COMPLETED");jdbc.execute("ALTER TABLE paiflow_publication_preparation ADD CONSTRAINT fixture_prepare_failure CHECK(request_key<>'rollback_prepare')");
        var error=assertThrows(RuntimeException.class,()->prepare(f,"rollback_prepare"));assertTrue(error.toString().toLowerCase().contains("fixture_prepare_failure"),error.toString());
        assertEquals(0,preparationMapper.count(space,42));assertEquals("COMPLETED",runMapper.find(f.debug().runId()).getState());assertEquals(1L,mapper.find(f.app().getString("id")).getRevision());
    }
    @Test void withdrawingOrDeletingApplicationKeepsFixedPreparationAudit(){
        var f=fixture();finish(f,"COMPLETED");var prepared=prepare(f,"withdraw_prepare_key");String id=prepared.getString("id");
        assertEquals(409,code(()->preparations.withdraw(id,null)));assertEquals(409,code(()->preparations.withdraw(id,1L)));
        var withdrawn=preparations.withdraw(id,0L);assertEquals("WITHDRAWN",withdrawn.getString("state"));assertFalse(withdrawn.getBooleanValue("isPublished"));assertEquals(409,code(()->preparations.withdraw(id,1L)));
        assertEquals(id,prepare(f,"withdraw_prepare_key").getString("id"));applications.delete(f.app().getString("id"),1L);assertEquals(id,preparations.detail(id).getString("id"));assertNotNull(versionMapper.version(f.app().getLongValue("workflowVersionId")));assertEquals(1,runs());
    }
    @Test void actualMvcUsesStandardPaginationAndRejectsForgedPublicationFields() throws Exception {
        var f=fixture();finish(f,"COMPLETED");prepare(f,"mvc_prepare_request");var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.ruoyi.paiflow.controller.PublicationPreparationController(preparations)).build();
        mvc.perform(get("/paiflow/publications/preparations").param("spaceId",Long.toString(space)).param("pageNum","1").param("pageSize","1")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].isPublished").value(false)).andExpect(jsonPath("$.data").doesNotExist());
        var controller=new com.ruoyi.paiflow.controller.PublicationPreparationController(preparations);
        assertEquals(400,code(()->controller.prepare("1","forged_prepare_request",JSONObject.of("applicationId",f.app().getString("id"),"channel","API","debugRunId",f.debug().runId(),"state","ACTIVE"))));
        assertEquals(400,code(()->controller.prepare("1","forged_prepare_request",JSONObject.of("applicationId",42,"channel","API","debugRunId",f.debug().runId()))));assertEquals(1,preparationMapper.count(space,42));
    }
}
