package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 纯规则复用共享原样例，不运行Factory/Engine、不建数据库或第二运行fixture。 */
class PureIterationEventScopeTest {
    private JSONObject graph,input;
    @BeforeEach void sample() throws Exception {
        var baseline=JSON.parseObject(Files.readString(Path.of("../../contracts/paiflow/fixtures/pure-iteration.json"))).getJSONObject("baseline");
        graph=copy(baseline.getJSONObject("dsl"));input=copy(baseline.getJSONObject("input"));
    }
    private static JSONObject copy(JSONObject value){return JSON.parseObject(value.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    private static JSONObject node(JSONObject graph,String id){return graph.getJSONArray("nodes").stream().map(n->(JSONObject)n).filter(n->id.equals(n.get("id"))).findFirst().orElseThrow();}
    private static JSONObject data(JSONObject graph,String id){return node(graph,id).getJSONObject("data");}
    private static JSONArray batch(int count){var value=new JSONArray();for(int i=0;i<count;i++)value.add("item"+i);return value;}
    private void accepted(String node,String tag){assertDoesNotThrow(()->PureIterationPolicy.requireEventScope(graph,input,node,tag));}
    private void rejected(String node,String tag,int code){assertEquals(code,assertThrows(ServiceException.class,()->PureIterationPolicy.requireEventScope(graph,input,node,tag)).getCode());}
    private JSONObject startOutput(){return data(graph,"node-start::s").getJSONArray("outputs").getJSONObject(0);}
    private static void edge(JSONObject graph,String from,String to){graph.getJSONArray("edges").add(JSONObject.of("sourceNodeId",from,"targetNodeId",to));}

    @Test void originalBudgetRemainsUnchangedAndAllActualNodesHaveTheirFixedScope() {
        var budget=PureIterationPolicy.validate(graph,input);
        assertEquals(12,budget.expandedNodes());assertEquals(2,budget.pathCount());assertEquals(3,budget.maxPathLength());assertEquals(Map.of("iteration::i",3),budget.batchSizes());
        for(String id:List.of("node-start::s","iteration::i","node-end::e")){accepted(id,null);rejected(id,"iteration::i:0",400);}
        for(String id:List.of("iteration-node-start::is","text-joiner::j","iteration-node-end::ie")) {
            for(int i=0;i<3;i++)accepted(id,"iteration::i:"+i);
            rejected(id,null,400);
        }
    }
    @Test void canonicalIndexRejectsAlternateSpellingsWrongOwnersAndNestedTags() {
        for(String tag:new String[]{"","iteration::i","iteration::i:","iteration::i:00","iteration::i:01","iteration::i:+1","iteration::i:-1",
                "iteration::i:1.0","iteration::i:1e0","iteration::i: 1","iteration::i:1 ","iteration::i:１","iteration::i:0/iteration::nested:0",
                "iteration::other:0","iteration::i-other:0","batch-0","iteration::i:"+"9".repeat(1000)})rejected("text-joiner::j",tag,400);
    }
    @Test void lastValidIndexIsBoundToTheFixedThreeItemInput() {
        accepted("text-joiner::j","iteration::i:2");rejected("text-joiner::j","iteration::i:3",400);
        input.put("items",batch(1));accepted("text-joiner::j","iteration::i:0");rejected("text-joiner::j","iteration::i:1",400);
    }
    @Test void emptyBatchHasNoInnerIndexButContainerStillBelongsToMain() {
        input.put("items",new JSONArray());assertEquals(3,PureIterationPolicy.validate(graph,input).expandedNodes());
        accepted("iteration::i",null);
        for(String id:List.of("iteration-node-start::is","text-joiner::j","iteration-node-end::ie"))rejected(id,"iteration::i:0",400);
    }
    @Test void batchSixtyFourAllowsSixtyThreeAndDoesNotRelaxTheOriginalBatchLimit() {
        input.put("items",batch(64));accepted("text-joiner::j","iteration::i:63");rejected("text-joiner::j","iteration::i:64",400);
        input.put("items",batch(65));rejected("text-joiner::j","iteration::i:0",413);
    }
    @Test void absentOptionalArrayUsesItsDeclaredDefaultAndPresentArrayOverridesIt() {
        startOutput().put("required",false);startOutput().getJSONObject("schema").put("default",batch(2));input.clear();
        accepted("text-joiner::j","iteration::i:1");rejected("text-joiner::j","iteration::i:2",400);
        input.put("items",batch(1));accepted("text-joiner::j","iteration::i:0");rejected("text-joiner::j","iteration::i:1",400);
        input.clear();startOutput().getJSONObject("schema").remove("default");rejected("text-joiner::j","iteration::i:0",400);
    }
    @Test void requiredMissingAndExplicitNullNeverSelectTheDefaultArray() {
        startOutput().getJSONObject("schema").put("default",batch(2));input.clear();rejected("text-joiner::j","iteration::i:0",400);
        startOutput().put("required",false);input.put("items",null);rejected("text-joiner::j","iteration::i:0",400);
        input.put("items","[]");rejected("text-joiner::j","iteration::i:0",400);
    }
    @Test void missingGraphInputOrActualNodeCannotAcquireIterationScope() {
        assertEquals(400,assertThrows(ServiceException.class,()->PureIterationPolicy.requireEventScope(null,input,"text-joiner::j","iteration::i:0")).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->PureIterationPolicy.requireEventScope(graph,null,"text-joiner::j","iteration::i:0")).getCode());
        rejected(null,null,400);rejected(null,"iteration::i:0",400);rejected("text-joiner::not-member","iteration::i:0",400);
        var malformed=copy(graph);malformed.put("nodes","[]");assertEquals(400,assertThrows(ServiceException.class,()->PureIterationPolicy.requireEventScope(malformed,input,"text-joiner::j","iteration::i:0")).getCode());
    }
    private void addSecondContainer() {
        data(graph,"node-start::s").getJSONArray("outputs").add(JSONObject.of("name","secondItems","schema",JSONObject.of("type","array","items",JSONObject.of("type","string")),"required",true));
        String[][] replacements={{"iteration::i","iteration::other:segment"},{"iteration-node-start::is","iteration-node-start::second"},{"text-joiner::j","text-joiner::second"},{"iteration-node-end::ie","iteration-node-end::second"}};
        for(String id:List.of("iteration::i","iteration-node-start::is","text-joiner::j","iteration-node-end::ie")) {
            String raw=node(graph,id).toJSONString(JSONWriter.Feature.WriteMapNullValue);
            for(String[] pair:replacements)raw=raw.replace(pair[0],pair[1]);
            var added=JSON.parseObject(raw);
            if(added.getString("id").equals("iteration::other:segment"))added.getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema").getJSONObject("value").getJSONObject("content").put("name","secondItems");
            graph.getJSONArray("nodes").add(added);
        }
        edge(graph,"node-start::s","iteration::other:segment");edge(graph,"iteration::other:segment","node-end::e");
        edge(graph,"iteration-node-start::second","text-joiner::second");edge(graph,"text-joiner::second","iteration-node-end::second");input.put("secondItems",batch(1));
    }
    @Test void multipleOwnersUseTheirOwnBatchAndOwnerMayContainAdditionalColons() {
        addSecondContainer();assertEquals(Map.of("iteration::i",3,"iteration::other:segment",1),PureIterationPolicy.validate(graph,input).batchSizes());
        accepted("text-joiner::j","iteration::i:2");accepted("text-joiner::second","iteration::other:segment:0");
        rejected("text-joiner::second","iteration::other:segment:1",400);rejected("text-joiner::second","iteration::i:0",400);rejected("text-joiner::j","iteration::other:segment:0",400);
    }
    @Test void parentMetadataCannotMoveAnInnerNodeToMainOrAnotherOwner() {
        addSecondContainer();data(graph,"text-joiner::j").put("parentId","iteration::other:segment");node(graph,"text-joiner::j").put("parentId","main");
        accepted("text-joiner::j","iteration::i:0");rejected("text-joiner::j",null,400);rejected("text-joiner::j","iteration::other:segment:0",400);
        data(graph,"iteration::i").put("parentId","iteration::other:segment");accepted("iteration::i",null);rejected("iteration::i","iteration::other:segment:0",400);
    }
    @Test void fullAnalysisStillRejectsCrossScopeEdgesAndSharedInternalStarts() {
        edge(graph,"text-joiner::j","node-end::e");rejected("text-joiner::j","iteration::i:0",400);
        graph.getJSONArray("edges").removeIf(raw->"text-joiner::j".equals(((JSONObject)raw).get("sourceNodeId")) && "node-end::e".equals(((JSONObject)raw).get("targetNodeId")));
        addSecondContainer();data(graph,"iteration::other:segment").getJSONObject("nodeParam").put("IterationStartNodeId","iteration-node-start::is");rejected("text-joiner::j","iteration::i:0",400);
    }
    @Test void hiddenResourcesQuestionAnswerNestedIterationAndActiveRetryRemainUnavailable() {
        var original=copy(graph);
        for(String type:List.of("spark-llm","question-answer","node-variable","iteration")) {
            graph=JSON.parseObject(original.toJSONString(JSONWriter.Feature.WriteMapNullValue).replace("text-joiner::j",type+"::nested"));
            rejected(type+"::nested","iteration::i:0",503);
        }
        graph=copy(original);data(graph,"text-joiner::j").put("retryConfig",JSONObject.of("shouldRetry",true));rejected("text-joiner::j","iteration::i:0",503);
        graph=copy(original);data(graph,"text-joiner::j").put("opaque",JSONObject.of("id","spark-llm::hidden"));rejected("text-joiner::j","iteration::i:0",503);
    }
    @Test void ordinaryGraphOnlyAssociatesActualNodesWithoutReAdmittingItsResourceTypes() {
        var ordinary=JSONObject.of("nodes",JSONArray.of(JSONObject.of("id","agent::existing")));
        assertDoesNotThrow(()->PureIterationPolicy.requireEventScope(ordinary,null,"agent::existing",null));
        assertEquals(400,assertThrows(ServiceException.class,()->PureIterationPolicy.requireEventScope(ordinary,null,"agent::existing","batch-0")).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->PureIterationPolicy.requireEventScope(ordinary,null,"agent::other",null)).getCode());
    }
}
