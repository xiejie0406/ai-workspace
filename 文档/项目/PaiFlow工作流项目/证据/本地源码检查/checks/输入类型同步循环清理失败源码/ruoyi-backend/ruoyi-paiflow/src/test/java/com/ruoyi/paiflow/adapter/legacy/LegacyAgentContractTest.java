package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.AgentService;
import com.ruoyi.paiflow.service.AgentTemplateCatalog;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 执行HTTP方法/查询与form/JSON绑定，配合实际持久化测试；不计真实页面/Provider通过。 */
class LegacyAgentContractTest {
    private AgentService agents;private MockMvc mvc;
    private JSONObject value() { return JSONObject.of("id",9,"botId",9,"revision",4,"workflowId",12,"maasId",12,"flowId","fixture-flow","workflowRevision",7,"publicationStatus","draft_unpublished"); }
    @BeforeEach void setup() { agents=mock(AgentService.class);mvc=MockMvcBuilders.standaloneSetup(new LegacyBotController(agents,new AgentTemplateCatalog()),new LegacyMyBotController(agents),new LegacyAgentWorkflowController(agents)).build(); }
    @Test void detailUsesOriginalPostQueryAndExposesTwoQuotedConditions() throws Exception {
        when(agents.legacyDetail(9,"5")).thenReturn(value());mvc.perform(post("/paiflow/legacy/my-bot/bot-detail").param("botId","9").header("space-id","5")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.botId").value(9)).andExpect(jsonPath("$.data.id").value(9)).andExpect(jsonPath("$.data.maasId").value(12)).andExpect(jsonPath("$.data.workflowId").value(12)).andExpect(header().string("ETag","\"4\"")).andExpect(header().string("X-Workflow-Revision","\"7\""));
    }
    @Test void originalMakeCreationBodyUsesWorkflowVersionAndCompatibleObjectResponse() throws Exception {
        when(agents.legacyCreate(eq("5"),eq(3),any())).thenReturn(value());String request="{\"name\":\"自定义fixture\",\"botType\":0,\"avatar\":\"/workflow/workflow.png\",\"botDesc\":\"\",\"botId\":null,\"inputExample\":[\"\",\"\",\"\"]}";
        mvc.perform(post("/paiflow/legacy/workflow/base-save").header("space-id","5").contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.data.maasId").value(12)).andExpect(header().string("ETag","\"4\""));var capture=ArgumentCaptor.forClass(JSONObject.class);verify(agents).legacyCreate(eq("5"),eq(3),capture.capture());assertEquals(JSON.parseObject(request),capture.getValue());
    }
    @Test void originalConfigStringBotIdBodyAndHeadersSupplyBothReadRevisions() throws Exception {
        when(agents.legacyUpdate(eq(9L),eq("5"),eq(4L),eq(7L),any())).thenReturn(value());
        String request="{\"botId\":\"9\",\"name\":\"fixture\",\"botType\":1,\"botDesc\":\"desc\",\"supportContext\":1,\"supportSystem\":0,\"promptType\":0,\"inputExample\":[\"a\",\"\",\"\"],\"maasDatasetList\":[],\"avatar\":\"/fixture.png\",\"vcnCn\":\"fixture-cn\",\"vcnEn\":\"fixture-en\",\"vcnSpeed\":50,\"isSentence\":0,\"openedTool\":\"\",\"prologue\":\"欢迎\",\"model\":\"generalv3.5\",\"modelId\":null,\"isCustom\":false,\"prompt\":\"fixture\",\"promptStructList\":[]}";
        mvc.perform(post("/paiflow/legacy/bot/update").header("space-id","5").header("If-Match","\"4\"").header("X-Workflow-Revision","\"7\"").contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(4));var capture=ArgumentCaptor.forClass(JSONObject.class);verify(agents).legacyUpdate(eq(9L),eq("5"),eq(4L),eq(7L),capture.capture());assertEquals(JSON.parseObject(request),capture.getValue());
    }
    @Test void ordinaryCreateReturnsObjectNeededByResBotIdAndDoesNotUseEchoedOwnerOrPublication() throws Exception {
        when(agents.legacyCreate(eq("5"),eq(1),any())).thenReturn(JSONObject.of("id",9,"botId",9,"revision",0));mvc.perform(post("/paiflow/legacy/bot/create").header("space-id","5").contentType("application/json").content("{\"name\":\"fixture\",\"uid\":\"forged\",\"ownerUserId\":777,\"botStatus\":1,\"releaseType\":[\"MARKET\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.botId").value(9)).andExpect(header().string("ETag","\"0\""));var capture=ArgumentCaptor.forClass(JSONObject.class);verify(agents).legacyCreate(eq("5"),eq(1),capture.capture());for(String key:List.of("uid","ownerUserId","botStatus","releaseType"))assertFalse(capture.getValue().containsKey(key));
    }
    @Test void originalListKeepsPageDataAndTotalCountAndCopyKeepsVoid() throws Exception {
        when(agents.legacyList(eq("5"),any())).thenReturn(new AgentService.Page(List.of(value()),23,20,2,2));mvc.perform(post("/paiflow/legacy/my-bot/list").header("space-id","5").contentType("application/json").content("{\"pageIndex\":2,\"pageSize\":20,\"version\":3,\"botStatus\":null,\"sort\":\"updateTime\",\"searchValue\":\"fixture\"}")).andExpect(jsonPath("$.data.pageData[0].botId").value(9)).andExpect(jsonPath("$.data.totalCount").value(23)).andExpect(jsonPath("$.data.page").value(2));
        when(agents.legacyCopy(9,"5")).thenReturn(value());var copied=mvc.perform(post("/paiflow/legacy/workflow/copy-bot").header("space-id","5").contentType("application/x-www-form-urlencoded").param("botId","9")).andExpect(status().isOk()).andReturn();assertNull(JSON.parseObject(copied.getResponse().getContentAsString()).get("data"));verify(agents).legacyCopy(9,"5");
    }
    @Test void originalDeleteQueryAcceptsConditionsAndNoReadConditionIsNotInvented() throws Exception {
        mvc.perform(post("/paiflow/legacy/my-bot/delete").param("botId","9").header("space-id","5").header("If-Match","\"4\"").header("X-Workflow-Revision","\"7\"")).andExpect(jsonPath("$.data").value(true));verify(agents).legacyDelete(9,"5",4L,7L);
        mvc.perform(post("/paiflow/legacy/my-bot/delete").param("botId","10").header("space-id","5")).andExpect(status().isOk());verify(agents).legacyDelete(10,"5",null,null);
    }
    @Test void contradictoryWeakWildcardAndNegativeConditionsRejectBeforeCallingService() {
        var controller=new LegacyBotController(agents,new AgentTemplateCatalog());var body=JSONObject.of("botId",9,"revision",4,"workflowRevision",7);
        for(String condition:new String[]{"*","W/\"4\"","4","\"-1\"","\"9223372036854775808\"","\"3\""})assertThrows(ServiceException.class,()->controller.update(body,"5",condition,"\"7\"",new MockHttpServletResponse()));assertThrows(ServiceException.class,()->controller.update(body,"5","\"4\"","\"8\"",new MockHttpServletResponse()));verifyNoInteractions(agents);
    }
    @Test void actualSqlPromptSeedsKeepOriginalListShapeAndWorkflowTemplatesRemainUnavailable() throws Exception {
        var catalog=new AgentTemplateCatalog();assertEquals(4,catalog.templates("zh",null).size());assertEquals(4,catalog.templates("en",null).size());assertEquals(7,catalog.types("zh").size());var template=catalog.templates("zh",1623).get(0);assertTrue(template.get("inputExampleList") instanceof com.alibaba.fastjson2.JSONArray);assertTrue(template.get("promptStructList") instanceof com.alibaba.fastjson2.JSONArray);template.put("botName","caller mutation");assertNotEquals("caller mutation",catalog.templates("zh",1623).get(0).getString("botName"));
        mvc.perform(get("/paiflow/legacy/bot/template").param("botId","1623").header("space-id","5")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1623));verify(agents).legacyCatalogAccess("5");
        mvc.perform(get("/paiflow/legacy/bot/template").header("space-id","5").header("Accept-Language","en-US,en;q=0.9")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].language").value("en"));assertEquals(4,catalog.templates("en-US,en;q=0.9",null).size());
        var controller=new LegacyAgentWorkflowController(agents);doThrow(new ServiceException("未配置",503)).when(agents).legacyUnavailable(eq("5"),eq("paiflow:agent:query"),anyString());assertThrows(ServiceException.class,()->controller.templateGroup("5"));
    }
    @Test void emptyAndAbsentHeadersReachDefaultCreationWithoutInventingAUserSpaceId() throws Exception {
        var response=JSONObject.of("id",9,"botId",9,"revision",0,"spaceId",null);when(agents.legacyCreate(isNull(),eq(1),any())).thenReturn(response);when(agents.legacyCreate(eq(""),eq(1),any())).thenReturn(response);
        mvc.perform(post("/paiflow/legacy/bot/create").contentType("application/json").content("{\"name\":\"默认\",\"spaceId\":null}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.spaceId").isEmpty());mvc.perform(post("/paiflow/legacy/bot/create").header("space-id","").contentType("application/json").content("{\"name\":\"默认\",\"spaceId\":\"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.spaceId").isEmpty());verify(agents).legacyCreate(isNull(),eq(1),any());verify(agents).legacyCreate(eq(""),eq(1),any());
    }
    @Test void defaultWorkflowUpdateStillBindsOriginalStringIdAndBothRevisionHeaders() throws Exception {
        var response=value();response.put("spaceId",null);when(agents.legacyUpdate(eq(9L),isNull(),eq(4L),eq(7L),any())).thenReturn(response);mvc.perform(post("/paiflow/legacy/workflow/base-save").header("If-Match","\"4\"").header("X-Workflow-Revision","\"7\"").contentType("application/json").content("{\"botId\":\"9\",\"name\":\"默认修改\",\"spaceId\":\"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.spaceId").isEmpty()).andExpect(header().string("X-Workflow-Revision","\"7\""));var capture=ArgumentCaptor.forClass(JSONObject.class);verify(agents).legacyUpdate(eq(9L),isNull(),eq(4L),eq(7L),capture.capture());assertEquals("",capture.getValue().get("spaceId"));
    }
    @Test void absentDefaultListDetailAndCopyKeepOriginalPostAndFormContracts() throws Exception {
        when(agents.legacyList(isNull(),any())).thenReturn(new AgentService.Page(List.of(),0,15,1,0));mvc.perform(post("/paiflow/legacy/my-bot/list").contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0)).andExpect(jsonPath("$.data.pageData").isEmpty());var response=value();response.put("spaceId",null);when(agents.legacyDetail(9,null)).thenReturn(response);mvc.perform(post("/paiflow/legacy/my-bot/bot-detail").param("botId","9")).andExpect(jsonPath("$.data.spaceId").isEmpty());when(agents.legacyCopy(9,"")).thenReturn(response);mvc.perform(post("/paiflow/legacy/workflow/copy-bot").header("space-id","").contentType("application/x-www-form-urlencoded").param("botId","9")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));verify(agents).legacyCopy(9,"");
    }
    @Test void inputTypesReturnsActualTwelveFieldArrayAndAgentBoundReceiptWithoutWorkflowCondition() throws Exception {
        var fields=com.alibaba.fastjson2.JSONArray.of(JSONObject.of("id","fixture","name","field","schema",JSONObject.of("type","string"),"futureRoot","mustDrop"));when(agents.legacyInputTypes(9,"5")).thenReturn(new AgentService.InputTypesResult(fields,9,5,12,7));
        var result=mvc.perform(post("/paiflow/legacy/workflow/bot/get-inputs-type").header("space-id","5").contentType("application/json").content("{\"botId\":9}")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].schema.type").value("string")).andExpect(header().string("ETag","\"5\"")).andExpect(header().string("X-PaiFlow-Agent-Id","9")).andReturn().getResponse();var parsed=JSON.parseObject(result.getContentAsString());assertTrue(parsed.get("data") instanceof com.alibaba.fastjson2.JSONArray);assertEquals(12,parsed.getJSONArray("data").getJSONObject(0).size());assertNull(parsed.getJSONArray("data").getJSONObject(0).get("allowedFileType"));assertFalse(parsed.getJSONArray("data").getJSONObject(0).containsKey("futureRoot"));assertNull(result.getHeader("X-Workflow-Revision"));verify(agents).legacyInputTypes(9,"5");
    }
    @Test void originalBotIdStringOnlyCanSyncWithAbsentHeaderWithoutInventedRequestRevision() throws Exception {
        when(agents.legacyInputTypes(9,null)).thenReturn(new AgentService.InputTypesResult(new com.alibaba.fastjson2.JSONArray(),9,2,12,0));mvc.perform(post("/paiflow/legacy/workflow/bot/get-inputs-type").header("If-Match","*").contentType("application/json").content("{\"botId\":\"9\",\"extraInputs\":\"forged\",\"inputTypesWorkflowId\":999}")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(header().string("ETag","\"2\""));verify(agents).legacyInputTypes(9,null);
    }
    @Test void inputSyncEmptyHeaderKeepsOriginalAgentAndFileArrayResponseOrder() throws Exception {
        var fields=com.alibaba.fastjson2.JSONArray.of(JSONObject.of("name","AGENT_USER_INPUT","schema",JSONObject.of("type","string")),JSONObject.of("name","files","schema",JSONObject.of("type","array-string"),"fileType","file","allowedFileType",com.alibaba.fastjson2.JSONArray.of("pdf")));when(agents.legacyInputTypes(9,"")).thenReturn(new AgentService.InputTypesResult(fields,9,2,12,0));mvc.perform(post("/paiflow/legacy/workflow/bot/get-inputs-type").header("space-id","").contentType("application/json").content("{\"botId\":9}")).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].name").value("AGENT_USER_INPUT")).andExpect(jsonPath("$.data[1].fileType").value("file")).andExpect(jsonPath("$.data[1].schema.type").value("array-string"));verify(agents).legacyInputTypes(9,"");
    }
    @Test void malformedInputSyncBotIdentityRefusesBeforeServiceOrReceipt() {
        var controller=new LegacyAgentWorkflowController(agents);for(Object bad:List.of(true,0,-1,"nine","9223372036854775808")){var response=new MockHttpServletResponse();assertThrows(ServiceException.class,()->controller.inputTypes(JSONObject.of("botId",bad),"5",response));assertNull(response.getHeader("ETag"));}verifyNoInteractions(agents);
    }
}
