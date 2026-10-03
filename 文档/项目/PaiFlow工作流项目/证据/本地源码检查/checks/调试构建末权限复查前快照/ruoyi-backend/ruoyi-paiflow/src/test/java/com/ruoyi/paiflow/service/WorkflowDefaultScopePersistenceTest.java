package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowScopeFacade;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import com.ruoyi.system.service.ISysUserService;
import java.io.InputStream;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真Mapper/事务隔离检查；默认NULL映射不意味着真实旧库资源已经迁入。 */
public class WorkflowDefaultScopePersistenceTest {
    private JdbcDataSource data;
    private java.sql.Connection schema;
    private SpaceMapper spaces;
    private WorkflowMapper mapper;
    private WorkflowService workflows;
    private SpaceService namedSpaces;
    private LegacyWorkflowScopeFacade legacy;
    private PermissionService permissions;
    private final ThreadLocal<Long> actor=ThreadLocal.withInitial(()->42L);
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    private SysUser user(long id){var u=new SysUser();u.setUserId(id);u.setUserName("fixture-"+id);return u;}
    private <T>T transactional(T target,Class<T> type){var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));return type.cast(proxy.getProxy());}
    private static String mysqlIfToCase(String text) {
        int start=text.indexOf("IF(");if(start<0)return text;
        int depth=1,end=start+3,partStart=end;boolean quoted=false;var parts=new java.util.ArrayList<String>();
        for(;end<text.length();end++) { char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;
            if(c=='(')depth++;else if(c==')'){if(--depth==0){parts.add(text.substring(partStart,end));break;}}else if(c==',' && depth==1){parts.add(text.substring(partStart,end));partStart=end+1;}
        }
        if(parts.size()!=3 || depth!=0)throw new IllegalArgumentException("fixture H2 IF adaptation");
        return mysqlIfToCase(text.substring(0,start)+"CASE WHEN "+mysqlIfToCase(parts.get(0))+" THEN "+mysqlIfToCase(parts.get(1))+" ELSE "+mysqlIfToCase(parts.get(2))+" END"+text.substring(end+1));
    }
    @BeforeEach void setup() throws Exception {
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE");schema=data.getConnection();
        try(var sql=schema.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.WorkflowDefaultScopePersistenceTest.utcNow'");
            for(String file:List.of("sql/001-paiflow-schema.sql","sql/002-paiflow-workflow.sql","sql/004-paiflow-membership.sql","sql/010-paiflow-default-personal.sql","sql/012-paiflow-workflow-model-snapshots.sql","sql/015-paiflow-execution-codec.sql","sql/018-paiflow-workflow-debug-snapshot.sql")) {
                String ddl=Files.readString(Path.of(file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
                // 仅作与已验证基础夹具相同的H2方言转换，保留真实生成列/唯一键/CHECK。
                ddl=ddl.replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE");
                ddl=ddl.replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:mysqlIfToCase(ddl).split(";"))if(!statement.isBlank())sql.execute(statement);
            }
        }
        var configuration=new Configuration(new Environment("workflow-default-isolated",new SpringManagedTransactionFactory(),data));
        for(String file:List.of("mapper/paiflow/SpaceMapper.xml","mapper/paiflow/WorkflowMapper.xml"))try(InputStream xml=getClass().getClassLoader().getResourceAsStream(file)) {assertNotNull(xml);new XMLMapperBuilder(xml,configuration,file,configuration.getSqlFragments()).parse();}
        var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));spaces=template.getMapper(SpaceMapper.class);mapper=template.getMapper(WorkflowMapper.class);
        var identities=mock(IdentityMappingService.class);when(identities.current()).thenAnswer(call->new LoginUser(actor.get(),8L,user(actor.get()),Set.of()));
        permissions=mock(PermissionService.class);when(permissions.hasPermi(anyString())).thenReturn(true);var access=new ResourceAccessService(identities,permissions,spaces);access.useCurrentSession(template);
        var defaults=transactional(new DefaultPersonalSpaceService(spaces,identities,access),DefaultPersonalSpaceService.class);
        workflows=transactional(new WorkflowService(mapper,access,identities),WorkflowService.class);
        namedSpaces=transactional(new SpaceService(spaces,access,identities,mock(ISysUserService.class)),SpaceService.class);
        legacy=transactional(new LegacyWorkflowScopeFacade(workflows,defaults,access),LegacyWorkflowScopeFacade.class);
    }
    @AfterEach void close() throws Exception {actor.remove();if(schema!=null){try(var sql=schema.createStatement()){sql.execute("SHUTDOWN");}finally{schema.close();}}}
    private static JSONObject body(Object... pairs){var result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private long count(String table)throws Exception{try(var c=data.getConnection();var sql=c.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}}
    private int error(Runnable action){return assertThrows(ServiceException.class,action::run).getCode();}
    @Test void absentDefaultReadIsEmptyAndNeverFallsBackToOwnedNamedWorkflow() throws Exception {
        long named=namedSpaces.createPersonal("具名",null,null);var existing=workflows.create(named,body("name","Named workflow"));long id=existing.getLongValue("id");
        assertEquals(0,legacy.list("",null,1,10,null,null,2,null).totalCount());
        assertEquals(404,error(()->legacy.detail(id,"",null)));assertEquals(404,error(()->legacy.versions(existing.getString("flowId"),null,1,10)));
        assertEquals(404,error(()->legacy.suggest(body("flowId",existing.getString("flowId")),"")));
        assertEquals(404,error(()->legacy.createVersion(body("flowId",existing.getString("flowId"),"name","v1.0"),"",0L)));
        assertEquals(404,error(()->legacy.restore(body("flowId",existing.getString("flowId"),"id",99),"",0L)));
        assertEquals(404,error(()->legacy.save(body("id",id,"description","wrong scope"),"",0L)));
        assertEquals(404,error(()->legacy.delete(id,"",null,0L)));assertEquals(404,error(()->legacy.clone(id,"")));
        assertEquals(1,count("paiflow_space"));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(0L,mapper.find(id).getRevision());
        assertEquals(400,error(()->legacy.list("",null,0,10,null,null,2,null)));assertEquals(503,error(()->legacy.list("",null,1,10,null,null,2,1)));
    }
    @Test void defaultCreateSaveVersionRestoreAndDeleteUseOnePhysicalScopeAndLegacyNull() throws Exception {
        var created=legacy.create(body("name","Default workflow","spaceId",null),"");long id=created.getLongValue("id"),space=mapper.find(id).getSpaceId();
        assertTrue(created.containsKey("spaceId"));assertNull(created.get("spaceId"));assertTrue(space>0);assertEquals(42L,spaces.find(space).getOwnerUserId());
        assertEquals(space,workflows.detail(id,space).getLongValue("spaceId"));assertNull(legacy.detail(id,null,null).get("spaceId"));
        var saved=legacy.save(body("id",id,"flowId",created.getString("flowId"),"description","original saved"),"",0L);assertEquals(1L,saved.getLongValue("revision"));assertNull(saved.get("spaceId"));
        var version=legacy.createVersion(body("flowId",created.getString("flowId"),"name","v1.0","spaceId",null),"",1L);assertEquals(2L,version.getLongValue("revision"));assertNull(version.get("spaceId"));
        var page=legacy.versions(created.getString("flowId"),"",1,10);assertEquals(1,page.total());assertNull(page.records().get(0).get("spaceId"));
        assertEquals(space,workflows.runtimeSnapshot(version.getLongValue("id")).spaceId());
        var restored=legacy.restore(body("flowId",created.getString("flowId"),"id",version.getLong("id")),"",2L);assertEquals(3L,restored.getLongValue("revision"));assertNull(restored.get("spaceId"));
        assertEquals(409,error(()->legacy.save(body("id",id,"description","stale"),"",2L)));assertEquals("original saved",mapper.find(id).getDescription());
        legacy.delete(id,"",null,3L);assertEquals(0,legacy.list("",null,1,10,null,null,2,null).totalCount());assertEquals(1,count("paiflow_space"));assertEquals(1,count("paiflow_space_member"));
    }
    @Test void firstInvalidRequestAndRealMapperFailureRollBackDefaultOwnerAndResourceTogether() throws Exception {
        assertEquals(400,error(()->legacy.create(body("name","Bad credential","nested",body("api_key","fixture-credential")),"")));
        assertEquals(0,count("paiflow_space"));assertEquals(0,count("paiflow_space_member"));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(0,count("paiflow_workflow"));
        try(var sql=schema.createStatement()){sql.execute("ALTER TABLE paiflow_workflow ADD CONSTRAINT fixture_workflow_default_failure CHECK(name<>'Mapper failure')");}
        RuntimeException failure=assertThrows(RuntimeException.class,()->legacy.create(body("name","Mapper failure"),null));
        assertTrue(failure.getMessage().toUpperCase(java.util.Locale.ROOT).contains("FIXTURE_WORKFLOW_DEFAULT_FAILURE"));
        assertEquals(0,count("paiflow_space"));assertEquals(0,count("paiflow_space_member"));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(0,count("paiflow_workflow"));
    }
    @Test void explicitNamedQueryAndBodyOverrideHeaderButMustAuthorizeTheSelectedDomain() {
        long first=namedSpaces.createPersonal("Header scope",null,null),second=namedSpaces.createPersonal("Explicit scope",null,null);
        var created=legacy.create(body("name","Explicit workflow","spaceId",second),String.valueOf(first));long id=created.getLongValue("id");
        assertEquals(second,mapper.find(id).getSpaceId());assertEquals(1,legacy.list(String.valueOf(first),second,1,10,null,null,2,null).totalCount());
        assertEquals(id,legacy.detail(id,String.valueOf(first),second).getLongValue("id"));assertEquals(404,error(()->legacy.detail(id,String.valueOf(first),null)));
        legacy.save(body("id",id,"spaceId",second,"description","selected body domain"),String.valueOf(first),0L);assertEquals(1L,mapper.find(id).getRevision());
        actor.set(43L);long foreign=namedSpaces.createPersonal("Other actor",null,null);actor.set(42L);
        assertEquals(404,error(()->legacy.create(body("name","Denied target","spaceId",foreign),String.valueOf(first))));
        assertEquals(404,error(()->legacy.list(String.valueOf(first),foreign,1,10,null,null,2,null)));
        assertEquals(404,error(()->legacy.save(body("id",id,"spaceId",foreign,"description","must not write"),String.valueOf(first),1L)));
        assertEquals("selected body domain",mapper.find(id).getDescription());legacy.delete(id,String.valueOf(first),second,1L);
    }
    @Test void defaultScopeDoesNotUseForeignOwnerOrDirtyMemberAndMalformedEchoCannotPromoteIt() throws Exception {
        var first=legacy.create(body("name","First owner"),"");long id=first.getLongValue("id"),firstSpace=mapper.find(id).getSpaceId();
        actor.set(43L);var second=legacy.create(body("name","Second owner"),null);assertNotEquals(firstSpace,mapper.find(second.getLongValue("id")).getSpaceId());spaces.insertMember(firstSpace,43,3,"dirty-fixture");
        assertEquals(404,error(()->legacy.detail(id,"",null)));assertEquals(404,error(()->legacy.detail(id,"",firstSpace)));
        assertEquals(1,legacy.list("",null,1,10,null,null,2,null).totalCount());actor.set(42L);
        for(Object invalid:List.of(List.of(),List.of(""),false,0,1.5,body("id",firstSpace)))assertEquals(400,error(()->legacy.create(body("name","bad","spaceId",invalid),"")));
        assertEquals(400,error(()->legacy.createVersion(body("flowId",first.getString("flowId"),"spaceId",firstSpace),"",0L)));
        assertEquals(2,count("paiflow_workflow"));assertEquals(2,count("paiflow_space"));
    }
    @Test void versionHeaderCannotBeReplacedByBodyNamedScopeAndUnboundCloneStaysUnavailable() throws Exception {
        long named=namedSpaces.createPersonal("Named",null,null);var namedFlow=legacy.create(body("name","Named flow"),String.valueOf(named));
        assertEquals(400,error(()->legacy.createVersion(body("flowId",namedFlow.getString("flowId"),"spaceId",named),"",0L)));
        var own=legacy.create(body("name","Default unbound"),"");long id=own.getLongValue("id");
        assertEquals(503,error(()->legacy.clone(id,"")));assertEquals(2,count("paiflow_workflow"));assertEquals(2,count("paiflow_space"));assertEquals(0L,mapper.find(id).getRevision());
        assertEquals(409,error(()->legacy.save(body("id",id,"description","missing revision"),"",null)));
        when(permissions.hasPermi("paiflow:workflow:create")).thenReturn(false);assertEquals(403,error(()->legacy.create(body("name","Menu denied"),"")));assertEquals(2,count("paiflow_workflow"));
    }
    @Test void concurrentFirstCreatesShareOneDefaultOwnerAndKeepTwoRealWorkflowRows() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=executor.submit(()->{start.await();return legacy.create(body("name","Concurrent first"),"");});
            var second=executor.submit(()->{start.await();return legacy.create(body("name","Concurrent second"),null);});start.countDown();
            long firstId=first.get(5,TimeUnit.SECONDS).getLongValue("id"),secondId=second.get(5,TimeUnit.SECONDS).getLongValue("id");
            assertNotEquals(firstId,secondId);assertEquals(mapper.find(firstId).getSpaceId(),mapper.find(secondId).getSpaceId());
            assertEquals(1,count("paiflow_space"));assertEquals(1,count("paiflow_space_member"));assertEquals(2,count("paiflow_workflow"));
        } finally {executor.shutdownNow();}
    }
}
