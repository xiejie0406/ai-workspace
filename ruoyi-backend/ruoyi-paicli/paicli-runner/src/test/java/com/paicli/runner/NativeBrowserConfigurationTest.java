package com.paicli.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeBrowserConfigurationTest {
    @Test void actualChromePagesAreParsedAndUnknownOutputIsPreservedAsUnreported() throws Exception {
        var tabs=NativeControlExecutor.browserTabs("## Pages\n0: about:blank\n1: https://example.test/path [selected]\n");
        assertEquals(2,tabs.size());assertEquals("1",tabs.get(1).path("id").asText());assertTrue(tabs.get(1).path("selected").asBoolean());
        assertNull(NativeControlExecutor.browserTabs("a vendor response without page rows"));
        assertEquals(0,NativeControlExecutor.browserTabs("[]").size());
    }
    @Test void approvedSharedAndDisconnectPreserveAllNonModeArguments() throws Exception {
        var browser = CanonicalJson.MAPPER.readTree("{\"mode\":\"SHARED\",\"endpoint\":\"http://127.0.0.1:9333\",\"modeArgs\":[\"--browserUrl=http://127.0.0.1:9333\"],\"disconnectModeArgs\":[\"--isolated=true\"]}");
        var mcp = CanonicalJson.MAPPER.readTree("{\"transport\":\"stdio\",\"command\":\"node\",\"args\":[\"chrome-devtools-mcp\",\"--isolated=true\",\"--viewport=900x600\"]}");
        var shared = NativeControlExecutor.browserRuntimeConfiguration(browser, mcp, false);
        assertEquals("[\"chrome-devtools-mcp\",\"--viewport=900x600\",\"--browserUrl=http://127.0.0.1:9333\"]", shared.path("args").toString());
        assertEquals("node", shared.path("command").asText());
        var isolated = NativeControlExecutor.browserRuntimeConfiguration(browser, shared, true);
        assertEquals("--isolated=true", isolated.path("args").get(2).asText()); assertEquals(3, isolated.path("args").size());
        assertEquals("--isolated=true", mcp.path("args").get(1).asText());
    }
    @Test void arbitraryFlagsAndRemoteEndpointAreRejected() throws Exception {
        var browser = CanonicalJson.MAPPER.readTree("{\"mode\":\"SHARED\",\"endpoint\":\"http://evil.invalid:9333\",\"modeArgs\":[\"--browserUrl=http://evil.invalid:9333\"]}");
        var mcp = CanonicalJson.MAPPER.readTree("{\"command\":\"node\",\"args\":[]}");
        assertThrows(java.io.IOException.class, () -> NativeControlExecutor.browserRuntimeConfiguration(browser, mcp, false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) browser).putArray("modeArgs").add("--arbitrary-command");
        assertThrows(java.io.IOException.class, () -> NativeControlExecutor.browserRuntimeConfiguration(browser, mcp, false));
    }
}
