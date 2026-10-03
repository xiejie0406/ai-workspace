package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.paiflow.controller.ConfigInfoController;
import com.ruoyi.paiflow.service.ConfigInfoService;
import com.ruoyi.paiflow.service.PublicConfigTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原services真实HTTP→真实Service/Mapper；没有Mock成功目录或Provider调用。 */
class LegacyConfigInfoContractTest {
    private PublicConfigTestFixture fixture;private MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        fixture=new PublicConfigTestFixture();var configs=fixture.service("prod");
        mvc=MockMvcBuilders.standaloneSetup(new LegacyConfigInfoController(configs),new ConfigInfoController(configs)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() throws Exception {if(fixture!=null)fixture.close();}
    @Test void unchangedGlobalStoreIconColorCallsKeepArrayAndIgnoreUnusedCodeParam() throws Exception {
        mvc.perform(get("/paiflow/legacy/config-info/get-list-by-category").param("category","ICON").param("code","1")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data[0].id").isNumber()).andExpect(jsonPath("$.data[0].isValid").value(1));
        String body=mvc.perform(get("/paiflow/legacy/config-info/get-list-by-category").param("category","COLOR").param("code","1")).andReturn().getResponse().getContentAsString();assertEquals(8,JSON.parseObject(body).getJSONArray("data").size());
    }
    @Test void originalDatabaseNodeDescriptionAndPythonPackageCallsKeepObjectAndJsonString() throws Exception {
        for(String[] pair:new String[][]{{"DB_TABLE_TEMPLATE","TB"},{"DB_TABLE_RESERVED_KEYWORD","reserved_keyword"},{"TEMPLATE","node"},{"WORKFLOW","python-dependency"}}) {
            mvc.perform(get("/paiflow/legacy/config-info/get-by-category-and-code").param("category",pair[0]).param("code",pair[1])).andExpect(status().isOk()).andExpect(jsonPath("$.data.category").value(pair[0])).andExpect(jsonPath("$.data.code").value(pair[1])).andExpect(jsonPath("$.data.value").isString());
        }
        var response=mvc.perform(get("/paiflow/legacy/config-info/get-by-category-and-code").param("category","TEMPLATE").param("code","node")).andReturn().getResponse().getContentAsString();assertTrue(JSON.parseArray(JSON.parseObject(response).getJSONObject("data").getString("value")).size()>0);
    }
    @Test void sixOriginalUploadSliceQueriesAndExactCategoryCodeListHaveActualSourceData() throws Exception {
        for(String category:new String[]{"DEFAULT_SLICE_RULES_CBG","CUSTOM_SLICE_RULES_CBG","DEFAULT_SLICE_RULES_AIUI","CUSTOM_SLICE_RULES_AIUI","DEFAULT_SLICE_RULES_SPARK","CUSTOM_SLICE_RULES_SPARK"})mvc.perform(get("/paiflow/legacy/config-info/get-list-by-category").param("category",category)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].category").value(category));
        mvc.perform(get("/paiflow/legacy/config-info/list-by-category-and-code").param("category","TAG").param("code","BOT_TAGS")).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data[0].code").value("BOT_TAGS"));
    }
    @Test void originalTagFlagsAndMissingCategoriesHaveHonestDifferentResponses() throws Exception {
        mvc.perform(get("/paiflow/legacy/config-info/tags").param("flag","unknown")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/paiflow/legacy/config-info/tags").param("flag","tool_v2")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").isNumber());
        mvc.perform(get("/paiflow/legacy/config-info/workflow/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503));
        mvc.perform(get("/paiflow/legacy/config-info/get-list-by-category").param("category","MODEL_SECRET_KEY")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503));
    }
    @Test void nativeUsesAjaxResultAndBothPathsHaveNoWriteRouteOrDoubleWrapping() throws Exception {
        mvc.perform(get("/paiflow/config-info/get-list-by-category").param("category","COLOR")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].category").value("COLOR"));
        // 平台实际全局异常信封为HTTP200/code500，不能用测试专用Advice假造HTTP405/400。
        mvc.perform(post("/paiflow/legacy/config-info/get-list-by-category").param("category","COLOR")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        mvc.perform(get("/paiflow/legacy/config-info/get-by-category-and-code").param("category","COLOR")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
    }
    @Test void originalDateFieldFormatAndCurrentValueAreReadFromMapperByActualHttp() throws Exception {
        fixture.update("UPDATE paiflow_public_config SET value=?,create_time=?,update_time=? WHERE category=? AND code=?","#fixture-current","2026-10-03 08:00:01","2026-10-03 08:00:02","COLOR","1");
        mvc.perform(get("/paiflow/legacy/config-info/get-by-category-and-code").param("category","COLOR").param("code","1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.value").value("#fixture-current")).andExpect(jsonPath("$.data.createTime").value("2026-10-03 08:00:01")).andExpect(jsonPath("$.data.updateTime").value("2026-10-03 08:00:02"));
    }
}
