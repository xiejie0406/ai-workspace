package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.adapter.legacy.LegacySpaceController;
import com.ruoyi.paiflow.domain.MembershipInvitation;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.paiflow.mapper.NotificationMapper;
import com.ruoyi.system.service.ISysUserService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 实际SpaceMapper/Spring事务/原HTTP形状隔离检查；MySQL8的010未执行，H2方言转换明确列出。 */
public class DefaultPersonalSpacePersistenceTest {
    private JdbcDataSource data;
    private java.sql.Connection schema;
    private SpaceMapper mapper;
    private ResourceAccessService access;
    private DefaultPersonalSpaceService defaults;
    private SpaceService spaces;
    private MembershipService members;
    private PermissionService permissions;
    private final ThreadLocal<Long> actor=ThreadLocal.withInitial(()->42L);
    private static final ThreadLocal<Instant> clock=new ThreadLocal<>();
    public static Timestamp utcNow(){return Timestamp.from(clock.get()==null?Instant.now():clock.get());}
    public static Timestamp utcNow(int precision){if(precision!=3)throw new IllegalArgumentException("fixture precision");return utcNow();}
    private SysUser user(long id){var u=new SysUser();u.setUserId(id);u.setUserName("fixture-"+id);u.setNickName("用户"+id);u.setStatus("0");u.setDelFlag("0");return u;}
    private <T>T transactional(T target,Class<T> type){var p=new ProxyFactory(target);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));return type.cast(p.getProxy());}
    private static String mysqlIfToCase(String text){
        int start=text.indexOf("IF(");if(start<0)return text;int depth=1,end=start+3,partStart=end;boolean quoted=false;var parts=new java.util.ArrayList<String>();
        for(;end<text.length();end++){char c=text.charAt(end);if(c=='\''){if(quoted && end+1<text.length() && text.charAt(end+1)=='\''){end++;continue;}quoted=!quoted;continue;}if(quoted)continue;if(c=='(')depth++;else if(c==')'){if(--depth==0){parts.add(text.substring(partStart,end));break;}}else if(c==',' && depth==1){parts.add(text.substring(partStart,end));partStart=end+1;}}
        if(parts.size()!=3 || depth!=0)throw new IllegalArgumentException("H2 IF adaptation");
        return mysqlIfToCase(text.substring(0,start)+"CASE WHEN "+mysqlIfToCase(parts.get(0))+" THEN "+mysqlIfToCase(parts.get(1))+" ELSE "+mysqlIfToCase(parts.get(2))+" END"+text.substring(end+1));
    }
    @BeforeEach void setup() throws Exception {
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");schema=data.getConnection();
        try(var sql=schema.createStatement()){
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.DefaultPersonalSpacePersistenceTest.utcNow'");
            for(String file:List.of("sql/001-paiflow-schema.sql","sql/004-paiflow-membership.sql","sql/010-paiflow-default-personal.sql","sql/020-paiflow-notifications.sql")){
                String ddl=Files.readString(Path.of(file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
                // 只转换H2不支持的MySQL ALTER/IF语法，保留生成列、唯一键及所有CHECK。
                ddl=ddl.replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE");
                ddl=ddl.replace("DROP CHECK ","DROP CONSTRAINT ").replace("ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id)","ADD CONSTRAINT uq_paiflow_default_owner UNIQUE (default_owner_user_id)");
                for(String statement:mysqlIfToCase(ddl).split(";"))if(!statement.isBlank())sql.execute(statement);
            }
            sql.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,nick_name VARCHAR(200))");sql.execute("INSERT INTO sys_user VALUES(42,'用户42'),(43,'用户43'),(44,'用户44')");
        }
        var c=new Configuration(new Environment("default-personal-isolated",new SpringManagedTransactionFactory(),data));
        for(String resource:List.of("mapper/paiflow/SpaceMapper.xml","mapper/paiflow/NotificationMapper.xml"))try(InputStream xml=getClass().getClassLoader().getResourceAsStream(resource)){assertNotNull(xml);new XMLMapperBuilder(xml,c,resource,c.getSqlFragments()).parse();}
        var currentTemplate=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));mapper=currentTemplate.getMapper(SpaceMapper.class);
        var identities=mock(IdentityMappingService.class);when(identities.current()).thenAnswer(a->new LoginUser(actor.get(),8L,user(actor.get()),Set.of()));when(identities.resolveReference(anyString())).thenAnswer(a->Long.parseLong(a.<String>getArgument(0).substring(6)));
        permissions=mock(PermissionService.class);when(permissions.hasPermi(anyString())).thenReturn(true);access=new ResourceAccessService(identities,permissions,mapper);access.useCurrentSession(currentTemplate);
        defaults=transactional(new DefaultPersonalSpaceService(mapper,identities,access),DefaultPersonalSpaceService.class);
        var users=mock(ISysUserService.class);when(users.selectUserById(anyLong())).thenAnswer(a->user(a.getArgument(0)));
        var s=new SpaceService(mapper,access,identities,users);s.useDefaultSpaces(defaults);s.useCapacityPolicy(new SpaceService.CapacityPolicy(){public SpaceService.TeamEntitlement teamCreation(long id){return new SpaceService.TeamEntitlement(2,LocalDateTime.now(java.time.Clock.systemUTC()).plusDays(30),20);}public int maximumMembers(long owner,Long team){return 20;}});spaces=transactional(s,SpaceService.class);
        var m=new MembershipService(access,identities,mapper);m.usePlatformServices(spaces,users);m.useNotifications(new NotificationService(currentTemplate.getMapper(NotificationMapper.class),access,identities));members=transactional(m,MembershipService.class);
    }
    @AfterEach void close() throws Exception {actor.remove();clock.remove();if(schema!=null){try(var sql=schema.createStatement()){sql.execute("SHUTDOWN");}finally{schema.close();}}}
    private long count(String table)throws Exception{try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    private DefaultPersonalSpaceService.Selection selection(){return defaults.defaultSelection("paiflow:space:query");}
    private long materialize(){return defaults.materialize(selection(),"paiflow:space:query");}
    private int error(Runnable action){return assertThrows(ServiceException.class,action::run).getCode();}
    private void moment(int milliseconds){clock.set(Instant.parse("2026-10-03T00:00:00Z").plusMillis(milliseconds));}
    @Test void missingDefaultReadsAreZeroWriteAndNeverChooseAnExistingNamedSpace()throws Exception{
        long named=spaces.createPersonal("原具名空间",null,null);assertNull(defaults.existing(defaults.select("",null,"paiflow:space:query"),"paiflow:space:query"));assertNull(defaults.existing(defaults.select(null,"","paiflow:space:query"),"paiflow:space:query"));
        assertNull(spaces.last());assertNull(mapper.defaultPersonal(42));assertEquals(1,count("paiflow_space"));assertEquals(0,count("paiflow_membership_scope_lock"));assertEquals(named,spaces.listPersonal(null,false).get(0).id());
    }
    @Test void materializationIsIdempotentUniqueAndAbsentFromNamedLists()throws Exception{
        long id=materialize();assertEquals(id,materialize());assertEquals(SpaceRecord.DEFAULT_PERSONAL,mapper.find(id).getKind());assertEquals(1,count("paiflow_space"));assertEquals(1,count("paiflow_space_member"));assertTrue(spaces.listPersonal(null,false).isEmpty());assertTrue(spaces.recent().isEmpty());assertNull(DefaultPersonalSpaceService.legacySpaceId(mapper.find(id)));
        var duplicate=new SpaceRecord();duplicate.setName("另一个内部名称");duplicate.setKind(SpaceRecord.DEFAULT_PERSONAL);duplicate.setOwnerUserId(42L);duplicate.setCreateBy("fixture");duplicate.setUpdateBy("fixture");var failure=assertThrows(RuntimeException.class,()->mapper.insert(duplicate));assertTrue(failure.getMessage().toUpperCase(java.util.Locale.ROOT).contains("UQ_PAIFLOW_DEFAULT_OWNER"));
    }
    @Test void differentOwnersHaveDistinctPrivateMappingsAndForeignMembershipCannotGrantAccess()throws Exception{
        long first=materialize();actor.set(43L);long second=materialize();assertNotEquals(first,second);mapper.insertMember(first,43,3,"dirty-fixture");assertEquals(404,error(()->access.requireSpace(first,"paiflow:space:query",3,false)));assertEquals(second,defaults.existing(selection(),"paiflow:space:query"));assertEquals(2,count("paiflow_space"));
    }
    @Test void selectionRejectsMalformedAndForeignEchoWithoutAnyWrites()throws Exception{
        for(String value:List.of("0","-1"," ","1,2","1.0","9223372036854775808"))assertEquals(400,error(()->defaults.select(value,null,"paiflow:space:query")));
        assertEquals(400,error(()->defaults.select("",77,"paiflow:space:query")));assertEquals(400,error(()->defaults.select("76",77,"paiflow:space:query")));
        assertEquals(76,defaults.select("76","76","paiflow:space:query").spaceId());assertEquals(403,error(()->defaults.existing(new DefaultPersonalSpaceService.Selection(43,null,true),"paiflow:space:query")));assertEquals(0,count("paiflow_space"));assertEquals(0,count("paiflow_membership_scope_lock"));
    }
    @Test void missingPlatformPermissionCannotMaterializeAnOwnerDomain()throws Exception{
        var selected=selection();when(permissions.hasPermi(anyString())).thenReturn(false);assertEquals(403,error(()->defaults.materialize(selected,"paiflow:space:query")));assertEquals(0,count("paiflow_space"));assertEquals(0,count("paiflow_membership_scope_lock"));
    }
    @Test void failedOwnerInsertRollsBackMappingAndOwnerScopeLock()throws Exception{
        try(var s=schema.createStatement()){s.execute("ALTER TABLE paiflow_space_member ADD CONSTRAINT fixture_default_owner CHECK(user_id<>42)");}
        var failure=assertThrows(RuntimeException.class,this::materialize);assertTrue(failure.getMessage().toUpperCase(java.util.Locale.ROOT).contains("FIXTURE_DEFAULT_OWNER"));assertEquals(0,count("paiflow_space"));assertEquals(0,count("paiflow_space_member"));assertEquals(0,count("paiflow_membership_scope_lock"));
    }
    @Test void ordinaryResourceTransactionRollsBackItsFirstDefaultMapping()throws Exception{
        var tx=new TransactionTemplate(new DataSourceTransactionManager(data));assertThrows(IllegalStateException.class,()->tx.execute(status->{materialize();throw new IllegalStateException("fixture-resource-failure");}));assertEquals(0,count("paiflow_space"));assertEquals(0,count("paiflow_space_member"));
    }
    @Test void explicitlyIndependentModelPreparationKeepsOnlyItsScopeOnLaterFailure()throws Exception{
        var selected=selection();var tx=new TransactionTemplate(new DataSourceTransactionManager(data));assertThrows(IllegalStateException.class,()->tx.execute(status->{defaults.materializeIndependent(selected,"paiflow:space:query");throw new IllegalStateException("fixture-provider-rejected");}));assertEquals(1,count("paiflow_space"));assertEquals(1,count("paiflow_space_member"));assertEquals(0,count("paiflow_space_visit"));
    }
    @Test void concurrentFirstMaterializationProducesOneMappingAndOneOwner()throws Exception{
        var selected=selection();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{Callable<Long> task=()->{start.await();return defaults.materialize(selected,"paiflow:space:query");};var first=pool.submit(task);var second=pool.submit(task);start.countDown();assertEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));assertEquals(1,count("paiflow_space"));assertEquals(1,count("paiflow_space_member"));}finally{pool.shutdownNow();}
    }
    @Test void permissionRevokedWhileWaitingForOwnerLockRejectsBeforeMappingInsert()throws Exception{
        mapper.lockOwnerScope(42);var selected=selection();var checked=new CountDownLatch(1);when(permissions.hasPermi(anyString())).thenAnswer(a->{checked.countDown();return true;});var pool=Executors.newSingleThreadExecutor();
        try{var tx=new TransactionTemplate(new DataSourceTransactionManager(data));var holder=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();tx.execute(status->{mapper.lockOwnerScope(42);holder.set(pool.submit(()->error(()->defaults.materialize(selected,"paiflow:space:query"))));try{assertTrue(checked.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->holder.get().get(100,TimeUnit.MILLISECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}when(permissions.hasPermi(anyString())).thenReturn(false);return null;});assertEquals(403,holder.get().get(5,TimeUnit.SECONDS));assertEquals(0,count("paiflow_space"));}finally{pool.shutdownNow();}
    }
    @Test void explicitDefaultVisitHasOriginalNullReceiptAndPersistsMillisecondHistory()throws Exception{
        long named=spaces.createPersonal("具名",null,null);moment(1);spaces.visit(named);assertEquals(named,spaces.last().id());moment(2);assertNull(spaces.visit(null));assertNull(spaces.last());assertEquals(1,spaces.recent().size());assertEquals(1,spaces.listPersonal(null,false).size());assertEquals(2,mapper.defaultPersonalVisit(42).getLastVisitTime().getNano()/1_000_000);moment(3);spaces.visit(named);assertEquals(named,spaces.last().id());
    }
    @Test void equalMillisecondVisitKeepsOriginalStrictIsAfterMeaning()throws Exception{
        long named=spaces.createPersonal("具名",null,null);moment(1);spaces.visit(named);spaces.visit(null);assertEquals(named,spaces.last().id());
    }
    @Test void defaultVisitClearsEnterpriseSelectionButStartupClearDoesNotStampDefault()throws Exception{
        long team=spaces.createTeam("隔离团队",null);spaces.visitTeam(team);assertEquals(1,count("paiflow_team_visit"));assertNull(spaces.visit(null));assertEquals(0,count("paiflow_team_visit"));var before=mapper.defaultPersonalVisit(42).getLastVisitTime();spaces.visitTeam(null);assertEquals(before,mapper.defaultPersonalVisit(42).getLastVisitTime());
    }
    @Test void defaultMappingCannotBeRenamedTransferredOrSoftDeleted()throws Exception{
        long id=materialize();assertEquals(400,error(()->spaces.detail(id)));assertEquals(400,error(()->spaces.checkName("new",id)));assertEquals(400,error(()->spaces.updatePersonal(id,"new",null,null,0L)));var row=mapper.find(id);row.setName("new");row.setUpdateBy("fixture");assertEquals(0,mapper.update(row));assertEquals(0,mapper.transfer(id,43,0,"fixture"));try(var sql=schema.createStatement()){assertThrows(java.sql.SQLException.class,()->sql.execute("UPDATE paiflow_space SET deleted=1 WHERE id="+id));}assertEquals(42L,mapper.find(id).getOwnerUserId());
    }
    @Test void defaultDomainRejectsAllCollaborationReadsAndMutations()throws Exception{
        long id=materialize();var page=new MembershipService.PageQuery(1,10,null,0,0);
        for(Runnable operation:List.<Runnable>of(()->members.memberPage(id,page),()->members.memberList(id),()->members.members(id),()->members.userLimit(1,id),()->members.invitations(1,id,page),()->members.searchUsers(1,id,"fixture-43",false),()->members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:43",3))),()->members.updateRole(id,"ruoyi:43",3),()->members.remove(id,"ruoyi:43"),()->members.leave(id),()->members.transfer(id,"ruoyi:43")))assertEquals(400,error(operation));assertEquals(1,count("paiflow_space_member"));assertEquals(0,count("paiflow_membership_invite"));
    }
    private long dirtyInvitation(long id){var i=new MembershipInvitation();i.setType(1);i.setSpaceId(id);i.setOwnerUserId(42L);i.setInviteeUserId(44L);i.setInviterUserId(42L);i.setRole(3);i.setNotificationStatus("not_connected");i.setExpireTime(LocalDateTime.now(java.time.Clock.systemUTC()).plusDays(1));mapper.insertInvitation(i);return i.getId();}
    @Test void hiddenOwnerAndDirtyDefaultInvitationsDoNotConsumeNamedPersonalCapacity()throws Exception{
        long id=materialize();assertEquals(0,mapper.countCapacityMembers(42,null));long invite=dirtyInvitation(id);assertEquals(0,mapper.countCapacityInvites(42,null));actor.set(44L);assertEquals(400,error(()->members.respondInvitation(invite,true,0L)));assertEquals(1,mapper.invitation(invite).getStatus());assertNull(mapper.member(id,44));actor.set(42L);long named=spaces.createPersonal("具名",null,null);mapper.insertMember(named,43,3,"fixture");assertEquals(2,mapper.countCapacityMembers(42,null));
    }
    @Test void realLegacyEmptyVisitAndLastReadPreserveNullWithoutImplicitReadCreation()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new LegacySpaceController(spaces)).build();mvc.perform(get("/paiflow/legacy/space/get-last-visit-space")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").doesNotExist());assertEquals(0,count("paiflow_space"));
        mvc.perform(get("/paiflow/legacy/space/visit-space").param("spaceId","")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").doesNotExist());assertEquals(1,count("paiflow_space"));assertEquals(1,count("paiflow_space_visit"));
    }
}
