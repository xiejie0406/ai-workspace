package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.SpaceMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 执行实际SpaceMapper和Spring事务；套餐fixture、平台账号fixture、H2不是付费/真实MySQL/页面证据。 */
public class MembershipPersistenceTest {
    private JdbcDataSource data;
    private java.sql.Connection schemaConnection;
    private SpaceMapper mapper;
    private SpaceService spaces;
    private MembershipService members;
    private IdentityMappingService identities;
    private ISysUserService users;
    private final ThreadLocal<Long> actor=ThreadLocal.withInitial(()->42L);
    private volatile int capacity=20;
    public static Timestamp utcNow() { return Timestamp.from(Instant.now()); }
    private SysUser user(long id) {
        var value=new SysUser();value.setUserId(id);value.setUserName("fixture-"+id);value.setNickName("成员"+id);value.setStatus("0");value.setDelFlag("0");return value;
    }
    private <T> T transaction(T target,Class<T> type) {
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
    private static String mysqlIfToCase(String text) {
        int start=text.indexOf("IF(");
        if(start<0)return text;
        int depth=1,end=start+3;boolean quoted=false;var parts=new java.util.ArrayList<String>();int partStart=end;
        for(;end<text.length();end++) {
            char c=text.charAt(end);
            if(c=='\'') { if(quoted && end+1<text.length() && text.charAt(end+1)=='\'') {end++;continue;}quoted=!quoted;continue; }
            if(quoted)continue;
            if(c=='(')depth++;
            else if(c==')') { if(--depth==0) {parts.add(text.substring(partStart,end));break;} }
            else if(c==',' && depth==1) {parts.add(text.substring(partStart,end));partStart=end+1;}
        }
        if(parts.size()!=3 || depth!=0)throw new IllegalArgumentException("隔离DDL的IF表达式不完整");
        String expression="CASE WHEN "+mysqlIfToCase(parts.get(0))+" THEN "+mysqlIfToCase(parts.get(1))+" ELSE "+mysqlIfToCase(parts.get(2))+" END";
        return mysqlIfToCase(text.substring(0,start)+expression+text.substring(end+1));
    }
    @BeforeEach void setup() throws Exception {
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=ROLE;LOCK_TIMEOUT=5000");
        // H2 2.4.240的CHECK/IN常量集合使用建表Session比较；保留此连接至用例结束，不删除任何CHECK。
        schemaConnection=data.getConnection();
        try(var sql=schemaConnection.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.MembershipPersistenceTest.utcNow'");
            for(String file:List.of("sql/001-paiflow-schema.sql","sql/004-paiflow-membership.sql")) {
                String ddl=Files.readString(Path.of(file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","").replace(" STORED","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
                // H2增量ALTER语法不同，保留同一新增列/唯一约束含义，不改正式DDL。
                ddl=ddl.replace("ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,\n  ADD UNIQUE KEY uq_paiflow_space_member_id (id)","ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE");
                ddl=mysqlIfToCase(ddl);
                for(String statement:ddl.split(";"))if(!statement.isBlank())sql.execute(statement);
            }
            sql.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,nick_name VARCHAR(200))");
            sql.execute("INSERT INTO sys_user VALUES(42,'成员42'),(43,'成员43'),(44,'成员44'),(45,'成员45'),(46,'成员46')");
        }
        var config=new Configuration(new Environment("membership-isolated",new SpringManagedTransactionFactory(),data));
        try(InputStream xml=getClass().getClassLoader().getResourceAsStream("mapper/paiflow/SpaceMapper.xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,config,"mapper/paiflow/SpaceMapper.xml",config.getSqlFragments()).parse();
        }
        var currentTemplate=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=currentTemplate.getMapper(SpaceMapper.class);
        users=mock(ISysUserService.class);when(users.selectUserById(anyLong())).thenAnswer(a->user(a.getArgument(0)));
        when(users.selectUserList(any())).thenReturn(List.of(user(42),user(43),user(44),user(45)));
        identities=mock(IdentityMappingService.class);
        when(identities.current()).thenAnswer(a->new LoginUser(actor.get(),8L,user(actor.get()),Set.of()));
        when(identities.resolveReference(anyString())).thenAnswer(a->Long.parseLong(a.<String>getArgument(0).substring(6)));
        var permissions=mock(PermissionService.class);when(permissions.hasPermi(anyString())).thenReturn(true);
        var access=new ResourceAccessService(identities,permissions,mapper);access.useCurrentSession(currentTemplate);
        var target=new SpaceService(mapper,access,identities,users);
        target.useCapacityPolicy(new SpaceService.CapacityPolicy() {
            public SpaceService.TeamEntitlement teamCreation(long id) { return new SpaceService.TeamEntitlement(2,LocalDateTime.now(java.time.Clock.systemUTC()).plusDays(30),capacity); }
            public int maximumMembers(long owner,Long team) { return capacity; }
        });
        spaces=transaction(target,SpaceService.class);
        var membership=new MembershipService(access,identities,mapper);membership.usePlatformServices(spaces,users);members=transaction(membership,MembershipService.class);
    }
    @AfterEach void closeSchemaSession() throws Exception {
        actor.remove();
        if(schemaConnection!=null) {
            try(var sql=schemaConnection.createStatement()) {sql.execute("SHUTDOWN");}
            finally {schemaConnection.close();}
        }
    }
    private long team() { actor.set(42L);return spaces.createTeam("隔离测试团队",null); }
    private long teamSpace(long team) {
        var s=new SpaceRecord();s.setName("团队空间");s.setTeamId(team);s.setKind("team");s.setOwnerUserId(42L);s.setCreateBy("fixture");s.setUpdateBy("fixture");mapper.insert(s);mapper.insertOwner(s.getId(),42,"fixture");return s.getId();
    }
    private long personal() { actor.set(42L);return spaces.createPersonal("个人空间",null,null); }
    private MembershipService.PageQuery query(Integer role,Integer status) { return new MembershipService.PageQuery(1,10,null,role,status); }
    private int error(Runnable operation) { return assertThrows(ServiceException.class,operation::run).getCode(); }
    private void fixtureFailure(String constraint,Runnable operation) {
        var failure=assertThrows(RuntimeException.class,operation::run);
        assertTrue(failure.getMessage().toUpperCase(java.util.Locale.ROOT).contains(constraint),"回滚必须来自指定故障约束，不能由其他SQL错误冒充");
    }
    @Test void teamAndOwnerAreSeparateFromSpaceAndMissingPolicyNeverWritesPaidFacts() {
        long id=team();assertEquals(1,mapper.teamMembers(id).size());assertTrue(mapper.teamSpaces(id).isEmpty());
        assertEquals(1,spaces.joinedTeams().size());assertEquals("ruoyi:42",spaces.teamDetail(id).uid());
        assertEquals(409,error(()->spaces.createTeam("另一个团队",null)));
        actor.set(43L);
        var noPolicy=new SpaceService(mapper,mock(ResourceAccessService.class),identities,users);
        assertEquals(503,error(()->noPolicy.createTeam("无套餐",null)));
        assertEquals(0,mapper.countTeamName("无套餐",null));
        assertEquals(404,error(()->spaces.teamDetail(id)));
    }
    @Test void realTeamOwnerInsertFailureRollsBackTeamAndUniqueName() throws Exception {
        try(var c=data.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_team_member ADD CONSTRAINT fixture_owner_failure CHECK(user_id<>42)"); }
        fixtureFailure("FIXTURE_OWNER_FAILURE",this::team);assertEquals(0,mapper.countTeamName("隔离测试团队",null));assertEquals(0,mapper.countOwnedTeams(42));
    }
    @Test void originalAdminCanChangeAndRemovePeerButNeverOwnerAndMembersCannotManage() {
        long id=personal();mapper.insertMember(id,43,2,"fixture");mapper.insertMember(id,44,2,"fixture");mapper.insertMember(id,45,3,"fixture");
        actor.set(43L);members.updateRole(id,"ruoyi:44",3);members.updateRole(id,"ruoyi:44",2);members.remove(id,"ruoyi:44");
        assertEquals("removed",mapper.member(id,44).getStatus());
        assertEquals(409,error(()->members.updateRole(id,"ruoyi:42",2)));assertEquals(409,error(()->members.remove(id,"ruoyi:42")));
        actor.set(45L);assertEquals(403,error(()->members.updateRole(id,"ruoyi:43",3)));
        var page=members.memberPage(id,new MembershipService.PageQuery(1,1,"成员",0,null));assertEquals(3,page.total());assertEquals(3,page.pages());assertEquals(1,page.records().size());assertNotNull(page.records().get(0).getLong("id"));
        assertEquals(0,members.memberPage(id,new MembershipService.PageQuery(1,10,"' OR 1=1 --",0,null)).total());
        assertTrue(members.memberList(id).stream().noneMatch(v->v.getIntValue("role")==1));
    }
    @Test void invitationsRejectDuplicateBatchAndExpiredPendingCanBeReplaced() throws Exception {
        long id=personal();assertEquals(409,error(()->members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3),new MembershipService.InviteAdd("ruoyi:44",2)))));
        assertEquals(0,mapper.countInvitations(1,id,null,0));
        long invitation=members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3))).get(0);
        assertEquals(409,error(()->members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3)))));
        assertEquals("not_connected",mapper.invitation(invitation).getNotificationStatus());
        assertTrue(mapper.invitation(invitation).getExpireTime().isAfter(LocalDateTime.now(java.time.Clock.systemUTC()).plusDays(6)));
        try(var c=data.getConnection();var sql=c.createStatement()) { sql.execute("UPDATE paiflow_membership_invite SET expire_time=DATEADD('DAY',-1,UTC_TIMESTAMP()) WHERE id="+invitation); }
        actor.set(44L);assertEquals(409,error(()->members.respondInvitation(invitation,true,null)));assertNull(mapper.member(id,44));
        actor.set(42L);assertEquals(5,members.invitations(1,id,query(0,5)).records().get(0).getIntValue("status"));
        assertEquals(1,members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3))).size());assertEquals(5,mapper.invitation(invitation).getStatus());
    }
    @Test void acceptingTeamSpaceInviteAddsActualBothMembershipsAndCasRejectsSecondReceipt() {
        long team=team(),id=teamSpace(team);
        long invitation=members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",2))).get(0);
        actor.set(45L);assertEquals(404,error(()->members.respondInvitation(invitation,true,0L)));assertNull(mapper.member(id,45));
        actor.set(44L);assertEquals(409,error(()->members.respondInvitation(invitation,true,1L)));
        members.respondInvitation(invitation,true,0L);assertEquals(2,mapper.member(id,44).getRole());assertEquals(2,mapper.teamMember(team,44).getRole());
        assertEquals(3,mapper.invitation(invitation).getStatus());assertEquals(1L,mapper.invitation(invitation).getRevision());
        assertEquals(409,error(()->members.respondInvitation(invitation,false,0L)));
    }
    @Test void failedMemberInsertRollsBackInviteStateAndBatchInsert() throws Exception {
        long id=personal();long invitation=members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3))).get(0);
        try(var c=data.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_space_member ADD CONSTRAINT fixture_member_failure CHECK(user_id<>44)"); }
        actor.set(44L);fixtureFailure("FIXTURE_MEMBER_FAILURE",()->members.respondInvitation(invitation,true,0L));assertEquals(1,mapper.invitation(invitation).getStatus());assertEquals(0L,mapper.invitation(invitation).getRevision());assertNull(mapper.member(id,44));
        actor.set(42L);
        try(var c=data.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_membership_invite ADD CONSTRAINT fixture_batch_failure CHECK(invitee_user_id<>46)"); }
        fixtureFailure("FIXTURE_BATCH_FAILURE",()->members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:45",3),new MembershipService.InviteAdd("ruoyi:46",3))));
        assertEquals(0,mapper.pendingInvitation(1,id,45));assertEquals(1,mapper.countInvitations(1,id,null,0));
    }
    @Test void capacityReservationSerializesConcurrentInvitationsAndUnconfiguredPolicyIs503() throws Exception {
        long id=personal();capacity=2;
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new java.util.ArrayList<Future<Integer>>();
            for(long user:List.of(44L,45L))tasks.add(pool.submit(()->{actor.set(42L);ready.countDown();start.await(5,TimeUnit.SECONDS);try{members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:"+user,3)));return 0;}catch(ServiceException ex){return ex.getCode();}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();var result=List.of(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS));assertTrue(result.contains(0));assertTrue(result.contains(409));
            assertEquals(1,mapper.countCapacityInvites(42,null));
        } finally { pool.shutdownNow(); }
        var noPolicy=new SpaceService(mapper,mock(ResourceAccessService.class),identities,users);members.usePlatformServices(noPolicy,users);
        assertEquals(503,error(()->members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:46",3)))));
        assertEquals(0,mapper.pendingInvitation(1,id,46));
    }
    @Test void concurrentAcceptanceHasOneMemberAndOneSuccessfulReceipt() throws Exception {
        long id=personal();long invitation=members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3))).get(0);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> call=()->{actor.set(44L);start.await(5,TimeUnit.SECONDS);try{members.respondInvitation(invitation,true,0L);return 0;}catch(ServiceException ex){return ex.getCode();}};
            var first=pool.submit(call);var second=pool.submit(call);start.countDown();var result=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertTrue(result.contains(0));assertTrue(result.contains(409));assertEquals(2,mapper.countMembers(id,null,0));assertEquals(1L,mapper.invitation(invitation).getRevision());
        } finally { pool.shutdownNow(); }
    }
    @Test void rejectionRevocationAndWrongScopeNeverCreateMembership() {
        long id=personal();long invitation=members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:44",3))).get(0);
        long other=spaces.createPersonal("另一个空间",null,null);assertEquals(404,error(()->members.revokeInvitation(1,other,invitation,null)));
        actor.set(44L);members.respondInvitation(invitation,false,0L);assertNull(mapper.member(id,44));assertEquals(2,mapper.invitation(invitation).getStatus());
        actor.set(42L);long withdrawn=members.invite(1,id,List.of(new MembershipService.InviteAdd("ruoyi:45",3))).get(0);members.revokeInvitation(1,id,withdrawn,0L);
        actor.set(45L);assertEquals(409,error(()->members.respondInvitation(withdrawn,true,null)));assertNull(mapper.member(id,45));
    }
    @Test void applicationChecksTeamBoundaryAndPendingStateThenAddsActualMember() {
        long team=team(),id=teamSpace(team);mapper.insertTeamMember(team,44,3,"fixture");
        long personal=personal();actor.set(44L);assertEquals(404,error(()->members.apply(team,personal)));
        members.apply(team,id);assertEquals(409,error(()->members.apply(team,id)));
        actor.set(42L);var page=members.applications(id,query(0,1));long apply=page.records().get(0).getLongValue("id");assertEquals(1,page.total());
        assertEquals(404,error(()->members.auditApplication(personal,apply,true,null)));assertEquals(409,error(()->members.auditApplication(id,apply,true,1L)));
        members.auditApplication(id,apply,true,0L);assertEquals(3,mapper.member(id,44).getRole());assertEquals(2,mapper.application(apply).getStatus());assertEquals(42L,mapper.application(apply).getAuditUserId());
        assertEquals(409,error(()->members.auditApplication(id,apply,false,null)));
    }
    @Test void failedApprovalMemberWriteRollsBackAuditAndRefusalDoesNotAddMember() throws Exception {
        long team=team(),id=teamSpace(team);mapper.insertTeamMember(team,44,3,"fixture");actor.set(44L);members.apply(team,id);
        actor.set(42L);long apply=members.applications(id,query(0,1)).records().get(0).getLongValue("id");
        try(var c=data.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_space_member ADD CONSTRAINT fixture_approval_failure CHECK(user_id<>44)"); }
        fixtureFailure("FIXTURE_APPROVAL_FAILURE",()->members.auditApplication(id,apply,true,0L));assertEquals(1,mapper.application(apply).getStatus());assertNull(mapper.application(apply).getAuditUserId());assertNull(mapper.member(id,44));
        members.auditApplication(id,apply,false,0L);assertEquals(3,mapper.application(apply).getStatus());assertNull(mapper.member(id,44));
    }
    @Test void removingTeamMemberTransfersOwnedSpacesToTeamOwnerAndProtectsLastOwner() {
        long team=team(),id=teamSpace(team);mapper.insertTeamMember(team,43,2,"fixture");mapper.insertTeamMember(team,44,2,"fixture");mapper.insertMember(id,44,3,"fixture");
        members.transfer(id,"ruoyi:44");actor.set(43L);members.updateTeamRole(team,"ruoyi:44",3);members.updateTeamRole(team,"ruoyi:44",2);members.removeTeam(team,"ruoyi:44",false);
        assertEquals(42L,mapper.find(id).getOwnerUserId());assertEquals(1,mapper.member(id,42).getRole());assertEquals("removed",mapper.member(id,44).getStatus());assertEquals("removed",mapper.teamMember(team,44).getStatus());
        assertEquals(409,error(()->members.updateTeamRole(team,"ruoyi:42",2)));assertEquals(409,error(()->members.removeTeam(team,"ruoyi:42",false)));
        actor.set(42L);assertEquals(409,error(()->members.removeTeam(team,null,true)));
    }
    @Test void failedTeamRemovalRollsBackOwnerTransferAndAllRelatedMemberChanges() throws Exception {
        long team=team(),id=teamSpace(team);mapper.insertTeamMember(team,44,2,"fixture");mapper.insertMember(id,44,3,"fixture");members.transfer(id,"ruoyi:44");
        try(var c=data.getConnection();var sql=c.createStatement()) { sql.execute("ALTER TABLE paiflow_team_member ADD CONSTRAINT fixture_team_remove_failure CHECK(status<>'removed' OR user_id<>44)"); }
        fixtureFailure("FIXTURE_TEAM_REMOVE_FAILURE",()->members.removeTeam(team,"ruoyi:44",false));
        assertEquals(44L,mapper.find(id).getOwnerUserId());assertEquals(1,mapper.member(id,44).getRole());assertEquals("active",mapper.member(id,44).getStatus());assertEquals(2,mapper.member(id,42).getRole());assertEquals("active",mapper.teamMember(team,44).getStatus());
    }
    @Test void sourceCapacityCountsDistinctPeopleAcrossSpacesAndIgnoresAlreadyJoinedInvitees() {
        long first=personal(),second=spaces.createPersonal("第二空间",null,null);mapper.insertMember(first,44,3,"fixture");mapper.insertMember(second,44,3,"fixture");
        members.invite(1,first,List.of(new MembershipService.InviteAdd("ruoyi:45",3)));members.invite(1,second,List.of(new MembershipService.InviteAdd("ruoyi:45",3)));
        assertEquals(2,mapper.countCapacityMembers(42,null));assertEquals(1,mapper.countCapacityInvites(42,null));assertEquals(3,members.userLimit(1,first).used());
        long team=team(),space=teamSpace(team);mapper.insertTeamMember(team,44,3,"fixture");mapper.insertTeamMember(team,46,3,"fixture");
        members.invite(1,space,List.of(new MembershipService.InviteAdd("ruoyi:44",3)));
        assertEquals(0,mapper.countCapacityInvites(42,team));assertEquals(3,members.userLimit(2,team).used());assertEquals(2,members.userLimit(1,space).used());
    }
}
