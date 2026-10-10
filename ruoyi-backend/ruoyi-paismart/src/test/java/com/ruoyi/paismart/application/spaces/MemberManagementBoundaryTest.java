package com.ruoyi.paismart.application.spaces;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.api.MemberController;
import com.ruoyi.paismart.application.*;
import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.system.service.ISysUserService;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** 内存库和平台用户服务替身，验证范围过滤、平台DataScope与真实成员版本。 */
class MemberManagementBoundaryTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private SpaceService spaces;
    private MembershipService eligibility;
    private ISysUserService users;
    private MemberController controller;
    private String managed,other,personal;
    private final SpaceService.Scope root=new SpaceService.Scope(1,true),manager=new SpaceService.Scope(2,false);
    @BeforeEach void setup() {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:member_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"),new ClassPathResource("db/paismart/V002__documents_spaces.sql"),new ClassPathResource("db/paismart/V003__intelligence.sql"),new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(source);
        db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,del_flag CHAR(1) DEFAULT '0')");
        for(long id=1;id<=4;id++)db.update("INSERT INTO sys_user(user_id) VALUES(?)",id);
        spaces=new SpaceService(db);eligibility=new MembershipService(db);users=mock(ISysUserService.class);
        db.update("INSERT INTO ps_member(user_id,status) VALUES(2,'ACTIVE')");
        managed=tx.execute(s->spaces.save(root,null,null,"研发",null,-1,null)).id();
        other=tx.execute(s->spaces.save(root,null,null,"财务",null,-1,null)).id();
        personal=tx.execute(s->spaces.personal(new SpaceService.Scope(3,false))).id();
        // 显式个人入口登记本人关系；组织批量分配不能清除该关系。
        assertTrue(spaces.memberships(3).stream().anyMatch(member->member.spaceId().equals(personal)));
        tx.executeWithoutResult(s->{spaces.setMember(root,managed,2,true,true,false);spaces.setMember(root,managed,3,true,false);spaces.setMember(root,other,3,true,false);spaces.primary(new SpaceService.Scope(3,false),other);});
        when(users.selectUserList(any())).thenReturn(List.of(user(3)));when(users.selectUserById(anyLong())).thenAnswer(inv->user(inv.getArgument(0)));
        controller=new MemberController(db,eligibility,spaces,new QuotaService(db,tx,java.time.Clock.systemUTC()),mock(InvitationService.class),users);login(2);
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    private SysUser user(long id) {SysUser user=new SysUser();user.setUserId(id);user.setUserName("member"+id);user.setNickName("成员"+id);user.setPassword("password-never-serialized");return user;}
    private void login(long id) {LoginUser login=new LoginUser();login.setUserId(id);login.setUser(user(id));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,List.of()));}
    @SuppressWarnings("unchecked") private Map<String,Object> data(Object result) {return (Map<String,Object>)result;}
    @Test void managementListReusesPlatformScopeAndHidesOtherOrganizationAndPersonalMetadata() {
        var result=controller.members(1,20,"","member");assertEquals(1,result.getRows().size());
        Map<String,Object> row=data(result.getRows().get(0));assertEquals(List.of(managed),row.get("spaceIds"));assertNull(row.get("primarySpaceId"));
        assertFalse(row.toString().contains(personal));assertFalse(row.toString().contains(other));assertFalse(row.toString().contains("password-never-serialized"));
        verify(users).selectUserList(argThat(query->"member".equals(query.getUserName())));
        login(3);Map<String,Object> me=data(controller.me().get("data"));assertTrue(((List<?>)me.get("spaceIds")).contains(other));assertTrue(((List<?>)me.get("spaceIds")).contains(personal));
    }
    @Test void buttonPermissionWithoutOrganizationManagerScopeReturnsNoPlatformUsers() {
        login(4);db.update("INSERT INTO ps_member(user_id,status) VALUES(4,'ACTIVE')");
        assertTrue(controller.members(1,20,"",null).getRows().isEmpty());verifyNoInteractions(users);
    }
    @Test void ownVisibleSpacesUseExistingScopeAndExposeOnlyNamesWithoutManagementPermission() {
        assertEquals(1,db.update("UPDATE ps_member SET status='ACTIVE' WHERE user_id=3"));
        String child=tx.execute(s->spaces.save(root,null,managed,"研发子空间",null,-1,null)).id();
        login(3);var me=data(controller.me().get("data"));
        @SuppressWarnings("unchecked") var visible=(List<Map<String,Object>>)me.get("visibleSpaces");
        assertEquals(spaces.visibleSpaceIds(new SpaceService.Scope(3,false)),new HashSet<>(visible.stream().map(row->(String)row.get("id")).toList()));
        assertFalse(visible.stream().anyMatch(row->child.equals(row.get("id"))));
        assertTrue(visible.stream().allMatch(row->row.keySet().equals(Set.of("id","name"))));
        verifyNoInteractions(users);
    }
    @Test void ownVisibleSpacesIncludeManagerChildrenAndExcludeUnrelatedSpaceNames() {
        String child=tx.execute(s->spaces.save(root,null,managed,"研发子空间",null,-1,null)).id();
        login(2);var me=data(controller.me().get("data"));
        @SuppressWarnings("unchecked") var visible=(List<Map<String,Object>>)me.get("visibleSpaces");
        assertTrue(visible.stream().anyMatch(row->child.equals(row.get("id"))));
        assertFalse(visible.stream().anyMatch(row->other.equals(row.get("id"))||personal.equals(row.get("id"))));
    }
    @Test void ineligibleMemberCannotReadVisibleSpaceNamesOrGainEligibilityThroughMe() {
        login(3);var me=data(controller.me().get("data"));
        assertEquals(List.of(),me.get("visibleSpaces"));assertFalse(eligibility.isEligible(3));
    }
    @Test void administratorEligibilityUsesPlatformIdentityWithoutActivatingBusinessMembership() {
        assertEquals("INELIGIBLE",db.queryForObject("SELECT status FROM ps_member WHERE user_id=1",String.class));
        login(1);var me=data(controller.me().get("data"));
        assertEquals(true,me.get("eligible"));assertEquals("ACTIVE",me.get("status"));
        @SuppressWarnings("unchecked") var visible=(List<Map<String,Object>>)me.get("visibleSpaces");
        assertEquals(spaces.visibleSpaceIds(root),new HashSet<>(visible.stream().map(row->(String)row.get("id")).toList()));
        assertEquals("INELIGIBLE",db.queryForObject("SELECT status FROM ps_member WHERE user_id=1",String.class));
        assertTrue(visible.stream().allMatch(row->row.keySet().equals(Set.of("id","name"))));
        verifyNoInteractions(users);
    }
    @Test void aggregateUsesRealVersionAndStaleRetryDoesNotPartiallyWriteOrGrantEligibility() {
        long version=spaces.membershipVersion(3);
        var input=new MemberController.MemberSpacesRequest(List.of(new SpaceService.Assignment(managed,false,true)),null,version,version);
        tx.executeWithoutResult(s->controller.spaces(3,input,Long.toString(version)));
        assertEquals(version+1,spaces.membershipVersion(3));assertFalse(eligibility.isEligible(3));
        assertTrue(spaces.memberships(3).stream().anyMatch(member->member.spaceId().equals(other)&&member.primary()));
        assertTrue(spaces.memberships(3).stream().anyMatch(member->member.spaceId().equals(personal)));
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->controller.spaces(3,new MemberController.MemberSpacesRequest(List.of(),null,version,version),null)));
        assertEquals(version+1,spaces.membershipVersion(3));assertTrue(spaces.memberships(3).stream().anyMatch(member->member.spaceId().equals(managed)&&member.managerAllowed()));
    }
    @Test void invalidSecondAssignmentRollsBackWholeBatchAndCannotClaimPersonalSpace() {
        long version=spaces.membershipVersion(3);var before=spaces.memberships(3);
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->controller.spaces(3,new MemberController.MemberSpacesRequest(List.of(new SpaceService.Assignment(managed,false,false),new SpaceService.Assignment(other,true,true)),null,version,version),null)));
        assertEquals(before,spaces.memberships(3));assertEquals(version,spaces.membershipVersion(3));
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->controller.spaces(3,new MemberController.MemberSpacesRequest(List.of(new SpaceService.Assignment(personal,true,true)),null,version,version),null)));
        assertEquals(before,spaces.memberships(3));
    }
    @Test void platformUserDataScopeRejectionAndNewRevisionRecordCannotEnableMember() {
        doThrow(new ServiceException("没有权限访问用户数据",403)).when(users).checkUserDataScope(4L);
        var input=new MemberController.MemberSpacesRequest(List.of(new SpaceService.Assignment(managed,true,false)),managed,0L,0L);
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->controller.spaces(4,input,null)));assertEquals(0,spaces.membershipVersion(4));
        reset(users);when(users.selectUserById(4L)).thenReturn(user(4));tx.executeWithoutResult(s->controller.spaces(4,input,"0"));
        assertEquals(1,spaces.membershipVersion(4));assertEquals("INELIGIBLE",db.queryForObject("SELECT status FROM ps_member WHERE user_id=4",String.class));assertFalse(eligibility.isEligible(4));
    }
    @Test void organizationAndPlatformStatusFilterApplyBeforePagingWithinPlatformScope() {
        tx.executeWithoutResult(s->spaces.setMember(root,managed,4,true,false));
        // root是创建组织的真实成员，不能拿root假装组织之外用户；新增真正未加入的可见平台用户。
        db.update("INSERT INTO sys_user(user_id) VALUES(5)");
        SysUser disabled3=user(3),disabled4=user(4),outside=user(5);disabled3.setStatus("1");disabled4.setStatus("1");outside.setStatus("1");
        when(users.selectUserList(any())).thenReturn(List.of(disabled3,disabled4,outside));
        var page=controller.members(2,1,"","member",managed,"1");
        assertEquals(2,page.getTotal());assertEquals(4L,data(page.getRows().get(0)).get("id"));
        verify(users).selectUserList(argThat(query->"1".equals(query.getStatus())&&"member".equals(query.getUserName())));
        assertThrows(ServiceException.class,()->controller.members(1,20,"",null,other,null));
        assertThrows(ServiceException.class,()->controller.members(1,20,"",null,personal,null));
        assertThrows(IllegalArgumentException.class,()->controller.members(1,20,"",null,null,"ACTIVE"));
    }
    @Test void memberUsageReadsActualDailyAndAccountRowsWithoutGrantingEligibility() {
        db.update("INSERT INTO ps_token_account(user_id,scope,available,consumed) VALUES(3,'LLM',37,11)");
        var row=data(controller.members(1,20,"",null).getRows().get(0));
        var usage=data(row.get("usage"));assertNotNull(usage.get("llm"));assertNotNull(usage.get("embedding"));assertEquals(0L,usage.get("chatRequestCount"));
        assertFalse(eligibility.isEligible(3));assertFalse(row.toString().contains("password-never-serialized"));
    }
}
