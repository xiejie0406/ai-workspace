package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowController;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原普通/比较body经真实HTTP绑定和实际Service/Mapper；不包含真实中心、浏览器或供应商。 */
class LegacyDebugBuildContractTest extends WorkflowDebugTestFixture {
    @Test void originalOrdinaryBodyProducesMinimalStringReceiptFixedEtagAndActualSnapshot() throws Exception {
        var agent=createAgent(canvas());var body=buildBody(agent,canvas());var mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowController(facade)).build();String request=body.toJSONString(JSONWriter.Feature.WriteMapNullValue);
        var response=mvc.perform(post("/paiflow/legacy/workflow/build").header("space-id",Long.toString(space)).header("If-Match","\"0\"").header("X-PaiFlow-Build-Key","original_build_key").contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(header().string("ETag","\"1\"")).andExpect(header().string("Access-Control-Expose-Headers","ETag")).andReturn().getResponse();
        byte[] bytes=response.getContentAsByteArray();var wire=JSON.parseObject(new String(bytes,StandardCharsets.UTF_8)).getJSONObject("data");assertEquals(Set.of("id","flowId","spaceId","revision","debugReceipt"),wire.keySet());assertInstanceOf(String.class,wire.get("id"));assertInstanceOf(String.class,wire.get("revision"));assertEquals(10,receipt(wire).size());assertTrue(receipt(wire).containsKey("comparisonId"));assertNull(receipt(wire).get("comparisonId"));assertNotNull(workflows.requireDebugReceipt(receipt(wire),space));assertEquals(1,workflowMapper.find(agent.getLongValue("workflowId")).getRevision());
        // 仅唯一Maven执行此测试时生成实际模拟HTTP证据，agent不手写target或伪造回执。
        Files.writeString(Path.of("target/debug-build-actual-response.json"),JSONObject.of("scope","isolated H2 and MockMvc; not live centre","request",body,"requestHeaders",JSONObject.of("space-id",Long.toString(space),"If-Match","\"0\"","X-PaiFlow-Build-Key","original_build_key"),"bodyUTF8Base64",Base64.getEncoder().encodeToString(bytes),"headers",JSONObject.of("ETag",response.getHeader("ETag"),"Access-Control-Expose-Headers",response.getHeader("Access-Control-Expose-Headers")),"response",JSON.parseObject(new String(bytes,StandardCharsets.UTF_8))).toJSONString(JSONWriter.Feature.WriteMapNullValue),StandardCharsets.UTF_8);
    }
    @Test void originalComparisonBodyHasUuidVersionNoIdAndDoesNotAdvanceDraftEtag() throws Exception {
        var agent=createAgent(canvas());var body=JSONObject.of("flowId",agent.get("flowId"),"name","原比较标题","data",canvas(),"version",UUID.randomUUID().toString());var mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowController(facade)).build();var response=mvc.perform(post("/paiflow/legacy/workflow/add-comparisons").header("space-id",Long.toString(space)).header("If-Match","\"0\"").header("X-PaiFlow-Build-Key","original_comparison_key").contentType("application/json").content(body.toJSONString())).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(header().string("ETag","\"0\"")).andReturn().getResponse();var result=JSON.parseObject(response.getContentAsString()).getJSONObject("data");assertEquals("comparison",receipt(result).get("mode"));assertEquals(body.get("version"),receipt(result).get("comparisonId"));assertEquals("0",result.get("revision"));assertEquals(0,workflowMapper.find(agent.getLongValue("workflowId")).getRevision());assertEquals(1,agentsMapper.find(agent.getLongValue("id")).getRevision());
    }
    @Test void replayHttpResponseKeepsOldSnapshotRevisionAfterCurrentDraftAdvances() throws Exception {
        var agent=createAgent(canvas());var controller=new LegacyWorkflowController(facade);var body=buildBody(agent,canvas());var firstResponse=new MockHttpServletResponse();var first=controller.build(body,Long.toString(space),"\"0\"","http_replay_key",firstResponse).data();workflows.save(agent.getLongValue("workflowId"),space,1L,JSONObject.of("description","later"));var response=new MockHttpServletResponse();var replay=controller.build(body,Long.toString(space),"\"0\"","http_replay_key",response).data();assertEquals(first,replay);assertEquals("\"1\"",response.getHeader("ETag"));assertEquals(2,workflowMapper.find(agent.getLongValue("workflowId")).getRevision());
    }
    @Test void headerScopeWinsOriginalOrdinaryBodySpaceEchoAndCannotAccessOtherWorkflow() {
        var agent=createAgent(canvas());var body=buildBody(agent,canvas());body.put("spaceId",space+100);var built=facade.build(body,Long.toString(space),0L,"header_priority_key");assertEquals(space,built.getLongValue("spaceId"));assertEquals(404,denied(()->facade.build(body,Long.toString(space+100),0L,"header_other_key")));assertEquals(1,workflowMapper.find(agent.getLongValue("workflowId")).getRevision());
    }
    @Test void missingOrInvalidKeyRevisionAndComparisonVersionRejectWithoutWriting() throws Exception {
        var agent=createAgent(canvas());var controller=new LegacyWorkflowController(facade);var body=buildBody(agent,canvas());for(String key:Arrays.asList(null,"short","has whitespace key","a".repeat(129))){assertEquals(400,denied(()->controller.build(body,Long.toString(space),"\"0\"",key,new MockHttpServletResponse())));}assertEquals(409,denied(()->controller.build(body,Long.toString(space),null,"missing_revision_key",new MockHttpServletResponse())));assertEquals(400,denied(()->controller.build(body,Long.toString(space),"W/\"0\"","weak_revision_key",new MockHttpServletResponse())));
        var comparison=JSONObject.of("flowId",agent.get("flowId"),"name","comparison","data",canvas(),"version","business-v1");assertEquals(400,denied(()->controller.comparisons(comparison,Long.toString(space),"\"0\"","invalid_comparison_key",new MockHttpServletResponse())));assertEquals(0,rows("paiflow_workflow_version"));assertEquals(0,workflowMapper.find(agent.getLongValue("workflowId")).getRevision());
    }
    @Test void defaultPersonalOriginalResponseKeepsNullSpaceWithExactDecimalIds() throws Exception {
        var agent=agents.legacyCreate("",3,JSONObject.of("name","default_contract","data",canvas().toJSONString(JSONWriter.Feature.WriteMapNullValue)));var controller=new LegacyWorkflowController(facade);var response=new MockHttpServletResponse();var value=controller.build(buildBody(agent,canvas()),"","\"0\"","default_contract_key",response).data();assertTrue(value.containsKey("spaceId"));assertNull(value.get("spaceId"));assertEquals(agent.getLong("workflowId").toString(),value.get("id"));assertEquals("1",value.get("revision"));assertEquals("\"1\"",response.getHeader("ETag"));assertEquals("ETag",response.getHeader("Access-Control-Expose-Headers"));
    }
}
