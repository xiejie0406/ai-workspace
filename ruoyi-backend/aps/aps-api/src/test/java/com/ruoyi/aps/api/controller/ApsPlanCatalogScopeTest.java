package com.ruoyi.aps.api.controller;

import java.util.List;
import com.ruoyi.aps.application.planning.PlanRepository;
import com.ruoyi.aps.application.planning.PlanWorkbenchService;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.application.resource.ResourceManagementService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApsPlanCatalogScopeTest
{
    @Test void emptyWorkshopScopeNeverQueriesPlanStorage()
    {
        PlanRepository plans = mock(PlanRepository.class);
        ResourceManagementService resources = mock(ResourceManagementService.class);
        ResourceAccessScope scope = new ResourceAccessScope("300", false);
        when(resources.listWorkshops(scope)).thenReturn(List.of());
        assertThat(new PlanWorkbenchService(plans, resources).list(scope)).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test void administratorReadsWithoutExpandingOrChangingWorkshopPermissions()
    {
        PlanRepository plans = mock(PlanRepository.class);
        ResourceManagementService resources = mock(ResourceManagementService.class);
        ResourceAccessScope scope = new ResourceAccessScope("1", true);
        when(plans.listVisiblePlans(List.of(), true)).thenReturn(List.of());
        assertThat(new PlanWorkbenchService(plans, resources).list(scope)).isEmpty();
        verify(plans).listVisiblePlans(List.of(), true);
        verifyNoInteractions(resources);
    }
}
