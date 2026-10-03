package com.ruoyi.aps.api.controller;

import com.ruoyi.aps.application.foundation.ApsAuditActor;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.planning.PlanWorkbenchService;
import com.ruoyi.aps.application.planning.PlanLockService;
import com.ruoyi.aps.application.planning.PlanAdjustmentService;
import com.ruoyi.aps.application.planning.PlanPublishService;
import com.ruoyi.aps.application.planning.PlanCandidateLifecycleService;
import com.ruoyi.aps.application.planning.PlanStructuralAdjustmentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApsPlanWorkbenchHttpAuthorizationTest
{
    private static final String PLAN_ID = "00000000-0000-4000-8000-000000000081";

    @Test
    void deniedUserCannotReadDetailOrComparisonBeforeApplicationServiceAccess() throws Exception
    {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext())
        {
            context.setServletContext(new MockServletContext());
            TestPropertyValues.of("aps.enabled=true", "aps.api.enabled=true", "aps.persistence.enabled=true")
                    .applyTo(context);
            context.register(TestWebSecurity.class);
            context.refresh();
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            mockMvc.perform(get("/api/aps/v1/plan-versions").with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/aps/v1/plan-versions/{id}", PLAN_ID).with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/aps/v1/plan-versions/{id}/comparison", PLAN_ID).with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/aps/v1/plan-versions/{id}/locks", PLAN_ID)
                            .contentType("application/json").content("""
                                    {"expectedPlanRowVersion":0,"targetType":"JOB",
                                     "targetId":"00000000-0000-4000-8000-000000000082",
                                     "lockType":"TIME","reason":"unauthorized probe"}
                                    """).with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/aps/v1/plan-versions/{id}/locks/{lockId}", PLAN_ID, PLAN_ID)
                            .param("expectedPlanRowVersion", "0").param("expectedLockRowVersion", "0")
                            .with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/aps/v1/plan-versions/{id}/adjustments", PLAN_ID)
                            .header("Idempotency-Key", "00000000-0000-4000-8000-000000000083")
                            .contentType("application/json").content("""
                                    {"expectedBaseRowVersion":0,"capturedAt":"2026-09-15T00:00:00.000Z",
                                     "targetType":"JOB","targetId":"00000000-0000-4000-8000-000000000082",
                                     "requestedStartAt":"2026-09-15T01:00:00.000Z",
                                     "requestedEndAt":"2026-09-15T02:00:00.000Z","reason":"unauthorized probe"}
                                    """).with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/aps/v1/plan-versions/{id}/structural-adjustments", PLAN_ID)
                            .header("Idempotency-Key", "00000000-0000-4000-8000-000000000084")
                            .contentType("application/json").content("""
                                    {"expectedBaseRowVersion":0,"capturedAt":"2026-09-15T00:00:00.000Z",
                                     "action":"INSERT_ORDER","orderId":"00000000-0000-4000-8000-000000000085",
                                     "reason":"unauthorized probe"}
                                    """).with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/aps/v1/plan-versions/{id}/publish", PLAN_ID)
                            .contentType("application/json")
                            .content("{\"expectedPlanRowVersion\":0,\"reason\":\"unauthorized probe\"}")
                    .with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/aps/v1/plan-versions/{id}/discard", PLAN_ID)
                            .contentType("application/json")
                            .content("{\"expectedPlanRowVersion\":0,\"reason\":\"unauthorized probe\"}")
                            .with(user("outsider")))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(context.getBean(PlanWorkbenchService.class));
            verifyNoInteractions(context.getBean(PlanLockService.class));
            verifyNoInteractions(context.getBean(PlanAdjustmentService.class));
            verifyNoInteractions(context.getBean(PlanStructuralAdjustmentService.class));
            verifyNoInteractions(context.getBean(PlanPublishService.class));
            verifyNoInteractions(context.getBean(PlanCandidateLifecycleService.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import(ApsPlanWorkbenchController.class)
    static class TestWebSecurity
    {
        @Bean(name = "ss")
        ApsSystemAuthorizationTest.DenyPermissionService denyPermissionService()
        {
            return new ApsSystemAuthorizationTest.DenyPermissionService();
        }

        @Bean PlanWorkbenchService planWorkbenchService() { return mock(PlanWorkbenchService.class); }
        @Bean PlanLockService planLockService() { return mock(PlanLockService.class); }
        @Bean PlanAdjustmentService planAdjustmentService() { return mock(PlanAdjustmentService.class); }
        @Bean PlanStructuralAdjustmentService planStructuralAdjustmentService()
        {
            return mock(PlanStructuralAdjustmentService.class);
        }
        @Bean PlanPublishService planPublishService() { return mock(PlanPublishService.class); }
        @Bean PlanCandidateLifecycleService planCandidateLifecycleService()
        {
            return mock(PlanCandidateLifecycleService.class);
        }
        @Bean ApsAuditActorProvider actorProvider() { return () -> new ApsAuditActor("300", "outsider"); }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception
        {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
        }
    }
}
