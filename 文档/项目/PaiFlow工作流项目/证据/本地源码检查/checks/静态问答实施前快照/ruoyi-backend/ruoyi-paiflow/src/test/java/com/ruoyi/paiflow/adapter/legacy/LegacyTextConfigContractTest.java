package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.controller.TextConfigController;
import com.ruoyi.paiflow.service.TextConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原React列表/保存/删除真实HTTP绑定，业务权限与事务由实际持久化测试另证。 */
class LegacyTextConfigContractTest {
    private TextConfigService service;private MockMvc mvc;
    @BeforeEach void setup() { service=mock(TextConfigService.class);mvc=MockMvcBuilders.standaloneSetup(new LegacyTextConfigController(service),new TextConfigController(service)).build(); }
    @Test void originalListHasNumericSystemUidAndExactLiteralSeparatorAndComment() throws Exception {
        when(service.list()).thenReturn(List.of(new TextConfigService.View(5L,-1L,"\n","换行",false,null,null,0),new TextConfigService.View(8L,"ruoyi:42","\\n","\\n",false,null,null,0)));
        mvc.perform(get("/paiflow/legacy/textNode/config/list")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].uid").value(-1)).andExpect(jsonPath("$.data[0].separator").value("\n")).andExpect(jsonPath("$.data[0].comment").value("换行")).andExpect(jsonPath("$.data[1].uid").value("ruoyi:42")).andExpect(jsonPath("$.data[1].separator").value("\\n"));
    }
    @Test void originalTextHandleSaveBodyIsUnchangedAndResponseStaysBoolean() throws Exception {
        mvc.perform(post("/paiflow/legacy/textNode/config/save").contentType("application/json").content("{\"separator\":\" :: \"}")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value(true));verify(service).create(JSONObject.of("separator"," :: "));
    }
    @Test void originalQueryDeleteReturnsIntegerAndNeverInventsReadRevision() throws Exception {
        mvc.perform(get("/paiflow/legacy/textNode/config/delete").param("id","8")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(1));verify(service).delete(8,null);
        mvc.perform(get("/paiflow/legacy/textNode/config/delete").param("id","9").param("revision","4")).andExpect(status().isOk());verify(service).delete(9,4L);
    }
    @Test void originalBackendUpdateUsesBodyIdAndNativeListUsesAjaxResultWithoutDoubleWrapping() throws Exception {
        mvc.perform(post("/paiflow/legacy/textNode/config/update").contentType("application/json").content("{\"id\":\"8\",\"separator\":\"new\",\"revision\":3}")).andExpect(jsonPath("$.data").value(true));verify(service).update(8,JSONObject.of("id","8","separator","new","revision",3));
        when(service.list()).thenReturn(List.of());mvc.perform(get("/paiflow/text-configs")).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.rows").isArray()).andExpect(jsonPath("$.total").value(0));
    }
    @Test void invalidLegacyBodyIdentifierRejectsBeforeAnyWriteAndPermissionExceptionsRemainFailures() {
        var controller=new LegacyTextConfigController(service);
        for(Object id:new Object[]{null,-1,true,1.5,"1 OR 1=1","9223372036854775808"})assertThrows(ServiceException.class,()->controller.update(JSONObject.of("id",id,"separator","x")));verifyNoInteractions(service);
        when(service.list()).thenThrow(new ServiceException("没有权限",403));assertEquals(403,assertThrows(ServiceException.class,controller::list).getCode());
    }
}
