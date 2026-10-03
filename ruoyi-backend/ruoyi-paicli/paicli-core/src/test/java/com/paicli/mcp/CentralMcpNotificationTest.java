package com.paicli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CentralMcpNotificationTest {
    @TempDir Path temp;
    @Test void notificationInvalidatesOnlyAndApprovedRefreshRestoresTools() throws Exception {
        ObjectMapper json = new ObjectMapper(); AtomicInteger requests = new AtomicInteger(); CountDownLatch dirty = new CountDownLatch(1);
        var transport = new InMemoryTransport().handle("initialize", ignored -> json.createObjectNode());
        transport.handle("tools/list", ignored -> { requests.incrementAndGet(); var root = json.createObjectNode(); root.putArray("tools").addObject().put("name", "echo").put("description", "echo").putObject("inputSchema").put("type", "object"); return root; });
        var config = new McpServerConfig(); config.setUrl("https://fixture.invalid/mcp");
        var registry = new ToolRegistry();
        try (var manager = new McpServerManager(registry, temp, new McpConfigLoader(temp) {
            @Override public Map<String, McpServerConfig> load() { return Map.of("demo", config); }
            @Override public void prepare(McpServerConfig config) { }
        }) {
            @Override protected McpTransport createTransport(McpServerConfig ignored) { return transport; }
            @Override protected void onToolsListChanged(McpServer server, McpClient client) { invalidateServerMetadata(server); dirty.countDown(); }
        }) {
            manager.loadConfiguredServers(); manager.startConfiguredServer("demo");
            assertTrue(registry.hasTool("mcp__demo__echo")); assertEquals(1, requests.get());
            transport.emit(json.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}"));
            assertTrue(dirty.await(2, TimeUnit.SECONDS));
            assertTrue(manager.server("demo").metadataDirty()); assertEquals(McpServerStatus.READY, manager.server("demo").status());
            assertFalse(registry.hasTool("mcp__demo__echo")); assertEquals(1, requests.get(), "无许可的通知线程不能发RPC");
            manager.refreshConfiguredServerMetadata("demo");
            assertFalse(manager.server("demo").metadataDirty()); assertTrue(registry.hasTool("mcp__demo__echo")); assertEquals(2, requests.get());
        }
    }
}
