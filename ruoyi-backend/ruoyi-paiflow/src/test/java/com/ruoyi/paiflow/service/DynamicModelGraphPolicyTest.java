package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicModelGraphPolicyTest {
    private JSONObject node(String id) {return JSONObject.of("id",id,"data",JSONObject.of("inputs",new JSONArray(),"outputs",new JSONArray(),"nodeParam",new JSONObject()));}
    private JSONObject edge(String from,String to) {return JSONObject.of("sourceNodeId",from,"targetNodeId",to);}
    private JSONObject branch() {
        return JSONObject.of("nodes",JSONArray.of(node("node-start::s"),node("decision-making::d"),node("node-end::first"),node("node-end::second")),
            "edges",JSONArray.of(edge("node-start::s","decision-making::d"),edge("decision-making::d","node-end::first"),edge("decision-making::d","node-end::second")));
    }
    @Test void originalMultipleTerminalEndsRemainDistinctPathsWhileOldStaticRuleStaysUnchanged() {
        var graph=branch();String before=graph.toJSONString();var budget=DynamicModelGraphPolicy.validate(graph);
        assertEquals(2,budget.pathCount());assertEquals(3,budget.maxPathLength());assertEquals(before,graph.toJSONString());
        assertEquals(400,assertThrows(ServiceException.class,()->PureIterationPolicy.validateTopLevel(graph,Set.of("node-start","decision-making","node-end"))).getCode());
    }
    @Test void terminalShapeOrphanCycleAndMissingDeclaredReferenceCannotReachProducerAdmission() {
        var terminal=branch();terminal.getJSONArray("edges").add(edge("node-end::first","node-end::second"));
        assertEquals(400,assertThrows(ServiceException.class,()->DynamicModelGraphPolicy.validate(terminal)).getCode());
        var orphan=branch();orphan.getJSONArray("nodes").add(node("message::orphan"));
        assertEquals(400,assertThrows(ServiceException.class,()->DynamicModelGraphPolicy.validate(orphan)).getCode());
        var cycle=branch();cycle.getJSONArray("edges").add(edge("decision-making::d","node-start::s"));
        assertEquals(400,assertThrows(ServiceException.class,()->DynamicModelGraphPolicy.validate(cycle)).getCode());
        var ref=branch();ref.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONArray("inputs").add(JSONObject.of("name","query","schema",JSONObject.of("type","string","value",JSONObject.of("type","ref","content",JSONObject.of("nodeId","node-start::s","name","missing")))));
        assertEquals(400,assertThrows(ServiceException.class,()->DynamicModelGraphPolicy.validate(ref)).getCode());
    }
    @Test void duplicateEdgesDoNotMultiplyPathsAndUnimplementedResourceTypesStayExplicit() {
        var graph=branch();graph.getJSONArray("edges").add(edge("decision-making::d","node-end::first"));assertEquals(2,DynamicModelGraphPolicy.validate(graph).pathCount());
        graph.getJSONArray("nodes").getJSONObject(1).put("id","mcp::d");var unavailable=graph;
        assertEquals(503,assertThrows(ServiceException.class,()->DynamicModelGraphPolicy.validate(unavailable)).getCode());
    }
}
