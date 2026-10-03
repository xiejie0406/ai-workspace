import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 隔离检查源：仅随机H2/实际Mapper，复现SESSION缓存，不连接任何真实账号库或Redis。 */
public class SessionCacheProbe {
    public static Timestamp utcNow(){return Timestamp.from(Instant.now());}
    private static void field(Object target,String name,Object value)throws Exception {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    public static void main(String[] args)throws Exception {
        Path module=Path.of(args[0]);var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE");
        // H2 IN/CHECK创建session保留，与本次真实fixture修复一致；不删约束。
        try(var schema=db.getConnection();var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'SessionCacheProbe.utcNow'");
            for(String name:List.of("001-paiflow-schema.sql","009-paiflow-model.sql")){
                String ddl=Files.readString(module.resolve("sql").resolve(name),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("IF(deleted = 0, name, NULL)","CASE WHEN deleted=0 THEN name ELSE NULL END").replace("IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))","CASE WHEN team_id IS NULL THEN CONCAT('user:',owner_user_id) ELSE CONCAT('team:',team_id) END");
                for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            }
            sql.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(128),status VARCHAR(1))");sql.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");sql.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");sql.execute("CREATE TABLE sys_role(role_id BIGINT,status VARCHAR(1))");
            sql.execute("INSERT INTO sys_menu VALUES(1,'paiflow:run:create','0')");sql.execute("INSERT INTO sys_role_menu VALUES(88,1)");sql.execute("INSERT INTO sys_user_role VALUES(42,88)");sql.execute("INSERT INTO sys_role VALUES(88,'0')");
            var configuration=new Configuration(new Environment("isolated-cache-probe",new SpringManagedTransactionFactory(),db));configuration.getTypeAliasRegistry().registerAlias("SysMenu",SysMenu.class);
            for(Path xml:List.of(module.resolve("src/main/resources/mapper/paiflow/ModelMapper.xml"),module.resolve("src/main/resources/mapper/paiflow/SpaceMapper.xml"),module.getParent().resolve("ruoyi-system/src/main/resources/mapper/system/SysMenuMapper.xml"))){try(var stream=Files.newInputStream(xml)){new XMLMapperBuilder(stream,configuration,xml.toString(),configuration.getSqlFragments()).parse();}}
            var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));var models=sessions.getMapper(ModelMapper.class);var spaces=sessions.getMapper(SpaceMapper.class);var menus=sessions.getMapper(SysMenuMapper.class);
            var space=new SpaceRecord();space.setName("cache fixture");space.setKind("personal");space.setOwnerUserId(42L);space.setCreateBy("fixture");space.setUpdateBy("fixture");spaces.insert(space);spaces.insertOwner(space.getId(),42,"fixture");
            var model=new ModelRecord();model.setSpaceId(space.getId());model.setOwnerUserId(42L);model.setName("cache model");model.setType(1);model.setState("VALIDATED");model.setEnabled(1);model.setConfigurationJson("{}");model.setCandidateHash("a".repeat(64));model.setCreateBy("fixture");model.setUpdateBy("fixture");models.insert(model);
            var menuService=new SysMenuServiceImpl();field(menuService,"menuMapper",menus);var authority=new SysPermissionService();field(authority,"menuService",menuService);
            var roleless=new SysUser();roleless.setUserId(42L);var roleful=new SysUser();roleful.setUserId(42L);var role=new SysRole();role.setRoleId(88L);role.setStatus("0");roleful.setRoles(List.of(role));
            var tx=new TransactionTemplate(new DataSourceTransactionManager(db));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            JSONObject evidence=tx.execute(status->{
                var first=models.find(model.getId());var firstMember=spaces.member(space.getId(),42);var firstUser=authority.getMenuPermission(roleless);var firstRole=authority.getMenuPermission(roleful);
                try(var independent=db.getConnection();var update=independent.createStatement()){update.executeUpdate("UPDATE paiflow_model SET enabled=0 WHERE id="+model.getId());update.executeUpdate("UPDATE paiflow_space_member SET status='removed' WHERE space_id="+space.getId());update.executeUpdate("UPDATE sys_menu SET status='1' WHERE menu_id=1");}
                catch(SQLException ex){throw new IllegalStateException(ex);}
                var second=models.find(model.getId());var secondMember=spaces.member(space.getId(),42);var secondUser=authority.getMenuPermission(roleless);var secondRole=authority.getMenuPermission(roleful);
                sessions.clearCache();var third=models.find(model.getId());var thirdMember=spaces.member(space.getId(),42);var thirdUser=authority.getMenuPermission(roleless);var thirdRole=authority.getMenuPermission(roleful);
                int isolation;try{isolation=DataSourceUtils.getConnection(db).getTransactionIsolation();}catch(SQLException ex){throw new IllegalStateException(ex);}
                return JSONObject.of("localCacheScope",configuration.getLocalCacheScope().name(),"transactionIsolation",isolation,"modelFirst",first.getEnabled(),"modelSecond",second.getEnabled(),"modelThirdAfterClear",third.getEnabled(),"sameModelDto",first==second,"memberFirst",firstMember.getStatus(),"memberSecond",secondMember.getStatus(),"memberThirdAfterClear",thirdMember.getStatus(),"menuUserFirst",firstUser.contains("paiflow:run:create"),"menuUserSecond",secondUser.contains("paiflow:run:create"),"menuUserThirdAfterClear",thirdUser.contains("paiflow:run:create"),"menuRoleFirst",firstRole.contains("paiflow:run:create"),"menuRoleSecond",secondRole.contains("paiflow:run:create"),"menuRoleThirdAfterClear",thirdRole.contains("paiflow:run:create"));
            });
            boolean stale=evidence.getIntValue("modelSecond")==1 && "active".equals(evidence.getString("memberSecond")) && evidence.getBooleanValue("menuUserSecond") && evidence.getBooleanValue("menuRoleSecond");
            boolean clear=evidence.getIntValue("modelThirdAfterClear")==0 && "removed".equals(evidence.getString("memberThirdAfterClear")) && !evidence.getBooleanValue("menuUserThirdAfterClear") && !evidence.getBooleanValue("menuRoleThirdAfterClear");
            evidence.put("staleAfterCommittedRevocationReproduced",stale);evidence.put("clearCacheReadsCommittedFacts",clear);System.out.println(evidence.toJSONString());
            if(!stale || !clear || evidence.getIntValue("transactionIsolation")!=Connection.TRANSACTION_READ_COMMITTED)throw new AssertionError("cache probe条件不符");
            System.out.println("REPRODUCED: same READ_COMMITTED transaction returns stale SESSION Model/Space/actual platform user+role menu facts; scoped clearCache sees all committed changes.");
        }
    }
}
