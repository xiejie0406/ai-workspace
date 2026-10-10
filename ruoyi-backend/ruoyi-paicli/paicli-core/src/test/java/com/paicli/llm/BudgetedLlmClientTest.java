package com.paicli.llm;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BudgetedLlmClientTest {
    @Test void embeddingIdentitySurvivesSuccessAndUncertainReservation() throws Exception {
        List<BudgetedLlmClient.Usage> usages = new ArrayList<>();
        LlmClient transport = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("not chat"); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public String getModelName() { return "main-model"; } public String getProviderName() { return "sub2api"; }
        };
        var identity = new BudgetedLlmClient.CallIdentity("EMBEDDING", "openai", "text-embedding-v4", 3, "a".repeat(64), "8", 2);
        BudgetedLlmClient client = new BudgetedLlmClient(transport, 20, 5, usages::add);
        client.externalInputOnly(4, identity, () -> new BudgetedLlmClient.ExternalResponse<>(new float[]{1}, 2, 0, 0, true));
        assertThrows(IOException.class, () -> client.externalInputOnly(4, identity, () -> { throw new IOException("unknown"); }));
        assertEquals(identity, usages.get(0).callIdentity()); assertEquals(identity, usages.get(1).callIdentity());
        assertEquals(0, usages.get(1).outputTokens()); assertFalse(usages.get(1).usagePresent());
        assertEquals("UNCERTAIN", usages.get(1).status()); assertEquals(14, client.remainingTokens());
    }
    @Test void embeddingAndModelSearchShareInputReservationAndDoNotChargeImaginaryEmbeddingOutput() throws Exception {
        List<BudgetedLlmClient.Usage> usages = new ArrayList<>();
        LlmClient transport = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("external fixture cannot invoke chat"); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public String getModelName() { return "fake"; } public String getProviderName() { return "fake"; }
        };
        BudgetedLlmClient client = new BudgetedLlmClient(transport, 20, 10, usages::add);
        assertEquals("vector", client.externalInputOnly(5, () -> new BudgetedLlmClient.ExternalResponse<>("vector", 3, 0, 0, true)));
        assertEquals(17, client.remainingTokens()); assertEquals(0, usages.get(0).outputTokens());
        assertEquals("search", client.external(3, () -> { assertEquals(10, LlmRequestLimit.current()); return new BudgetedLlmClient.ExternalResponse<>("search", 3, 4, 0, true); }));
        assertEquals(10, client.remainingTokens()); assertNull(LlmRequestLimit.current());
        assertEquals(3, usages.get(0).totalTokens()); assertEquals(7, usages.get(1).totalTokens(), "中心每次增量记账不能重复上送任务累计");
        assertThrows(IOException.class, () -> client.externalInputOnly(10, () -> { throw new IOException("uncertain"); }));
        assertEquals(0, client.remainingTokens()); assertEquals("UNCERTAIN", usages.get(2).status());
    }
    @Test void sharedBudgetCapsRequestsAndPreventsNextRequestAfterCharge() throws Exception {
        List<BudgetedLlmClient.Usage> usages = new ArrayList<>(); AtomicInteger calls = new AtomicInteger();
        LlmClient transport = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                calls.incrementAndGet(); assertEquals(10, LlmRequestLimit.current());
                return new ChatResponse("assistant", "ok", null, null, 15, 10, 0, "fake", true);
            }
            public String getModelName() { return "fake"; } public String getProviderName() { return "fake"; }
        };
        BudgetedLlmClient client = new BudgetedLlmClient(transport, 25, 10, usages::add);
        assertEquals("ok", client.chat(List.of(LlmClient.Message.user("hello")), List.of()).content());
        assertNull(LlmRequestLimit.current(), "request limit must be removed on return");
        assertThrows(IOException.class, () -> client.chat(List.of(LlmClient.Message.user("hello")), List.of()));
        assertEquals(1, calls.get()); assertEquals(25, usages.get(0).totalTokens());
    }
    @Test void uncertainFailureKeepsReservationAndRemovesLimit() {
        List<BudgetedLlmClient.Usage> usages = new ArrayList<>();
        LlmClient transport = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { throw new IOException("network outcome unknown"); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException { return chat(messages, tools); }
            public String getModelName() { return "fake"; } public String getProviderName() { return "fake"; }
        };
        BudgetedLlmClient client = new BudgetedLlmClient(transport, 20, 20, usages::add);
        assertThrows(IOException.class, () -> client.chat(List.of(LlmClient.Message.user("hello")), List.of()));
        assertEquals(0, client.remainingTokens()); assertEquals("UNCERTAIN", usages.get(0).status()); assertNull(LlmRequestLimit.current());
    }
}
