package com.ruoyi.paiflow.worker.service;

import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Effect;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectCompletion;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutionScope implements AutoCloseable {
    private static final ThreadLocal<ExecutionScope> CURRENT = new ThreadLocal<>();
    private final CenterClient center;
    private final Assignment assignment;
    private final Clock clock;
    private final CenterToolConsumer toolOperations;
    private final CenterModelConsumer modelOperations;
    private final CenterHistoryConsumer histories;
    private final AtomicBoolean leaseLost = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<String, Effect> effectsInFlight = new ConcurrentHashMap<>();
    private final Map<String, Object> temporaryState = new ConcurrentHashMap<>();
    private final Object activityMonitor = new Object();
    private int childActivities;

    public ExecutionScope(CenterClient center, Assignment assignment, Clock clock) {
        this.center = center;
        this.assignment = assignment;
        this.clock = clock;
        this.toolOperations = new CenterToolConsumer(this, center, assignment);
        this.modelOperations = new CenterModelConsumer(this, center, assignment);
        this.histories = new CenterHistoryConsumer(this, center, assignment);
    }

    public void bind() {
        if (CURRENT.get() != null) throw new WorkerException("CONTEXT_CONFLICT", "执行上下文已经绑定");
        CURRENT.set(this);
    }

    public static ExecutionScope current() {
        ExecutionScope scope = CURRENT.get();
        if (scope == null) throw new WorkerException("LEASE_REQUIRED", "节点只能在中心租约范围内执行");
        scope.check();
        return scope;
    }

    public void check() {
        if (leaseLost.get()) throw new WorkerException("LEASE_LOST", "中心租约已失效，停止后续节点");
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new WorkerException("RUN_CANCELLED", "运行已请求取消");
        if (!clock.instant().isBefore(assignment.deadline())) throw new WorkerException("DEPENDENCY_TIMEOUT", "运行已超过中心期限");
        if (toolOperations.hasUnknown()) throw new WorkerException("EFFECT_UNKNOWN", "此前工具操作尚未确认，停止后续节点");
        if (modelOperations.hasUnknown()) throw new WorkerException("EFFECT_UNKNOWN", "此前模型操作尚未确认，停止后续节点");
    }

    public void loseLease() { leaseLost.set(true); }
    public void cancel() { cancelled.set(true); }
    public boolean hasUnconfirmedEffect() { return !effectsInFlight.isEmpty() || toolOperations.hasPending() || modelOperations.hasPending(); }
    public void stopPendingOperations() { toolOperations.stopPending(); modelOperations.stopPending(); }
    public com.iflytek.astron.workflow.engine.integration.model.bo.LlmResVo modelCompletion(
            com.iflytek.astron.workflow.engine.integration.model.bo.LlmReqBo request,
            com.iflytek.astron.workflow.engine.integration.model.bo.LlmCallback callback) {
        check(); return modelOperations.call(request, callback);
    }
    private void registerChild() {
        synchronized (activityMonitor) { childActivities++; }
    }
    private void finishChild() {
        synchronized (activityMonitor) { childActivities--; activityMonitor.notifyAll(); }
    }
    /** 包括已排队的节点；等待超时只说明停止尚未确认。 */
    public boolean awaitChildrenStopped(Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        synchronized (activityMonitor) {
            while (childActivities != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(activityMonitor, remaining);
            }
            return true;
        }
    }
    public CenterClient center() { return center; }
    public Lease lease() { return new Lease(assignment.runId(), assignment.leaseId(), assignment.attempt()); }
    public Map<String, Map<String, Object>> resources() { return assignment.resourceSnapshots(); }
    public String executionChatId(String legacy) { check(); return histories.chatId(legacy); }
    public boolean hasHistoryContext() { return assignment.executionContext() != null; }
    public java.util.List<com.iflytek.astron.workflow.engine.integration.model.LlmChatHistory.ChatItem> javaHistory(String chatId, String nodeId) {
        check(); return histories.history(chatId, nodeId);
    }
    public void verifyJavaHistory(com.iflytek.astron.workflow.engine.integration.model.bo.LlmReqBo req,
            java.util.List<Map<String,Object>> messages) { check(); histories.verify(req, messages); }

    public Map<String, Object> boundResource(String resourceId, Map<String, Object> expected) {
        check();
        Map<String, Object> fixed = assignment.resourceSnapshots().get(resourceId);
        if (fixed == null || !Boolean.TRUE.equals(fixed.get("enabled")) || !(fixed.get("version") instanceof String version)
                || version.isBlank() || expected.entrySet().stream().anyMatch(entry -> !java.util.Objects.equals(fixed.get(entry.getKey()), entry.getValue()))) {
            throw new WorkerException("RESOURCE_UNAVAILABLE", "节点资源与中心固定版本不一致");
        }
        Map<String, Object> actual = center.resource(lease(), resourceId);
        check();
        if (!fixed.equals(actual)) throw new WorkerException("VERSION_CONFLICT", "中心资源已变化，须重新核定运行版本");
        return fixed;
    }

    public Map<String, Object> centerOperation(String nodeId, String operation, Map<String, Object> parameters) {
        check();
        if (operation != null && operation.startsWith("tools.")) return toolOperations.execute(nodeId, operation, parameters);
        boolean read = Set.of("tools.read", "knowledge.retrieve", "data.query").contains(operation);
        if (!assignment.effectPolicy().allowedOperations().contains(operation)
                || !read && !"approved_write".equals(assignment.effectPolicy().mode())
                || !(assignment.dsl().get("nodes") instanceof java.util.List<?> nodes)
                || nodes.stream().noneMatch(node -> node instanceof Map<?, ?> entry && nodeId.equals(entry.get("id")))) {
            throw new WorkerException("TOOL_POLICY_DENIED", "运行未授权该中心资源操作");
        }
        String key;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    com.alibaba.fastjson2.JSON.toJSONBytes(parameters));
            key = assignment.runId() + ":" + assignment.attempt() + ":" + nodeId + ":" + operation + ":"
                    + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        String inFlight = "operation:" + key;
        if (!read) effectsInFlight.put(inFlight, new Effect(assignment.leaseId(), assignment.attempt(), operation,
                String.valueOf(parameters.get("resourceId")), "center-operation", assignment.effectPolicy().approvalRef()));
        Map<String, Object> result = center.operation(lease(), nodeId, operation, parameters, key);
        check();
        if (result == null || java.util.List.of("kind", "provider", "resourceId", "resourceVersion", "resourceBindings").stream()
                .anyMatch(field -> !java.util.Objects.equals(parameters.get(field), result.get(field)))) {
            throw new WorkerException("EFFECT_UNKNOWN", "中心操作回执与固定资源不一致");
        }
        if (Set.of("COMPLETED", "FAILED").contains(String.valueOf(result.get("state")))) effectsInFlight.remove(inFlight);
        return result;
    }

    @SuppressWarnings("unchecked")
    public <T> T temporaryState(String key, Supplier<T> initializer) {
        check();
        return (T) temporaryState.computeIfAbsent(key, ignored -> initializer.get());
    }

    public String authorize(String operation, String target) {
        check();
        if (assignment.effectPolicy() == null || assignment.effectPolicy().allowedOperations() == null
                || !assignment.effectPolicy().allowedOperations().contains(operation)) {
            throw new WorkerException("TOOL_POLICY_DENIED", "中心未授权此操作");
        }
        String resourceId = matchingResource(target);
        Effect effect = new Effect(assignment.leaseId(), assignment.attempt(), operation, resourceId, target, assignment.effectPolicy().approvalRef());
        EffectGrant grant = center.authorizeEffect(lease(), effect);
        check();
        if (grant == null || !grant.authorized() || grant.grantId() == null || grant.grantId().isBlank()) {
            throw new WorkerException("TOOL_POLICY_DENIED", "中心未签发本次操作授权");
        }
        if (effectsInFlight.putIfAbsent(grant.grantId(), effect) != null) {
            throw new WorkerException("EFFECT_UNKNOWN", "中心为并行操作重复分配了grant");
        }
        return grant.grantId();
    }

    public void confirmedEffect(String grantId, Map<String, Object> receipt) {
        check();
        if (grantId == null || !effectsInFlight.containsKey(grantId) || receipt == null || receipt.isEmpty()) {
            throw new WorkerException("EFFECT_UNKNOWN", "实际副作用回执缺少本次grant或证据");
        }
        center.completeEffect(lease(), new EffectCompletion(assignment.leaseId(), assignment.attempt(), grantId, "COMPLETED", receipt));
        check();
        effectsInFlight.remove(grantId);
    }

    private String matchingResource(String target) {
        URI actual;
        try { actual = URI.create(target).normalize(); }
        catch (RuntimeException e) { throw new WorkerException("TOOL_POLICY_DENIED", "资源地址无效"); }
        if (actual.getHost() == null || actual.getUserInfo() != null || actual.getFragment() != null
                || !Set.of("http", "https", "ws", "wss").contains(actual.getScheme())) {
            throw new WorkerException("TOOL_POLICY_DENIED", "资源地址协议或凭证无效");
        }
        if (actual.getRawPath() != null && actual.getRawPath().toLowerCase().matches(".*%(2e|2f|5c).*")) {
            throw new WorkerException("TOOL_POLICY_DENIED", "资源地址包含非规范路径");
        }
        for (Map.Entry<String, Map<String, Object>> entry : assignment.resourceSnapshots().entrySet()) {
            Object value = entry.getValue().get("endpoint");
            if (!(value instanceof String endpoint)) continue;
            URI allowed = URI.create(endpoint).normalize();
            String base = allowed.getPath() == null ? "" : allowed.getPath().replaceAll("/+$", "");
            String path = actual.getPath() == null ? "" : actual.getPath();
            if (allowed.getHost() != null && allowed.getUserInfo() == null && allowed.getQuery() == null && allowed.getFragment() == null
                    && actual.getScheme().equals(allowed.getScheme()) && actual.getHost().equalsIgnoreCase(allowed.getHost())
                    && actual.getPort() == allowed.getPort() && (path.equals(base) || path.startsWith(base + "/"))) {
                return entry.getKey();
            }
        }
        throw new WorkerException("RESOURCE_UNAVAILABLE", "地址不属于本次中心资源快照");
    }

    public static Runnable wrap(Runnable action) {
        return wrap(action, () -> {});
    }

    /** 即使许可检查阻止 action 进入，也须完成本地任务的退出通知。 */
    public static Runnable wrap(Runnable action, Runnable onExit) {
        ExecutionScope captured = CURRENT.get();
        if (captured != null) captured.registerChild();
        return () -> {
            ExecutionScope previous = CURRENT.get();
            try { CURRENT.set(captured); if (captured != null) captured.check(); action.run(); }
            finally {
                try { onExit.run(); }
                finally {
                    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                    if (captured != null) captured.finishChild();
                }
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> action) {
        ExecutionScope captured = CURRENT.get();
        if (captured != null) captured.registerChild();
        return () -> {
            ExecutionScope previous = CURRENT.get();
            try { CURRENT.set(captured); if (captured != null) captured.check(); return action.call(); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                if (captured != null) captured.finishChild();
            }
        };
    }

    @Override public void close() { cancelled.set(true); temporaryState.clear(); histories.clear(); CURRENT.remove(); }
}
