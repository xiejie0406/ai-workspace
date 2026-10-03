package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.controller.ModelController;
import com.ruoyi.paiflow.service.ModelService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原React路由/请求和响应形状；持久化/授权/Probe证据由ModelPersistenceTest独立验证。 */
class LegacyModelContractTest {
    private ModelService service;private MockMvc mvc;
    @BeforeEach void setup(){service=mock(ModelService.class);mvc=MockMvcBuilders.standaloneSetup(new LegacyModelController(service),new ModelController(service)).build();}
    private static JSONObject value(){return JSONObject.of("id",8,"modelId",8,"revision",3,"spaceId",4,"name","fixture","domain","fixture-model","apiKey","env:FIXTURE_REFERENCE","config","[]","enabled",false,"state","DRAFT","status",0,"categoryTree",List.of());}
    @Test void originalNewThirdPartyBodyIgnoresForgedUidAndSuccessIsStringWithQualifiedEtag() throws Exception {
        when(service.validateLegacy(eq(4L),isNull(),any(),eq(false))).thenReturn(value());mvc.perform(post("/paiflow/legacy/api/model").header("space-id","4").contentType("application/json").content("{\"uid\":\"forged\",\"modelName\":\"fixture\",\"endpoint\":\"https://provider.invalid/v1\",\"apiKey\":\"env:FIXTURE_REFERENCE\",\"apiKeyMasked\":true,\"config\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value("Model validation passed")).andExpect(header().string("ETag","\"paiflow-model-8-3\""));verify(service).validateLegacy(4L,null,JSONObject.of("modelName","fixture","endpoint","https://provider.invalid/v1","apiKey","env:FIXTURE_REFERENCE","apiKeyMasked",true,"config",List.of()),false);
    }
    @Test void originalDetailKeepsModelInfoStringConfigAndReferenceAndReturnsActualReadCondition() throws Exception {
        when(service.legacyDetail(8L,4L,0)).thenReturn(value());mvc.perform(get("/paiflow/legacy/api/model/detail").header("space-id","4").param("modelId","8").param("llmSource","0"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.config").value("[]")).andExpect(jsonPath("$.data.apiKey").value("env:FIXTURE_REFERENCE")).andExpect(jsonPath("$.data.revision").value(3)).andExpect(jsonPath("$.data.status").value(0)).andExpect(header().string("ETag","\"paiflow-model-8-3\""));
    }
    @Test void originalPersonalPageShapeHasReadTicketsOnRowsAndNoVersionEtag() throws Exception {
        when(service.legacyList(eq(4L),any())).thenReturn(new ModelService.Page(List.of(value()),21,10,2,3));mvc.perform(post("/paiflow/legacy/api/model/list").header("space-id","4").contentType("application/json").content("{\"type\":2,\"filter\":0,\"page\":2,\"pageSize\":10}"))
            .andExpect(jsonPath("$.data.records[0].id").value(8)).andExpect(jsonPath("$.data.records[0].revision").value(3)).andExpect(jsonPath("$.data.records[0].spaceId").value(4)).andExpect(jsonPath("$.data.total").value(21)).andExpect(jsonPath("$.data.size").value(10)).andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.data.pages").value(3)).andExpect(header().doesNotExist("ETag"));
    }
    @Test void existingOriginalPostUsesBodyIdAndExplicitPriorConditionWithoutReadingNewState() throws Exception {
        when(service.validateLegacy(eq(4L),eq(2L),any(),eq(false))).thenReturn(value());mvc.perform(post("/paiflow/legacy/api/model").header("space-id","4").header("If-Match","\"paiflow-model-8-2\"").contentType("application/json").content("{\"id\":8,\"apiKeyMasked\":false,\"apiKey\":\"opaque-rsa-envelope\"}"))
            .andExpect(jsonPath("$.data").value("Model validation passed")).andExpect(header().string("ETag","\"paiflow-model-8-3\""));verify(service).validateLegacy(4L,2L,JSONObject.of("id",8,"apiKeyMasked",false,"apiKey","opaque-rsa-envelope"),false);verify(service,never()).detail(anyLong(),any());
    }
    @Test void originalGetOnOffAndDeleteAllRequireQualifiedCardConditionAndPreserveBooleanData() throws Exception {
        when(service.switchEnabled(8L,4L,2L,false)).thenReturn(value());mvc.perform(get("/paiflow/legacy/api/model/off").header("space-id","4").header("If-Match","\"paiflow-model-8-2\"").param("modelId","8").param("llmSource","0"))
            .andExpect(jsonPath("$.data").value(true)).andExpect(header().string("ETag","\"paiflow-model-8-3\""));var on=value();on.put("enabled",true);when(service.switchEnabled(8L,4L,3L,true)).thenReturn(on);mvc.perform(get("/paiflow/legacy/api/model/on").header("space-id","4").param("modelId","8").param("llmSource","0").param("revision","3")).andExpect(jsonPath("$.data").value(true));
        mvc.perform(get("/paiflow/legacy/api/model/delete").header("space-id","4").header("If-Match","\"paiflow-model-8-3\"").param("modelId","8")).andExpect(jsonPath("$.data").value(true));verify(service).delete(8L,4L,3L);
    }
    @Test void originalLocalDeploymentReturnsTrueOnlyAfterServiceReceiptAndHasBoundConditions() throws Exception {
        when(service.validateLegacy(eq(4L),eq(2L),any(),eq(true))).thenReturn(value());mvc.perform(post("/paiflow/legacy/api/model/local-model").header("space-id","4").header("If-Match","\"paiflow-model-8-2\"").contentType("application/json").content("{\"id\":8,\"modelName\":\"local\",\"domain\":\"fixture\",\"modelPath\":\"approved-artifact\",\"acceleratorCount\":2,\"replicaCount\":1}"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value(true));verify(service).validateLegacy(eq(4L),eq(2L),argThat(body->body.getIntValue("acceleratorCount")==2 && "approved-artifact".equals(body.getString("modelPath"))),eq(true));
    }
    @Test void qualifiedConditionsRejectCrossIdWeakWildcardOverflowAndConflictingBodyBeforeAnyService() {
        assertEquals(3L,ModelController.condition(8,3,"\"paiflow-model-8-3\""));for(String tag:List.of("*","W/\"paiflow-model-8-3\"","\"3\"","\"paiflow-model-8-9223372036854775808\""))assertThrows(ServiceException.class,()->ModelController.condition(8,null,tag));assertEquals(409,assertThrows(ServiceException.class,()->ModelController.condition(8,null,"\"paiflow-model-9-3\"")).getCode());assertEquals(409,assertThrows(ServiceException.class,()->ModelController.condition(8,null,null)).getCode());assertEquals(400,assertThrows(ServiceException.class,()->ModelController.condition(8,2,"\"paiflow-model-8-3\"")).getCode());
        var controller=new LegacyModelController(service);var response=mock(HttpServletResponse.class);assertEquals(409,assertThrows(ServiceException.class,()->controller.validate(JSONObject.of("id",8),"4",null,response)).getCode());assertEquals(400,assertThrows(ServiceException.class,()->controller.validate(JSONObject.of("spaceId",5),"4",null,response)).getCode());assertEquals(400,assertThrows(ServiceException.class,()->controller.validate(new JSONObject(),"4","\"paiflow-model-8-0\"",response)).getCode());verifyNoInteractions(service);
    }
    @Test void nativeDraftUsesAjaxResultAndImmutableVersionReadNeverProducesCurrentCardEtag() throws Exception {
        when(service.createDraft(eq(4L),any())).thenReturn(value());mvc.perform(post("/paiflow/models").contentType("application/json").content("{\"spaceId\":4,\"modelName\":\"fixture\"}"))
            .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.state").value("DRAFT")).andExpect(header().string("ETag","\"paiflow-model-8-3\""));when(service.versionDetail(10L,4L)).thenReturn(JSONObject.of("modelVersionId",10,"modelId",8,"revision",1));mvc.perform(get("/paiflow/models/versions/10").param("spaceId","4")).andExpect(jsonPath("$.data.modelVersionId").value(10)).andExpect(header().doesNotExist("ETag"));
    }
    @Test void nativeMutationCannotUseAnotherModelsEchoAndCategoryKeepsArrayShape() throws Exception {
        var controller=new ModelController(service);assertEquals(400,assertThrows(ServiceException.class,()->controller.validate(8,JSONObject.of("id",9,"type",1,"spaceId",4),"\"paiflow-model-8-3\"",mock(HttpServletResponse.class))).getCode());assertThrows(ServiceException.class,()->controller.enabled(8,JSONObject.of("enabled","true"),"\"paiflow-model-8-3\"",mock(HttpServletResponse.class)));verifyNoInteractions(service);when(service.categoryTree(4L)).thenReturn(List.of(JSONObject.of("id",75,"key","contextLengthTag","children",List.of())));mvc.perform(get("/paiflow/legacy/api/model/category-tree").header("space-id","4")).andExpect(jsonPath("$.data[0].id").value(75)).andExpect(jsonPath("$.data[0].children").isEmpty());
    }
    @Test void unavailableRsaAndCatalogNeverBecomeAKeyOrEmptySuccess() {
        doThrow(new ServiceException("缺真实端口",503)).when(service).unavailable(4L,"原RSA/模型部署目录/下架绑定端口");assertEquals(503,assertThrows(ServiceException.class,()->new LegacyModelController(service).unavailable("4")).getCode());
    }
}
