package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowService;
import com.ruoyi.paiflow.service.DefaultPersonalSpaceService;
import com.ruoyi.paiflow.service.ResourceAccessService;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.domain.WorkflowRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 对照原React common.ts/flow.ts和flow-manager-function消费者，不将源路由存在当契约闭环。 */
class LegacyWorkflowContractTest {
    private static LegacyWorkflowScopeFacade facade(WorkflowService service) {
        var defaults=mock(DefaultPersonalSpaceService.class);var access=mock(ResourceAccessService.class);
        when(defaults.select(nullable(String.class),isNull(),anyString())).thenAnswer(call->{Long id=DefaultPersonalSpaceService.parse(call.getArgument(0));return new DefaultPersonalSpaceService.Selection(42,id,id==null);});
        when(defaults.existing(any(),anyString())).thenAnswer(call->call.<DefaultPersonalSpaceService.Selection>getArgument(0).spaceId());
        when(access.requireSpace(anyLong(),anyString(),anyInt(),anyBoolean())).thenAnswer(call->{var space=new SpaceRecord();space.setId(call.getArgument(0));space.setKind("personal");return space;});
        return new LegacyWorkflowScopeFacade(service,defaults,access);
    }
    @Test void legacyDetailCreateAndSaveKeepCodeZeroCanvasStringAndResponseRevision() {
        var service=mock(WorkflowService.class);var controller=new LegacyWorkflowController(facade(service));
        JSONObject value=JSON.parseObject("{\"id\":9,\"flowId\":\"owned-flow\",\"revision\":4,\"updateTime\":\"2026-10-03\",\"data\":\"{\\\"nodes\\\":[],\\\"edges\\\":[],\\\"viewport\\\":{\\\"zoom\\\":1}}\"}");
        when(service.detail(9,5L)).thenReturn(value);var response=new MockHttpServletResponse();var result=controller.detail(9,"5",null,response);
        assertEquals(0,result.code());assertInstanceOf(String.class,result.data().get("data"));assertEquals("\"4\"",response.getHeader("ETag"));
        JSONObject request=JSON.parseObject("{\"id\":9,\"flowId\":\"owned-flow\",\"data\":{\"nodes\":[],\"edges\":[]}}");
        when(service.save(9,5L,4L,request)).thenReturn(value);controller.save(request,"5","\"4\"",response);verify(service).save(9,5L,4L,request);
    }
    @Test void listKeepsOriginalPageDataAndVersionListKeepsRecordsTotalCurrentSize() {
        var service=mock(WorkflowService.class);when(service.list(5,2,10,"search",null,2,null)).thenReturn(new WorkflowService.Page(List.of(),23,2,10,3));
        var result=new LegacyWorkflowController(facade(service)).list("5",null,2,10,"search",null,2,null);JSONObject serialized=JSON.parseObject(JSON.toJSONString(result));
        assertEquals(23,serialized.getJSONObject("data").getLongValue("totalCount"));assertNotNull(serialized.getJSONObject("data").getJSONArray("pageData"));
        var row=new WorkflowRecord();row.setId(9L);
        when(service.requireFlow("owned-flow",5L,"paiflow:workflow:query",3,false)).thenReturn(row);
        when(service.versions(9,5L,2,10)).thenReturn(new WorkflowService.VersionPage(List.of(),23,2,10,3));
        var versionResult=new LegacyWorkflowVersionController(facade(service)).list("owned-flow",2,10,"5");
        var versionPage=JSON.parseObject(JSON.toJSONString(versionResult)).getJSONObject("data");
        assertEquals(23,versionPage.getLongValue("total"));assertEquals(2,versionPage.getLongValue("current"));assertEquals(10,versionPage.getLongValue("size"));assertNotNull(versionPage.getJSONArray("records"));
    }
    @Test void bodyAndHeaderScopeCannotDisagreeAndWeakOrWildcardEtagsAreRejected() {
        assertThrows(ServiceException.class,()->LegacyWorkflowController.scope(5L,6L));assertThrows(ServiceException.class,()->LegacyWorkflowController.scope(null,null));
        assertEquals(7L,LegacyWorkflowController.revision(7L,"\"7\""));assertThrows(ServiceException.class,()->LegacyWorkflowController.revision(6L,"\"7\""));
        for(String header:new String[]{"*","W/\"7\"","7","\"9223372036854775808\""})assertThrows(ServiceException.class,()->LegacyWorkflowController.revision(null,header));
    }
}
