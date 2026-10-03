package com.ruoyi.aps.infrastructure.mysql.routing;

import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.order.OrderManagementService;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.planning.SolverInputCompiler;
import com.ruoyi.aps.application.planning.PlanRepository;
import com.ruoyi.aps.application.execution.ExecutionRepository;
import com.ruoyi.aps.application.execution.QuantityRepository;
import com.ruoyi.aps.application.resource.ResourceManagementService;
import com.ruoyi.aps.application.routing.RoutingManagementService;
import com.ruoyi.aps.application.routing.RoutingRepository;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsOrderMapper;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsRoutingMapper;
import com.ruoyi.aps.infrastructure.mysql.order.MybatisOrderRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.datasource", name = "enabled", havingValue = "true")
public class ApsManufacturingPersistenceConfiguration
{
    @Bean public RoutingRepository apsRoutingRepository(ApsRoutingMapper mapper) { return new MybatisRoutingRepository(mapper); }
    @Bean public OrderRepository apsOrderRepository(ApsOrderMapper mapper) { return new MybatisOrderRepository(mapper); }
    @Bean public RoutingManagementService apsRoutingManagementService(RoutingRepository repository, ApsTransactionOperations transactions) { return new RoutingManagementService(repository, transactions); }
    @Bean public OrderManagementService apsOrderManagementService(OrderRepository orders, RoutingRepository routings, ApsTransactionOperations transactions) { return new OrderManagementService(orders, routings, transactions); }
    @Bean public SolverInputCompiler apsSolverInputCompiler(ResourceManagementService resources, OrderRepository orders,
            RoutingRepository routings, PlanRepository plans, ExecutionRepository executions,
            QuantityRepository quantities,@org.springframework.beans.factory.annotation.Value("${aps.site.code:}") String siteCode,
            @org.springframework.beans.factory.annotation.Value("${aps.site.zone-id:Asia/Shanghai}") String zoneId)
    {
        return new SolverInputCompiler(resources, orders, routings, plans, executions, quantities,siteCode,zoneId);
    }
}
