package com.ruoyi.aps.api.controller;

import java.util.List;
import com.ruoyi.aps.application.order.OrderManagementService;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/aps/v1/reports")
@ConditionalOnProperty(prefix="aps",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="aps.api",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="aps.persistence",name="enabled",havingValue="true")
public class ApsReportDirectoryController {
    private final OrderManagementService orders; private final ApsAuditActorProvider actors;
    public ApsReportDirectoryController(OrderManagementService orders,ApsAuditActorProvider actors){this.orders=orders;this.actors=actors;}
    @GetMapping("/order-candidates") @PreAuthorize("@ss.hasPermi('aps:report:view') or @ss.hasPermi('aps:report:export')")
    public List<OrderOption> candidates(){return orders.listOrders(new ResourceAccessScope(actors.currentActor().userId(),SecurityUtils.isAdmin()||SecurityUtils.hasPermi("aps:scope:manage"))).stream()
            .map(o->new OrderOption(o.id(),o.orderNo(),o.customerName(),o.status())).toList();}
    public record OrderOption(String id,String orderNo,String customerName,String status) { }
}
