package com.paicli.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path cache;
    private final EmbeddingClient embedding = new EmbeddingClient("ollama", "fixture", "http://unused.invalid", "") {
        @Override public float[] embed(String text) { return new float[]{1f, text.length() / 1000f}; }
    };

    @Test void indexesOnlySelectedSubdirectoryUnderTheBoundWorkspaceDomain() throws Exception {
        var root=java.nio.file.Files.createDirectory(cache.resolve("workspace")); var selected=java.nio.file.Files.createDirectory(root.resolve("selected"));
        java.nio.file.Files.writeString(selected.resolve("kept.md"),"SelectedUniqueFact belongs to selected source.");
        java.nio.file.Files.writeString(root.resolve("excluded.md"),"ExcludedUniqueFact belongs to sibling source.");
        var result=new CodeIndex(embedding,CodeIndex.ProgressListener.noop(),cache.resolve("rag")).indexStrict(root.toString(),"selected");
        assertTrue(result.message().startsWith("索引完成"));
        try(var retriever=new CodeRetriever(root.toString(),embedding,cache.resolve("rag"))){assertFalse(retriever.keywordSearch("SelectedUniqueFact").isEmpty());assertTrue(retriever.keywordSearch("ExcludedUniqueFact").isEmpty());}
        assertThrows(com.paicli.policy.PolicyException.class,()->new CodeIndex(embedding,CodeIndex.ProgressListener.noop(),cache.resolve("rag")).indexStrict(root.toString(),".."));
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        CodeIndex indexer = new CodeIndex(embedding, CodeIndex.ProgressListener.noop(), cache);
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(embedding, messages::add, cache);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }
}
