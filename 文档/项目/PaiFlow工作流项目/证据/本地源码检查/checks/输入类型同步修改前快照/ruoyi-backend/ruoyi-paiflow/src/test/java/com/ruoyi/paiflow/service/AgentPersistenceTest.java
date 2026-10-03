package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.adapter.legacy.LegacyBotController;
import com.ruoyi.paiflow.adapter.legacy.LegacyMyBotController;
import com.ruoyi.paiflow.adapter.legacy.LegacyAgentWorkflowController;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowScopeFacade;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.AgentMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真Mapper/资源成员读取/事务和CAS；账号与权限fixture不代表真实菜单配置或已付费套餐。 */
public class AgentPersistenceTest {
    private JdbcDataSource data;private java.sql.Connection schema;
    private AgentMapper mapper;private WorkflowMapper workflowMapper;private SpaceMapper spaces;
    private AgentService agents;private WorkflowService workflows;private PermissionService permissions;
    private DefaultPersonalSpaceService defaults;
    private LegacyWorkflowScopeFacade legacyFlows;
    private long space,other;private final ThreadLocal<Long> actor=ThreadLocal.withInitial(()->42L);
    private final Set<String> denied=ConcurrentHashMap.newKeySet();
    public static Timestamp utcNow() { return Timestamp.from(Instant.now()); }
    public static Timestamp utcNow(int precision) { if(precision!=3)throw new IllegalArgumentException("fixture precision");return utcNow(); }
    /** 只适配H2不支持的MySQL IF生成列表达式，不放宽生产约束。 */
    private static String mysqlIfToCase(String text) {
        int start=text.indexOf("IF(");if(start<0)return text;int depth=1,end=start+3,partStart=end;boolean quoted=false;var parts=new java.util.ArrayList<String>();
        for(;end<text.length();end++){char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;if(c=='(')depth++;else if(c==')'){if(--depth==0){parts.add(text.substring(partStart,end));break;}}else if(c==',' && depth==1){parts.add(text.substring(partStart,end));partStart=end+1;}}
        if(parts.size()!=3 || depth!=0)throw new IllegalArgumentException("H2 IF adaptation");return mysqlIfToCase(text.substring(0,start)+"CASE WHEN "+mysqlIfToCase(parts.get(0))+" THEN "+mysqlIfToCase(parts.get(1))+" ELSE "+mysqlIfToCase(parts.get(2))+" END"+text.substring(end+1));
    }
    private <T> T transaction(T target,Class<T> type) { var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));return type.cast(proxy.getProxy()); }
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");schema=data.getConnection();
        try(var sql=schema.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.AgentPersistenceTest.utcNow'");
            for(String file:List.of("sql/001-paiflow-schema.sql","sql/002-paiflow-workflow.sql","sql/004-paiflow-membership.sql","sql/007-paiflow-agent.sql","sql/010-paiflow-default-personal.sql","sql/011-paiflow-agent-owner-guard.sql","sql/012-paiflow-workflow-model-snapshots.sql","sql/015-paiflow-execution-codec.sql")) {
                var ddl=Files.readString(Path.of(file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
                ddl=ddl.replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE")
                    .replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:mysqlIfToCase(ddl).split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var config=new Configuration(new Environment("agent-isolated",new SpringManagedTransactionFactory(),data));
        for(String file:List.of("SpaceMapper","WorkflowMapper","AgentMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+file+".xml")) { assertNotNull(xml);new XMLMapperBuilder(xml,config,file,config.getSqlFragments()).parse(); }
        var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=template.getMapper(AgentMapper.class);workflowMapper=template.getMapper(WorkflowMapper.class);spaces=template.getMapper(SpaceMapper.class);
        var identities=mock(IdentityMappingService.class);when(identities.current()).thenAnswer(call->{var user=new SysUser();user.setUserId(actor.get());user.setUserName("fixture-"+actor.get());return new LoginUser(actor.get(),8L,user,Set.of());});
        permissions=mock(PermissionService.class);when(permissions.hasPermi(anyString())).thenAnswer(call->!denied.contains(call.<String>getArgument(0)));
        var access=new ResourceAccessService(identities,permissions,spaces);
        access.useCurrentSession(template);
        var workflowTarget=new WorkflowService(workflowMapper,access,identities);workflows=transaction(workflowTarget,WorkflowService.class);
        defaults=transaction(new DefaultPersonalSpaceService(spaces,identities,access),DefaultPersonalSpaceService.class);
        legacyFlows=new LegacyWorkflowScopeFacade(workflows,defaults,access);
        var agentTarget=new AgentService(mapper,access,identities,workflows);agentTarget.useDefaultSpaces(defaults);agents=transaction(agentTarget,AgentService.class);
        var provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(agents);workflowTarget.useAgentBindings(provider);
        space=createSpace("本地fixture");other=createSpace("另一空间fixture");spaces.insertMember(space,43,3,"fixture");spaces.insertMember(other,43,3,"fixture");
    }
    @AfterEach void close() throws Exception { if(schema!=null)schema.close(); }
    private long createSpace(String name) { var row=new SpaceRecord();row.setName(name);row.setOwnerUserId(42L);row.setKind("personal");row.setCreateBy("fixture");row.setUpdateBy("fixture");assertEquals(1,spaces.insert(row));spaces.insertOwner(row.getId(),42,"fixture");return row.getId(); }
    private static JSONObject body(Object... pairs) { var b=new JSONObject();for(int i=0;i<pairs.length;i+=2)b.put((String)pairs[i],pairs[i+1]);return b; }
    /** 字段逐项取自原config-base/index.tsx:396-437；值为隔离fixture，无外部资源。 */
    private JSONObject originalConfig(String name,Object id) { return JSON.parseObject("{\"botId\":null,\"name\":\"fixture\",\"botType\":1,\"botDesc\":\"原页面完整字段\",\"supportContext\":1,\"supportSystem\":0,\"promptType\":0,\"inputExample\":[\"示例1\",\"\",\"\"],\"maasDatasetList\":[],\"avatar\":\"/fixture/avatar.png\",\"vcnCn\":\"x4_lingxiaoyan\",\"vcnEn\":\"x4_enus_luna\",\"vcnSpeed\":50,\"isSentence\":0,\"openedTool\":\"\",\"prologue\":\"欢迎\",\"model\":\"generalv3.5\",\"modelId\":null,\"isCustom\":false,\"prompt\":\"fixture prompt\",\"promptStructList\":[]}").fluentPut("name",name).fluentPut("botId",id); }
    private JSONObject workflow(String name) { return agents.create(space,3,body("name",name,"botType",0,"avatar","/workflow/workflow.png","botDesc","","botId",null,"inputExample",List.of("","",""))); }
    @Test void actualOriginalConfigBodyPersistsAndMemberEditKeepsCreatorWhileDraftDoesNotClaimPublication() {
        var request=originalConfig("指令型fixture",null);var created=agents.create(space,1,request);long id=created.getLongValue("botId");assertEquals(42L,mapper.find(id).getOwnerUserId());
        assertEquals("generalv3.5",created.getString("model"));assertEquals(request.getJSONArray("inputExample"),created.getJSONArray("inputExampleList"));assertNull(created.get("modelId"));assertEquals(-9,created.getIntValue("botStatus"));assertEquals("draft_unpublished",created.getString("publicationStatus"));assertTrue(created.getJSONArray("releaseType").isEmpty());
        actor.set(43L);var edited=agents.update(id,space,0L,null,originalConfig("成员合法编辑",String.valueOf(id)));assertEquals(42L,edited.getLongValue("ownerUserId"));assertEquals(1L,edited.getLongValue("revision"));assertEquals("fixture-43",mapper.find(id).getUpdateBy());
        assertEquals(409,assertThrows(ServiceException.class,()->agents.update(id,space,0L,null,body("name","旧窗口"))).getCode());assertEquals("成员合法编辑",mapper.find(id).getName());
    }
    @Test void workflowCreationHasRealUniqueBindingAndBothRevisionsRequiredForMetadataUpdate() {
        var created=workflow("工作流fixture");long id=created.getLongValue("botId"),wf=created.getLongValue("workflowId");assertEquals(wf,created.getLongValue("maasId"));assertEquals(1L,created.getLongValue("revision"));assertEquals(0L,created.getLongValue("workflowRevision"));assertNotNull(created.getString("flowId"));
        var request=body("botId",id,"name","绑定更新","prologue","更正欢迎","inputExample",List.of("a"),"workflowId",wf,"maasId",wf,"flowId",created.getString("flowId"));
        assertEquals(409,assertThrows(ServiceException.class,()->agents.update(id,space,1L,null,request)).getCode());assertEquals("工作流fixture",mapper.find(id).getName());
        var updated=agents.update(id,space,1L,0L,request);assertEquals(2L,updated.getLongValue("revision"));assertEquals(1L,updated.getLongValue("workflowRevision"));assertEquals("绑定更新",workflowMapper.find(wf).getName());assertEquals("更正欢迎",JSON.parseObject(workflowMapper.find(wf).getAdvancedConfigJson()).getJSONObject("prologue").getString("prologueText"));
        request.put("maasId",wf+100);assertEquals(409,assertThrows(ServiceException.class,()->agents.update(id,space,2L,1L,request)).getCode());assertEquals(2L,mapper.find(id).getRevision());
    }
    @Test void directCanvasSaveSyncsAgentRevisionAndRollsBackWhenAgentPermissionIsMissing() {
        var created=workflow("双向同步");long id=created.getLongValue("botId"),wf=created.getLongValue("workflowId");
        workflows.save(wf,space,0L,body("name","画布改名","advancedConfig",body("prologue",body("prologueText","新欢迎","inputExample",List.of("问题")))));
        assertEquals(2L,mapper.find(id).getRevision());assertEquals("画布改名",mapper.find(id).getName());assertEquals("新欢迎",agents.detail(id,space).getString("prologue"));
        denied.add("paiflow:agent:edit");assertEquals(403,assertThrows(ServiceException.class,()->workflows.save(wf,space,1L,body("name","不能半写"))).getCode());assertEquals(1L,workflowMapper.find(wf).getRevision());assertEquals("画布改名",mapper.find(id).getName());
    }
    @Test void fullCopyAndLegacyCloneCreateNewAgentWorkflowAndPreserveExactCanvasAndMetadata() {
        var created=workflow("复制fixture");long id=created.getLongValue("botId"),wf=created.getLongValue("workflowId");var graph=JSON.parseObject(workflowMapper.find(wf).getCanvasJson());graph.put("unknown",body("keep",true));graph.put("viewport",body("x",3,"zoom",0.75));String raw=" \n"+graph.toJSONString()+" \n";
        workflows.save(wf,space,0L,body("data",raw,"futureMetadata",body("keep",true)));
        var copied=agents.copy(id,space);long copyWf=copied.getLongValue("workflowId");assertNotEquals(id,copied.getLongValue("botId"));assertNotEquals(wf,copyWf);assertNotEquals(created.getString("flowId"),copied.getString("flowId"));assertEquals(raw,workflowMapper.find(copyWf).getCanvasJson());assertEquals(true,workflows.detail(copyWf,space).getJSONObject("futureMetadata").getBoolean("keep"));assertEquals(copied.getLong("botId"),JSON.parseObject(workflows.detail(copyWf,space).getString("ext")).getLong("botId"));
        var cloned=workflows.legacyClone(wf,space);assertNotNull(cloned.getLong("botId"));assertEquals("bound",cloned.getString("agentBindingStatus"));assertEquals(3,mapper.count(space,null,3));assertEquals(3,workflowMapper.count(space,null,null));assertEquals(0,workflowMapper.countVersions(copyWf));
        assertEquals(404,assertThrows(ServiceException.class,()->agents.copy(id,other)).getCode());
    }
    @Test void bindingFailureRollsBackBothCopyRowsUsingAnActualDatabaseConstraint() throws Exception {
        var created=workflow("绑定回滚fixture");long id=created.getLongValue("botId");
        try(var sql=schema.createStatement()) { sql.execute("ALTER TABLE paiflow_agent ADD CONSTRAINT fixture_copy_bind_fail CHECK(workflow_id IS NULL OR name NOT LIKE '%复制%')"); }
        var error=assertThrows(RuntimeException.class,()->agents.copy(id,space));assertTrue(causes(error).toLowerCase().contains("fixture_copy_bind_fail"));assertEquals(1,mapper.count(space,null,null));assertEquals(1,workflowMapper.count(space,null,null));assertEquals(1L,mapper.find(id).getRevision());
    }
    @Test void createWorkflowFailureDoesNotLeaveAnUnboundAgent() {
        denied.add("paiflow:workflow:create");assertEquals(403,assertThrows(ServiceException.class,()->workflow("无画布创建权限")).getCode());assertEquals(0,mapper.count(space,null,null));assertEquals(0,workflowMapper.count(space,null,null));
    }
    @Test void deletingBoundAgentNeedsBothVersionsAndPreservesImmutableHistory() {
        var created=workflow("删除fixture");long id=created.getLongValue("botId"),wf=created.getLongValue("workflowId");var version=workflows.createVersion(wf,space,0L,body("name","v1.0"));
        assertEquals(409,assertThrows(ServiceException.class,()->agents.delete(id,space,1L,0L)).getCode());assertNotNull(mapper.find(id));assertNotNull(workflowMapper.find(wf));
        actor.set(43L);agents.delete(id,space,1L,1L);assertNull(mapper.find(id));assertNull(workflowMapper.find(wf));assertNotNull(workflowMapper.version(version.getLongValue("id")));assertEquals(404,assertThrows(ServiceException.class,()->workflows.runtimeSnapshot(version.getLongValue("id"))).getCode());
    }
    @Test void directWorkflowDeleteDeletesItsBoundAgentWithinTheSameTransaction() {
        var created=workflow("画布删除");long id=created.getLongValue("botId"),wf=created.getLongValue("workflowId");denied.add("paiflow:agent:remove");assertThrows(ServiceException.class,()->workflows.delete(wf,space,0L));assertNotNull(mapper.find(id));assertNotNull(workflowMapper.find(wf));denied.clear();workflows.delete(wf,space,0L);assertNull(mapper.find(id));assertNull(workflowMapper.find(wf));
    }
    @Test void memberAndPlatformPermissionsAndCrossSpaceAreBothEnforced() {
        var created=agents.create(space,1,originalConfig("权限fixture",null));long id=created.getLongValue("botId");assertEquals(404,assertThrows(ServiceException.class,()->agents.detail(id,other)).getCode());actor.set(44L);assertEquals(404,assertThrows(ServiceException.class,()->agents.detail(id,space)).getCode());actor.set(43L);denied.add("paiflow:agent:edit");assertEquals(403,assertThrows(ServiceException.class,()->agents.update(id,space,0L,null,body("name","拒绝"))).getCode());assertEquals(0L,mapper.find(id).getRevision());
    }
    @Test void listCountAndRecordsShareSpaceFiltersAndNamesAreUniquePerSpace() {
        var one=agents.create(space,1,originalConfig("%_fixture",null));actor.set(43L);agents.create(space,1,originalConfig("成员fixture",null));assertThrows(ServiceException.class,()->agents.create(space,1,originalConfig("%_fixture",null)));agents.create(other,1,originalConfig("%_fixture",null));
        var page=agents.list(space,body("pageIndex",1,"pageSize",1));assertEquals(2,page.totalCount());assertEquals(1,page.pageData().size());assertEquals(2,page.totalPages());assertEquals(1,agents.list(space,body("searchValue","%_")).totalCount());assertEquals(0,agents.list(space,body("searchValue","' OR 1=1 --")).totalCount());assertEquals(0,agents.list(space,body("botStatus",List.of(1))).totalCount());agents.delete(one.getLongValue("botId"),space,0L,null);assertEquals(1,agents.list(space,new JSONObject()).totalCount());
    }
    @Test void originalCountGreaterThan100IsPreservedAndScopedToCurrentOwnerAndSpace() {
        for(int i=0;i<100;i++)agents.create(space,1,body("name","count-"+i));var last=agents.create(space,1,body("name","源允许第101个"));assertEquals(101,mapper.countOwner(space,42));assertEquals(409,assertThrows(ServiceException.class,()->agents.create(space,1,body("name","第102个"))).getCode());agents.copy(last.getLongValue("id"),space);assertEquals(102,mapper.countOwner(space,42));actor.set(43L);agents.create(space,1,body("name","另一创建者"));actor.set(42L);agents.create(other,1,body("name","另一空间"));assertEquals(103,mapper.count(space,null,null));
    }
    @Test void unknownCredentialsResourcesAndPublicationClaimsCannotWriteFormalRows() {
        for(JSONObject request:List.of(body("name","秘密","nested",body("apiSecret","fixture-private")),body("name","资源","datasetList",List.of("external-id")),body("name","模型","modelId",9),body("name","发布","publishStatus",1)))assertThrows(ServiceException.class,()->agents.create(space,1,request));assertEquals(0,mapper.count(space,null,null));
        var body=originalConfig("合法默认",null);body.put("uid","forged-user");body.put("botStatus",1);body.put("releaseType",List.of("MARKET"));var created=agents.create(space,1,LegacyBotController.form(body));assertEquals(42L,created.getLongValue("ownerUserId"));assertEquals(-9,created.getIntValue("botStatus"));assertTrue(created.getJSONArray("releaseType").isEmpty());assertEquals(503,assertThrows(ServiceException.class,()->agents.unavailableForAgent(created.getLongValue("botId"),space,"MARKET")).getCode());
    }
    @Test void concurrentWritesWithTheSameReadRevisionHaveExactlyOneWinner() throws Exception {
        var created=workflow("并发fixture");long id=created.getLongValue("botId");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try { Callable<Integer> write=()->{start.await();try{agents.update(id,space,1L,0L,body("botDesc",Thread.currentThread().getName()));return 200;}catch(ServiceException error){return error.getCode();}};var a=pool.submit(write);var b=pool.submit(write);start.countDown();assertEquals(Set.of(200,409),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));assertEquals(2L,mapper.find(id).getRevision());assertEquals(1L,workflowMapper.find(created.getLongValue("workflowId")).getRevision()); }finally{pool.shutdownNow();}
    }
    @Test void actualStartExtraOutputDeterminesMultiInputAndRestoreKeepsTheSingleBinding() {
        var created=workflow("额外输入fixture");long id=created.getLongValue("botId"),wf=created.getLongValue("workflowId");assertFalse(created.getBooleanValue("multiInput"));var version=workflows.createVersion(wf,space,0L,body("name","v1.0"));
        var graph=JSON.parseObject(workflowMapper.find(wf).getCanvasJson());graph.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONArray("outputs").add(body("id","extra-fixture","name","business_input","schema",body("type","string")));
        workflows.save(wf,space,1L,body("data",graph));assertTrue(agents.detail(id,space).getBooleanValue("multiInput"));assertEquals(2L,mapper.find(id).getRevision());var restored=workflows.restore(wf,version.getLongValue("id"),space,2L);assertEquals(id,restored.getLongValue("botId"));assertEquals(3L,mapper.find(id).getRevision());assertFalse(agents.detail(id,space).getBooleanValue("multiInput"));assertEquals(1,mapper.count(space,null,null));assertEquals(1,workflowMapper.count(space,null,null));
    }
    @Test void permissionRevokedWhileWaitingForSpaceLockIsRecheckedBeforeMutation() throws Exception {
        var created=agents.create(space,1,body("name","等待锁权限"));long id=created.getLongValue("botId");var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var initialAuth=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        when(permissions.hasPermi("paiflow:agent:edit")).thenAnswer(call->{initialAuth.countDown();return !denied.contains("paiflow:agent:edit");});
        try { var holder=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(data)).execute(status->{spaces.lock(space);acquired.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}return null;}));assertTrue(acquired.await(5,TimeUnit.SECONDS));var waiter=pool.submit(()->assertThrows(ServiceException.class,()->agents.update(id,space,0L,null,body("name","不能写入"))));assertTrue(initialAuth.await(5,TimeUnit.SECONDS));denied.add("paiflow:agent:edit");release.countDown();holder.get(10,TimeUnit.SECONDS);assertEquals(403,waiter.get(10,TimeUnit.SECONDS).getCode());assertEquals("等待锁权限",mapper.find(id).getName());assertEquals(0L,mapper.find(id).getRevision()); }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void clientMetadataCannotCreateOrSwapAnAgentBindingButActualEchoAndUnknownExtArePreserved() {
        var ordinary=agents.create(space,1,body("name","独立Agent"));long falseId=ordinary.getLongValue("id");
        assertEquals(409,assertThrows(ServiceException.class,()->workflows.create(space,body("name","伪造引用","ext",body("botId",falseId),"botId",falseId))).getCode());assertEquals(0,workflowMapper.count(space,null,null));
        var standalone=workflows.create(space,body("name","无绑定","ext",body("unknown",true)));long independent=standalone.getLongValue("id");assertEquals(409,assertThrows(ServiceException.class,()->workflows.save(independent,space,0L,body("ext",body("botId",falseId+100)))).getCode());assertEquals(0L,workflowMapper.find(independent).getRevision());
        var bound=workflow("真实绑定");long id=bound.getLongValue("id"),wf=bound.getLongValue("workflowId");var echo=body("botId",id,"agentRevision",1,"agentBindingStatus","bound","ext",body("botId",id,"unknown",body("keep",true)));
        var saved=workflows.save(wf,space,0L,echo);assertEquals(id,saved.getLongValue("botId"));assertEquals(2L,saved.getLongValue("agentRevision"));assertTrue(JSON.parseObject(saved.getString("ext")).getJSONObject("unknown").getBooleanValue("keep"));
        assertEquals(409,assertThrows(ServiceException.class,()->workflows.save(wf,space,1L,body("ext",body("botId",falseId)))).getCode());assertEquals(1L,workflowMapper.find(wf).getRevision());
        var bareCopy=workflows.copy(wf,space);assertNull(bareCopy.get("botId"));assertNull(bareCopy.get("agentRevision"));assertNull(JSON.parseObject(bareCopy.getString("ext")).get("botId"));assertTrue(JSON.parseObject(bareCopy.getString("ext")).getJSONObject("unknown").getBooleanValue("keep"));
        var boundCopy=agents.copy(id,space);var copiedWorkflow=workflows.detail(boundCopy.getLongValue("workflowId"),space);assertEquals(boundCopy.getLong("id"),JSON.parseObject(copiedWorkflow.getString("ext")).getLong("botId"));assertTrue(JSON.parseObject(copiedWorkflow.getString("ext")).getJSONObject("unknown").getBooleanValue("keep"));
    }
    @Test void legacyImportedAndRestoredOpaqueMetadataNeverBecomesAnUnboundAgentReference() {
        var created=workflows.create(space,body("name","历史不可信绑定"));long id=created.getLongValue("id");var row=workflowMapper.find(id);
        String historical=body("ext",body("botId",999,"unknown",true).toJSONString(),"botId",999,"agentRevision",888,"agentBindingStatus","bound","future",body("keep",true)).toJSONString();row.setMetadataJson(historical);assertEquals(1,workflowMapper.update(row));
        var detail=workflows.detail(id,space);assertNull(detail.get("botId"));assertNull(detail.get("agentRevision"));assertNull(detail.get("agentBindingStatus"));assertNull(JSON.parseObject(detail.getString("ext")).get("botId"));assertTrue(JSON.parseObject(detail.getString("ext")).getBooleanValue("unknown"));assertEquals(historical,workflowMapper.find(id).getMetadataJson());
        var version=workflows.createVersion(id,space,1L,body("name","v1.0"));assertEquals(historical,workflowMapper.version(version.getLongValue("id")).getMetadataJson());var restored=workflows.restore(id,version.getLongValue("id"),space,2L);assertNull(restored.get("botId"));assertNull(restored.get("agentRevision"));assertTrue(restored.getJSONObject("future").getBooleanValue("keep"));assertEquals(historical,workflowMapper.find(id).getMetadataJson());assertEquals(0,mapper.count(space,null,null));
    }
    @Test void defaultReadsAreEmptyOr404AndNeverChooseAnExistingNamedSpace() throws Exception {
        var named=agents.create(space,1,body("name","具名已有资源"));
        for(String header:new String[]{null,""}) { var page=agents.legacyList(header,body("pageIndex",1,"pageSize",15));assertEquals(0,page.totalCount());assertTrue(page.pageData().isEmpty());assertEquals(404,error(()->agents.legacyDetail(named.getLongValue("id"),header)));agents.legacyCatalogAccess(header); }
        assertNull(spaces.defaultPersonal(42));assertEquals(2,count("paiflow_space"));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(1,count("paiflow_agent_owner_guard"));
        assertEquals(400,error(()->agents.legacyList("",body("pageSize",0))));assertEquals(400,error(()->agents.legacyList(null,body("version",2))));assertEquals(400,error(()->agents.legacyList("",body("sort","unsafe"))));
    }
    @Test void headerScopeAndBodyEchoAreExactAndCannotExposeOtherJoinedDomains() {
        var named=agents.create(space,1,body("name","头部具名"));var second=agents.create(other,1,body("name","另一个具名"));
        assertEquals(1,agents.legacyList(String.valueOf(space),new JSONObject()).totalCount());assertEquals(404,error(()->agents.legacyDetail(second.getLongValue("id"),String.valueOf(space))));
        for(String bad:List.of("0","-1"," ","1.0","1,2","9223372036854775808"))assertEquals(400,error(()->agents.legacyList(bad,new JSONObject())));
        assertEquals(400,error(()->agents.legacyCreate("",1,body("name","伪域","spaceId",space))));assertEquals(400,error(()->agents.legacyUpdate(named.getLongValue("id"),String.valueOf(space),0L,null,body("spaceId",other,"name","错误echo"))));assertNull(spaces.defaultPersonal(42));
    }
    @Test void originalDefaultHttpCreationKeepsNullWireAndNativePositiveIdWithoutQueryPrivilege() throws Exception {
        denied.add("paiflow:agent:query");var mvc=MockMvcBuilders.standaloneSetup(new LegacyBotController(agents,new AgentTemplateCatalog())).build();
        var response=mvc.perform(post("/paiflow/legacy/bot/create").header("space-id","").contentType("application/json").content("{\"name\":\"默认原表单\",\"spaceId\":\"\",\"uid\":\"ignored\",\"ownerUserId\":777,\"inputExample\":[\"\",\"\",\"\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.spaceId").isEmpty()).andExpect(header().string("ETag","\"0\"")).andReturn().getResponse();
        var created=JSON.parseObject(response.getContentAsString()).getJSONObject("data");long id=created.getLongValue("id"),actual=spaces.defaultPersonal(42).getId();assertEquals(actual,mapper.find(id).getSpaceId());assertEquals(42,mapper.find(id).getOwnerUserId());denied.clear();assertEquals(actual,agents.detail(id,actual).getLongValue("spaceId"));assertTrue(agents.legacyDetail(id,null).containsKey("spaceId"));assertNull(agents.legacyDetail(id,"").get("spaceId"));assertEquals(0,count("paiflow_space_visit"));
    }
    @Test void defaultWorkflowBindingUpdateAndDeleteKeepBothCasAndImmutableHistory() {
        var created=agents.legacyCreate(null,3,body("name","默认绑定","spaceId",null,"inputExample",List.of("","","")));long id=created.getLongValue("id"),wf=created.getLongValue("workflowId"),actual=mapper.find(id).getSpaceId();assertNull(created.get("spaceId"));assertEquals(actual,workflowMapper.find(wf).getSpaceId());assertEquals(id,mapper.byWorkflow(wf).getId());
        assertEquals(409,error(()->agents.legacyUpdate(id,"",1L,null,body("name","缺画布条件"))));var updated=agents.legacyUpdate(id,"",1L,0L,body("botId",String.valueOf(id),"spaceId","","name","默认更新"));assertEquals(2,updated.getLongValue("revision"));assertEquals(1,updated.getLongValue("workflowRevision"));assertNull(updated.get("spaceId"));assertEquals("默认更新",workflowMapper.find(wf).getName());
        assertEquals(409,error(()->agents.legacyUpdate(id,null,1L,0L,body("name","旧窗口"))));var version=workflows.createVersion(wf,actual,1L,body("name","v1.0"));assertEquals(409,error(()->agents.legacyDelete(id,null,2L,1L)));agents.legacyDelete(id,"",2L,2L);assertNull(mapper.find(id));assertNull(workflowMapper.find(wf));assertNotNull(workflowMapper.version(version.getLongValue("id")));assertNotNull(spaces.defaultPersonal(42));
    }
    @Test void actualBindingConstraintFailureRollsBackFirstDefaultAndBothResources() throws Exception {
        try(var sql=schema.createStatement()){sql.execute("ALTER TABLE paiflow_agent ADD CONSTRAINT fixture_first_default_bind CHECK(workflow_id IS NULL)");}
        var failure=assertThrows(RuntimeException.class,()->agents.legacyCreate("",3,body("name","失败绑定")));assertTrue(causes(failure).toUpperCase(java.util.Locale.ROOT).contains("FIXTURE_FIRST_DEFAULT_BIND"));assertNull(spaces.defaultPersonal(42));assertEquals(2,count("paiflow_space"));assertEquals(0,count("paiflow_agent"));assertEquals(0,count("paiflow_workflow"));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(0,count("paiflow_agent_owner_guard"));
    }
    @Test void missingProviderResourcesAndWorkflowPrivilegeCannotInitializeTheDefaultDomain() throws Exception {
        assertEquals(503,error(()->agents.legacyCreate("",1,body("name","未接模型","modelId",88))));assertEquals(503,error(()->agents.legacyUnavailable(null,"paiflow:agent:create","真实模板端口")));assertNull(spaces.defaultPersonal(42));assertEquals(0,count("paiflow_agent_owner_guard"));
        denied.add("paiflow:workflow:create");assertEquals(403,error(()->agents.legacyCreate(null,3,body("name","不能半创建"))));assertNull(spaces.defaultPersonal(42));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(0,count("paiflow_agent_owner_guard"));assertEquals(0,count("paiflow_agent"));assertEquals(0,count("paiflow_workflow"));
        denied.clear();denied.add("paiflow:agent:create");assertEquals(403,error(()->agents.legacyCreate("",1,body("name","不能初始化"))));assertNull(spaces.defaultPersonal(42));
    }
    @Test void defaultDomainIsOwnerOnlyEvenWithDirtyForeignMembershipAndKnownResourceId() {
        var created=agents.legacyCreate("",1,body("name","用户42默认"));long id=created.getLongValue("id"),actual=mapper.find(id).getSpaceId();spaces.insertMember(actual,43,3,"dirty-fixture");actor.set(43L);
        assertEquals(0,agents.legacyList(null,new JSONObject()).totalCount());assertEquals(404,error(()->agents.legacyDetail(id,"")));assertEquals(404,error(()->agents.legacyDetail(id,String.valueOf(actual))));assertEquals(404,error(()->agents.legacyUpdate(id,"",0L,null,body("name","跨用户"))));assertEquals(404,error(()->agents.legacyDelete(id,null,0L,null)));assertEquals(404,error(()->agents.legacyCopy(id,"")));assertNull(spaces.defaultPersonal(43));assertEquals("用户42默认",mapper.find(id).getName());
    }
    @Test void defaultCountUsesAllLiveOwnerBotsAcrossNamedAndTeamButKeepsOriginalGreaterThan100() throws Exception {
        long team=createTeam();seed(space,42,40,"个人-",false);seed(team,42,60,"团队-",false);seed(other,42,5,"旧删-",true);seed(other,43,5,"别人-",false);assertEquals(100,mapper.countAllOwner(42));
        var last=agents.legacyCreate("",1,body("name","原允许101"));assertEquals(101,mapper.countAllOwner(42));assertEquals(409,error(()->agents.legacyCreate(null,1,body("name","不能102"))));assertEquals(1,mapper.count(spaces.defaultPersonal(42).getId(),null,null));
        agents.create(other,1,body("name","具名仍按本域"));assertEquals(102,mapper.countAllOwner(42));agents.legacyDelete(last.getLongValue("id"),null,0L,null);assertEquals(101,mapper.countAllOwner(42));assertEquals(409,error(()->agents.legacyCreate("",1,body("name","仍超上限"))));
    }
    @Test void originalUnlimitedDefaultCopyAndBoundCloneAreIncludedInLaterOwnerCount() throws Exception {
        var created=agents.legacyCreate("",3,body("name","默认原复制"));long id=created.getLongValue("id"),wf=created.getLongValue("workflowId"),actual=mapper.find(id).getSpaceId();seed(space,42,100,"填充-",false);assertEquals(101,mapper.countAllOwner(42));
        var graph=JSON.parseObject(workflowMapper.find(wf).getCanvasJson());graph.put("futureDefault",body("preserved",true));String raw=" \n"+graph.toJSONString()+" \n";workflows.save(wf,actual,0L,body("data",raw));
        var copied=agents.legacyCopy(id,null);assertNull(copied.get("spaceId"));assertEquals(raw,workflowMapper.find(copied.getLongValue("workflowId")).getCanvasJson());assertEquals(102,mapper.countAllOwner(42));var clone=legacyFlows.clone(wf,"");assertNotNull(clone.get("botId"));assertNull(clone.get("spaceId"));assertEquals(actual,mapper.find(clone.getLongValue("botId")).getSpaceId());assertEquals(103,mapper.countAllOwner(42));assertEquals(409,error(()->agents.legacyCreate("",1,body("name","复制计入后续"))));
    }
    @Test void concurrentDefaultCreatesAt100HaveOneWinnerAndOneCountConflict() throws Exception {
        seed(space,42,100,"并发填充-",false);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{Callable<Integer> create=()->{start.await();try{agents.legacyCreate("",1,body("name",Thread.currentThread().getName()));return 200;}catch(ServiceException ex){return ex.getCode();}};var a=pool.submit(create);var b=pool.submit(create);start.countDown();assertEquals(Set.of(200,409),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));assertEquals(101,mapper.countAllOwner(42));assertEquals(1,mapper.count(spaces.defaultPersonal(42).getId(),null,null));assertEquals(1,count("paiflow_agent_owner_guard"));}finally{pool.shutdownNow();}
    }
    @Test void namedInsertUsesSameGuardAndDefaultWaiterSeesItsCommittedOwnerCount() throws Exception {
        seed(space,42,100,"等具名-",false);var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var holder=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(data)).execute(tx->{mapper.lockOwnerGuard(42);agents.create(other,1,body("name","先提交具名"));acquired.countDown();await(release);return null;}));assertTrue(acquired.await(5,TimeUnit.SECONDS));var checked=new CountDownLatch(1);when(permissions.hasPermi("paiflow:agent:create")).thenAnswer(a->{checked.countDown();return true;});var waiter=pool.submit(()->error(()->agents.legacyCreate("",1,body("name","后默认"))));assertTrue(checked.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiter.get(100,TimeUnit.MILLISECONDS));release.countDown();holder.get(10,TimeUnit.SECONDS);assertEquals(409,waiter.get(10,TimeUnit.SECONDS));assertEquals(101,mapper.countAllOwner(42));assertNull(spaces.defaultPersonal(42));}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void permissionRevokedWhileWaitingForCreatorGuardRejectsBeforeAnyDefaultWrite() throws Exception {
        mapper.lockOwnerGuard(42);var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var checked=new CountDownLatch(1);when(permissions.hasPermi("paiflow:agent:create")).thenAnswer(a->{checked.countDown();return !denied.contains("paiflow:agent:create");});var pool=Executors.newFixedThreadPool(2);
        try{var holder=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(data)).execute(tx->{mapper.lockOwnerGuard(42);acquired.countDown();await(release);return null;}));assertTrue(acquired.await(5,TimeUnit.SECONDS));var waiter=pool.submit(()->error(()->agents.legacyCreate("",1,body("name","失权不能插入"))));assertTrue(checked.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiter.get(100,TimeUnit.MILLISECONDS));denied.add("paiflow:agent:create");release.countDown();holder.get(10,TimeUnit.SECONDS);assertEquals(403,waiter.get(10,TimeUnit.SECONDS));assertNull(spaces.defaultPersonal(42));assertEquals(0,count("paiflow_agent"));assertEquals(0,count("paiflow_membership_scope_lock"));}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void defaultUpdateAndDeleteDoNotWaitForCreatorGuardOrReverseItsLockOrder() throws Exception {
        var created=agents.legacyCreate("",1,body("name","更新不拿创建锁"));long id=created.getLongValue("id");var pool=Executors.newSingleThreadExecutor();try{new TransactionTemplate(new DataSourceTransactionManager(data)).execute(tx->{mapper.lockOwnerGuard(42);try{var updated=pool.submit(()->agents.legacyUpdate(id,"",0L,null,body("name","已更新"))).get(2,TimeUnit.SECONDS);assertEquals(1,updated.getLongValue("revision"));pool.submit(()->agents.legacyDelete(id,null,1L,null)).get(2,TimeUnit.SECONDS);}catch(Exception ex){throw new RuntimeException(ex);}return null;});assertNull(mapper.find(id));}finally{pool.shutdownNow();}
    }
    @Test void originalDefaultMvcDetailAndListPreserveNullAndNamedHeaderIsStillExact() throws Exception {
        var created=agents.legacyCreate(null,1,body("name","默认HTTP读取"));long id=created.getLongValue("id");agents.create(space,1,body("name","具名HTTP读取"));var mvc=MockMvcBuilders.standaloneSetup(new LegacyMyBotController(agents),new LegacyAgentWorkflowController(agents)).build();
        mvc.perform(post("/paiflow/legacy/my-bot/bot-detail").param("botId",String.valueOf(id))).andExpect(status().isOk()).andExpect(jsonPath("$.data.spaceId").isEmpty()).andExpect(header().string("ETag","\"0\""));mvc.perform(post("/paiflow/legacy/my-bot/list").header("space-id","").contentType("application/json").content("{}")).andExpect(jsonPath("$.data.totalCount").value(1)).andExpect(jsonPath("$.data.pageData[0].spaceId").isEmpty());mvc.perform(post("/paiflow/legacy/my-bot/list").header("space-id",String.valueOf(space)).contentType("application/json").content("{}")).andExpect(jsonPath("$.data.totalCount").value(1)).andExpect(jsonPath("$.data.pageData[0].spaceId").value(space));
    }
    private int error(Runnable action){return assertThrows(ServiceException.class,action::run).getCode();}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}
    private long count(String table)throws Exception{try(var connection=data.getConnection();var sql=connection.createStatement();var result=sql.executeQuery("SELECT COUNT(*) FROM "+table)){result.next();return result.getLong(1);}}
    private long createTeam(){var row=new SpaceRecord();row.setName("团队fixture");row.setKind("team");row.setTeamId(71L);row.setOwnerUserId(42L);row.setCreateBy("fixture");row.setUpdateBy("fixture");spaces.insert(row);spaces.insertOwner(row.getId(),42,"fixture");return row.getId();}
    private void seed(long domain,long owner,int amount,String prefix,boolean deleted)throws Exception{
        try(var connection=data.getConnection();var statement=connection.prepareStatement("INSERT INTO paiflow_agent(space_id,owner_user_id,version,bot_type,name,config_json,deleted,create_by,update_by,create_time,update_time) VALUES(?,?,1,0,?,'{}',?,'fixture','fixture',CURRENT_TIMESTAMP(),CURRENT_TIMESTAMP())")){for(int i=0;i<amount;i++){statement.setLong(1,domain);statement.setLong(2,owner);statement.setString(3,prefix+i);statement.setInt(4,deleted?1:0);statement.addBatch();}statement.executeBatch();}
    }
    private static String causes(Throwable error) { var value=new StringBuilder();for(Throwable cause=error;cause!=null;cause=cause.getCause())value.append(cause.getMessage());return value.toString(); }
}
