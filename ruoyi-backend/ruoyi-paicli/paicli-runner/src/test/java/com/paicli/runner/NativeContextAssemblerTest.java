package com.paicli.runner;

import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class NativeContextAssemblerTest {
    private static MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"code\":200,\"data\":" + body + "}"); }
    private RunnerLease lease() throws IOException { return new RunnerLease(CanonicalJson.MAPPER.createObjectNode().put("taskId", "1").put("sessionId", "2").put("leaseId", "3").put("fencingToken", 4).put("version",1).put("policyVersion",1).put("rootFingerprint","fixture").put("expiresAt", Instant.now().plusSeconds(90).toString())); }
    @Test void loadsSummaryProgressAndBothHistoryAndOriginalLedgerToFixedEnd() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(ok("{\"messages\":[{\"role\":\"system\",\"content\":\"摘要2\"}],\"cursor\":1,\"summaryOffset\":2,\"hasMore\":true,\"endSequence\":3}"));
            server.enqueue(ok("{\"messages\":[{\"role\":\"user\",\"content\":\"历史完整\"}],\"cursor\":3,\"summaryOffset\":2,\"hasMore\":false,\"endSequence\":3}"));
            server.enqueue(ok("{\"entries\":[{\"sequence\":2},{\"sequence\":3}],\"cursor\":3,\"hasMore\":false,\"endSequence\":3}")); server.start();
            try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "fixture", true)) {
                var initial = CanonicalJson.parseObject("{\"history\":[{\"role\":\"system\",\"content\":\"摘要1\"}],\"historyCursor\":1,\"historySummaryOffset\":1,\"historyHasMore\":true,\"ledgerEntries\":[{\"sequence\":1}],\"ledgerCursor\":1,\"ledgerHasMore\":true,\"endSequence\":3}");
                var all = NativeContextAssembler.load(center, lease(), initial);
                assertEquals(3, all.path("history").size()); assertEquals(3, all.path("ledgerEntries").size());
                assertEquals("摘要2", all.path("history").get(1).path("content").asText()); assertFalse(all.path("historyHasMore").asBoolean());
                var first = CanonicalJson.parseObject(server.takeRequest().getBody().readUtf8()); assertEquals(3, first.path("endSequence").asLong()); assertEquals(1, first.path("summaryOffset").asLong());
                var second = CanonicalJson.parseObject(server.takeRequest().getBody().readUtf8()); assertEquals(2, second.path("summaryOffset").asLong()); assertEquals(1, second.path("afterSequence").asLong());
                assertEquals("/paicli/runner/v1/ledger/read", server.takeRequest().getPath()); assertEquals(1, initial.path("history").size());
            }
        }
    }
    @Test void stalledCursorOrChangedEndStopsBeforeAgentCanUsePartialHistory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(ok("{\"messages\":[],\"cursor\":1,\"summaryOffset\":0,\"hasMore\":true,\"endSequence\":3}")); server.start();
            try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "fixture", true)) {
                var context = CanonicalJson.parseObject("{\"historyCursor\":1,\"historyHasMore\":true,\"endSequence\":3}");
                assertThrows(IOException.class, () -> NativeContextAssembler.load(center, lease(), context)); assertEquals(1, server.getRequestCount());
            }
        }
    }
}
