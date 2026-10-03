package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.core.domain.model.LoginBody;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyLoginControllerTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    @Test void delegatesCredentialsAndCaptchaAndReturnsOnlyPlatformToken() {
        var platform = mock(SysLoginService.class);
        var body = new LoginBody();
        body.setUsername("fixture-user"); body.setPassword("fixture-password"); body.setCode("2345"); body.setUuid("captcha-uuid");
        when(platform.login("fixture-user", "fixture-password", "2345", "captcha-uuid")).thenReturn("platform-token-fixture");
        var result = new LegacyLoginController(platform).login(body);
        verify(platform).login("fixture-user", "fixture-password", "2345", "captcha-uuid");
        assertEquals(0, result.code());
        assertEquals("platform-token-fixture", result.data().get("accessToken"));
        assertFalse(result.data().containsKey("refreshToken"));
    }
    @Test void platformRejectIsNotConvertedToEmptySuccess() {
        var platform = mock(SysLoginService.class);
        var body = new LoginBody();body.setUsername("disabled-fixture"); body.setPassword("fixture-password");
        when(platform.login("disabled-fixture", "fixture-password", null, null)).thenThrow(new IllegalArgumentException("平台拒绝"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyLoginController(platform).login(body));
    }
    @Test void sessionReturnsOnlyAuthenticatedPlatformIdentity() {
        var user = new SysUser();user.setUserId(42L);user.setUserName("platform-fixture");user.setPassword("private-fixture");
        var principal = new LoginUser(42L, 8L, user, Set.of("paiflow:workflow:query"));
        principal.setToken("private-session-fixture");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var identity = new LegacyLoginController(mock(SysLoginService.class)).session().data();
        assertEquals(42L, identity.userId());assertEquals("platform-fixture", identity.username());
        var json = com.alibaba.fastjson2.JSON.toJSONString(identity);
        assertFalse(json.contains("private-fixture"));assertFalse(json.contains("private-session-fixture"));
    }
    @Test void missingPlatformSessionCannotSucceed() {
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> new LegacyLoginController(mock(SysLoginService.class)).session());
    }
}
