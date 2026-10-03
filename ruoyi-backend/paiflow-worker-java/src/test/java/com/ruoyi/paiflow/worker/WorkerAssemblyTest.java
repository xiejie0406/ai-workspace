package com.ruoyi.paiflow.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.astron.workflow.engine.node.NodeExecutor;
import com.ruoyi.paiflow.worker.config.WorkerRuntimeConfiguration;
import com.ruoyi.paiflow.worker.controller.WorkerTaskController;
import com.ruoyi.paiflow.worker.service.WorkerClaimLoop;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkerAssemblyTest {
    @Test
    void independentWorkerAssemblesAllFourExecutorsWithoutStartingServerOrProvider() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("offline-worker", Map.of(
                    "paiflow.worker.enabled", "true", "paiflow.worker.center.endpoint", "https://center.invalid",
                    "paiflow.worker.center.service-token", "offline-outgoing-token",
                    "paiflow.worker.receive-service-token", "offline-incoming-token")));
            context.register(MapperOnly.class, WorkerRuntimeConfiguration.class, WorkerTaskController.class);
            context.refresh();
            assertEquals(4, context.getBeansOfType(NodeExecutor.class).size());
            assertNotNull(context.getBean(WorkerTaskController.class));
            assertTrue(context.getBeansOfType(WorkerClaimLoop.class).isEmpty(), "默认不领取任务、不访问中心");
            assertTrue(Arrays.stream(context.getBeanDefinitionNames()).noneMatch(name ->
                    name.toLowerCase().contains("mapper") && !name.equals("objectMapper") && !name.contains("MapperOnly")
                            || name.toLowerCase().contains("datasource") || name.toLowerCase().contains("redis")));
        }
    }

    @Configuration static class MapperOnly {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
