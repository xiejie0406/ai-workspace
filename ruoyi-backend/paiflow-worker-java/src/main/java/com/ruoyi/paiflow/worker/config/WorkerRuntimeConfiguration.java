package com.ruoyi.paiflow.worker.config;

import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.adapter.HttpCenterClient;
import com.ruoyi.paiflow.worker.service.WorkerTaskRunner;
import com.ruoyi.paiflow.worker.service.WorkerClaimLoop;
import com.ruoyi.paiflow.worker.service.WorkerServiceIdentity;
import com.ruoyi.paiflow.worker.controller.WorkerReceiveBoundaryFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(prefix = "paiflow.worker", name = "enabled", havingValue = "true")
@ComponentScan(basePackages = {"com.iflytek.astron.workflow.engine", "com.iflytek.astron.link.execution"})
public class WorkerRuntimeConfiguration {
    @Bean
    WorkerServiceIdentity workerReceiveIdentity(@Value("${paiflow.worker.receive-service-token}") String token) {
        return new WorkerServiceIdentity(token);
    }

    @Bean
    WorkerReceiveBoundaryFilter workerReceiveBoundaryFilter(WorkerServiceIdentity identity, ObjectMapper mapper) {
        return new WorkerReceiveBoundaryFilter(identity, mapper);
    }

    @Bean
    CenterClient centerClient(ObjectMapper mapper,
            @Value("${paiflow.worker.center.endpoint}") URI endpoint,
            @Value("${paiflow.worker.center.service-token}") String token) {
        return new HttpCenterClient(endpoint, token, mapper);
    }

    @Bean(destroyMethod = "close")
    WorkerTaskRunner workerTaskRunner(CenterClient center, WorkflowEngine sequential, ParallelWorkflowEngine parallel, ObjectMapper mapper,
            @Value("${paiflow.worker.max-run-seconds:600}") long maxRunSeconds,
            @Value("${paiflow.worker.heartbeat-seconds:10}") long heartbeatSeconds) {
        return new WorkerTaskRunner(center, sequential, parallel, Clock.systemUTC(), maxRunSeconds, heartbeatSeconds, mapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "paiflow.worker.polling", name = "enabled", havingValue = "true")
    WorkerClaimLoop workerClaimLoop(WorkerTaskRunner runner,
            @Value("${paiflow.worker.polling.interval-millis:1000}") long intervalMillis,
            @Value("${paiflow.worker.polling.max-backoff-millis:30000}") long maxBackoffMillis) {
        return new WorkerClaimLoop(runner, intervalMillis, maxBackoffMillis);
    }
}
