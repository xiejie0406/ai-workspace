package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.paiflow.mapper.PublicConfigMapper;
import com.ruoyi.system.service.ISysUserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;

/** 隔离实际Mapper/平台主体夹具；只有这张公开配置表，未连接业务MySQL。 */
public class PublicConfigTestFixture implements AutoCloseable {
    public final DriverManagerDataSource dataSource;
    // H2 CHECK 的 IN 常量集比较器引用建表会话；保留该会话，不删正式约束。
    private final Connection schemaConnection;
    public final PublicConfigMapper mapper;
    public final IdentityMappingService identities;
    public final ISysUserService users=mock(ISysUserService.class);
    public final SysPermissionService permissions=mock(SysPermissionService.class);
    public final JSONObject catalog;
    public final AtomicInteger accountReads=new AtomicInteger();
    public SysUser currentUser=user(42);
    public PublicConfigTestFixture() throws Exception {
        dataSource=new DriverManagerDataSource("jdbc:h2:mem:public-config-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE","sa","");
        String ddl=Files.readString(Path.of("sql/014-paiflow-public-config.sql")).split("INSERT IGNORE INTO",2)[0]
            .replace(" ON UPDATE CURRENT_TIMESTAMP","")
            .replaceAll("(?m)^\\s*KEY idx_paiflow_public_config_lookup[^\\n]+\\n","")
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci","");
        schemaConnection=dataSource.getConnection();
        try(var statement=schemaConnection.createStatement()) {
            statement.execute("SET IGNORECASE TRUE");statement.execute(ddl);
        }
        catalog=JSON.parseObject(Files.readString(Path.of("src/main/resources/paiflow/public-config-source.json")));
        try(var connection=dataSource.getConnection();var insert=connection.prepareStatement("INSERT INTO paiflow_public_config (id,category,code,name,value,is_valid,remarks,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for(var raw:catalog.getJSONArray("records")) {
                var row=(JSONObject)raw;insert.setLong(1,row.getLongValue("id"));
                int i=2;for(String key:new String[]{"category","code","name","value"})insert.setString(i++,row.getString(key));
                insert.setObject(6,row.getInteger("isValid"));insert.setString(7,row.getString("remarks"));
                insert.setObject(8,date(row.getString("createTime")));insert.setObject(9,date(row.getString("updateTime")));insert.executeUpdate();
            }
        }
        var factory=new SqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setMapperLocations(new ClassPathResource("mapper/paiflow/PublicConfigMapper.xml"));
        var sessions=new SqlSessionTemplate(factory.getObject());mapper=sessions.getMapper(PublicConfigMapper.class);
        when(users.selectUserById(42L)).thenAnswer(call->{accountReads.incrementAndGet();return currentUser;});
        when(permissions.getMenuPermission(any(SysUser.class))).thenReturn(Set.of());
        identities=new IdentityMappingService(mock(IdentityMapper.class),users);
        identities.useCurrentSession(sessions);identities.useCurrentPermissions(permissions);login();
    }
    private static LocalDateTime date(String raw){return raw==null?null:LocalDateTime.parse(raw.replace(' ','T'));}
    public static SysUser user(long id){var user=new SysUser();user.setUserId(id);user.setUserName("config-fixture-"+id);user.setStatus("0");user.setDelFlag("0");return user;}
    public void login(){var login=new LoginUser(42L,8L,user(42),Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));}
    public ConfigInfoService service(String profile){return new ConfigInfoService(mapper,identities,new MockEnvironment().withProperty("spring.profiles.active",profile));}
    public void update(String sql,Object... args) throws Exception {
        try(var connection=dataSource.getConnection();var statement=connection.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);statement.executeUpdate();
        }
    }
    public int count() throws Exception {try(var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM paiflow_public_config")){r.next();return r.getInt(1);}}
    @Override public void close() throws Exception {
        try {
            SecurityContextHolder.clearContext();try(Connection c=dataSource.getConnection();var statement=c.createStatement()){statement.execute("SHUTDOWN");}
        } finally { schemaConnection.close(); }
    }
}
