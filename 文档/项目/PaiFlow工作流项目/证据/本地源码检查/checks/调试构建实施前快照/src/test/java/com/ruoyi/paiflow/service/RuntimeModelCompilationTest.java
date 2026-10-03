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
    private JdbcDataSource db;private java.sql.Connection schema;private ModelMapper models;private SpaceMapper spaces;private WorkflowMapper workflows;
    private ModelRuntimeCatalog catalog;private WorkflowService service;private ResourceAccessService access;
    private ISysUserService users;private SysPermissionService permissions;private long space,other;
    private final AtomicReference<ModelRuntimeCatalog.ProfilePort> profile=new AtomicReference<>();
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(java.util.function.Supplier<T> value){var p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenAnswer(c->value.get());return p;}
    @BeforeEach void setup() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE");
        schema=db.getConnection();
        // H2 2.4.240的IN常量CHECK持有创建session；保留DDL连接，不放宽原约束。
        try(var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.RuntimeModelCompilationTest.utcNow'");
            for(String file:List.of("001-paiflow-schema.sql","002-paiflow-workflow.sql","009-paiflow-model.sql","010-paiflow-default-personal.sql","012-paiflow-workflow-model-snapshots.sql","015-paiflow-execution-codec.sql")){
                String ddl=Files.readString(Path.of("sql",file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","")
                    .replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END").replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END")
                    .replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var conf=new Configuration(new Environment("runtime-model-isolated",new SpringManagedTransactionFactory(),db));
        for(String name:List.of("SpaceMapper","ModelMapper","WorkflowMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){assertNotNull(xml);new XMLMapperBuilder(xml,conf,name,conf.getSqlFragments()).parse();}
        var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(conf));models=template.getMapper(ModelMapper.class);spaces=template.getMapper(SpaceMapper.class);workflows=template.getMapper(WorkflowMapper.class);
        access=mock(ResourceAccessService.class);when(access.requireSpace(anyLong(),anyString(),anyInt(),anyBoolean())).thenReturn(new SpaceRecord());
        var identities=mock(IdentityMappingService.class);var actor=user(42);when(identities.current()).thenReturn(new LoginUser(42L,8L,actor,Set.of()));
        users=mock(ISysUserService.class);when(users.selectUserById(anyLong())).thenAnswer(c->user(c.getArgument(0)));permissions=mock(SysPermissionService.class);when(permissions.getMenuPermission(any())).thenReturn(Set.of("paiflow:model:query"));
        catalog=new ModelRuntimeCatalog(models,spaces,access,users,permissions);catalog.useCurrentSession(template);catalog.useProfiles(provider(profile::get));approved();
        var target=new WorkflowService(workflows,access,identities);target.useModelCatalogs(provider(()->catalog));var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));service=(WorkflowService)proxy.getProxy();
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
    @Test void originalTemplateObjectInputsRemainWhileHistoryMultimodalAndOverridesAreRejected(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var canvas=graph(m.getId(),"domain-model");canvas.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").put("inputs",new JSONArray(List.of(JSONObject.of("name","value","type","object"))));assertNotNull(WorkflowModelCompiler.compile(canvas,(id,s,d)->fixed(m)));
        for(JSONObject patch:List.of(JSONObject.of("enableChatHistory",true),JSONObject.of("enableChatHistoryV2",JSONObject.of("isEnabled",true)),JSONObject.of("multiMode",true),JSONObject.of("extraParams",JSONObject.of("extra_headers",JSONObject.of("x","y"))),JSONObject.of("extraParams",JSONObject.of("n",2)))){var g=graph(m.getId(),"domain-model");param(g).putAll(patch);assertThrows(ServiceException.class,()->WorkflowModelCompiler.compile(g,(id,s,d)->{fail("preflight must reject before model read");return null;}));}
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
    @Test void javaResourceAndNestedModelCompilesNeverPretendTheirRuntimeIsConnected(){
        var m=model(space,"domain-model","https://provider.invalid/v1/chat/completions");var c=service.create(space,JSONObject.of("name","java model","engineProfile","java","data",graph(m.getId(),"domain-model")));assertEquals(503,assertThrows(ServiceException.class,()->service.createVersion(c.getLongValue("id"),space,0L,new JSONObject())).getCode());assertEquals(0,workflows.countVersions(c.getLongValue("id")));
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
