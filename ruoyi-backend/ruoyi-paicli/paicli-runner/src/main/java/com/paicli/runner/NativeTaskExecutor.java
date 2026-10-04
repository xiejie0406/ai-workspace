package com.paicli.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.AgentBudget;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.agent.TeamExecutionObserver;
import com.paicli.config.PaiCliConfig;
import com.paicli.history.ConversationLedger;
import com.paicli.image.ImageProcessor;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.llm.BudgetedLlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.render.PlainRenderer;
import com.paicli.runtime.CancellationContext;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillFrontmatterParser;
import com.paicli.skill.SkillRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 三条原生路径复用完整算法，仅凭中心租约、上下文、供应商配置和批准执行。 */
public final class NativeTaskExecutor implements RunnerEngine.TaskExecutor, AutoCloseable {
    @Override public void close() { controls.close(); }
    @FunctionalInterface public interface LlmClientProvider { LlmClient create(JsonNode context) throws Exception; }
    private final CenterHttpClient center;
    private final LlmClientProvider providers;
    private final NativeControlExecutor controls;
    private final Path state;

    public NativeTaskExecutor(CenterHttpClient center) { this(center, defaultState(), null); }
    public NativeTaskExecutor(CenterHttpClient center, Path state) { this(center, state, null); }
    public NativeTaskExecutor(CenterHttpClient center, LlmClientProvider providers) {
        this(center, defaultState(), providers);
    }
    public NativeTaskExecutor(CenterHttpClient center, Path state, LlmClientProvider providers) {
        this.center = center; this.providers = providers; this.state = state.toAbsolutePath().normalize(); this.controls = new NativeControlExecutor(center, this.state, providers);
    }
    private static Path defaultState() { return Path.of(System.getProperty("user.home"), ".paicli-runner-test-state").toAbsolutePath().normalize(); }

    @Override public String execute(RunnerLease lease, CenterToolRegistry tools) throws Exception {
        lease.ensureActive();
        Path project = Path.of(tools.getProjectPath()).toRealPath();
        RunnerPrivateDirectory.createDirectories(state);
        if (!state.toRealPath().equals(state) || state.startsWith(project)) throw new IOException("Runner派生状态必须在工作区外的真实私有目录");
        JsonNode context = hydrateImages(NativeContextAssembler.load(center, lease, center.post("/paicli/runner/v1/context", lease.identity(), UUID.randomUUID().toString())), lease);
        try (var prompts = com.paicli.prompt.PromptRepository.bindCentral(approvedPrompts(context))) {
            return executeBound(lease, tools, context);
        }
    }
    private String executeBound(RunnerLease lease, CenterToolRegistry tools, JsonNode context) throws Exception {
        tools.setCentralStateMode(true); tools.setCentralProjectContext(context.path("projectMemoryContext").asText(""));
        HttpToolResultOffloader offloader = new HttpToolResultOffloader(center, lease, tools, state.resolve("tool-outputs"));
        tools.setToolResultOffloader(offloader); tools.setVirtualFileReader(offloader::read);
        controls.configureDerivedState(tools, context, lease);
        if ("CONTROL".equalsIgnoreCase(context.path("mode").asText(context.path("taskPayload").path("mode").asText())))
            return controls.execute(lease, tools, context);
        try (var snapshots = controls.snapshotService(tools)) {
            tools.setSnapshotService(snapshots);
            new NativeSnapshotAdapter(center,lease,tools,snapshots.manager()).install();
            String turn = "task-" + lease.taskId(); String summary = context.path("text").asText(context.path("input").asText());
            lease.ensureActive(); recordSnapshot(lease, snapshots.manager().preTurnSnapshot(turn, summary));
            try { return runCoding(lease, tools, context); }
            finally {
                boolean evidence = true; try { lease.ensureEvidenceAllowed(); } catch (IOException lost) { evidence = false; }
                if (evidence) recordSnapshot(lease, snapshots.manager().postTurnSnapshot(turn, summary));
            }
        }
    }
    private void recordSnapshot(RunnerLease lease, com.paicli.snapshot.TurnSnapshot snapshot) {
        ObjectNode payload = CanonicalJson.MAPPER.valueToTree(java.util.Map.of("commitId", snapshot.commitId(), "snapshotId", snapshot.commitId(),
                "phase", snapshot.phase().name(), "turnId", snapshot.turnId(), "createdAt", snapshot.createdAt().toString(), "summary", snapshot.summary()));
        payload.put("hashKind", "CANONICAL_MANIFEST"); String hash = CanonicalJson.hash(payload); payload.put("manifestHash", hash); payload.put("sha256", hash);
        sendEvent(lease, "SNAPSHOT", payload);
    }
    private String runCoding(RunnerLease lease, CenterToolRegistry tools, JsonNode context) throws Exception {
        String input = context.path("text").asText(context.path("input").asText());
        if (input.isBlank()) throw new IOException("中心未提供任务输入");
        LlmClient client = budgeted(center, lease, context, providers == null ? managedProvider(center, lease, context) : providers.create(context));
        controls.configureSearchBudget(tools, (BudgetedLlmClient) client);
        if (client == null) throw new IOException("中心未提供可用供应商配置");
        List<LlmClient.ContentPart> images = images(context.path("attachments"));
        if (!images.isEmpty() && !client.supportsImageInput()) throw new IOException("当前模型不支持图片，请切换模型或移除附件");
        Function<String, LlmClient.Message> userMessages = text -> {
            if (images.isEmpty()) return LlmClient.Message.user(text);
            List<LlmClient.ContentPart> parts = new ArrayList<>(); parts.add(LlmClient.ContentPart.text(text)); parts.addAll(images);
            return LlmClient.Message.user(parts);
        };
        HttpCentralMemoryStore storage = new HttpCentralMemoryStore(center, lease, input, tools.getProjectPath(), tools::currentExecutionBinding);
        MemoryManager memory = new MemoryManager(client, 0, context.path("contextWindow").asInt(client.maxContextWindow()), new LongTermMemory(storage));
        memory.setAutoFactExtractionEnabled(context.path("autoFactExtractionEnabled").asBoolean(false));
        ConversationLedger ledger = ConversationLedger.central(lease.sessionId(), context.path("lastMessageSequence").asLong(), ledgerEntries(context), entry -> {
            // 原用户输入已经由中心持久化；模型任务包装文本不能冒充用户原话。
            if (entry.message() != null && "user".equals(entry.message().role())) return;
            JsonNode wire = ledgerPayload(entry, tools, context);
            sendEvent(lease, "LEDGER_MESSAGE", wire);
            if ("compaction".equals(entry.event()) && entry.metadata().get("derivedMessages") instanceof List<?> derived) {
                ObjectNode compact = CanonicalJson.MAPPER.createObjectNode(); compact.put("derived", true); compact.put("source", "AUTOCOMPACTION");
                compact.put("compactionLedgerSequence", entry.sequence()); compact.put("viewMode", entry.mode()); compact.put("viewActor", entry.actor());
                compact.set("messages", wire.path("metadata").path("derivedMessages")); sendEvent(lease, "CONTEXT_COMPACT", compact);
            }
        });
        SkillRegistry skills = approvedSkills(context);
        tools.setCentralSkillBindings(context.path("skillsConfigs"));
        tools.setSkillRegistry(skills); controls.attachConnections(lease, tools, context);
        String effectiveInput = NativeMentionExpander.expand(input, tools);
        lease.ensureActive();
        List<LlmClient.Message> history = new ArrayList<>(history(context));
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).role()) && input.equals(history.get(history.size() - 1).content())) history.remove(history.size() - 1);
        String prior = history.stream().filter(message -> !"system".equals(message.role()))
                .map(message -> message.role() + ": " + message.content()).collect(java.util.stream.Collectors.joining("\n"));
        String mode = context.path("mode").asText("react").toLowerCase(Locale.ROOT);
        try (PrintStream output = new PrintStream(new EventOutput(lease), true, StandardCharsets.UTF_8);
             NativeEventRenderer renderer = new NativeEventRenderer(output, payload -> sendEvent(lease, "MODEL_DELTA", payload))) {
            if ("plan".equals(mode)) {
                AtomicReference<ExecutionPlan> acceptedPlan = new AtomicReference<>();
                PlanExecuteAgent plan = new PlanExecuteAgent(client, tools, memory, (goal, dag) -> {
                    var decision = review(lease, dag, tools); if (decision.action() == PlanExecuteAgent.PlanReviewAction.EXECUTE) acceptedPlan.set(dag); return decision;
                }, output);
                plan.setBudgetFactory(() -> centralBudget(context));
                plan.setConversationLedger(ledger); plan.setExternalContextSupplier(() -> prior);
                plan.setSkillRegistry(skills); plan.setUserMessageFactory(userMessages);
                plan.setExecutionObserver(event -> sendEvent(lease, "PLAN_PROGRESS", CanonicalJson.MAPPER.valueToTree(event)));
                String result = plan.runExplicitTask(effectiveInput, input);
                if (acceptedPlan.get() != null) for (var task : acceptedPlan.get().getAllTasks()) {
                    ObjectNode state = CanonicalJson.MAPPER.createObjectNode(); state.put("id", task.getId()); state.put("status", task.getStatus().name()); sendEvent(lease, "PLAN_STEP", state);
                }
                lease.ensureActive();
                if (plan.getExecutionObservationFailures() > 0) throw new IOException("计划轨迹未获中心确认");
                if (!result.startsWith("✅") && !CancellationContext.isCancelled()) throw new IOException(result);
                return result;
            }
            if ("team".equals(mode)) {
                AgentOrchestrator team = new AgentOrchestrator(client, tools, memory, output);
                team.setBudgetFactory(() -> centralBudget(context));
                team.setPlanApprovalHandler(steps -> {
                    ExecutionPlan dag = new ExecutionPlan("team-" + lease.taskId(), input);
                    for (var step : steps) dag.addTask(new com.paicli.plan.Task(step.id(), step.description(), com.paicli.plan.Task.TaskType.ANALYSIS, step.dependencies()));
                    return review(lease, dag, tools).action() == PlanExecuteAgent.PlanReviewAction.EXECUTE;
                });
                team.setConversationLedger(ledger); team.setExternalContextSupplier(() -> prior);
                team.setSkillSystem(skills); team.setUserMessageFactory(userMessages);
                AtomicReference<TeamExecutionObserver.RunExitReason> terminal = new AtomicReference<>();
                team.setExecutionObserver(event -> {
                    if (event instanceof TeamExecutionObserver.RunExited exit) terminal.set(exit.reason());
                    ObjectNode payload = CanonicalJson.MAPPER.valueToTree(event);
                    if (event instanceof TeamExecutionObserver.RunExited exit)
                        payload.put("reviewStatus", exit.reason() == TeamExecutionObserver.RunExitReason.COMPLETED ? "PASSED" : "FAILED");
                    if (event instanceof TeamExecutionObserver.StepExited step) {
                        ObjectNode progress = CanonicalJson.MAPPER.createObjectNode(); progress.put("id", step.stepId()); progress.put("status", step.productStatus().name());
                        progress.put("reviewStatus", step.reason() == TeamExecutionObserver.StepExitReason.APPROVED ? "PASSED" : "FAILED");
                        sendEvent(lease, "PLAN_STEP", progress);
                    }
                    sendEvent(lease, "TEAM_PROGRESS", payload);
                });
                String result = team.runExplicitTask(effectiveInput, input);
                lease.ensureActive();
                if (team.getExecutionObservationFailures() > 0) throw new IOException("团队轨迹未获中心确认");
                if (terminal.get() != TeamExecutionObserver.RunExitReason.COMPLETED && !CancellationContext.isCancelled()) throw new IOException(result);
                return result;
            }
            if (!List.of("react", "auto", "ask").contains(mode)) throw new IOException("未知中心执行模式");
            Agent react = new Agent(client, tools, memory);
            react.setReturnFinalResponseWhenStreamed(true);
            react.setBudgetFactory(() -> centralBudget(context));
            react.setRenderer(renderer); react.setConversationLedger(ledger); react.setSkillRegistry(skills);
            react.setUserMessageFactory(userMessages); react.restoreConversationHistory(history);
            String result = react.runExplicitTask(effectiveInput, input);
            lease.ensureEvidenceAllowed();
            if (react.getLastRunOutcome() != Agent.RunOutcome.COMPLETED && !CancellationContext.isCancelled())
                throw new IOException("ReAct未完整完成（" + react.getLastRunOutcome() + "）：" + result);
            return result;
        }
    }

    static AgentBudget centralBudget(JsonNode context) {
        JsonNode policy = context.path("policy");
        return new AgentBudget((int) Math.min(Integer.MAX_VALUE, policy.path("taskTokenBudget").asLong(100_000)),
                Math.max(2, policy.path("stagnationWindow").asInt(5)), Math.max(1, policy.path("hardMaxIterations").asInt(100)));
    }
    public static LlmClient budgeted(CenterHttpClient center, RunnerLease lease, JsonNode context, LlmClient client) throws IOException {
        if (client == null) throw new IOException("中心未提供可用供应商配置");
        JsonNode policy = context.path("policy");
        long task = policy.path("taskTokenBudget").asLong();
        long daily = policy.path("dailyRemainingTokenBudget").asLong(task);
        int output = policy.path("maxOutputTokens").asInt(context.path("modelConfig").path("maxOutputTokens").asInt(4096));
        if (task <= 0 || daily <= 0 || output <= 0) throw new IOException("中心剩余Token预算不足或未提供，执行已拒绝");
        return new BudgetedLlmClient(client, Math.min(task, daily), output, usage -> {
            try { ObjectNode body = lease.identity(); String id = UUID.randomUUID().toString(); body.put("eventId", id); body.put("type", "USAGE"); body.set("payload", CanonicalJson.MAPPER.valueToTree(usage)); center.post("/paicli/runner/v1/events", body, id); }
            catch (IOException failure) { lease.invalidate(); throw new UncheckedIOException("中心用量未确认，停止执行", failure); }
        });
    }

    private PlanExecuteAgent.PlanReviewDecision review(RunnerLease lease, ExecutionPlan plan, CenterToolRegistry tools) {
        try {
            ObjectNode body = lease.identity(); body.put("goal", plan.getGoal());
            body.set("steps", CanonicalJson.MAPPER.valueToTree(plan.getAllTasks().stream().map(task ->
                    java.util.Map.of("id", task.getId(), "description", task.getDescription(), "dependencies", task.getDependencies())).toList()));
            while (true) {
                lease.ensureActive();
                if (CancellationContext.isCancelled()) return PlanExecuteAgent.PlanReviewDecision.cancel();
                JsonNode response = center.post("/paicli/runner/v1/plans/review", body, UUID.randomUUID().toString());
                String decision = response.path("decision").asText("pending");
                switch (decision) {
                    case "execute" -> { tools.updatePlanVersion(response.path("planVersion").asText()); return PlanExecuteAgent.PlanReviewDecision.execute(); }
                    case "supplement" -> { return PlanExecuteAgent.PlanReviewDecision.supplement(response.path("feedback").asText()); }
                    case "cancel" -> { return PlanExecuteAgent.PlanReviewDecision.cancel(); }
                    case "pending" -> {
                        if (response.has("planId")) body.set("planId", response.get("planId"));
                        if (response.has("planVersion")) body.set("planVersion", response.get("planVersion"));
                        try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return PlanExecuteAgent.PlanReviewDecision.cancel(); }
                    }
                    default -> throw new IOException("中心计划审查状态无效");
                }
            }
        } catch (IOException failure) { lease.invalidate(); throw new UncheckedIOException("中心计划批准未确认", failure); }
    }

    private void sendEvent(RunnerLease lease, String type, JsonNode payload) {
        try {
            lease.ensureEvidenceAllowed();
            ObjectNode body = lease.identity(); String id = UUID.randomUUID().toString();
            body.put("eventId", id); body.put("type", type); body.set("payload", payload);
            center.post("/paicli/runner/v1/events", body, id);
        } catch (IOException failure) { lease.invalidate(); throw new UncheckedIOException("中心事件未确认，停止执行", failure); }
    }
    private final class EventOutput extends OutputStream {
        private final RunnerLease lease;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        EventOutput(RunnerLease lease) { this.lease = lease; }
        @Override public synchronized void write(int value) {
            pending.write(value);
            if (value == '\n' || pending.size() >= 4096) flush();
        }
        @Override public synchronized void flush() {
            if (pending.size() == 0) return;
            ByteBuffer bytes = ByteBuffer.wrap(pending.toByteArray());
            CharBuffer characters = CharBuffer.allocate(bytes.remaining());
            var result = decoder.decode(bytes, characters, false);
            if (result.isError()) throw new IllegalStateException("Runner输出包含无效UTF-8");
            pending.reset();
            if (bytes.hasRemaining()) pending.write(bytes.array(), bytes.position(), bytes.remaining());
            characters.flip();
            String value = characters.toString();
            if (value.isEmpty()) return;
            ObjectNode payload = CanonicalJson.MAPPER.createObjectNode(); payload.put("delta", value);
            sendEvent(lease, "MODEL_DELTA", payload);
        }
        @Override public void close() {
            flush();
            if (pending.size() > 0) throw new IllegalStateException("Runner输出包含未完成的UTF-8字符");
        }
    }
    private static List<LlmClient.Message> history(JsonNode context) throws IOException {
        JsonNode value = context.has("history") ? context.get("history") : context.path("messages");
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) throw new IOException("中心会话历史格式无效");
        return CanonicalJson.MAPPER.convertValue(value, new TypeReference<List<LlmClient.Message>>() {});
    }
    public static List<ConversationLedger.Entry> ledgerEntries(JsonNode context) throws IOException {
        JsonNode values = context.path("ledgerEntries");
        if (values.isMissingNode()) return List.of();
        if (!values.isArray()) throw new IOException("中心原始账本格式无效");
        return CanonicalJson.MAPPER.convertValue(values, new TypeReference<List<ConversationLedger.Entry>>() {});
    }
    public static JsonNode ledgerPayload(ConversationLedger.Entry entry, CenterToolRegistry tools) {
        return ledgerPayload(entry, tools, CanonicalJson.MAPPER.createObjectNode());
    }
    public static JsonNode ledgerPayload(ConversationLedger.Entry entry, CenterToolRegistry tools, JsonNode context) {
        ObjectNode payload = CanonicalJson.MAPPER.valueToTree(entry);
        return ledgerImageReferences(payload, tools, context.path("__imageArtifactRefs"));
    }
    private static JsonNode ledgerImageReferences(JsonNode value, CenterToolRegistry tools, JsonNode images) {
        if (value.isObject() && "image_base64".equals(value.path("type").asText())) {
            var part = CanonicalJson.MAPPER.convertValue(value, LlmClient.ContentPart.class);
            JsonNode reference = tools.imageArtifactReference(part);
            if (reference == null) {
                String sha = CanonicalJson.sha256(java.util.Base64.getDecoder().decode(part.imageBase64()));
                for (JsonNode image : images) if (sha.equalsIgnoreCase(image.path("sha256").asText()) && part.mimeType().equals(image.path("mimeType").asText())) { reference = image; break; }
            }
            if (reference == null) throw new IllegalStateException("图片尚未保存中心artifact，账本不能复制原始base64");
            return reference.deepCopy();
        }
        if (value.isArray()) { var array = CanonicalJson.MAPPER.createArrayNode(); value.forEach(item -> array.add(ledgerImageReferences(item, tools, images))); return array; }
        if (value.isObject()) { var object = CanonicalJson.MAPPER.createObjectNode(); value.fields().forEachRemaining(field -> object.set(field.getKey(), ledgerImageReferences(field.getValue(), tools, images))); return object; }
        return value;
    }
    private JsonNode hydrateImages(JsonNode context, RunnerLease lease) throws IOException {
        ObjectNode hydrated = ((ObjectNode) context).deepCopy();
        MapImageCache cache = new MapImageCache(lease);
        var attachments = CanonicalJson.MAPPER.createArrayNode();
        for (JsonNode attachment : context.path("attachments")) attachments.add(cache.image(attachment));
        if (attachments.size() > 10) throw new IOException("一轮最多允许10张图片附件");
        hydrated.set("attachments", attachments);
        for (String key : List.of("history", "messages")) if (hydrated.path(key).isArray()) {
            for (JsonNode message : hydrated.path(key)) if (message.path("contentParts").isArray()) {
                var parts = CanonicalJson.MAPPER.createArrayNode();
                int imageCount = 0;
                for (JsonNode part : message.path("contentParts")) {
                    if (part.hasNonNull("artifactId")) {
                        JsonNode image = cache.image(part);
                        parts.add(CanonicalJson.MAPPER.valueToTree(ImageProcessor.toContentPart(ImageProcessor.fromBase64(image.path("base64Data").asText(), image.path("mimeType").asText()))));
                        imageCount++;
                    } else parts.add(part);
                }
                if (imageCount > 10) throw new IOException("历史消息超过图片附件数量上限");
                ((ObjectNode) message).set("contentParts", parts);
            }
        }
        hydrated.set("__imageArtifactRefs", cache.references); return hydrated;
    }
    private final class MapImageCache {
        private final RunnerLease lease;
        private final java.util.Map<String, JsonNode> fetched = new java.util.HashMap<>();
        private final com.fasterxml.jackson.databind.node.ArrayNode references = CanonicalJson.MAPPER.createArrayNode();
        MapImageCache(RunnerLease lease) { this.lease = lease; }
        JsonNode image(JsonNode reference) throws IOException {
            if (!reference.hasNonNull("artifactId")) return reference;
            String id = reference.path("artifactId").asText();
            if (!id.matches("[0-9]+")) throw new IOException("中心图片引用无效");
            JsonNode image = fetched.get(id);
            if (image == null) {
                lease.ensureActive();
                image = center.post("/paicli/runner/v1/artifacts/" + id + "/image", lease.identity(), UUID.randomUUID().toString());
                byte[] bytes;
                try { bytes = java.util.Base64.getDecoder().decode(image.path("base64Data").asText()); }
                catch (IllegalArgumentException invalid) { throw new IOException("中心图片编码无效", invalid); }
                if (bytes.length > 5 * 1024 * 1024 || bytes.length != reference.path("size").asLong(-1)
                        || !CanonicalJson.sha256(bytes).equalsIgnoreCase(reference.path("sha256").asText())
                        || !image.path("mimeType").asText().equals(reference.path("mimeType").asText())) throw new IOException("中心图片内容与已批准附件引用不符");
                ImageProcessor.fromBase64(image.path("base64Data").asText(), image.path("mimeType").asText());
                fetched.put(id, image);
                ObjectNode saved = ((ObjectNode) reference).deepCopy(); saved.put("type", "image_artifact"); references.add(saved);
            }
            return image;
        }
    }
    private static List<LlmClient.ContentPart> images(JsonNode attachments) throws IOException {
        List<LlmClient.ContentPart> result = new ArrayList<>();
        for (JsonNode value : attachments) result.add(ImageProcessor.toContentPart(ImageProcessor.fromBase64(
                value.path("base64Data").asText(value.path("base64").asText()), value.path("mimeType").asText())));
        return List.copyOf(result);
    }
    public static SkillRegistry approvedSkills(JsonNode context) throws IOException {
        SkillRegistry registry = new SkillRegistry(null, null, null, null);
        List<JsonNode> configs = new ArrayList<>(); context.path("skillsConfigs").forEach(configs::add);
        configs.sort(java.util.Comparator.comparingInt(config -> switch (config.path("scope").asText().toUpperCase(Locale.ROOT)) {
            case "BUILTIN" -> 0; case "USER", "PERSONAL" -> 1; default -> 2;
        }));
        for (JsonNode config : configs) {
            if (!config.path("enabled").asBoolean()) continue;
            String content = config.path("content").asText();
            String sha = config.path("sha256").asText();
            if (!config.path("manifestApproved").asBoolean() || config.path("version").asText().isBlank()
                    || content.isBlank() || !sha.matches("[a-fA-F0-9]{64}")
                    || !sha.equalsIgnoreCase(config.path("trustHash").asText())
                    || !sha.equalsIgnoreCase(CanonicalJson.sha256(content.getBytes(StandardCharsets.UTF_8))))
                throw new IOException("技能批准、版本或全文摘要无效: " + config.path("name").asText());
            var parsed = SkillFrontmatterParser.parse(content);
            String name = config.path("name").asText();
            if (name.isBlank() || !parsed.warnings().isEmpty()) throw new IOException("技能名称或frontmatter无效");
            String declared = String.valueOf(parsed.frontmatter().getOrDefault("name", name));
            if (!name.equals(declared)) throw new IOException("技能正文名称与中心manifest不符");
            Skill.Source source = "BUILTIN".equalsIgnoreCase(config.path("scope").asText()) ? Skill.Source.BUILTIN
                    : List.of("USER", "PERSONAL").contains(config.path("scope").asText().toUpperCase(Locale.ROOT)) ? Skill.Source.USER : Skill.Source.PROJECT;
            List<String> tags = new ArrayList<>(); Object declaredTags = parsed.frontmatter().get("tags");
            if (declaredTags instanceof List<?> list) for (Object tag : list) if (tag instanceof String text) tags.add(text);
            registry.registerApproved(new Skill(name, String.valueOf(parsed.frontmatter().getOrDefault("description", "")),
                    String.valueOf(parsed.frontmatter().getOrDefault("version", config.path("version").asText())),
                    String.valueOf(parsed.frontmatter().getOrDefault("author", "")), List.copyOf(tags), source, parsed.body(), null, null));
        }
        return registry;
    }
    public static com.paicli.prompt.PromptRepository approvedPrompts(JsonNode context) throws IOException {
        Map<String, String> personal = new java.util.LinkedHashMap<>(), project = new java.util.LinkedHashMap<>();
        for (JsonNode template : context.path("promptTemplates")) {
            if (!template.path("enabled").asBoolean()) continue;
            String content = template.path("content").asText(), path = template.path("relativePath").asText();
            if (template.path("version").asText().isBlank() || content.isBlank()
                    || !CanonicalJson.sha256(content.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(template.path("sha256").asText()))
                throw new IOException("中心模板版本或全文摘要无效");
            Map<String, String> selected = switch (template.path("scope").asText()) {
                case "PERSONAL", "USER" -> personal; case "PROJECT" -> project; default -> throw new IOException("中心模板scope无效");
            };
            if (selected.putIfAbsent(path, content) != null) throw new IOException("同一层级模板路径重复");
        }
        try { return com.paicli.prompt.PromptRepository.central(personal, project); }
        catch (IllegalArgumentException invalid) { throw new IOException("中心模板路径无效", invalid); }
    }
    public static LlmClient managedProvider(JsonNode context) throws IOException {
        JsonNode secret = context.path("managedSecret");
        String provider = context.path("provider").asText(); String model = context.path("model").asText();
        String key = secret.path("apiKey").asText();
        if (provider.isBlank() || model.isBlank() || key.isBlank() || secret.path("secretRef").asText().isBlank()
                || !RunnerLease.expiry(secret.path("expiresAt")).isAfter(Instant.now())) throw new IOException("中心供应商短期能力缺失或已过期");
        String baseUrl = secret.path("baseUrl").asText(null);
        String loraId = context.path("providerConfig").path("loraId").asText(null);
        // 所有动态getter均封闭到中心配置；空字段不能回退原用户ENV或.env。
        PaiCliConfig config = new PaiCliConfig() {
            @Override public String getApiKey(String ignored) { return key; }
            @Override public String getModel(String ignored) { return model; }
            @Override public String getBaseUrl(String ignored) { return baseUrl; }
            @Override public String getLoraId(String ignored) { return loraId; }
            @Override public String getStartupModel(String ignored) { return model; }
        };
        config.setDefaultProvider(provider);
        PaiCliConfig.ProviderConfig selected = new PaiCliConfig.ProviderConfig(key, secret.path("baseUrl").asText(null), model);
        selected.setWireApi(context.path("providerConfig").path("wireApi").asText(null));
        if (context.path("modelConfig").isObject()) {
            try { selected.getModels().put(model, CanonicalJson.MAPPER.convertValue(context.path("modelConfig"), com.paicli.llm.ModelProfile.class)); }
            catch (IllegalArgumentException invalid) { throw new IOException("中心模型能力配置无效", invalid); }
        }
        config.getProviders().put(provider, selected);
        LlmClient client;
        try { client = LlmClientFactory.create(provider, model, config); }
        catch (IllegalArgumentException invalid) { throw new IOException("中心供应商模型或调用 API 配置非法", invalid); }
        if (client == null) throw new IOException("中心供应商配置不受支持");
        return client;
    }
    public static LlmClient managedProvider(CenterHttpClient center, RunnerLease lease, JsonNode context) throws IOException {
        ManagedProviderGrant grant = new ManagedProviderGrant(center, lease, context, "managedSecret");
        JsonNode current = grant.currentContext(); return LlmClientFactory.attachManagedKey(managedProvider(current), grant::currentKey);
    }
}
