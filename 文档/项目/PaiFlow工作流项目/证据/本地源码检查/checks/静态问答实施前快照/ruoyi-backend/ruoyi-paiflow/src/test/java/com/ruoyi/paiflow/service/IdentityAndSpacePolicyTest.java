package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.paiflow.domain.SpaceMember;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityAndSpacePolicyTest {
    @BeforeEach void webRequest() {
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
    }
    @AfterEach void clearContext() {
        SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
    private static SysUser user(long id) {
        var user=new SysUser();user.setUserId(id);user.setUserName("fixture-"+id);user.setNickName("原昵称");
        user.setStatus("0");user.setDelFlag("0");user.setPassword("private-fixture");return user;
    }
    private static LoginUser login(SysUser user,Set<String> permissions) {
        var principal=new LoginUser(user.getUserId(),8L,user,permissions);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        return principal;
    }
    private static SpaceRecord space() {
        var space=new SpaceRecord();space.setId(10L);space.setOwnerUserId(42L);space.setKind("team");space.setTeamId(1L);space.setRevision(3L);space.setDeleted(0);return space;
    }
    private static SpaceMember member(long id,int role,String status) {
        var member=new SpaceMember();member.setSpaceId(10L);member.setUserId(id);member.setRole(role);member.setStatus(status);return member;
    }
    @Test void unknownUidCannotFallBackToAdminAndCaseSensitiveMappingIsExplicit() {
        var map=mock(IdentityMapper.class);var users=mock(ISysUserService.class);var service=new IdentityMappingService(map,users);
        service.useCurrentSession(mock(SqlSessionTemplate.class));service.useCurrentPermissions(mock(SysPermissionService.class));
        when(map.findPlatformUserId("paiflow","unmapped-old-user")).thenReturn(null);
        var missing=assertThrows(ServiceException.class,()->service.resolveReference("unmapped-old-user"));assertEquals(404,missing.getCode());
        verifyNoInteractions(users);
        when(map.findPlatformUserId("paiflow","explicit-old-user")).thenReturn(42L);when(users.selectUserById(42L)).thenReturn(user(42));
        assertEquals(42,service.resolveReference("explicit-old-user"));assertEquals("ruoyi:42",IdentityMappingService.legacyUid(42));
    }
    @Test void disabledPlatformAccountCannotKeepUsingCachedLogin() {
        var active=user(42);login(active,Set.of("*:*:*"));var disabled=user(42);disabled.setStatus("1");
        var users=mock(ISysUserService.class);when(users.selectUserById(42L)).thenReturn(disabled);
        var identity=new IdentityMappingService(mock(IdentityMapper.class),users);
        identity.useCurrentSession(mock(SqlSessionTemplate.class));identity.useCurrentPermissions(mock(SysPermissionService.class));
        assertEquals(401,assertThrows(ServiceException.class,identity::current).getCode());
    }
    @Test void profileUsesPlatformFieldsAndDoesNotInventAgreementOrExposeSecrets() {
        var identity=mock(IdentityMappingService.class);var user=user(42);user.setAvatar("/profile/fixture.png");
        when(identity.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));
        var service=new UserProfileService(identity,mock(IdentityMapper.class),mock(ISysUserService.class),mock(TokenService.class));
        var profile=service.current();assertEquals("ruoyi:42",profile.uid());assertEquals(0,profile.userAgreement());assertEquals("原昵称",profile.nickname());
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(profile).contains("private-fixture"));
    }
    @Test void legacyProfileDisplaysPlatformUtcInstantInBeijingTime() {
        var identity=mock(IdentityMappingService.class);var user=user(42);
        user.setCreateTime(java.util.Date.from(java.time.Instant.parse("2026-10-03T00:00:00Z")));
        when(identity.current()).thenReturn(new LoginUser(42L,8L,user,Set.of()));
        var profile=new UserProfileService(identity,mock(IdentityMapper.class),mock(ISysUserService.class),mock(TokenService.class)).current();
        assertEquals(java.time.LocalDateTime.of(2026,10,3,8,0),profile.createTime());
    }
    @Test void nicknameUpdateCannotOverwritePlatformDepartmentOrPassword() {
        var identity=mock(IdentityMappingService.class);var current=user(42);var principal=new LoginUser(42L,8L,current,Set.of());
        when(identity.current()).thenReturn(principal);var users=mock(ISysUserService.class);
        when(users.selectUserById(42L)).thenReturn(current);when(users.updateUserProfile(any())).thenReturn(1);
        new UserProfileService(identity,mock(IdentityMapper.class),users,mock(TokenService.class)).update("新昵称",null);
        var captor=org.mockito.ArgumentCaptor.forClass(SysUser.class);verify(users).updateUserProfile(captor.capture());
        assertEquals(42L,captor.getValue().getUserId());assertEquals(0L,captor.getValue().getDeptId());assertNull(captor.getValue().getPassword());assertNull(captor.getValue().getStatus());
    }
    @Test void ownershipNeverGrantsMissingPlatformPermission() {
        var identity=mock(IdentityMappingService.class);when(identity.current()).thenReturn(new LoginUser(42L,8L,user(42),Set.of()));
        var spaces=mock(SpaceMapper.class);var access=new ResourceAccessService(identity,new PermissionService(),spaces);
        access.useCurrentSession(mock(SqlSessionTemplate.class));
        login(user(42),Set.of());assertEquals(403,assertThrows(ServiceException.class,()->access.requireSpace(10,"paiflow:space:edit",2,true)).getCode());
        verifyNoInteractions(spaces);
    }
    @Test void removedMemberCannotReadSpaceEvenWithPlatformPermission() {
        var identity=mock(IdentityMappingService.class);var principal=login(user(42),Set.of("paiflow:space:query"));when(identity.current()).thenReturn(principal);
        var spaces=mock(SpaceMapper.class);when(spaces.find(10L)).thenReturn(space());when(spaces.member(10L,42L)).thenReturn(member(42,3,"removed"));
        var access=new ResourceAccessService(identity,new PermissionService(),spaces);
        access.useCurrentSession(mock(SqlSessionTemplate.class));
        assertEquals(404,assertThrows(ServiceException.class,()->access.requireSpace(10,"paiflow:space:query",3,false)).getCode());
    }
    @Test void lastOwnerCannotLeaveOrBeRemovedAndPersonalTransferIsRejected() {
        var access=mock(ResourceAccessService.class);var identity=mock(IdentityMappingService.class);var spaces=mock(SpaceMapper.class);
        var personal=space();personal.setTeamId(null);personal.setKind("personal");
        when(access.requireSpace(eq(10L),anyString(),anyInt(),eq(true))).thenReturn(personal);
        when(identity.current()).thenReturn(new LoginUser(42L,8L,user(42),Set.of()));when(identity.resolveReference("ruoyi:42")).thenReturn(42L);
        var membership=new MembershipService(access,identity,spaces);
        assertEquals(409,assertThrows(ServiceException.class,()->membership.leave(10)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->membership.remove(10,"ruoyi:42")).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->membership.transfer(10,"ruoyi:43")).getCode());verifyNoInteractions(spaces);
    }
    @Test void transferChangesOwnerAndBothRolesUsingOneLockedRevision() {
        var access=mock(ResourceAccessService.class);var identity=mock(IdentityMappingService.class);var spaces=mock(SpaceMapper.class);
        when(access.requireSpace(10L,"paiflow:space:members",1,true)).thenReturn(space());when(identity.current()).thenReturn(new LoginUser(42L,8L,user(42),Set.of()));
        when(identity.resolveReference("ruoyi:43")).thenReturn(43L);when(spaces.member(10L,43L)).thenReturn(member(43,3,"active"));
        when(spaces.transfer(10L,43L,3L,"fixture-42")).thenReturn(1);when(spaces.setRole(eq(10L),anyLong(),anyInt(),eq("fixture-42"))).thenReturn(1);
        new MembershipService(access,identity,spaces).transfer(10,"ruoyi:43");
        verify(spaces).transfer(10L,43L,3L,"fixture-42");verify(spaces).setRole(10L,43L,1,"fixture-42");verify(spaces).setRole(10L,42L,2,"fixture-42");
    }
}
