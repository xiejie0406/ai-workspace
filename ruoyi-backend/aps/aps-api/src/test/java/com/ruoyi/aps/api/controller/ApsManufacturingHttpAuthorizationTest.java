package com.ruoyi.aps.api.controller;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;
import com.ruoyi.aps.application.foundation.ApsAuditActor;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.order.OrderManagementService;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.routing.RoutingManagementService;
import com.ruoyi.aps.application.routing.RoutingRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApsManufacturingHttpAuthorizationTest
{
    @Test
    void deniedUserCannotReadRoutesOrOrdersBeforeRepositoryAccess() throws Exception
    {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext())
        {
            context.setServletContext(new MockServletContext());
            TestPropertyValues.of("aps.enabled=true", "aps.api.enabled=true", "aps.persistence.enabled=true")
                    .applyTo(context);
            context.register(TestWebSecurity.class);
            context.refresh();
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            mockMvc.perform(get("/api/aps/v1/routings/items").with(user("outsider")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/aps/v1/orders").with(user("outsider")))
                    .andExpect(status().isForbidden());
            for(String path : new String[]{"/api/aps/v1/resources/directory","/api/aps/v1/resources/user-candidates",
                    "/api/aps/v1/reports/order-candidates","/api/aps/v1/execution-runs",
                    "/api/aps/v1/execution-runs/dispatch-plans","/api/aps/v1/execution-runs/x/report-members",
                    "/api/aps/v1/execution-runs/x/resource-candidates?replacedResourceId=y",
                    "/api/aps/v1/execution-runs/x/output-lots/y/movement-targets"})
                mockMvc.perform(get(path).with(user("outsider"))).andExpect(status().isForbidden());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import({ApsRoutingController.class, ApsOrderController.class,ApsResourceController.class,
            ApsContextController.class,ApsExecutionDirectoryController.class,ApsReportDirectoryController.class})
    static class TestWebSecurity
    {
        @Bean(name = "ss")
        ApsSystemAuthorizationTest.DenyPermissionService denyPermissionService()
        {
            return new ApsSystemAuthorizationTest.DenyPermissionService();
        }

        @Bean RoutingRepository routingRepository() { return failFast(RoutingRepository.class); }
        @Bean OrderRepository orderRepository() { return failFast(OrderRepository.class); }
        @Bean com.ruoyi.aps.application.resource.ResourceManagementService resourceManagementService(){return org.mockito.Mockito.mock(com.ruoyi.aps.application.resource.ResourceManagementService.class);}
        @Bean com.ruoyi.aps.application.execution.ExecutionDirectoryService executionDirectoryService(){return org.mockito.Mockito.mock(com.ruoyi.aps.application.execution.ExecutionDirectoryService.class);}
        @Bean com.ruoyi.system.service.ISysUserService sysUserService(){return org.mockito.Mockito.mock(com.ruoyi.system.service.ISysUserService.class);}

        @Bean
        ApsTransactionOperations transactionOperations()
        {
            return new ApsTransactionOperations()
            {
                @Override public <T> T required(Supplier<T> action)
                {
                    throw new AssertionError("权限拒绝前不应开启 APS 事务");
                }
            };
        }

        @Bean RoutingManagementService routingManagementService(RoutingRepository repository,
                ApsTransactionOperations transactions) { return new RoutingManagementService(repository, transactions); }
        @Bean OrderManagementService orderManagementService(OrderRepository orders, RoutingRepository routings,
                ApsTransactionOperations transactions) { return new OrderManagementService(orders, routings, transactions); }

        @Bean
        ApsAuditActorProvider actorProvider() { return () -> new ApsAuditActor("300", "outsider"); }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception
        {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
        }

        @SuppressWarnings("unchecked")
        private static <T> T failFast(Class<T> type)
        {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, arguments) -> { throw new AssertionError("权限拒绝前不应访问仓储"); });
        }
    }
}
