package com.ruoyi.aps.infrastructure.mysql;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import com.ruoyi.aps.application.foundation.*;
import com.ruoyi.aps.application.order.*;
import com.ruoyi.aps.application.order.OrderCatalog.*;
import com.ruoyi.aps.application.routing.*;
import com.ruoyi.aps.application.routing.RoutingCatalog.*;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.application.resource.ResourceManagementService;
import com.ruoyi.aps.application.planning.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无数据库的规则回归；不把模拟仓储成功解释为原库迁移或联机通过。 */
class ProductImplementationTest {
    final OrderRepository orders=mock(OrderRepository.class);
    final RoutingRepository routes=mock(RoutingRepository.class);
    final ApsTransactionOperations transactions=new ApsTransactionOperations(){public <T>T required(Supplier<T> action){return action.get();}};
    final OrderManagementService service=new OrderManagementService(orders,routes,transactions);
    final ProductionOrder draft=order(3);
    private ProductionOrder order(long version){return new ProductionOrder("o1","SO-1","LOCAL",null,"C1","客户一",50,null,null,"DRAFT",null,
            List.of(new OrderLine("l1","o1",1,"item1","route1",BigDecimal.TEN,"PCS",null,null,"DRAFT",List.of(),List.of(),2)),version);}
    @Test void staleOrExpandedDraftCannotReplaceChildren(){
        when(orders.findOrderForUpdate("o1")).thenReturn(Optional.of(draft));
        assertThatThrownBy(()->service.updateDraft("o1",2,draft,"u")).isInstanceOf(ApsBusinessException.class).hasMessageContaining("修改");
        when(orders.listLots("o1")).thenReturn(List.of(new ProductionLot("b1","l1","item1","route1",null,"B1",LotType.NORMAL,BigDecimal.TEN,"PCS",null,"DRAFT",0)));
        assertThatThrownBy(()->service.updateDraft("o1",3,draft,"u")).isInstanceOf(ApsBusinessException.class).hasMessageContaining("未展开");
        verify(orders,never()).deleteDraftLines(anyString());verify(orders,never()).updateDraftOrder(any(),anyString());verifyNoInteractions(routes);
    }
    @Test void draftUpdateAdvancesHeadAndLineVersionsAfterParentLock(){
        when(orders.findOrderForUpdate("o1")).thenReturn(Optional.of(draft));
        when(routes.findItem("item1")).thenReturn(Optional.of(new Item("item1","P1","产品一",ItemType.PRODUCT,null,"PCS","ACTIVE",null,0)));
        RouteVersion route=new RouteVersion("route1","item1","R1","V1","ACTIVE",null,null,null,"u",Instant.EPOCH,1);
        when(routes.findRoute("route1")).thenReturn(Optional.of(route));
        when(routes.getRouteGraph("route1")).thenReturn(new RouteGraph(route,List.of(),List.of()));
        when(orders.updateDraftOrder(any(),eq("u"))).thenReturn(1);
        var saved=service.updateDraft("o1",3,draft,"u");
        assertThat(saved.rowVersion()).isEqualTo(4);assertThat(saved.lines().get(0).rowVersion()).isEqualTo(3);
        assertThat(saved.lines().get(0).id()).isEqualTo("l1");
        var sequence=inOrder(orders);sequence.verify(orders).findOrderForUpdate("o1");sequence.verify(orders).updateDraftOrder(any(),eq("u"));sequence.verify(orders).deleteDraftLines("o1");sequence.verify(orders).insertOrderLine(any(),eq("u"));
    }
    @Test void emptyWorkshopScopeDoesNotReturnUnassignedOrders(){
        var hidden=new ProductionOrder("00000000-0000-4000-8000-000000000002",draft.orderNo(),draft.sourceSystem(),draft.externalId(),draft.customerCode(),draft.customerName(),draft.priority(),draft.promisedAt(),draft.earliestStartAt(),draft.status(),draft.remark(),draft.lines(),draft.rowVersion());
        when(orders.listOrders()).thenReturn(List.of(hidden));
        assertThat(service.listOrders(new ResourceAccessScope("ordinary",false))).isEmpty();
        assertThatThrownBy(()->service.requireOrderScope(new ResourceAccessScope("ordinary",false),"o1")).isInstanceOf(ApsBusinessException.class);
    }
    @Test void readinessCapturesServerTimeAndRejectsUnknownScopeWithoutPlanWrites(){
        var plans=mock(PlanRepository.class);var resources=mock(ResourceManagementService.class);
        var compiler=new SolverInputCompiler(resources,orders,routes,plans);
        var hidden=new ProductionOrder("00000000-0000-4000-8000-000000000002",draft.orderNo(),draft.sourceSystem(),draft.externalId(),draft.customerCode(),draft.customerName(),draft.priority(),draft.promisedAt(),draft.earliestStartAt(),draft.status(),draft.remark(),draft.lines(),draft.rowVersion());
        when(orders.listOrders()).thenReturn(List.of(hidden));
        Instant now=Instant.parse("2026-10-01T00:00:00Z");
        var input=new SolverInputCompiler.CompileRequest(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),"S1",List.of("00000000-0000-4000-8000-000000000001"),List.of("00000000-0000-4000-8000-000000000002"),Instant.EPOCH,0,0,now.plusSeconds(60),now.plusSeconds(3600),now.plusSeconds(3600),now,60,"Asia/Shanghai","aps-cpsat-v1",30,1,1,0,0);
        var result=new PlanReadinessService(compiler,Clock.fixed(now,ZoneOffset.UTC)).check(new ResourceAccessScope("ordinary",false),input);
        assertThat(result.ready()).isFalse();assertThat(result.checkedAt()).isEqualTo(now);assertThat(result.issues()).allMatch(i->i.message().equals("计划范围中的订单不存在"));
        verifyNoInteractions(plans,resources,routes);verify(orders,never()).insertOrder(any(),anyString());
    }
    @Test void staleGraphCannotReplaceRouteChildren(){
        when(routes.findRoute("r1")).thenReturn(Optional.of(new RouteVersion("r1","item1","R1","V1","DRAFT",null,null,null,null,null,4)));
        var routing=new RoutingManagementService(routes,transactions);
        assertThatThrownBy(()->routing.saveGraph("r1",3L,new RouteGraphDraft(List.of(),List.of()),"u"))
                .isInstanceOf(ApsBusinessException.class).hasMessageContaining("修改");
        verify(routes,never()).replaceRouteGraph(anyString(),anyList(),anyList(),anyString());
        verify(routes,never()).advanceDraftRouteRevision(anyString(),anyLong(),anyString());
    }
    @Test void reportMemberBalanceCannotExceedRunRemainingQuantity(){
        var lifecycle=mock(com.ruoyi.aps.application.execution.ExecutionLifecycleService.class);
        var executions=mock(com.ruoyi.aps.application.execution.ExecutionRepository.class);
        var quantities=mock(com.ruoyi.aps.application.execution.QuantityRepository.class);
        var run=new com.ruoyi.aps.application.execution.ExecutionCatalog.ExecutionRun("run","plan","job",1,com.ruoyi.aps.domain.execution.ExecutionRunStatus.RUNNING,new BigDecimal("8"),"PCS",Instant.EPOCH,null,null,"request",0);
        when(lifecycle.detail(any(),eq("run"))).thenReturn(new com.ruoyi.aps.application.execution.ExecutionLifecycleService.ExecutionDetail(run,List.of(),0));
        when(executions.listReportMembers("job")).thenReturn(List.of(new com.ruoyi.aps.application.execution.ExecutionCatalog.ReportMember("m1",1,"task","任务",BigDecimal.TEN,"PCS")));
        when(quantities.effectiveProcessedForRun("run")).thenReturn(new BigDecimal("6"));
        when(quantities.effectiveProcessedForMember("run","m1")).thenReturn(BigDecimal.ONE);
        var directory=new com.ruoyi.aps.application.execution.ExecutionDirectoryService(lifecycle,executions,quantities,mock(ResourceManagementService.class),orders,mock(PlanWorkbenchService.class));
        assertThat(directory.members(new ResourceAccessScope("u",false),"run").get(0).remainingQty()).isEqualByComparingTo("2");
    }
}
