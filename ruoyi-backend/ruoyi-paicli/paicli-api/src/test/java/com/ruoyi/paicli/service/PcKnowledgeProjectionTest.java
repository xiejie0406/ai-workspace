package com.ruoyi.paicli.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PcKnowledgeProjectionTest {
    @Test void nativeSearchKeepsActualEvidenceAndLeavesUnknownLocationNull(){var hit=PcKnowledgeProjection.hits(Map.of("mode","SEMANTIC","results",List.of(Map.of("filePath","src/A.java","chunkType","method","name","run","content","真实代码","similarity",0.8))),7).get(0);assertEquals("src/A.java",hit.get("file"));assertEquals("真实代码",hit.get("snippet"));assertNull(hit.get("startLine"));assertNull(hit.get("fileHash"));assertEquals(0.8,hit.get("score"));assertEquals(7L,hit.get("indexVersion"));}
    @Test void nativeRelationsBecomeDeduplicatedRealNodesAndEdges(){Map<String,Object> relation=Map.of("fromFile","src/A.java","fromName","A","toFile","src/B.java","toName","B","relationType","calls");var graph=PcKnowledgeProjection.graph(Map.of("results",List.of(relation,relation)),9);assertEquals(2,((List<?>)graph.get("nodes")).size());assertEquals(1,((List<?>)graph.get("edges")).size());Map<?,?> node=(Map<?,?>)((List<?>)graph.get("nodes")).get(0);assertNull(node.get("line"));assertEquals("src/A.java",node.get("file"));assertEquals(9L,graph.get("indexVersion"));}
}
