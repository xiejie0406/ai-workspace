package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 随机H2＋实际平台User/Menu读取链和PaiFlow权限设施；无Mock菜单、Redis、网络或真实账号库。 */
public class CurrentAuthorityPersistenceTest {
    private JdbcDataSource db;private Connection schema;private SqlSessionTemplate sessions;
    private SpaceMapper spaces;private SysUserServiceImpl users;private ObservedPermissions permissions;
    private IdentityMappingService identities;private ResourceAccessService access;private TransactionTemplate tx;private long spaceId;
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    /** 只观察真实菜单读取完成时机，不改变super返回的权限集合。 */
    static class ObservedPermissions extends SysPermissionService {
        final AtomicReference<Runnable> afterRead=new AtomicReference<>();
        @Override public Set<String> getMenuPermission(SysUser user){var value=super.getMenuPermission(user);var hook=afterRead.getAndSet(null);if(hook!=null)hook.run();return value;}
    }
    @BeforeEach void setup() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");schema=db.getConnection();
        // 保留H2 CHECK的创建session，不放宽约束。
        try(var s=schema.createStatement()){
            s.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.CurrentAuthorityPersistenceTest.utcNow'");
            String ddl=Files.readString(Path.of("sql/001-paiflow-schema.sql"),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END").replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END");
            for(String statement:ddl.split(";"))if(!statement.isBlank())s.execute(statement);
            s.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,user_name VARCHAR(64),nick_name VARCHAR(64),email VARCHAR(64),avatar VARCHAR(64),phonenumber VARCHAR(32),password VARCHAR(64),sex VARCHAR(1),status VARCHAR(1),del_flag VARCHAR(1),login_ip VARCHAR(64),login_date TIMESTAMP,pwd_update_date TIMESTAMP,create_by VARCHAR(64),create_time TIMESTAMP,update_by VARCHAR(64),update_time TIMESTAMP,remark VARCHAR(255))");
            s.execute("CREATE TABLE sys_dept(dept_id BIGINT PRIMARY KEY,parent_id BIGINT,ancestors VARCHAR(64),dept_name VARCHAR(64),order_num INT,leader VARCHAR(64),status VARCHAR(1))");
            s.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_name VARCHAR(64),role_key VARCHAR(64),role_sort INT,data_scope VARCHAR(1),status VARCHAR(1))");
            s.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(128),status VARCHAR(1))");s.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");s.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
            s.execute("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(42,'fixture42','隔离用户42','0','0'),(43,'fixture43','隔离用户43','0','0')");
            s.execute("INSERT INTO sys_role VALUES(88,'fixture','fixture',1,'1','0')");s.execute("INSERT INTO sys_user_role VALUES(42,88)");s.execute("INSERT INTO sys_menu VALUES(1,'paiflow:space:query','0'),(2,'paiflow:space:edit','0')");s.execute("INSERT INTO sys_role_menu VALUES(88,1),(88,2)");
            s.execute("INSERT INTO paiflow_identity_map(source,old_uid,platform_user_id,status,create_by,update_by,create_time,update_time) VALUES('paiflow','legacy-fixture',42,'mapped','fixture','fixture',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        }
        var config=new Configuration(new Environment("current-authority-isolated",new SpringManagedTransactionFactory(),db));
        config.getTypeAliasRegistry().registerAlias("SysUser",SysUser.class);config.getTypeAliasRegistry().registerAlias("SysRole",SysRole.class);config.getTypeAliasRegistry().registerAlias("SysDept",SysDept.class);config.getTypeAliasRegistry().registerAlias("SysMenu",SysMenu.class);
        for(String name:List.of("SpaceMapper","IdentityMapper"))try(var xml=getClass().getResourceAsStream("/mapper/paiflow/"+name+".xml")){assertNotNull(xml);new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();}
        for(String name:List.of("SysUserMapper","SysMenuMapper"))try(var xml=Files.newInputStream(Path.of("../ruoyi-system/src/main/resources/mapper/system/"+name+".xml"))){new XMLMapperBuilder(xml,config,name,config.getSqlFragments()).parse();}
        sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));spaces=sessions.getMapper(SpaceMapper.class);
        users=new SysUserServiceImpl();ReflectionTestUtils.setField(users,"userMapper",sessions.getMapper(SysUserMapper.class));var menu=new SysMenuServiceImpl();ReflectionTestUtils.setField(menu,"menuMapper",sessions.getMapper(SysMenuMapper.class));permissions=new ObservedPermissions();ReflectionTestUtils.setField(permissions,"menuService",menu);
        identities=new IdentityMappingService(sessions.getMapper(IdentityMapper.class),users);identities.useCurrentSession(sessions);identities.useCurrentPermissions(permissions);
        access=new ResourceAccessService(identities,new PermissionService(),spaces);access.useCurrentSession(sessions);
        var space=new SpaceRecord();space.setName("current authority fixture");space.setKind("personal");space.setOwnerUserId(42L);space.setCreateBy("fixture");space.setUpdateBy("fixture");assertEquals(1,spaces.insert(space));spaceId=space.getId();assertEquals(1,spaces.insertOwner(spaceId,42,"fixture"));
        tx=new TransactionTemplate(new DataSourceTransactionManager(db));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);login();
    }
    @AfterEach void close() throws Exception {SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();if(schema!=null)schema.close();}
    private LoginUser login(){org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));var user=users.selectUserById(42L);var principal=new LoginUser(42L,8L,user,Set.of("paiflow:space:query","paiflow:space:edit"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));return principal;}
    private void committed(String statement){try(var c=db.getConnection();var s=c.createStatement()){assertEquals(1,s.executeUpdate(statement));}catch(SQLException ex){throw new IllegalStateException(ex);}}
    @Test void missingRequiredSessionOrMenuDoesNotSilentlyReuseOldLoginPermissions(){
        var missing=new IdentityMappingService(sessions.getMapper(IdentityMapper.class),users);assertEquals(503,assertThrows(ServiceException.class,missing::current).getCode());missing.useCurrentSession(sessions);assertEquals(503,assertThrows(ServiceException.class,missing::current).getCode());
        var missingAccess=new ResourceAccessService(identities,new PermissionService(),spaces);assertEquals(503,assertThrows(ServiceException.class,()->missingAccess.requireSpace(spaceId,"paiflow:space:query",3,false)).getCode());
    }
    @Test void accountDisabledByCommittedPlatformSqlIsSeenInsideSameReadCommittedTransaction(){
        tx.execute(status->{assertEquals("0",identities.current().getUser().getStatus());committed("UPDATE sys_user SET status='1' WHERE user_id=42");assertEquals(401,assertThrows(ServiceException.class,identities::current).getCode());return null;});
    }
    @Test void actualRoleMenuRevocationRefreshesAuthenticatedPrincipalAndOriginalPermissionService(){
        var principal=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();tx.execute(status->{assertEquals(42,access.requirePermission("paiflow:space:query"));committed("UPDATE sys_menu SET status='1' WHERE menu_id=1");assertEquals(403,assertThrows(ServiceException.class,()->access.requirePermission("paiflow:space:query")).getCode());assertSame(principal,identities.current());assertEquals(42L,principal.getUserId());assertFalse(principal.getPermissions().contains("paiflow:space:query"));assertTrue(new PermissionService().hasPermi("paiflow:space:edit"));return null;});
    }
    @Test void actualPlatformRoleDisableIsSeenWithoutReplacingPlatformAuthorization(){
        tx.execute(status->{assertEquals(42,access.requirePermission("paiflow:space:query"));committed("UPDATE sys_role SET status='1' WHERE role_id=88");assertEquals(403,assertThrows(ServiceException.class,()->access.requirePermission("paiflow:space:query")).getCode());assertTrue(identities.current().getPermissions().isEmpty());return null;});
    }
    @Test void referenceMappingAndTargetStatusAreFreshButNeverChangeAuthenticatedActor(){
        tx.execute(status->{assertEquals(42,identities.resolveReference("legacy-fixture"));committed("UPDATE paiflow_identity_map SET platform_user_id=43 WHERE old_uid='legacy-fixture'");assertEquals(43,identities.resolveReference("legacy-fixture"));committed("UPDATE sys_user SET del_flag='2' WHERE user_id=43");assertEquals(404,assertThrows(ServiceException.class,()->identities.resolveReference("legacy-fixture")).getCode());assertEquals(42L,identities.current().getUserId());return null;});
    }
    @Test void committedMemberRevocationCannotBeMaskedBySameTransactionSessionCache(){
        tx.execute(status->{assertEquals(spaceId,access.requireSpace(spaceId,"paiflow:space:query",3,false).getId());committed("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+spaceId+" AND user_id=42");assertEquals(404,assertThrows(ServiceException.class,()->access.requireSpace(spaceId,"paiflow:space:query",3,false)).getCode());return null;});
    }
    @Test void menuRevokedWhileWaitingForRealSpaceLockIsCheckedAfterLock() throws Exception {
        var read=new CountDownLatch(1);permissions.afterRead.set(read::countDown);var executor=Executors.newSingleThreadExecutor();
        try(var blocker=db.getConnection()){
            blocker.setAutoCommit(false);try(var s=blocker.createStatement()){assertEquals(1,s.executeUpdate("UPDATE paiflow_space SET revision=revision WHERE id="+spaceId));}
            Future<Integer> result=executor.submit(()->{login();try{return tx.execute(status->{try{access.requireSpace(spaceId,"paiflow:space:query",3,true);return 200;}catch(ServiceException rejected){return rejected.getCode();}});}finally{SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}});
            assertTrue(read.await(5,TimeUnit.SECONDS));committed("UPDATE sys_menu SET status='1' WHERE menu_id=1");blocker.commit();assertEquals(403,result.get(5,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
