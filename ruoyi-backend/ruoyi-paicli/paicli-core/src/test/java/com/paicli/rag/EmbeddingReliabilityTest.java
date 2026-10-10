package com.paicli.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingReliabilityTest {
    @TempDir Path temp;

    @Test void compatibleRootIsAppendedOnceAndUsageIsPreserved() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            float[] vector = new float[1024]; vector[0] = 1;
            String values = new ObjectMapper().writeValueAsString(vector);
            server.enqueue(new MockResponse().setBody("{\"model\":\"text-embedding-v4\",\"data\":[{\"embedding\":"+values+"}],\"usage\":{\"prompt_tokens\":8}}"));
            EmbeddingClient client = new EmbeddingClient("openai", "text-embedding-v4", server.url("/compatible-mode/v1/").toString(), "fixture", 1024);
            assertEquals(1024, client.embed("固定测试代码").length);
            var request = server.takeRequest();
            assertEquals("/compatible-mode/v1/embeddings", request.getPath());
            var body = new ObjectMapper().readTree(request.getBody().readUtf8());
            assertFalse(body.has("dimensions")); assertEquals("text-embedding-v4", body.path("model").asText());
            assertEquals(new EmbeddingClient.Usage(8, true), client.consumeUsage());
        }
    }

    @Test void malformedResponseAndWrongDimensionFailWithoutFallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            var client = new EmbeddingClient("openai", "fixed", server.url("/v1").toString(), "fixture", 2);
            for (String vector : List.of("{}", "[]", "[1]", "[0,0]", "[1,\"2\"]", "[1,1e100]")) {
                server.enqueue(new MockResponse().setBody("{\"data\":[{\"embedding\":" + vector + "}]}"));
                assertThrows(IOException.class, () -> client.embed("query"), vector);
            }
            server.enqueue(new MockResponse().setBody("{\"model\":\"different\",\"data\":[{\"embedding\":[1,2]}]}"));
            assertThrows(IOException.class, () -> client.embed("query"));
        }
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingClient("unknown", "m", "http://localhost", ""));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingClient("openai", "m", "https://example/v1/embeddings", ""));
        assertThrows(IOException.class, () -> EmbeddingClient.validateVector(new float[]{Float.NaN, 1}, 2));
    }

    @Test void staleOrLegacyIndexIsRejectedBeforePaidQueryAndExactSearchSurvives() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var oldClient = client("old", requests, false);
        var newClient = client("new", requests, false);
        String project = temp.resolve("project").toString();
        var chunk = CodeChunk.fileChunk("a.md", "PermissionService unique reference");
        try (VectorStore store = new VectorStore(project, temp)) {
            store.replaceProject(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{1,2})), List.of(), oldClient.getIdentity(), 2);
        }
        try (CodeRetriever retriever = new CodeRetriever(project, newClient, temp)) {
            assertTrue(assertThrows(SQLException.class, () -> retriever.semanticSearch("permission", 5)).getMessage().contains("重建"));
            assertFalse(retriever.keywordSearch("PermissionService").isEmpty());
        }
        assertEquals(0, requests.get());
        try (VectorStore store = new VectorStore(project, temp)) {
            store.replaceProject(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{1,2})), List.of());
        }
        try (CodeRetriever retriever = new CodeRetriever(project, oldClient, temp)) {
            assertThrows(SQLException.class, () -> retriever.semanticSearch("permission", 5));
            assertFalse(retriever.keywordSearch("PermissionService").isEmpty());
        }
        assertEquals(0, requests.get());
    }

    @Test void strictFailedRebuildAndPersistenceFailurePreservePreviousIdentityAndChunks() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("source.md"), "PreviousUniqueFact");
        var good = client("model", new AtomicInteger(), false);
        var index = new CodeIndex(good, null, temp.resolve("rag"));
        var first = index.indexStrict(project.toString()); assertTrue(first.success());
        Files.writeString(project.resolve("source.md"), "ReplacementUniqueFact");
        var failed = new CodeIndex(client("changed", new AtomicInteger(), true), null, temp.resolve("rag")).indexStrict(project.toString());
        assertFalse(failed.success());
        try (VectorStore store = new VectorStore(project.toString(), temp.resolve("rag"))) {
            assertEquals(first.fingerprint(), store.getIndexMetadata().fingerprint());
            assertFalse(store.searchByKeyword("PreviousUniqueFact").isEmpty());
            assertThrows(SQLException.class, () -> store.replaceProject(List.of(), java.util.Arrays.asList((CodeRelation)null), good.getIdentity(), 2));
            assertEquals(first.fingerprint(), store.getIndexMetadata().fingerprint());
            assertFalse(store.searchByKeyword("PreviousUniqueFact").isEmpty());
        }
        try (CodeRetriever retriever = new CodeRetriever(project.toString(), good, temp.resolve("rag"))) {
            assertFalse(retriever.semanticSearch("previous", 5).isEmpty());
        }
    }

    @Test void identityIgnoresCredentialRotationButIncludesModelEndpointAndDimensions() {
        var a = new EmbeddingClient("openai", "m", "https://example/v1/", "key1", 1024);
        assertEquals(a.getIdentity().fingerprint(), new EmbeddingClient("openai", "m", "https://example/v1", "key2", 1024).getIdentity().fingerprint());
        assertNotEquals(a.getIdentity().fingerprint(), new EmbeddingClient("openai", "other", "https://example/v1", "key1", 1024).getIdentity().fingerprint());
        assertNotEquals(a.getIdentity().fingerprint(), new EmbeddingClient("openai", "m", "https://example/other", "key1", 1024).getIdentity().fingerprint());
        assertNotEquals(a.getIdentity().fingerprint(), new EmbeddingClient("openai", "m", "https://example/v1", "key1", 768).getIdentity().fingerprint());
    }

    @Test void cancellationAfterLastEmbeddingOrDuringCommitPreservesPreviousIndex() throws Exception {
        Path project=Files.createDirectory(temp.resolve("cancel-project"));Path cache=temp.resolve("rag");
        Files.writeString(project.resolve("source.md"),"PreviousUniqueFact");
        var good=client("model",new AtomicInteger(),false);
        var first=new CodeIndex(good,null,cache).indexStrict(project.toString());assertTrue(first.success());
        Files.writeString(project.resolve("source.md"),"ReplacementUniqueFact");
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var lastEmbedding=new EmbeddingClient("openai","model","http://localhost/v1","fixture",2) {
            @Override public float[] embed(String text) { cancelled.set(true);return new float[]{1,2}; }
        };
        var result=new CodeIndex(lastEmbedding,null,cache,()->{if(cancelled.get())throw new IOException("模拟取消");}).indexStrict(project.toString());
        assertFalse(result.success());
        try(var store=new VectorStore(project.toString(),cache)) {
            assertEquals(first.fingerprint(),store.getIndexMetadata().fingerprint());
            assertFalse(store.searchByKeyword("PreviousUniqueFact").isEmpty());
            assertThrows(SQLException.class,()->store.replaceProject(List.of(),List.of(),good.getIdentity(),2,()->{throw new IOException("提交前取消");}));
            assertEquals(first.fingerprint(),store.getIndexMetadata().fingerprint());
            assertFalse(store.searchByKeyword("PreviousUniqueFact").isEmpty());
        }
    }

    private EmbeddingClient client(String model, AtomicInteger calls, boolean fail) {
        return new EmbeddingClient("openai", model, "http://localhost/v1", "fixture", 2) {
            @Override public float[] embed(String text) throws IOException {
                calls.incrementAndGet(); if (fail) throw new IOException("模拟中断");
                return new float[]{1,2};
            }
        };
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"-1","\"20\"","1.5","100000001"})
    void malformedInputUsageRemainsUnknownInsteadOfZeroCost(String value) throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json")
                .setBody("{\"data\":[{\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":"+value+"}}"));server.start();
            var client=new EmbeddingClient("openai","model",server.url("/v1").toString(),"fixture",2);
            assertArrayEquals(new float[]{1,2},client.embed("code"));assertFalse(client.consumeUsage().present());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"8,80","80,-1","-1,80"})
    void conflictingUsageRetainsLargerLegalCountAsUnknown(int prompt,int total) throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json")
                .setBody("{\"data\":[{\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":"+prompt+",\"total_tokens\":"+total+"}}"));server.start();
            var client=new EmbeddingClient("openai","model",server.url("/v1").toString(),"fixture",2);client.embed("code");
            assertEquals(new EmbeddingClient.Usage(80,false),client.consumeUsage());
        }
    }
}
