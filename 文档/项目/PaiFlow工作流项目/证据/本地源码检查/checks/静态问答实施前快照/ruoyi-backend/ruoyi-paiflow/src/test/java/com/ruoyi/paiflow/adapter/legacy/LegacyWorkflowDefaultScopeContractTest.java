package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 仅证明实际HTTP绑定与旧信封；真实同域/零写/事务由独立PersistenceTest验证。 */
class LegacyWorkflowDefaultScopeContractTest {
    private LegacyWorkflowScopeFacade facade;
    private MockMvc mvc;
    private static JSONObject value(long revision){return JSONObject.of("id",9,"flowId","default-flow","spaceId",null,"revision",revision,"data","{\"nodes\":[],\"edges\":[],\"futureRoot\":null}");}
    @BeforeEach void setup(){facade=mock(LegacyWorkflowScopeFacade.class);mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowController(facade),new LegacyWorkflowVersionController(facade)).build();}
    @Test void emptyHeaderAndMissingHeaderReachDefaultReadWithoutNumericBindingFailure() throws Exception {
        when(facade.detail(9,"",null)).thenReturn(value(4));when(facade.detail(9,null,null)).thenReturn(value(4));
        for(boolean supplied:List.of(true,false)) {
            var request=get("/paiflow/legacy/workflow").param("id","9");if(supplied)request.header("space-id","");
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.flowId").value("default-flow")).andExpect(jsonPath("$.data.data").value(value(4).getString("data"))).andExpect(header().string("ETag","\"4\""));
        }
        verify(facade).detail(9,"",null);verify(facade).detail(9,null,null);
    }
    @Test void listForwardsExplicitSpaceSeparatelyAndPreservesOriginalPageDataShape() throws Exception {
        when(facade.list("5",6L,2,10,"original",null,2,null)).thenReturn(new WorkflowService.Page(List.of(value(4)),21,2,10,3));
        mvc.perform(get("/paiflow/legacy/workflow/list").header("space-id","5").param("spaceId","6").param("current","2").param("pageSize","10").param("search","original"))
            .andExpect(jsonPath("$.data.totalCount").value(21)).andExpect(jsonPath("$.data.pageData[0].id").value(9)).andExpect(jsonPath("$.data.page").value(2)).andExpect(header().doesNotExist("ETag"));
        verify(facade).list("5",6L,2,10,"original",null,2,null);
    }
    @Test void createAndSaveKeepActualJsonObjectBodyAndOnlyForwardReadRevision() throws Exception {
        when(facade.create(any(),eq(""))).thenReturn(value(0));
        mvc.perform(post("/paiflow/legacy/workflow").header("space-id","").contentType("application/json").content("{\"name\":\"原工作流\",\"spaceId\":null,\"data\":{\"nodes\":[],\"edges\":[],\"futureRoot\":null}}"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("ETag","\"0\""));
        var create=ArgumentCaptor.forClass(JSONObject.class);verify(facade).create(create.capture(),eq(""));assertTrue(create.getValue().containsKey("spaceId"));assertNull(create.getValue().get("spaceId"));assertInstanceOf(java.util.Map.class,create.getValue().get("data"));
        when(facade.save(any(),eq(""),eq(4L))).thenReturn(value(5));
        mvc.perform(put("/paiflow/legacy/workflow").header("space-id","").header("If-Match","\"4\"").contentType("application/json").content("{\"id\":9,\"flowId\":\"default-flow\",\"advancedConfig\":{\"prologue\":{\"enabled\":true}}}"))
            .andExpect(jsonPath("$.data.revision").value(5)).andExpect(header().string("ETag","\"5\""));
        verify(facade).save(argThat(body->body.getLongValue("id")==9 && body.get("advancedConfig") instanceof java.util.Map),eq(""),eq(4L));
    }
    @Test void versionCreateRestoreAndListPreserveWorkflowIdentityAndQuotedDraftCondition() throws Exception {
        var version=JSONObject.of("id",901,"workflowVersionId",901,"workflowId",9,"flowId","default-flow","spaceId",null,"revision",5,"data",value(4).getString("data"));
        when(facade.createVersion(any(),eq(""),eq(4L))).thenReturn(version);
        mvc.perform(post("/paiflow/legacy/workflow/version").header("space-id","").header("If-Match","\"4\"").contentType("application/json").content("{\"flowId\":\"default-flow\",\"name\":\"v1.0\"}"))
            .andExpect(jsonPath("$.data.id").value(901)).andExpect(jsonPath("$.data.workflowId").value(9)).andExpect(header().string("ETag","\"5\""));
        when(facade.restore(any(),eq(""),eq(5L))).thenReturn(value(6));
        mvc.perform(post("/paiflow/legacy/workflow/version/restore").header("space-id","").header("If-Match","\"5\"").contentType("application/json").content("{\"flowId\":\"default-flow\",\"id\":901}"))
            .andExpect(jsonPath("$.data.id").value(9)).andExpect(header().string("ETag","\"6\""));
        when(facade.versions("default-flow","",1,10)).thenReturn(new WorkflowService.VersionPage(List.of(version),1,1,10,1));
        mvc.perform(get("/paiflow/legacy/workflow/version/list").header("space-id","").param("flowId","default-flow")).andExpect(jsonPath("$.data.records[0].workflowVersionId").value(901)).andExpect(jsonPath("$.data.total").value(1)).andExpect(header().doesNotExist("ETag"));
    }
    @Test void deleteForwardsQueryScopeAndMissingCloneHeaderIsOptionalAtTransportOnly() throws Exception {
        mvc.perform(delete("/paiflow/legacy/workflow").header("space-id","").header("If-Match","\"6\"").param("id","9")).andExpect(jsonPath("$.code").value(0));verify(facade).delete(9,"",null,6L);
        when(facade.clone(9,null)).thenReturn(value(0));mvc.perform(get("/paiflow/legacy/workflow/clone").param("id","9")).andExpect(jsonPath("$.data.id").value(9));verify(facade).clone(9,null);
        // 此处mock仅验证可选头绑定；不代表真实无绑定clone可用，PersistenceTest保持503。
    }
    @Test void revisionConflictsAndWeakTagsFailBeforeFacadeAndBodyIsNotRewritten() {
        var controller=new LegacyWorkflowController(facade);var response=new org.springframework.mock.web.MockHttpServletResponse();var request=JSON.parseObject("{\"id\":9,\"revision\":3,\"spaceId\":null}");
        assertEquals(400,assertThrows(ServiceException.class,()->controller.save(request,"","\"4\"",response)).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->controller.save(request,"","W/\"3\"",response)).getCode());verifyNoInteractions(facade);assertEquals(3L,request.getLongValue("revision"));assertTrue(request.containsKey("spaceId"));assertNull(request.get("spaceId"));
    }
}
