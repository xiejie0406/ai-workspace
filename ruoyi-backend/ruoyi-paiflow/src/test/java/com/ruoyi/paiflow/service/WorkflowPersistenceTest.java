package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际Mapper与Spring事务的隔离验证；不是用户MySQL/页面/Worker可用证据。 */
public class WorkflowPersistenceTest {
    private JdbcDataSource source;
    private java.sql.Connection schema;
    private WorkflowMapper mapper;
    private ResourceAccessService access;
    private IdentityMappingService identities;
    private WorkflowService service;
    public static Timestamp utcNow() { return Timestamp.from(Instant.now()); }
    @BeforeEach void setup() throws Exception {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        String ddl=(java.nio.file.Files.readString(java.nio.file.Path.of("sql/002-paiflow-workflow.sql"),StandardCharsets.UTF_8)+"\n"+java.nio.file.Files.readString(java.nio.file.Path.of("sql/012-paiflow-workflow-model-snapshots.sql"),StandardCharsets.UTF_8)+"\n"+java.nio.file.Files.readString(java.nio.file.Path.of("sql/015-paiflow-execution-codec.sql"),StandardCharsets.UTF_8)+"\n"+java.nio.file.Files.readString(java.nio.file.Path.of("sql/018-paiflow-workflow-debug-snapshot.sql"),StandardCharsets.UTF_8))
            .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("(?m)^--.*$","");
        schema=source.getConnection();
        try(var sql=schema.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.WorkflowPersistenceTest.utcNow'");
            for(String statement:ddl.split(";")) if(!statement.isBlank()) sql.execute(statement);
        }
        var configuration=new Configuration(new Environment("isolated-workflow",new SpringManagedTransactionFactory(),source));
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("mapper/paiflow/WorkflowMapper.xml")) {
            assertNotNull(input);new XMLMapperBuilder(input,configuration,"mapper/paiflow/WorkflowMapper.xml",configuration.getSqlFragments()).parse();
        }
        var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));mapper=template.getMapper(WorkflowMapper.class);
        access=mock(ResourceAccessService.class);identities=mock(IdentityMappingService.class);
        var user=new SysUser();user.setUserId(42L);user.setUserName("fixture");when(identities.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));
        when(access.requireSpace(anyLong(),anyString(),anyInt(),anyBoolean())).thenReturn(new SpaceRecord());
        var target=new WorkflowService(mapper,access,identities);var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));service=(WorkflowService)proxy.getProxy();
    }
    private JSONObject create(String name) { var body=new JSONObject();body.put("name",name);return service.create(5,body); }
    private static JSONObject body(Object... pairs) { var b=new JSONObject();for(int i=0;i<pairs.length;i+=2)b.put((String)pairs[i],pairs[i+1]);return b; }
    @Test void actualJsonObjectCanvasAndConfigurationSurviveJacksonBindingAndPersistence() throws Exception {
        var initial=create("JSON object source");
        var graph=JSON.parseObject(initial.getString("data"));graph.put("viewport",body("x",123,"y",456,"zoom",0.75));graph.put("futureCanvas",body("text","原画布未知字段"));
        var wire=body("name","JSON object create","data",graph,"advancedConfig",body("prologue",body("enabled",true,"prologueText","原开场白")),"flowConfig",body("futureFlow",body("keep",true)));
        var request=new com.fasterxml.jackson.databind.ObjectMapper().readValue(wire.toJSONString(),JSONObject.class);
        assertInstanceOf(java.util.Map.class,request.get("data"));assertFalse(request.get("data") instanceof JSONObject);
        var created=service.create(5,request);long id=created.getLongValue("id");
        assertEquals(graph.toJSONString(),mapper.find(id).getCanvasJson());
        assertEquals(wire.getJSONObject("advancedConfig"),JSON.parseObject(mapper.find(id).getAdvancedConfigJson()));
        assertEquals(wire.getJSONObject("flowConfig"),JSON.parseObject(mapper.find(id).getFlowConfigJson()));
        // 原高级配置面板发送对象；不能用手工构造JSONObject绕开真实HTTP嵌套Map。
        var update=new com.fasterxml.jackson.databind.ObjectMapper().readValue(body("id",id,"flowId",created.getString("flowId"),"advancedConfig",body("prologue",body("enabled",false,"prologueText","更新开场白")),"flowConfig",body("futureFlow",body("keep",false))).toJSONString(),JSONObject.class);
        assertFalse(update.get("advancedConfig") instanceof JSONObject);
        var saved=service.save(id,5L,0L,update);assertEquals(1L,saved.getLongValue("revision"));
        assertEquals("更新开场白",JSON.parseObject(mapper.find(id).getAdvancedConfigJson()).getJSONObject("prologue").getString("prologueText"));
        assertFalse(JSON.parseObject(mapper.find(id).getFlowConfigJson()).getJSONObject("futureFlow").getBooleanValue("keep"));
        assertEquals(graph.toJSONString(),mapper.find(id).getCanvasJson());
    }
    @org.junit.jupiter.api.AfterEach void closeSchema() throws Exception {if(schema!=null)schema.close();}
    @Test void encodedCanvasRemainsByteIdenticalAndConfigurationArraysAreRejectedAtomically() throws Exception {
        String raw="{\n  \"nodes\" : [], \"edges\" : [], \"futureText\":\"原画布\"\n}";
        var request=new com.fasterxml.jackson.databind.ObjectMapper().readValue(body("name","Raw wire canvas","data",raw).toJSONString(),JSONObject.class);
        var created=service.create(5,request);long id=created.getLongValue("id");assertEquals(raw,mapper.find(id).getCanvasJson());
        var invalid=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"description\":\"must rollback\",\"advancedConfig\":[{\"enabled\":true}]}",JSONObject.class);
        assertEquals(400,assertThrows(ServiceException.class,()->service.save(id,5L,0L,invalid)).getCode());
        assertEquals(0L,mapper.find(id).getRevision());assertNull(mapper.find(id).getDescription());assertEquals("{}",mapper.find(id).getAdvancedConfigJson());assertEquals(raw,mapper.find(id).getCanvasJson());
        assertEquals(400,assertThrows(ServiceException.class,()->service.save(id,5L,0L,body("data",java.util.List.of()))).getCode());
        assertEquals(0L,mapper.find(id).getRevision());
    }
    @Test void originalPartialAdvancedConfigurationMergesObjectsAndReplacesArraysScalarsAndNull() throws Exception {
        var created=service.create(5,body("name","Partial advanced","advancedConfig","{\"prologue\":{\"enabled\":true,\"prologueText\":\"旧开场白\",\"inputExample\":[\"原问题\"]},\"suggestedQuestionsAfterAnswer\":{\"enabled\":true},\"replaceObject\":{\"old\":true},\"clearValue\":\"旧值\"}","flowConfig",body("oldFlow",true)));
        long id=created.getLongValue("id");
        var update=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"advancedConfig\":{\"prologue\":{\"prologueText\":\"新开场白\",\"inputExample\":[\"新问题\"]},\"replaceObject\":\"新标量\",\"clearValue\":null},\"flowConfig\":{\"newFlow\":true}}",JSONObject.class);
        var saved=service.save(id,5L,0L,update);var config=JSON.parseObject(saved.getString("advancedConfig"));
        assertTrue(config.getJSONObject("prologue").getBooleanValue("enabled"));assertEquals("新开场白",config.getJSONObject("prologue").getString("prologueText"));
        assertEquals(java.util.List.of("新问题"),config.getJSONObject("prologue").getJSONArray("inputExample"));assertTrue(config.getJSONObject("suggestedQuestionsAfterAnswer").getBooleanValue("enabled"));
        assertEquals("新标量",config.getString("replaceObject"));assertTrue(config.containsKey("clearValue"));assertNull(config.get("clearValue"));
        assertEquals("{\"newFlow\":true}",saved.getString("flowConfig"));assertEquals(1L,mapper.find(id).getRevision());
    }
    @Test void actualJsonCanvasPreservesExplicitRootAndNestedNullForCreateAndSave() throws Exception {
        var request=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"name\":\"Explicit null canvas\",\"data\":{\"nodes\":[],\"edges\":[],\"futureRoot\":null,\"futureNested\":{\"keepNull\":null},\"untouched\":\"原字段\"}}",JSONObject.class);
        long id=service.create(5,request).getLongValue("id");var graph=JSON.parseObject(mapper.find(id).getCanvasJson());
        assertTrue(graph.containsKey("futureRoot"));assertNull(graph.get("futureRoot"));assertTrue(graph.getJSONObject("futureNested").containsKey("keepNull"));assertNull(graph.getJSONObject("futureNested").get("keepNull"));
        service.save(id,5L,0L,body("data",body("nodes",java.util.List.of(),"edges",java.util.List.of(),"futureRoot","旧值")));
        var update=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"data\":{\"nodes\":[],\"edges\":[],\"futureRoot\":null,\"futureNested\":{\"keepNull\":null}}}",JSONObject.class);
        service.save(id,5L,1L,update);graph=JSON.parseObject(mapper.find(id).getCanvasJson());
        assertTrue(graph.containsKey("futureRoot"));assertNull(graph.get("futureRoot"));assertTrue(graph.getJSONObject("futureNested").containsKey("keepNull"));assertNull(graph.getJSONObject("futureNested").get("keepNull"));assertEquals("原字段",graph.getString("untouched"));assertEquals(2L,mapper.find(id).getRevision());
    }
    @Test void advancedConfigurationBudgetAppliesAfterMergingAndRejectsWithoutPersistingPatch() {
        var created=service.create(5,body("name","Merged budget","advancedConfig",body("oldSection","a".repeat(9*1024*1024))));long id=created.getLongValue("id");
        var error=assertThrows(ServiceException.class,()->service.save(id,5L,0L,body("advancedConfig",body("newSection","b".repeat(8*1024*1024)))));
        assertEquals(413,error.getCode());assertEquals(0L,mapper.find(id).getRevision());var persisted=JSON.parseObject(mapper.find(id).getAdvancedConfigJson());assertEquals(9*1024*1024,persisted.getString("oldSection").length());assertFalse(persisted.containsKey("newSection"));
    }
    @Test void nullFieldNamesCountTowardFinalMergedPhysicalBudget() throws Exception {
        var created=service.create(5,body("name","Null merged budget","advancedConfig",body("oldSection","a".repeat(9*1024*1024))));long id=created.getLongValue("id");
        // 每枚字段名均低于实际Jackson的50000字符限制，全部null键合计仍超过9MiB。
        var fields=new JSONObject();String prefix="b".repeat(32768);for(int i=0;i<300;i++)fields.put(prefix+i,null);
        var patch=new com.fasterxml.jackson.databind.ObjectMapper().readValue(body("advancedConfig",fields).toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),JSONObject.class);
        var error=assertThrows(ServiceException.class,()->service.save(id,5L,0L,patch));assertEquals(413,error.getCode());assertEquals(0L,mapper.find(id).getRevision());
        var persisted=JSON.parseObject(mapper.find(id).getAdvancedConfigJson());assertEquals(1,persisted.size());assertEquals(9*1024*1024,persisted.getString("oldSection").length());
    }
    @Test void canvasRequiresActualArraysAndNeverCoercesArrayStringsOnCreateOrSave() throws Exception {
        var invalid=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"name\":\"Array strings\",\"data\":{\"nodes\":\"[]\",\"edges\":\"[]\"}}",JSONObject.class);
        assertEquals(400,assertThrows(ServiceException.class,()->service.create(5,invalid)).getCode());assertEquals(0,mapper.count(5,null,null));
        long id=create("Real arrays").getLongValue("id");String existing=mapper.find(id).getCanvasJson();
        for(Object bad:java.util.List.of("{\"nodes\":\"[]\",\"edges\":[]}",body("nodes",java.util.List.of(),"edges","[]"))) {
            assertEquals(400,assertThrows(ServiceException.class,()->service.save(id,5L,0L,body("data",bad))).getCode());
            assertEquals(0L,mapper.find(id).getRevision());assertEquals(existing,mapper.find(id).getCanvasJson());
        }
    }
    @Test void objectCanvasSaveRetainsUnsentOriginalRootMetadataAndMergedSecretsAreRejected() throws Exception {
        var initial=create("Partial canvas source");var graph=JSON.parseObject(initial.getString("data"));graph.put("viewport",body("x",17,"y",29,"zoom",1));graph.put("futureRoot",body("retain","原根字段"));
        long id=service.create(5,body("name","Partial canvas","data",graph)).getLongValue("id");
        var update=new com.fasterxml.jackson.databind.ObjectMapper().readValue(body("data",body("nodes",graph.get("nodes"),"edges",graph.get("edges"))).toJSONString(),JSONObject.class);
        var saved=service.save(id,5L,0L,update);var persisted=JSON.parseObject(saved.getString("data"));
        assertEquals("原根字段",persisted.getJSONObject("futureRoot").getString("retain"));assertEquals(17,persisted.getJSONObject("viewport").getIntValue("x"));
        // 模拟迁入旧记录含未托管秘密；本次安全patch不能让合并结果绕过同一边界。
        var row=mapper.find(id);row.setAdvancedConfigJson("{\"provider\":{\"api_key\":\"fixture-old-secret\"}}");assertEquals(1,mapper.update(row));
        var error=assertThrows(ServiceException.class,()->service.save(id,5L,2L,body("advancedConfig",body("prologue",body("enabled",true)))));
        assertEquals(400,error.getCode());assertFalse(error.getMessage().contains("fixture-old-secret"));assertEquals(2L,mapper.find(id).getRevision());assertEquals(row.getAdvancedConfigJson(),mapper.find(id).getAdvancedConfigJson());
    }
    @Test void originalCanvasAndUnknownFieldsRemainIndependentOfExecutableVersion() {
        var created=create("Original canvas");long id=created.getLongValue("id");
        JSONObject graph=JSON.parseObject(created.getString("data"));graph.put("viewport",body("x",123,"y",456,"zoom",0.75));graph.put("futureRoot",body("keep",true));
        graph.getJSONArray("nodes").getJSONObject(0).put("futureNode",body("original",true));
        graph.getJSONArray("nodes").getJSONObject(0).getJSONObject("position").put("x",999);
        var saved=service.save(id,5L,0L,body("data",graph,"futureMetadata",body("source","original"),"advancedConfig",body("futureAdvanced",true)));
        // 原消费者使用JSON字符串；逐字比较可同时证明布局、未知字段和数值传输保全，避免Double/BigDecimal夹具差异。
        assertEquals(graph.toJSONString(),saved.getString("data"));assertEquals(1L,saved.getLongValue("revision"));
        var version=service.createVersion(id,5L,1L,body("name","v1.0"));
        var snapshot=service.runtimeSnapshot(version.getLongValue("id"));assertEquals(graph.toJSONString(),snapshot.canvas().toJSONString());assertNotNull(snapshot.dslHash());assertNotNull(snapshot.canonicalDslBase64());
        assertEquals(true,snapshot.advancedConfig().getBoolean("futureAdvanced"));assertFalse(snapshot.execution().getJSONArray("nodes").getJSONObject(0).containsKey("futureNode"));
        snapshot.canvas().put("futureRoot","changed by caller");assertEquals(graph.toJSONString(),service.runtimeSnapshot(version.getLongValue("id")).canvas().toJSONString());
        assertEquals("unverified",saved.getString("publicationStatus"));assertFalse(saved.getBooleanValue("canPublish"));
    }
    @Test void staleRevisionMissingRevisionCrossSpaceAndForgedFlowIdCannotOverwrite() {
        var created=create("Concurrent");long id=created.getLongValue("id");service.save(id,5L,0L,body("description","first writer"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.save(id,5L,0L,body("description","stale writer"))).getCode());
        assertThrows(ServiceException.class,()->service.save(id,5L,null,body("description","missing version")));
        assertEquals(404,assertThrows(ServiceException.class,()->service.save(id,6L,1L,body("description","cross space"))).getCode());
        assertThrows(ServiceException.class,()->service.save(id,5L,1L,body("flowId","forged")));
        assertEquals("first writer",service.detail(id,5L).getString("description"));assertEquals(1L,mapper.find(id).getRevision());
    }
    @Test void versionInsertFailureRollsBackVersionAndPointerAndSnapshotNeverUpdates() throws Exception {
        var created=create("Rollback");long id=created.getLongValue("id");
        try(var c=source.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_workflow ADD CONSTRAINT fixture_fail_pointer CHECK(latest_version_id IS NULL)"); }
        assertThrows(RuntimeException.class,()->service.createVersion(id,5L,0L,body("name","v1.0")));
        assertEquals(0,mapper.countVersions(id));assertNull(mapper.find(id).getLatestVersionId());assertEquals(0L,mapper.find(id).getRevision());
    }
    @Test void restoreRejectsForeignVersionAndKeepsHistoricalSnapshotWhileCreatingNewDraftRevision() {
        var first=create("First");long id=first.getLongValue("id");var version=service.createVersion(id,5L,0L,body("name","v1.0"));long versionId=version.getLongValue("id");
        JSONObject graph=JSON.parseObject(first.getString("data"));graph.put("draftOnly",true);service.save(id,5L,1L,body("data",graph));
        var other=create("Other");var otherVersion=service.createVersion(other.getLongValue("id"),5L,0L,body("name","v1.0"));
        assertThrows(ServiceException.class,()->service.restore(id,otherVersion.getLongValue("id"),5L,2L));
        var restored=service.restore(id,versionId,5L,2L);assertEquals(3L,restored.getLongValue("revision"));assertEquals(JSON.parseObject(first.getString("data")),JSON.parseObject(restored.getString("data")));
        assertEquals(version.getString("data"),mapper.version(versionId).getCanvasJson());assertEquals(1,mapper.countVersions(id));
    }
    @Test void duplicateNamesAreSpaceScopedSearchIsLiteralAndDeletedWorkflowStopsNewRuntimeReads() {
        var created=create("%_fixture");long id=created.getLongValue("id");assertThrows(ServiceException.class,()->create("%_fixture"));
        var otherSpace=service.create(6,body("name","%_fixture"));assertNotEquals(created.getLong("id"),otherSpace.getLong("id"));
        assertEquals(1,service.list(5,1,10,"%_",null,2,null).totalCount());assertEquals(0,service.list(5,1,10,"' OR 1=1 --",null,2,null).totalCount());
        var version=service.createVersion(id,5L,0L,body("name","v1.0"));service.delete(id,5L,1L);assertThrows(ServiceException.class,()->service.runtimeSnapshot(version.getLongValue("id")));
        assertNotNull(mapper.version(version.getLongValue("id")));assertEquals(0,service.list(5,1,10,null,null,2,null).totalCount());
    }
    @Test void copyHasNewIdentifiersPreservesLayoutAndDoesNotInventAgentApplicationOrPublication() {
        var created=service.create(5,body("name","Source","ext",body("sourceAnnotation",123),"appId","opaque-old-app"));
        var copy=service.copy(created.getLongValue("id"),5);assertNotEquals(created.getLong("id"),copy.getLong("id"));assertNotEquals(created.getString("flowId"),copy.getString("flowId"));
        assertEquals(JSON.parseObject(created.getString("data")),JSON.parseObject(copy.getString("data")));assertEquals(5L,copy.getLongValue("spaceId"));assertEquals(123,JSON.parseObject(copy.getString("ext")).getIntValue("sourceAnnotation"));assertNull(copy.get("appId"));assertEquals("unverified",copy.getString("bindingStatus"));
        assertEquals(404,assertThrows(ServiceException.class,()->service.copy(created.getLongValue("id"),6)).getCode());
        assertEquals(503,assertThrows(ServiceException.class,()->service.legacyClone(created.getLongValue("id"),5)).getCode());
        assertEquals(2,mapper.count(5,null,null));
    }
    @Test void resourceDenialHappensBeforeFormalInsertAndClientsCannotClaimPublishedVersions() {
        doThrow(new ServiceException("denied",403)).when(access).requireSpace(9,"paiflow:workflow:create",3,true);
        assertThrows(ServiceException.class,()->service.create(9,body("name","Denied")));assertEquals(0,mapper.count(9,null,null));
        var created=create("No fake publication");assertThrows(ServiceException.class,()->service.createVersion(created.getLongValue("id"),5L,0L,body("publishResult","Success")));
        assertEquals(0,mapper.countVersions(created.getLongValue("id")));
    }
    @Test void nestedUnmanagedCredentialsIncludingStringWrappedConfigCannotEnterDraftOrVersion() {
        var rejected=body("name","Rejected","futureMetadata",body("provider",body("api_key","fixture-unmanaged-credential")));
        var error=assertThrows(ServiceException.class,()->service.create(5,rejected));
        assertTrue(error.getMessage().contains("request/futureMetadata/provider/api_key"));assertFalse(error.getMessage().contains("fixture-unmanaged-credential"));assertEquals(0,mapper.count(5,null,null));
        var created=create("Credential boundary");long id=created.getLongValue("id");
        String config="{\"nested\":{\"client-secret\":\"fixture-private-value\"}}";var request=body("advancedConfig",config);
        assertThrows(ServiceException.class,()->service.save(id,5L,0L,request));
        assertEquals(0L,mapper.find(id).getRevision());assertEquals("{}",mapper.find(id).getAdvancedConfigJson());assertEquals(config,request.getString("advancedConfig"));
        var existing=mapper.find(id);existing.setMetadataJson("{\"ext\":\"{\\\"password\\\":\\\"legacy-fixture\\\"}\"}");assertEquals(1,mapper.update(existing));
        assertThrows(ServiceException.class,()->service.createVersion(id,5L,1L,body("name","v1.0")));
        assertEquals(0,mapper.countVersions(id));assertEquals(1L,mapper.find(id).getRevision());
    }
}
