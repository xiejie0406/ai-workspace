package com.ruoyi.aps.infrastructure.mysql.planning;

import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.planning.PlanRepository;
import com.ruoyi.aps.application.planning.PlanLockService;
import com.ruoyi.aps.application.planning.PlanAdjustmentService;
import com.ruoyi.aps.application.planning.PlanPublishService;
import com.ruoyi.aps.application.planning.PlanCandidateLifecycleService;
import com.ruoyi.aps.application.planning.PlanStructuralAdjustmentService;
import com.ruoyi.aps.application.planning.PlanRequestService;
import com.ruoyi.aps.application.planning.PlanWorkbenchService;
import com.ruoyi.aps.application.planning.SolverInputCompiler;
import com.ruoyi.aps.application.resource.ResourceManagementService;
import com.ruoyi.aps.application.order.OrderManagementService;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsPlanMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.datasource", name = "enabled", havingValue = "true")
public class ApsPlanningPersistenceConfiguration
{
    @Bean
    public com.ruoyi.aps.application.planning.PlanReadinessService apsPlanReadinessService(SolverInputCompiler compiler)
    {
        return new com.ruoyi.aps.application.planning.PlanReadinessService(compiler);
    }
    @Bean public PlanRepository apsPlanRepository(ApsPlanMapper mapper) { return new MybatisPlanRepository(mapper); }
    @Bean
    public PlanRequestService apsPlanRequestService(SolverInputCompiler compiler, PlanRepository plans,
            ApsTransactionOperations transactions, ResourceManagementService resources)
    {
        return new PlanRequestService(compiler, plans, transactions, resources);
    }
    @Bean
    public PlanWorkbenchService apsPlanWorkbenchService(PlanRepository plans, ResourceManagementService resources)
    {
        return new PlanWorkbenchService(plans, resources);
    }
    @Bean
    public PlanLockService apsPlanLockService(PlanRepository plans, PlanWorkbenchService workbench,
            ApsTransactionOperations transactions)
    {
        return new PlanLockService(plans, workbench, transactions);
    }
    @Bean
    public PlanAdjustmentService apsPlanAdjustmentService(PlanRepository plans, PlanWorkbenchService workbench,
            ApsTransactionOperations transactions)
    {
        return new PlanAdjustmentService(plans, workbench, transactions);
    }
    @Bean
    public PlanStructuralAdjustmentService apsPlanStructuralAdjustmentService(PlanRepository plans,
            PlanWorkbenchService workbench, OrderRepository orders, OrderManagementService orderManagement,
            SolverInputCompiler compiler, ApsTransactionOperations transactions)
    {
        return new PlanStructuralAdjustmentService(plans, workbench, orders, orderManagement, compiler,
                transactions);
    }
    @Bean
    public PlanPublishService apsPlanPublishService(PlanRepository plans, PlanWorkbenchService workbench,
            ApsTransactionOperations transactions)
    {
        return new PlanPublishService(plans, workbench, transactions);
    }
    @Bean
    public PlanCandidateLifecycleService apsPlanCandidateLifecycleService(PlanRepository plans,
            PlanWorkbenchService workbench, ApsTransactionOperations transactions)
    {
        return new PlanCandidateLifecycleService(plans, workbench, transactions);
    }
}
