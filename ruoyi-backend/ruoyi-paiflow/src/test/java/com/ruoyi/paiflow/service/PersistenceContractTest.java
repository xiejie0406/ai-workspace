package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.io.InputStream;
import java.sql.Connection;
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

/** 执行实际Mapper及Spring事务；H2仅隔离验证，不冒称MySQL结构/真实库已验证。 */
public class PersistenceContractTest {
    private JdbcDataSource source;
    private SpaceMapper spaces;
    private IdentityMapper identities;
    public static Timestamp utcNow() { return Timestamp.from(Instant.now()); }
    // 正式访问时间使用UTC_TIMESTAMP(3)保留原默认时间的毫秒比较；H2显式适配该函数形状。
    public static Timestamp utcNow(int precision) { if(precision!=3)throw new IllegalArgumentException("fixture precision");return utcNow(); }
    @BeforeEach void setup() throws Exception {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE");
        try(Connection connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.PersistenceContractTest.utcNow'");
            sql.execute("CREATE TABLE paiflow_identity_map(source VARCHAR(64),old_uid VARCHAR(255),platform_user_id BIGINT,status VARCHAR(24),deleted INT,UNIQUE(source,old_uid))");
            sql.execute("CREATE TABLE paiflow_user_preference(user_id BIGINT PRIMARY KEY,user_agreement INT,agreed_at TIMESTAMP,revision BIGINT,deleted INT,create_by VARCHAR(64),update_by VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP)");
            sql.execute("CREATE TABLE paiflow_space(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(200),description VARCHAR(2000),avatar_url VARCHAR(2048),owner_user_id BIGINT,dept_id BIGINT,team_id BIGINT,kind VARCHAR(16),revision BIGINT,deleted INT,create_by VARCHAR(64),update_by VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(owner_user_id,kind,name))");
            sql.execute("CREATE TABLE paiflow_space_member(space_id BIGINT,user_id BIGINT,role INT,status VARCHAR(16),revision BIGINT,create_by VARCHAR(64),update_by VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,PRIMARY KEY(space_id,user_id))");
            sql.execute("CREATE TABLE paiflow_space_visit(space_id BIGINT,user_id BIGINT,last_visit_time TIMESTAMP,PRIMARY KEY(space_id,user_id))");
        }
        var configuration=new Configuration(new Environment("isolated-test",new SpringManagedTransactionFactory(),source));
        for(String file:new String[]{"mapper/paiflow/IdentityMapper.xml","mapper/paiflow/SpaceMapper.xml"}) {
            try(InputStream input=getClass().getClassLoader().getResourceAsStream(file)) {
                assertNotNull(input);new XMLMapperBuilder(input,configuration,file,configuration.getSqlFragments()).parse();
            }
        }
        var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        spaces=template.getMapper(SpaceMapper.class);identities=template.getMapper(IdentityMapper.class);
    }
    private SpaceRecord insert(String name,long owner) {
        var space=new SpaceRecord();space.setName(name);space.setOwnerUserId(owner);space.setKind("personal");space.setDeptId(8L);space.setCreateBy("fixture");space.setUpdateBy("fixture");space.setRevision(0L);
        assertEquals(1,spaces.insert(space));assertNotNull(space.getId());return space;
    }
    @Test void mapperBindsUidRatherThanInterpolatingAndRejectsUnclaimedRows() throws Exception {
        try(var c=source.getConnection();var sql=c.createStatement()) {
            sql.execute("INSERT INTO paiflow_identity_map VALUES ('paiflow','known',42,'mapped',0),('paiflow','orphan',NULL,'unclaimed',0)");
        }
        assertEquals(42L,identities.findPlatformUserId("paiflow","known"));
        assertNull(identities.findPlatformUserId("paiflow","orphan"));
        assertNull(identities.findPlatformUserId("paiflow","known' OR '1'='1"));
    }
    @Test void agreementIsPersistedAndRepeatedAcceptanceKeepsFirstTimestampAndRevision() {
        assertNull(identities.findAgreement(42));identities.agree(42,"fixture");var first=identities.findAgreement(42);
        assertEquals(1,first.getUserAgreement());assertNotNull(first.getAgreedAt());identities.agree(42,"fixture");var again=identities.findAgreement(42);
        assertEquals(first.getAgreedAt(),again.getAgreedAt());assertEquals(first.getRevision(),again.getRevision());
    }
    @Test void staleRevisionCannotOverwriteAndRemovedMembershipDisappearsFromListAndVisits() {
        var space=insert("Test space",42);spaces.insertOwner(space.getId(),42,"fixture");
        assertEquals(1,spaces.list(42,"personal",null,false).size());assertEquals(0,spaces.list(43,"personal",null,false).size());
        space.setName("Updated");assertEquals(1,spaces.update(space));space.setName("Stale");assertEquals(0,spaces.update(space));
        assertEquals("Updated",spaces.find(space.getId()).getName());
        spaces.visit(space.getId(),42);assertEquals(1,spaces.recent(42).size());
        spaces.setRole(space.getId(),42,3,"fixture");assertEquals(1,spaces.removeMember(space.getId(),42,"fixture"));
        assertTrue(spaces.list(42,"personal",null,false).isEmpty());assertTrue(spaces.recent(42).isEmpty());
    }
    @Test void ownerWriteFailureRollsBackSpaceCreationThroughActualSpringTransaction() throws Exception {
        try(var c=source.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_space_member ADD CONSTRAINT fixture_fail_owner CHECK(user_id <> 42)"); }
        var access=mock(ResourceAccessService.class);when(access.requirePermission("paiflow:space:create")).thenReturn(42L);
        var user=new SysUser();user.setUserId(42L);user.setUserName("fixture");
        var identity=mock(IdentityMappingService.class);when(identity.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));
        var target=new SpaceService(spaces,access,identity,mock(ISysUserService.class));
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(SpaceService)proxy.getProxy();assertThrows(RuntimeException.class,()->service.createPersonal("Rollback",null,null));
        assertEquals(0,spaces.countName(42,"Rollback",null));
    }
}
