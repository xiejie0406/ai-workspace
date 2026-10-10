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
    }

    public void loseLease() { leaseLost.set(true); }
    public void cancel() { cancelled.set(true); }
    public boolean hasUnconfirmedEffect() { return !effectsInFlight.isEmpty(); }
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
        ExecutionScope captured = CURRENT.get();
        if (captured != null) captured.registerChild();
        return () -> {
            ExecutionScope previous = CURRENT.get();
            try { CURRENT.set(captured); if (captured != null) captured.check(); action.run(); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                if (captured != null) captured.finishChild();
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

    @Override public void close() { cancelled.set(true); temporaryState.clear(); CURRENT.remove(); }
}
