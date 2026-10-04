package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.browser.BrowserConnectivityCheck;
import com.paicli.llm.LlmClient;
import com.paicli.llm.ModelDiscovery;
import com.paicli.memory.AutoCompactionManager;
import com.paicli.mcp.McpServer;
import com.paicli.mcp.McpServerManager;
import com.paicli.mcp.McpServerStatus;
import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.mcp.transport.StdioTransport;
import com.paicli.rag.CodeIndex;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.EmbeddingClient;
import com.paicli.snapshot.SideGitManager;
import com.paicli.snapshot.SnapshotConfig;
import com.paicli.tool.ToolOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;

/** 中心控制任务不进入 Agent；每个处理器在同一许可消费和回执边界内执行。 */
public final class NativeControlExecutor implements AutoCloseable {
    /** CLI 提供微信和 Harness 通道，Runner 无编译时 CLI 依赖。 */
    public interface Extension {
        boolean supports(String operation);
        ToolOutput execute(String operation, JsonNode arguments, JsonNode context,
                           CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools) throws Exception;
        default ToolOutput execute(String operation, JsonNode arguments, JsonNode context,
                                   CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools, Path derivedState) throws Exception {
            return execute(operation, arguments, context, center, lease, tools);
        }
        default void close() { }
    }
    private static final java.util.Set<String> OPERATIONS = java.util.Set.of("INDEX_BUILD", "REPOSITORY_SEARCH", "AST_GRAPH",
            "CONTEXT_COMPRESS", "SNAPSHOT_PREVIEW", "SNAPSHOT_RESTORE", "PROVIDER_REFRESH", "PROVIDER_VERIFY", "MCP_START", "MCP_STOP", "MCP_RESTART",
            "MCP_READ_RESOURCE", "MCP_READ_PROMPT", "SKILLS_RELOAD", "BROWSER_PROBE", "HARNESS_QUICK", "HARNESS_NORMAL", "HARNESS_INLINE",
            "MEMORY_VERIFY", "MEMORY_DELETE", "MEMORY_CLEAR", "WECHAT_SETUP", "WECHAT_START", "WECHAT_PAUSE", "WECHAT_RESUME", "WECHAT_STOP", "WECHAT_RESTART",
            "INIT_PROJECT_MEMORY", "SNAPSHOT_CLEAN", "SNAPSHOT_CLEAN_PREVIEW", "BROWSER_TABS", "BROWSER_DISCONNECT", "BROWSER_CONNECT", "REPOSITORY_LIST");
    private final CenterHttpClient center;
    private final NativeTaskExecutor.LlmClientProvider providers;
    private final Path state;
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, com.paicli.browser.BrowserSession> browserSessions = new LinkedHashMap<>();
    private final Map<CenterToolRegistry, com.paicli.llm.BudgetedLlmClient> usageBudgets = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final ThreadLocal<CenterToolRegistry> currentTools = new ThreadLocal<>();
    private List<Extension> extensions;
    @FunctionalInterface interface McpTransportFactory { McpTransport create(McpServerConfig config, ManagedMcpGrant grant, CenterToolRegistry tools) throws IOException; }
    private McpTransportFactory fixtureTransport;
    private synchronized List<Extension> extensions() { if (extensions == null) extensions = ServiceLoader.load(Extension.class).stream().map(ServiceLoader.Provider::get).toList(); return extensions; }

    public NativeControlExecutor(CenterHttpClient center, Path state, NativeTaskExecutor.LlmClientProvider providers) {
        this.center = center; this.state = java.util.Objects.requireNonNull(state).toAbsolutePath().normalize(); this.providers = providers;
    }
    NativeControlExecutor(CenterHttpClient center, Path state, NativeTaskExecutor.LlmClientProvider providers, McpTransportFactory transport) {
        this(center, state, providers); this.fixtureTransport = java.util.Objects.requireNonNull(transport);
    }

    public void configureDerivedState(CenterToolRegistry tools, JsonNode context, RunnerLease lease) throws IOException {
        if (state.startsWith(Path.of(tools.getProjectPath()).toRealPath())) throw new IOException("Runner派生状态目录必须在工作区外");
        tools.setCodeRetrieverFactory(root -> new CodeRetriever(root, embedding(context, lease, tools), state.resolve("rag")));
        JsonNode config = context.path("searchConfig"), secret = context.path("searchSecret");
        com.paicli.web.SearchProvider search;
        if (!config.isObject()) {
            search = new com.paicli.web.SearchProvider() {
                public String name() { return "center"; }
                public boolean isReady() { return false; }
                public String unavailableHint() { return "中心尚未配置搜索供应商"; }
                public List<com.paicli.web.SearchResult> search(String query, int limit) throws IOException { throw new IOException(unavailableHint()); }
            };
        } else {
            String provider = required(config, "provider").toLowerCase(java.util.Locale.ROOT);
            String key = secret.path("apiKey").asText("");
            if (!"searxng".equals(provider) && (key.isBlank() || secret.path("secretRef").asText().isBlank()
                    || !RunnerLease.expiry(secret.path("expiresAt")).isAfter(java.time.Instant.now())))
                throw new IOException("中心搜索凭证缺失或已过期");
            if (!List.of("zhipu", "serpapi", "searxng", "deepseek").contains(provider)) throw new IOException("中心搜索供应商不受支持");
            search = new ManagedSearch(provider, config, context, lease);
        }
        tools.setCentralSearchProvider(search);
    }

    public String execute(RunnerLease lease, CenterToolRegistry tools, JsonNode context) throws Exception {
        if (state.startsWith(Path.of(tools.getProjectPath()).toRealPath())) throw new IOException("Runner派生状态目录必须在工作区外");
        JsonNode task = context.path("taskPayload");
        if (!task.isObject()) task = lease.claim().path("task").path("payload");
        String operation = task.path("operation").asText(context.path("operation").asText());
        JsonNode arguments = task.has("arguments") ? task.get("arguments") : context.path("arguments");
        if (!OPERATIONS.contains(operation) || !arguments.isObject()) throw new IOException("中心控制操作或冻结参数无效");
        JsonNode frozen = arguments;
        ToolOutput output = tools.executeControl(operation, arguments.toString(), () -> {
            currentTools.set(tools);
            try { lease.ensureActive(); return perform(operation, frozen, context, lease, tools); }
            catch (java.io.UncheckedIOException uncertain) { throw uncertain; }
            catch (Exception failure) {
                try { lease.ensureActive(); } catch (IOException stopped) { throw new java.io.UncheckedIOException("控制任务已暂停对账", stopped); }
                return ToolOutput.failure("控制操作失败: " + failure.getMessage());
            } finally { currentTools.remove(); }
        });
        lease.ensureActive();
        if (!output.successful()) throw new IOException(output.text());
        return output.text();
    }

    private ToolOutput perform(String operation, JsonNode args, JsonNode context, RunnerLease lease, CenterToolRegistry tools) throws Exception {
        switch (operation) {
            case "REPOSITORY_LIST" -> {
                String path = required(args, "path");
                var entries = tools.listDirectoryEntries(path);
                ObjectNode payload = metadata(args); payload.put("path", path); payload.put("source", "RUNNER");
                payload.set("entries", CanonicalJson.MAPPER.valueToTree(entries));
                event(lease, "DIRECTORY_RESULT", payload); return ToolOutput.text(payload.toString());
            }
            case "INDEX_BUILD" -> {
                var result = new CodeIndex(embedding(context, lease, tools), message -> {}, state.resolve("rag")).indexStrict(tools.getProjectPath(), args.path("path").asText("."));
                if (!result.message().startsWith("索引完成")) return ToolOutput.failure(result.message());
                ObjectNode manifest = metadata(args); manifest.set("index", CanonicalJson.MAPPER.valueToTree(result));
                manifest.put("selectionPath", args.path("path").asText("."));
                manifest.put("hashKind", "CANONICAL_MANIFEST"); manifest.put("manifestHash", CanonicalJson.hash(manifest));
                event(lease, "INDEX_MANIFEST", manifest);
                return ToolOutput.text(result.message());
            }
            case "REPOSITORY_SEARCH", "AST_GRAPH" -> {
                String query = required(args, "query");
                boolean semantic = "SEMANTIC".equalsIgnoreCase(args.path("mode").asText());
                try (CodeRetriever retriever = new CodeRetriever(tools.getProjectPath(), semantic ? embedding(context, lease, tools) : null, state.resolve("rag"))) {
                    Object result = "AST_GRAPH".equals(operation) ? retriever.getRelationGraph(query)
                            : semantic ? retriever.semanticSearch(query, Math.min(100, Math.max(1, args.path("limit").asInt(50))))
                            : retriever.keywordSearch(query).stream().limit(Math.min(100, Math.max(1, args.path("limit").asInt(50)))).toList();
                    JsonNode value = CanonicalJson.MAPPER.valueToTree(result);
                    ObjectNode payload = metadata(args); payload.put("query", query); payload.put("mode", semantic ? "SEMANTIC" : "EXACT"); payload.set("results", value);
                    event(lease, "AST_GRAPH".equals(operation) ? "GRAPH_RESULT" : "SEARCH_RESULT", payload);
                    return ToolOutput.text(value.toString());
                }
            }
            case "CONTEXT_COMPRESS" -> {
                List<LlmClient.Message> history = new ArrayList<>(CanonicalJson.MAPPER.convertValue(context.path("history"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<LlmClient.Message>>() {}));
                if (history.isEmpty()) return ToolOutput.text("会话历史为空，无需压缩");
                AutoCompactionManager.Result result = new AutoCompactionManager(NativeTaskExecutor.budgeted(center, lease, context, provider(lease, context))).compactNow(history);
                if (!result.compacted()) return ToolOutput.failure("摘要未成功或未减少上下文，原历史保留");
                ObjectNode payload = metadata(args); payload.put("sessionVersion", args.path("sessionVersion").asText());
                payload.set("messages", CanonicalJson.MAPPER.valueToTree(history)); payload.put("derived", true);
                event(lease, "CONTEXT_COMPACT", payload);
                return ToolOutput.text("中心会话上下文已压缩，原始消息保留审计");
            }
            case "MEMORY_VERIFY", "MEMORY_DELETE", "MEMORY_CLEAR" -> {
                HttpCentralMemoryStore store = new HttpCentralMemoryStore(center, lease, "", tools.getProjectPath(), tools::currentExecutionBinding).withControlArguments(args);
                if ("MEMORY_VERIFY".equals(operation)) {
                    var result = store.verify(required(args, "id"), java.time.Instant.now());
                    return result.isPresent() ? ToolOutput.text("中心记忆已核实") : ToolOutput.failure("中心记忆不存在或版本已变更");
                }
                if ("MEMORY_DELETE".equals(operation)) return store.delete(required(args, "id")) ? ToolOutput.text("中心记忆已删除") : ToolOutput.failure("中心记忆不存在或版本已变更");
                store.clear(); return ToolOutput.text("中心指定作用域记忆已清空");
            }
            case "SKILLS_RELOAD" -> {
                var registry = NativeTaskExecutor.approvedSkills(context); tools.setSkillRegistry(registry);
                ObjectNode result = metadata(args); result.put("kind", "SKILL"); result.set("skills", CanonicalJson.MAPPER.valueToTree(registry.allSkills().stream().map(skill -> Map.of("name", skill.name(), "version", skill.version())).toList()));
                event(lease, "CONNECTION_RESULT", result); return ToolOutput.text("已加载中心批准技能: " + registry.allSkills().size());
            }
            case "BROWSER_PROBE" -> {
                JsonNode config = approvedConfiguration(args, context, "browserConfig");
                var result = new BrowserConnectivityCheck().probe(browserPort(config));
                ObjectNode payload = metadata(args); payload.put("kind", "BROWSER"); payload.set("result", CanonicalJson.MAPPER.valueToTree(result));
                event(lease, "CONNECTION_RESULT", payload);
                return result.ok() ? ToolOutput.text("浏览器连接成功: " + result.browserUrl()) : ToolOutput.failure(result.message());
            }
            case "BROWSER_CONNECT", "BROWSER_TABS", "BROWSER_DISCONNECT" -> {
                JsonNode config = approvedConfiguration(args, context, "browserConfig");
                if (!"BROWSER_TABS".equals(operation)) return browserConnect(config, context, lease, tools, "BROWSER_DISCONNECT".equals(operation), true);
                Connection chrome = connections.get(config.path("mcpConfigId").asText());
                if (chrome == null) return ToolOutput.failure("浏览器MCP连接未启动");
                chrome.grant().bind(lease, tools); chrome.manager().attachToolRegistry(tools);
                var descriptor = chrome.manager().server(chrome.serverName()).tools().stream().filter(value -> "list_pages".equals(value.name())).findFirst();
                if (descriptor.isEmpty()) return ToolOutput.failure("浏览器连接未提供list_pages");
                ToolOutput pages = chrome.manager().server(chrome.serverName()).client().callToolOutput("list_pages", "{}");
                ObjectNode payload = metadata(args); payload.put("kind", "BROWSER"); payload.put("status", pages.successful() ? "READY" : "ERROR"); payload.put("tabsResult", pages.text());
                JsonNode tabs = browserTabs(pages.text()); payload.put("tabsStatus", tabs == null ? "UNREPORTED" : "REPORTED"); if (tabs != null) payload.set("tabs", tabs);
                event(lease, "CONNECTION_RESULT", payload); return pages;
            }
            case "PROVIDER_REFRESH" -> {
                approvedConfiguration(args, context, "providerConfig");
                List<String> models = new ModelDiscovery().discover(provider(lease, context));
                ObjectNode payload = metadata(args); payload.put("kind", "PROVIDER"); payload.set("models", CanonicalJson.MAPPER.valueToTree(models));
                event(lease, "CONNECTION_RESULT", payload); return ToolOutput.text("中心供应商模型刷新完成: " + models.size());
            }
            case "PROVIDER_VERIFY" -> {
                approvedConfiguration(args,context,"providerConfig");
                if(!"sub2api".equals(context.path("provider").asText()))throw new IOException("兼容验证供应商不符");
                ObjectNode bounded=context.deepCopy();((ObjectNode)bounded.path("policy")).put("maxOutputTokens",256);
                LlmClient client=NativeTaskExecutor.budgeted(center,lease,bounded,provider(lease,context));
                try {
                    var verification=com.paicli.llm.Sub2ApiVerifier.verify(client);
                    lease.ensureActive();ObjectNode payload=metadata(args);payload.put("kind","PROVIDER");
                    payload.set("verification",CanonicalJson.MAPPER.valueToTree(verification));
                    payload.put("wireApi",context.path("providerConfig").path("wireApi").asText());
                    payload.set("modelId",args.path("modelId"));payload.set("modelVersion",args.path("modelVersion"));
                    payload.set("secretVersion",context.path("managedSecret").path("secretVersion"));
                    event(lease,"CONNECTION_RESULT",payload);return ToolOutput.text("Sub2API文本、流式、工具回灌和用量兼容验证完成");
                } finally { client.cancelInFlightCalls(); }
            }
            case "SNAPSHOT_PREVIEW", "SNAPSHOT_RESTORE" -> {
                SideGitManager snapshots = snapshots(tools);
                String id = required(args, "snapshotId");
                var fingerprints = new ResourceFingerprint(Path.of(tools.getProjectPath()));
                ObjectNode preview=NativeSnapshotAdapter.actualPreview(snapshots,fingerprints,args);
                String current=preview.path("currentFingerprint").asText();
                ObjectNode payload = metadata(args); payload.put("snapshotId", id); payload.put("commitId", id); payload.set("preview", CanonicalJson.MAPPER.valueToTree(preview));
                if (args.has("snapshotMetadataId")) payload.set("snapshotMetadataId", args.get("snapshotMetadataId"));
                payload.put("currentFingerprint",current);
                payload.put("hashKind", "CANONICAL_MANIFEST");
                if ("SNAPSHOT_RESTORE".equals(operation)) {
                    String expected = required(args, "currentFingerprint");
                    if (!expected.equals(fingerprints.capture("control__SNAPSHOT_RESTORE", args))) return ToolOutput.failure("恢复冲突：工作区内容已改变，请重新预览批准");
                    JsonNode approved = args.path("snapshot").path("restorePreview");
                    if (!preview.path("canRestore").asBoolean(false) || !approved.path("canRestore").asBoolean(false) || !approved.path("files").isArray()
                            || !snapshotProof(approved.path("files")).equals(snapshotProof(CanonicalJson.MAPPER.valueToTree(preview.get("files")))))
                        return ToolOutput.failure("恢复目标或文件模式已改变，需重新预览并批准");
                    com.paicli.snapshot.RestoreResult result;
                    try {result=snapshots.restoreSnapshot(id);}
                    catch(com.paicli.snapshot.SnapshotRestoreException incomplete) {
                        payload.set("restoreFailure",CanonicalJson.MAPPER.valueToTree(incomplete.evidence()));
                        try {tools.preserveCurrentOutput(ToolOutput.text(payload.toString()));}finally{lease.invalidate();}
                        throw new java.io.UncheckedIOException("恢复失败，已保留补偿与未恢复清单，暂停对账",incomplete);
                    }
                    payload.set("result", CanonicalJson.MAPPER.valueToTree(result));
                    payload.put("manifestHash", CanonicalJson.hash(payload));
                    event(lease, "SNAPSHOT", payload); return result.success() ? ToolOutput.text("已恢复中心批准快照: " + id) : ToolOutput.failure(result.message());
                }
                payload.put("manifestHash", CanonicalJson.hash(payload));
                event(lease, "SNAPSHOT", payload); return ToolOutput.text(payload.toString());
            }
            case "SNAPSHOT_CLEAN", "SNAPSHOT_CLEAN_PREVIEW" -> { return cleanSnapshots(operation,args,lease,tools); }
            case "MCP_START", "MCP_STOP", "MCP_RESTART", "MCP_READ_RESOURCE", "MCP_READ_PROMPT" -> { return mcp(operation, args, context, lease, tools); }
            default -> {
                for (Extension extension : extensions()) if (extension.supports(operation)) {
                    ToolOutput output = extension.execute(operation, args, context, center, lease, tools, state);
                    if (output == null) return ToolOutput.failure("控制扩展未返回执行结果");
                    return output;
                }
                return ToolOutput.failure("当前分发缺少控制通道: " + operation);
            }
        }
    }

    private LlmClient provider(RunnerLease lease, JsonNode context) throws Exception { return providers == null ? NativeTaskExecutor.managedProvider(center, lease, context) : providers.create(context); }
    private synchronized ToolOutput mcp(String operation, JsonNode args, JsonNode context, RunnerLease lease, CenterToolRegistry tools) throws Exception {
        String id = required(args, "configId"); String version = required(args, "configVersion");
        if ("MCP_STOP".equals(operation)) {
            Connection connection = connections.remove(id); if (connection != null) {
                if (!connection.grant().isClosed() && connection.version().equals(version)) {
                    connection.grant().bind(lease, tools); connection.transport().closeApproved();
                }
                connection.manager().close();
            }
            connectionEvent(lease, args, "STOPPED", null); return ToolOutput.text("MCP已停止");
        }
        JsonNode config = approvedConfiguration(args, context, "mcpConfigs");
        String hash = config.path("configHash").asText();
        if (hash.isBlank() || !hash.equals(config.path("trustedHash").asText())) throw new IOException("MCP配置未批准或内容已改变");
        Connection connection = connections.get(id);
        if (connection != null && connection.grant().isClosed()) { connection.manager().close(); connections.remove(id); connection = null; }
        if (connection != null && !connection.version().equals(version)) { connection.manager().close(); connections.remove(id); connection = null; }
        if ("MCP_RESTART".equals(operation) && connection != null) {
            connection.grant().bind(lease, tools); connection.transport().closeApproved();
            connection.manager().close(); connections.remove(id); connection = null;
        }
        if (connection == null) {
            if (operation.startsWith("MCP_READ")) throw new IOException("MCP未启动，请先由中心批准启动连接");
            connection = startConnection(config, config, context, lease, tools);
        }
        connection.grant().bind(lease, tools);
        connection.manager().attachToolRegistry(tools);
        if ("MCP_START".equals(operation) && connection.manager().server(connection.serverName()).metadataDirty())
            connection.manager().refreshConfiguredServerMetadata(connection.serverName());
        Object result = "MCP_READ_RESOURCE".equals(operation) ? connection.manager().readResourceForMention(connection.serverName(), required(args, "reference"))
                : "MCP_READ_PROMPT".equals(operation) ? connection.manager().server(connection.serverName()).client().getPrompt(required(args, "reference"), promptArguments(args))
                : connection.manager().server(connection.serverName()).tools();
        if(ManagedMcpTransport.jsonBytes(CanonicalJson.MAPPER.valueToTree(result))>ManagedMcpTransport.MAX_TYPED_BYTES) {
            tools.preserveCurrentOutput(ToolOutput.text(CanonicalJson.MAPPER.valueToTree(result).toString()));
            connection.grant().uncertainExecution();throw new IOException("MCP控制结果超过支持范围，完整实际结果已保留并暂停对账");
        }
        ObjectNode payload = metadata(args); payload.put("kind", "MCP"); payload.put("status", "READY"); payload.set("result", CanonicalJson.MAPPER.valueToTree(result));
        payload.set("tools", CanonicalJson.MAPPER.valueToTree(connection.manager().server(connection.serverName()).tools()));
        payload.set("resources", CanonicalJson.MAPPER.valueToTree(connection.manager().resourceCandidates()));
        payload.set("prompts", CanonicalJson.MAPPER.valueToTree(connection.manager().server(connection.serverName()).client().listPromptDefinitions()));
        payload.set("logs", CanonicalJson.MAPPER.valueToTree(connection.manager().server(connection.serverName()).logs()));
        if(ManagedMcpTransport.jsonBytes(payload.path("logs"))>ManagedMcpTransport.MAX_LOG_BYTES){connection.grant().uncertainExecution();throw new IOException("MCP控制日志超过支持范围，已暂停对账");}
        event(lease, "CONNECTION_RESULT", payload); return ToolOutput.text(payload.toString());
    }

    private Connection startConnection(JsonNode config, JsonNode runtimeConfig, JsonNode context, RunnerLease lease, CenterToolRegistry tools) throws Exception {
        String id = required(config, "id"), version = required(config, "version"), hash = required(config, "configHash");
        if (!hash.equals(config.path("trustedHash").asText())) throw new IOException("MCP配置未批准或已改变");
            McpServerConfig explicit = CanonicalJson.MAPPER.convertValue(runtimeConfig, McpServerConfig.class);
            String serverName = required(config, "name");
            if (!serverName.matches("[a-zA-Z0-9_.-]{1,128}")) throw new IOException("MCP服务器名称无效");
            if (explicit.isHttp() == explicit.isStdio() || config.toString().contains("${")) throw new IOException("MCP显式配置无效或包含未派发环境变量");
            if (explicit.isStdio() && !context.path("workspace").path("sandboxVerified").asBoolean()) throw new IOException("STDIO启动缺少已验证沙箱");
            var transportReference = new java.util.concurrent.atomic.AtomicReference<McpTransport>();
            var managedTransport = new java.util.concurrent.atomic.AtomicReference<ManagedMcpTransport>();
            ManagedMcpGrant grant = new ManagedMcpGrant(center, lease, tools, config, () -> {
                McpTransport transport = transportReference.get(); if (transport != null) ManagedMcpTransport.closeLocal(transport);
            });
            McpConfigLoader loader = new McpConfigLoader(Path.of(tools.getProjectPath())) {
                @Override public Map<String, McpServerConfig> load() { return Map.of(serverName, explicit); }
                @Override public void prepare(McpServerConfig value) { /* 中心提供全部展开后的冻结参数。 */ }
            };
            var evidenceRegistry = new java.util.concurrent.atomic.AtomicReference<>(tools);
            McpServerManager manager = new McpServerManager(tools, Path.of(tools.getProjectPath()), loader) {
                @Override protected void onToolsListChanged(McpServer server, com.paicli.mcp.McpClient client) {
                    // 通知线程没有消费许可，不能刷新凭证或发RPC，也不能破坏当前租约。
                    invalidateServerMetadata(server);
                }
                @Override public synchronized void attachToolRegistry(com.paicli.tool.ToolRegistry next) {
                    if (!(next instanceof CenterToolRegistry central)) throw new IllegalArgumentException("MCP连接必须重绑中心执行入口");
                    evidenceRegistry.set(central); super.attachToolRegistry(next);
                }
                @Override protected com.paicli.mcp.McpClient createClient(String name, McpTransport transport) {
                    return new com.paicli.mcp.McpClient(name, transport) {
                        @Override public List<com.paicli.mcp.protocol.McpToolDescriptor> listTools() throws IOException { return metadata(super::listTools); }
                        @Override public List<com.paicli.mcp.resources.McpResourceDescriptor> listResources() throws IOException { return metadata(super::listResources); }
                        @Override public List<String> listPrompts() throws IOException { return metadata(super::listPrompts); }
                        @Override public List<JsonNode> listPromptDefinitions() throws IOException {
                            try { return ManagedMcpTransport.requireMetadata(super.listPromptDefinitions()); }
                            catch (com.paicli.mcp.McpClient.PromptCatalogueException incomplete) {
                                try { evidenceRegistry.get().preserveCurrentOutput(ToolOutput.text(incomplete.response().toString())); }
                                finally { grant.uncertainExecution(); }
                                throw new IOException("MCP提示词目录格式无效，完整安全响应已保全，需要中心对账");
                            }
                            catch (IOException | RuntimeException incomplete) {
                                grant.uncertainExecution(); throw new IOException("MCP提示词目录无法完整保留，需要中心对账");
                            }
                        }
                        private <T> T metadata(IoValue<T> value) throws IOException {
                            try { return ManagedMcpTransport.requireMetadata(value.get()); }
                            catch(IOException|RuntimeException incomplete){grant.uncertainExecution();throw new IOException("MCP元数据无法完整保留，租约已停止，需要中心对账");}
                        }
                        @Override public ToolOutput callToolOutput(String name, String arguments) throws IOException {
                            try { return ManagedMcpTransport.requireBounded(super.callToolOutput(name, arguments)); }
                            catch (com.paicli.mcp.jsonrpc.JsonRpcException completeError) { throw completeError; }
                            catch (IOException | RuntimeException incomplete) {
                                grant.uncertainExecution();
                                throw new IOException("MCP执行结果不完整，租约已停止，需要中心对账；不会自动重执行");
                            }
                        }
                        @Override protected ToolOutput toToolOutput(com.paicli.mcp.protocol.McpCallToolResult result) throws IOException {
                            return ManagedMcpTransport.strictOutput(result, grant, evidenceRegistry.get()::preserveCurrentOutput);
                        }
                        @Override protected JsonNode request(String method, JsonNode params, int timeout) throws IOException {
                            try { return super.request(method, params, timeout); }
                            catch (com.paicli.mcp.jsonrpc.JsonRpcException completeError) {
                                if (completeError.code() != -32098) throw completeError;
                                try { ManagedMcpTransport.preserveIncompleteException(completeError, evidenceRegistry.get()); }
                                finally { grant.uncertainExecution(); }
                                throw new IOException("MCP执行结果不完整，完整安全原文已保全，需要中心对账；不会自动重执行");
                            }
                            catch (IOException incomplete) {
                                grant.uncertainExecution();
                                throw new IOException("MCP执行结果不完整，租约已停止，需要中心对账；不会自动重执行");
                            }
                        }
                    };
                }
                @Override protected McpTransport createTransport(McpServerConfig value) throws IOException {
                    McpTransport raw;
                    if (fixtureTransport != null) raw = fixtureTransport.create(value, grant, tools);
                    else if (value.isHttp()) raw = new com.paicli.mcp.transport.StreamableHttpTransport(value.getUrl(), () -> grant.current().headers());
                    else {
                        Map<String, String> environment = new java.util.LinkedHashMap<>(value.getEnv()); environment.putAll(grant.current().env());
                        List<String> command = new ArrayList<>(); command.add(value.getCommand()); command.addAll(value.getArgs());
                        raw = new StdioTransport(tools.sandboxedProcessBuilder(command, environment));
                    }
                    transportReference.set(raw); var managed = new ManagedMcpTransport(raw, grant); managedTransport.set(managed); return managed;
                }
            };
            manager.loadConfiguredServers(); manager.startConfiguredServer(serverName);
            McpServer server = manager.server(serverName);
            if (server == null || server.status() != McpServerStatus.READY) { grant.close(); manager.close(); throw new IOException("MCP启动失败: " + (server == null ? "连接未创建" : grant.redact(server.errorMessage()))); }
            Connection created = new Connection(version, hash, serverName, manager, grant, managedTransport.get()); connections.put(id, created); return created;
    }

    public synchronized void attachConnections(RunnerLease lease, CenterToolRegistry tools, JsonNode context) throws IOException {
        JsonNode browser = context.path("browserConfig");
        if (!browser.isObject()) for (JsonNode candidate : context.path("browserConfigs")) if (candidate.path("enabled").asBoolean(true)) { browser = candidate; break; }
        if (browser.isObject() && browser.path("enabled").asBoolean(false)) {
            JsonNode selected = browser.deepCopy();
            tools.setBrowserGuard(new com.paicli.browser.BrowserGuard(browserSession(selected), new com.paicli.browser.SensitivePagePolicy((Path) null)));
            tools.setBrowserConnector(new com.paicli.browser.BrowserConnector() {
                public String status() { return browserStatus(selected); }
                public String connectDefault() { return browserTool(selected, context, lease, tools, false); }
                public String disconnect() { return browserTool(selected, context, lease, tools, true); }
            });
        }
        var allowed = new java.util.HashSet<String>();
        for (JsonNode config : context.path("mcpConfigs")) {
            String id = config.path("id").asText(); Connection live = connections.get(id);
            if (live != null && !live.grant().isClosed() && live.version().equals(config.path("version").asText()) && live.hash().equals(config.path("trustedHash").asText()) && config.path("enabled").asBoolean(true)) {
                live.grant().bind(lease, tools);
                live.manager().attachToolRegistry(tools); allowed.add(id);
            }
        }
        for (String id : List.copyOf(connections.keySet())) if (!allowed.contains(id)) connections.remove(id).manager().close();
    }

    private String browserTool(JsonNode config, JsonNode context, RunnerLease lease, CenterToolRegistry tools, boolean disconnect) {
        try { return browserConnect(config, context, lease, tools, disconnect, false).text(); }
        catch (Exception failure) { lease.invalidate(); throw new java.io.UncheckedIOException("浏览器连接状态未确认，已停止并保留证据待中心对账", failure instanceof IOException io ? io : new IOException(failure)); }
    }

    private String browserStatus(JsonNode config) {
        var session = browserSession(config);
        Connection live = connections.get(config.path("mcpConfigId").asText());
        return "浏览器模式: " + session.mode() + "；MCP: " + (live != null && !live.grant().isClosed() ? "READY" : "STOPPED")
                + (session.browserUrl() == null ? "" : "；端点: " + session.browserUrl());
    }

    private synchronized ToolOutput browserConnect(JsonNode browser, JsonNode context, RunnerLease lease, CenterToolRegistry tools,
                                                   boolean disconnect, boolean reportControl) throws Exception {
        lease.ensureActive();
        JsonNode config = browser.path("mcpConfig");
        if (!config.isObject() || !"chrome-devtools".equals(config.path("name").asText())
                || !required(browser, "mcpConfigId").equals(config.path("id").asText())
                || !required(browser, "mcpConfigVersion").equals(config.path("version").asText())
                || !required(browser, "mcpConfigHash").equals(config.path("configHash").asText())) throw new IOException("浏览器关联MCP定义未冻结或版本不符");
        ObjectNode runtime = browserRuntimeConfiguration(browser, config, disconnect);
        String id = config.path("id").asText(); Connection prior = connections.remove(id);
        if (prior != null) {
            if (!prior.grant().isClosed()) { prior.grant().bind(lease, tools); prior.transport().closeApproved(); }
            prior.manager().close();
        }
        Connection chrome = startConnection(config, runtime, context, lease, tools);
        chrome.grant().bind(lease, tools); chrome.manager().attachToolRegistry(tools);
        var session = browserSession(browser);
        if (disconnect || "ISOLATED".equalsIgnoreCase(browser.path("mode").asText())) session.switchToIsolated();
        else session.switchToShared(browser.path("endpoint").asText());
        ObjectNode payload = CanonicalJson.MAPPER.createObjectNode(); payload.put("configId", required(browser, "id")); payload.put("configVersion", required(browser, "version"));
        payload.put("kind", "BROWSER"); payload.put("status", "READY"); payload.put("mode", session.mode().name()); payload.put("connected", !disconnect);
        payload.set("tools", CanonicalJson.MAPPER.valueToTree(chrome.manager().server(chrome.serverName()).tools()));
        if (reportControl) event(lease, "CONNECTION_RESULT", payload);
        return ToolOutput.text(payload.toString());
    }

    static ObjectNode browserRuntimeConfiguration(JsonNode browser, JsonNode mcp, boolean disconnect) throws IOException {
        if (!"stdio".equalsIgnoreCase(mcp.path("transport").asText("stdio"))) throw new IOException("Chrome浏览器模式切换仅支持批准的STDIO连接");
        JsonNode flags = browser.path(disconnect ? "disconnectModeArgs" : "modeArgs");
        if (!flags.isArray() || flags.size() != 1 || !flags.get(0).isTextual()) throw new IOException("中心未冻结浏览器模式参数");
        String flag = flags.get(0).asText();
        String expected = disconnect || "ISOLATED".equalsIgnoreCase(browser.path("mode").asText()) ? "--isolated=true"
                : browser.path("autoConnect").asBoolean(false) ? "--autoConnect" : "--browserUrl=" + requiredStatic(browser, "endpoint");
        if (!expected.equals(flag)) throw new IOException("浏览器模式参数与冻结配置不一致");
        if (flag.startsWith("--browserUrl=")) browserPort(browser);
        ObjectNode runtime = mcp.deepCopy(); var args = CanonicalJson.MAPPER.createArrayNode(); boolean skipNext = false;
        for (JsonNode item : mcp.path("args")) {
            if (!item.isTextual()) throw new IOException("MCP启动参数必须是文本");
            String argument = item.asText(); if (skipNext) { skipNext = false; continue; }
            if (argument.equals("--browserUrl") || argument.equals("--isolated")) { skipNext = true; continue; }
            if (argument.equals("--autoConnect") || argument.startsWith("--autoConnect=") || argument.startsWith("--browserUrl=") || argument.startsWith("--isolated=")) continue;
            args.add(argument);
        }
        if (skipNext) throw new IOException("原浏览器模式参数缺少值");
        args.add(flag); runtime.set("args", args); return runtime;
    }
    static JsonNode browserTabs(String text) {
        if (text == null) return null;
        try {
            JsonNode value = CanonicalJson.MAPPER.readTree(text); JsonNode rows = value.isArray() ? value : value.path("pages");
            if (rows.isArray()) {
                var normalized = CanonicalJson.MAPPER.createArrayNode();
                for (JsonNode row : rows) {
                    String id = row.path("id").asText(row.path("pageId").asText()), url = row.path("url").asText();
                    if (!row.isObject() || id.isBlank() || url.isBlank()) return null;
                    var page = normalized.addObject().put("id", id).put("url", url); if (row.has("title")) page.set("title", row.get("title"));
                }
                return normalized;
            }
        } catch (IOException ignored) { }
        var rows = CanonicalJson.MAPPER.createArrayNode();
        var pattern = java.util.regex.Pattern.compile("^\\s*(\\d+):\\s+(\\S+?)(?:\\s+\\[selected])?\\s*$");
        for (String line : text.lines().toList()) { var match = pattern.matcher(line); if (match.matches()) rows.addObject().put("id", match.group(1)).put("url", match.group(2)).put("selected", line.endsWith("[selected]")); }
        return rows.isEmpty() ? null : rows;
    }
    private JsonNode approvedConfiguration(JsonNode args, JsonNode context, String collection) throws IOException {
        String id = required(args, "configId"); String version = required(args, "configVersion");
        JsonNode configs = context.path(collection);
        if (configs.isMissingNode() && "browserConfig".equals(collection)) configs = context.path("browserConfigs");
        for (JsonNode value : configs.isArray() ? configs : List.of(configs)) {
            if (id.equals(value.path("id").asText()) && version.equals(value.path("version").asText())) return value;
        }
        throw new IOException("中心配置版本不符: " + id);
    }
    private EmbeddingClient embedding(JsonNode context, RunnerLease lease, CenterToolRegistry tools) throws IOException {
        JsonNode config = context.path("embeddingConfig"); JsonNode secret = context.path("embeddingSecret");
        String provider = required(config, "provider"); String model = required(config, "model"); String url = required(config, "baseUrl");
        if (!List.of("ollama", "openai", "zhipu", "glm").contains(provider.toLowerCase(java.util.Locale.ROOT))) throw new IOException("Embedding供应商不受支持");
        if (!"ollama".equalsIgnoreCase(provider) && (secret.path("apiKey").asText().isBlank() || secret.path("secretRef").asText().isBlank())) throw new IOException("中心未派发Embedding凭证");
        if (!secret.path("expiresAt").isMissingNode() && !RunnerLease.expiry(secret.path("expiresAt")).isAfter(java.time.Instant.now())) throw new IOException("Embedding能力已过期");
        ManagedProviderGrant grant = "ollama".equalsIgnoreCase(provider) ? null : new ManagedProviderGrant(center, lease, context, "embeddingSecret");
        return new EmbeddingClient(provider, model, url, "") {
            @Override public float[] embed(String text) throws IOException {
                lease.ensureActive();
                String key = grant == null ? "" : grant.currentKey();
                var budget = usageBudget(tools, context, lease);
                String bounded = text == null ? "" : text.substring(0, Math.min(text.length(), 2000));
                int estimated = Math.max(1, com.paicli.memory.MemoryEntry.estimateTokens(bounded));
                return budget.externalInputOnly(estimated, () -> {
                    EmbeddingClient client = new EmbeddingClient(provider, model, url, key); var result = client.embed(text); var usage = client.consumeUsage();
                    return new com.paicli.llm.BudgetedLlmClient.ExternalResponse<>(result, usage.inputTokens(), 0, 0, usage.present());
                });
            }
        };
    }
    private SideGitManager snapshots(CenterToolRegistry tools) throws IOException {
        return new SideGitManager(Path.of(tools.getProjectPath()), new SnapshotConfig(true, state.resolve("snapshots"), 500,
                List.of(".git", ".paicli/snapshots", "target", "node_modules", "dist", ".idea", "*.class", "*.jar")));
    }
    private ToolOutput cleanSnapshots(String operation,JsonNode args,RunnerLease lease,CenterToolRegistry tools)throws Exception {
        if(!"current".equals(args.path("range").asText())||!lease.claim().path("rootFingerprint").asText().equals(args.path("rootFingerprint").asText())
                ||lease.claim().path("runnerId").asText().isBlank()||!lease.claim().path("runnerId").asText().equals(args.path("runnerId").asText()))throw new IOException("快照清理未绑定当前root和Runner");
        JsonNode targets=args.path("targets");if(!targets.isArray()||targets.size()>10000)throw new IOException("快照清理缺少完整正式目标集合");
        var known=new java.util.HashSet<String>();
        for(JsonNode target:targets) {
            if(target.size()!=3||!target.path("snapshotMetadataId").asText().matches("[1-9][0-9]{0,18}")||!target.path("snapshotVersion").asText().matches("[1-9][0-9]{0,18}")||!target.path("snapshotId").asText().matches("[a-f0-9]{40}"))throw new IOException("正式快照清理目标无效");
            known.add(target.path("snapshotId").asText());
        }
        SideGitManager snapshots=snapshots(tools);requirePrivateSnapshotCache(snapshots);Map<String,Object> cache=snapshots.snapshotCacheManifest();
        ObjectNode preview=CanonicalJson.MAPPER.valueToTree(cache);preview.remove("manifest");preview.put("operation","CLEAN_PREVIEW").put("range","current").put("previewVersion",1);preview.set("knownTargets",targets);
        var unknown=preview.putArray("unregistered");for(JsonNode id:preview.path("commits"))if(!known.contains(id.asText()))unknown.add(id.asText());
        if(preview.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw new IOException("快照完整清理预览超过16MiB，保留全部原缓存，未清理");
        if("SNAPSHOT_CLEAN_PREVIEW".equals(operation)){event(lease,"SNAPSHOT",preview);return ToolOutput.text(preview.toString());}
        JsonNode approved=args.path("cleanPreview");
        for(String field:List.of("head","refs","commits","knownTargets","unregistered","repositoryManifestHash","fileCount","totalBytes","previewVersion"))
            if(!approved.has(field)||!CanonicalJson.hash(approved.path(field)).equals(CanonicalJson.hash(preview.path(field))))throw new IOException("批准后快照缓存或正式目标发生变化，未清理，请重新预览");
        lease.ensureActive();String id=UUID.randomUUID().toString();Path archive=RunnerPrivateDirectory.createDirectories(state.resolve("snapshot-archives").resolve(id));
        byte[] manifest=CanonicalJson.MAPPER.writeValueAsBytes(cache.get("manifest"));String manifestHash=CanonicalJson.sha256(manifest);
        if(!manifestHash.equals(preview.path("repositoryManifestHash").asText()))throw new IOException("快照备份清单摘要不一致，未清理");
        RunnerPrivateDirectory.writeAtomic(archive,"manifest.json",manifest);
        ObjectNode payload=CanonicalJson.MAPPER.createObjectNode().put("operation","CLEAN").put("range","current").put("repositoryManifestHash",manifestHash);payload.set("knownTargets",targets);
        payload.putObject("backup").put("archiveId",id).put("manifestHash",manifestHash).put("sha256",CanonicalJson.sha256(java.nio.file.Files.readAllBytes(archive.resolve("manifest.json"))))
                .put("fileCount",preview.path("fileCount").asLong()).put("totalBytes",preview.path("totalBytes").asLong()).put("restorable",true);
        try {requirePrivateSnapshotCache(snapshots);snapshots.archiveSnapshotCache(manifestHash,archive.resolve("repository"));}
        catch(Exception incomplete) {
            payload.put("status","CLEAN_RESULT_INCOMPLETE");((ObjectNode)payload.get("backup")).put("restorable",java.nio.file.Files.exists(archive.resolve("repository")));
            try{tools.preserveCurrentOutput(ToolOutput.text(payload.toString()));}finally{lease.invalidate();}
            throw new java.io.UncheckedIOException("快照归档结果未确认，已保留批准范围与备份证据，暂停对账",new IOException(incomplete));
        }
        event(lease,"SNAPSHOT",payload);return ToolOutput.text("当前root快照缓存已归档；可恢复备份 "+id+"，正式清理目标 "+targets.size()+" 项");
    }
    private static void requirePrivateSnapshotCache(SideGitManager snapshots)throws IOException {
        Path cache=snapshots.gitDir().getParent();if(!java.nio.file.Files.exists(cache,java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
        try(var walk=java.nio.file.Files.walk(cache)) {
            var nodes=walk.limit(100_001).toList();if(nodes.size()>100_000)throw new IOException("快照私有缓存超过十万个节点，未清理");
            for(Path node:nodes)if(java.nio.file.Files.isDirectory(node,java.nio.file.LinkOption.NOFOLLOW_LINKS))RunnerPrivateDirectory.requireDirectory(node);else RunnerPrivateDirectory.requireRegularFile(node);
        }
    }
    private ObjectNode metadata(JsonNode args) { ObjectNode value = CanonicalJson.MAPPER.createObjectNode(); for (String key : List.of("configId", "configVersion", "harnessId")) if (args.has(key)) value.set(key, args.get(key)); return value; }
    private void connectionEvent(RunnerLease lease, JsonNode args, String status, String error) throws IOException { ObjectNode payload = metadata(args); payload.put("kind", "MCP"); payload.put("status", status); if (error != null) payload.put("error", error); event(lease, "CONNECTION_RESULT", payload); }
    private void event(RunnerLease lease, String type, JsonNode payload) throws IOException {
        CenterToolRegistry tools = currentTools.get(); if (tools == null) throw new IOException("控制事件缺少执行上下文");
        sendControlEvent(center, lease, tools, type, payload);
    }
    private Map<String, String> promptArguments(JsonNode args) throws IOException {
        JsonNode values = args.path("arguments"); if (values.isMissingNode() || values.isNull()) return Map.of();
        if (!values.isObject()) throw new IOException("MCP提示词参数必须是对象");
        Map<String, String> result = new LinkedHashMap<>();
        for (var fields = values.fields(); fields.hasNext();) {
            var field = fields.next(); if (!field.getValue().isTextual()) throw new IOException("MCP提示词参数必须是文本");
            result.put(field.getKey(), field.getValue().asText());
        }
        return Map.copyOf(result);
    }
    /** 实际控制结果在网络上报前保全；失败只能暂停对账，不转成普通失败回执。 */
    public static void sendControlEvent(CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools, String type, JsonNode payload) throws IOException {
        ObjectNode body=lease.identity(); String id=UUID.randomUUID().toString(); body.put("eventId",id); body.put("type",type); body.set("payload",payload);
        tools.preserveCurrentOutput(ToolOutput.text(body.toString()));
        try {
            lease.ensureEvidenceAllowed();
            boolean largePreviewDiff=false;
            for(JsonNode file:payload.path("preview").path("files"))
                if(file.path("diff").isTextual()&&file.path("diff").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)largePreviewDiff=true;
            if (largePreviewDiff || CanonicalJson.MAPPER.writeValueAsBytes(body).length > 4 * 1024 * 1024) {
                if (!(tools.getToolResultOffloader() instanceof HttpToolResultOffloader offloader)) throw new IOException("完整控制事件缺少中心产物上传适配");
                ObjectNode binding = tools.currentExecutionBinding(); if (binding == null) throw new IOException("控制事件缺少消费绑定");
                JsonNode artifact = offloader.uploadContent(binding.path("toolName").asText(), body.toString());
                ObjectNode reference = CanonicalJson.MAPPER.createObjectNode();
                for (String field : List.of("kind", "configId", "configVersion", "harnessId", "snapshotId", "snapshotMetadataId", "commitId", "currentFingerprint", "operation", "query", "mode", "status"))
                    if (payload.has(field) && payload.path(field).isValueNode()) reference.set(field, payload.get(field));
                ObjectNode catalogues=CanonicalJson.MAPPER.createObjectNode();
                for (String field : List.of("tools", "resources", "prompts")) if (payload.has(field)) {
                    byte[] canonical=CanonicalJson.sorted(payload.get(field)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if(canonical.length>16*1024*1024)throw new IOException("MCP目录超过16MiB支持范围，完整原始结果已保留待对账");
                    catalogues.putObject(field).put("sha256",CanonicalJson.sha256(canonical)).put("size",canonical.length);
                }
                if(!catalogues.isEmpty())reference.set("catalogueDigests",catalogues);
                if(payload.has("logs"))reference.set("logs",payload.get("logs"));
                if("CLEAN_PREVIEW".equals(payload.path("operation").asText())) {
                    for(String field:List.of("range","head","refs","commits","knownTargets","unregistered","repositoryManifestHash","fileCount","totalBytes","previewVersion"))
                        if(payload.has(field))reference.set(field,payload.get(field));
                }
                if("CLEAN".equals(payload.path("operation").asText())) {
                    for(String field:List.of("range","knownTargets","repositoryManifestHash","backup"))if(payload.has(field))reference.set(field,payload.get(field));
                }
                if (payload.path("preview").isObject()) { ObjectNode proof=payload.path("preview").deepCopy();proof.set("files",snapshotProof(proof.path("files")));reference.set("preview",proof); }
                if(payload.has("manifestHash"))reference.set("sourceManifestHash",payload.get("manifestHash"));
                reference.put("hashKind","ARTIFACT_REFERENCE");reference.set("manifestHash",artifact.path("sha256"));
                reference.set("eventArtifactId", artifact.path("artifactId")); reference.set("eventArtifactSha256", artifact.path("sha256")); reference.set("eventArtifactSize", artifact.path("size"));
                body.set("payload", reference);
            }
            center.post("/paicli/runner/v1/events",body,id);
        }
        catch (IOException failure) { lease.invalidate(); throw new java.io.UncheckedIOException("中心控制结果未确认，完整实际结果保留待对账",failure); }
    }
    static JsonNode snapshotProof(JsonNode files) {
        var proof=CanonicalJson.MAPPER.createArrayNode();if(!files.isArray())return proof;
        for(JsonNode value:files) {ObjectNode row=CanonicalJson.MAPPER.createObjectNode();for(String field:List.of("path","status","currentHash","targetHash","currentMode","targetMode"))if(value.has(field))row.set(field,value.get(field));proof.add(row);}return proof;
    }
    static int browserPort(JsonNode config) throws IOException {
        String endpoint = requiredStatic(config, "endpoint");
        java.net.URI uri;
        try { uri = java.net.URI.create(endpoint); } catch (IllegalArgumentException invalid) { throw new IOException("浏览器端点无效", invalid); }
        if (!"http".equals(uri.getScheme()) || !List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) throw new IOException("浏览器必须使用批准的本机HTTP端点");
        int port = uri.getPort(); if (port < 1024 || port > 65535) throw new IOException("浏览器端口必须在1024-65535之间"); return port;
    }
    public com.paicli.snapshot.SnapshotService snapshotService(CenterToolRegistry tools) throws IOException { return new com.paicli.snapshot.SnapshotService(snapshots(tools)); }
    public void configureSearchBudget(CenterToolRegistry tools, com.paicli.llm.BudgetedLlmClient budget) {
        usageBudgets.put(tools, budget);
        if (tools.getCentralSearchProvider() instanceof ManagedSearch search) { search.budget = budget; return; }
        if (!(tools.getCentralSearchProvider() instanceof com.paicli.web.DeepSeekSearchProvider search)) return;
        tools.setCentralSearchProvider(new com.paicli.web.SearchProvider() {
            public String name() { return search.name(); }
            public boolean isReady() { return search.isReady(); }
            public String unavailableHint() { return search.unavailableHint(); }
            public List<com.paicli.web.SearchResult> search(String query, int limit) throws IOException {
                return budget.external(Math.max(1, query.length() / 2 + 1000), () -> {
                    var result = search.search(query, limit); var usage = search.consumeUsage();
                    return new com.paicli.llm.BudgetedLlmClient.ExternalResponse<>(result, usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(), usage.present());
                });
            }
        });
    }
    private com.paicli.llm.BudgetedLlmClient usageBudget(CenterToolRegistry tools, JsonNode context, RunnerLease lease) throws IOException {
        synchronized (usageBudgets) {
            var existing = usageBudgets.get(tools); if (existing != null) return existing;
            LlmClient metadata = new LlmClient() {
                public ChatResponse chat(List<Message> messages, List<Tool> schemas) throws IOException { throw new IOException("向量控制任务不得调用对话模型"); }
                public ChatResponse chat(List<Message> messages, List<Tool> schemas, StreamListener listener) throws IOException { return chat(messages, schemas); }
                public String getModelName() { return "embedding"; } public String getProviderName() { return "center"; }
            };
            var budget = (com.paicli.llm.BudgetedLlmClient) NativeTaskExecutor.budgeted(center, lease, context, metadata); usageBudgets.put(tools, budget); return budget;
        }
    }
    private final class ManagedSearch implements com.paicli.web.SearchProvider {
        private final String provider; private final JsonNode config; private final ManagedProviderGrant grant; private final RunnerLease lease;
        private com.paicli.llm.BudgetedLlmClient budget;
        private ManagedSearch(String provider, JsonNode config, JsonNode context, RunnerLease lease) {
            this.provider = provider; this.config = config.deepCopy(); this.lease = lease;
            this.grant = "searxng".equals(provider) ? null : new ManagedProviderGrant(center, lease, context, "searchSecret");
        }
        public String name() { return provider; }
        public boolean isReady() { return true; }
        public String unavailableHint() { return "中心搜索能力不可用"; }
        public List<com.paicli.web.SearchResult> search(String query, int limit) throws IOException {
            lease.ensureActive(); String key = grant == null ? "" : grant.currentKey();
            com.paicli.web.SearchProvider current = switch (provider) {
                case "zhipu" -> new com.paicli.web.ZhipuSearchProvider(key, config.path("searchEngine").asText("search_std"));
                case "serpapi" -> new com.paicli.web.SerpApiSearchProvider(key);
                case "searxng" -> new com.paicli.web.SearxngSearchProvider(required(config, "baseUrl"));
                default -> new com.paicli.web.DeepSeekSearchProvider(key, required(config, "model"));
            };
            if (current instanceof com.paicli.web.DeepSeekSearchProvider deepSeek && budget != null) {
                return budget.external(Math.max(1, query.length() / 2 + 1000), () -> {
                    var result = deepSeek.search(query, limit); var usage = deepSeek.consumeUsage();
                    return new com.paicli.llm.BudgetedLlmClient.ExternalResponse<>(result, usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(), usage.present());
                });
            }
            return current.search(query, limit);
        }
    }
    private com.paicli.browser.BrowserSession browserSession(JsonNode config) {
        return browserSessions.computeIfAbsent(config.path("id").asText() + ":" + config.path("version").asText(), ignored -> new com.paicli.browser.BrowserSession());
    }
    private static String requiredStatic(JsonNode value, String key) throws IOException { String result = value.path(key).asText(); if (result.isBlank()) throw new IOException("中心缺少 " + key); return result; }
    private String required(JsonNode value, String key) throws IOException { String result = value.path(key).asText(); if (result.isBlank()) throw new IOException("中心缺少 " + key); return result; }
    @Override public synchronized void close() { connections.values().forEach(connection -> connection.manager().close()); connections.clear(); if (extensions != null) extensions.forEach(Extension::close); }
    private record Connection(String version, String hash, String serverName, McpServerManager manager, ManagedMcpGrant grant, ManagedMcpTransport transport) {}
    @FunctionalInterface private interface IoValue<T> { T get() throws IOException; }
}
