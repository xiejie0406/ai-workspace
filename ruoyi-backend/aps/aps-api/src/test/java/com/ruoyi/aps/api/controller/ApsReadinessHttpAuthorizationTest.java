package com.ruoyi.aps.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.ruoyi.aps.application.foundation.ApsAuditActor;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.planning.PlanReadinessService;
import com.ruoyi.aps.application.planning.SolverInputCompiler;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApsReadinessHttpAuthorizationTest
{
    @Test
    void ordinaryPlannerWithBothRegisteredPermissionsCanPrecheckWithinOwnScope() throws Exception
    {
        try (var context = context())
        {
            var service = context.getBean(PlanReadinessService.class);
            when(service.check(any(), any())).thenReturn(new PlanReadinessService.Readiness(true,
                    Instant.parse("2026-10-01T00:00:00Z"), "hash", List.of()));
            mvc(context).perform(post("/api/aps/v1/readiness").with(user(planner(Set.of(
                    "aps:readiness:view", "aps:order:list")))).contentType(MediaType.APPLICATION_JSON)
                    .content(validBody())).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
            verify(service).check(eq(new ResourceAccessScope("300", false)), any(SolverInputCompiler.CompileRequest.class));
        }
    }

    @Test
    void missingEitherPermissionCannotPrecheckOrReachService() throws Exception
    {
        try (var context = context())
        {
            for (var permissions : List.of(Set.of("aps:readiness:view"), Set.of("aps:order:list")))
                mvc(context).perform(post("/api/aps/v1/readiness").with(user(planner(permissions)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isForbidden());
            verifyNoInteractions(context.getBean(PlanReadinessService.class));
        }
    }

    private AnnotationConfigWebApplicationContext context()
    {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertyValues.of("aps.enabled=true", "aps.api.enabled=true", "aps.persistence.enabled=true").applyTo(context);
        context.register(TestWebSecurity.class); context.refresh(); return context;
    }
    private MockMvc mvc(AnnotationConfigWebApplicationContext context)
    { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private LoginUser planner(Set<String> permissions)
    {
        var account = new SysUser(); account.setUserId(300L); account.setUserName("planner");
        return new LoginUser(300L, 10L, account, permissions);
    }
    private String validBody()
    {
        return """
                {"siteCode":"SITE_01","workshopIds":["00000000-0000-4000-8000-000000000002"],
                "orderIds":["00000000-0000-4000-8000-000000000003"],"capturedAt":"2026-09-14T00:00:00Z",
                "definitionRevision":1,"executionRevision":1,
                "horizon":{"startAt":"2026-09-14T00:00:00Z","detailEndAt":"2026-09-15T00:00:00Z",
                "endAt":"2026-09-16T00:00:00Z","planningAnchorAt":"2026-09-14T00:00:00Z",
                "timeUnitSeconds":60,"displayTimeZone":"Asia/Shanghai"},
                "parameters":{"maxSolveSeconds":30,"randomSeed":1,"solverSearchThreads":1,
                "absoluteGapLimit":0,"relativeGapLimit":0}}
                """;
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import(ApsReadinessController.class)
    static class TestWebSecurity
    {
        @Bean(name = "ss") PlatformPermissionService permissions() { return new PlatformPermissionService(); }
        @Bean PlanReadinessService service() { return mock(PlanReadinessService.class); }
        @Bean ApsAuditActorProvider actors() { return () -> new ApsAuditActor("300", "planner"); }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception
        { return http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(r -> r.anyRequest().permitAll()).build(); }
    }
    static class PlatformPermissionService
    {
        public boolean hasPermi(String permission) { return SecurityUtils.hasPermi(permission); }
    }
}
