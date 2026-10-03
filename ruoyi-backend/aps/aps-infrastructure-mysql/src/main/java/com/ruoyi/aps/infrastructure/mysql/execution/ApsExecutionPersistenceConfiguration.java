package com.ruoyi.aps.infrastructure.mysql.execution;

import com.ruoyi.aps.application.execution.ExecutionLifecycleService;
import com.ruoyi.aps.application.execution.ExecutionRepository;
import com.ruoyi.aps.application.execution.ProductionReportingService;
import com.ruoyi.aps.application.execution.QuantityRepository;
import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsExecutionMapper;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsQuantityMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.datasource", name = "enabled", havingValue = "true")
public class ApsExecutionPersistenceConfiguration
{
    @Bean public com.ruoyi.aps.application.execution.ExecutionDirectoryService apsExecutionDirectoryService(
            ExecutionLifecycleService lifecycle,ExecutionRepository executions,QuantityRepository quantities,
            com.ruoyi.aps.application.resource.ResourceManagementService resources,OrderRepository orders,
            com.ruoyi.aps.application.planning.PlanWorkbenchService plans) {
        return new com.ruoyi.aps.application.execution.ExecutionDirectoryService(lifecycle,executions,quantities,resources,orders,plans);
    }
    @Bean public ExecutionRepository apsExecutionRepository(ApsExecutionMapper mapper)
    {
        return new MybatisExecutionRepository(mapper);
    }

    @Bean public QuantityRepository apsQuantityRepository(ApsQuantityMapper mapper)
    {
        return new MybatisQuantityRepository(mapper);
    }

    @Bean public ExecutionLifecycleService apsExecutionLifecycleService(ExecutionRepository repository,
            OrderRepository orders, ApsTransactionOperations transactions)
    {
        return new ExecutionLifecycleService(repository, orders, transactions);
    }

    @Bean public ProductionReportingService apsProductionReportingService(ExecutionRepository executions,
            QuantityRepository quantities, OrderRepository orders, ApsTransactionOperations transactions)
    {
        return new ProductionReportingService(executions, quantities, orders, transactions);
    }
}
