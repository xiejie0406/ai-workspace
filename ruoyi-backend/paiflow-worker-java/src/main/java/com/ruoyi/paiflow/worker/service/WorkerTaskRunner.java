package com.ruoyi.paiflow.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.VariablePool;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.domain.WorkflowDSL;
import com.iflytek.astron.workflow.engine.constants.NodeTypeEnum;
import com.iflytek.astron.workflow.engine.domain.callbacks.LLMGenerate;
import com.iflytek.astron.workflow.engine.domain.callbacks.NodeInfo;
import com.iflytek.astron.workflow.engine.node.StreamCallback;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Error;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Event;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Heartbeat;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkerTaskRunner implements AutoCloseable {
    private final CenterClient center;
    private final WorkflowEngine sequential;
    private final ParallelWorkflowEngine parallel;
    private final Clock clock;
    private final long maxRunSeconds;
    private final long heartbeatSeconds;
    private final ObjectMapper mapper;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(4);
    private final AtomicBoolean closed = new AtomicBoolean();
    // 仅进程内防重复投递，不保存正式任务/结果；到中心固定deadline后旧租约必然无效。
    private final Map<Lease, Instant> admittedDeliveries = new HashMap<>();
    private static final int MAX_ADMITTED_DELIVERIES = 4096;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paiflow-worker-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public WorkerTaskRunner(CenterClient center, WorkflowEngine sequential, ParallelWorkflowEngine parallel, Clock clock,
            long maxRunSeconds, long heartbeatSeconds, ObjectMapper mapper) {
        if (maxRunSeconds < 1 || maxRunSeconds > 86400 || heartbeatSeconds < 1 || heartbeatSeconds > 60) {
            throw new IllegalArgumentException("运行预算或心跳间隔无效");
        }
        this.center = center;
        this.sequential = sequential;
        this.parallel = parallel;
        this.clock = clock;
        this.maxRunSeconds = maxRunSeconds;
        this.heartbeatSeconds = heartbeatSeconds;
        this.mapper = mapper;
    }

    public Result execute(Lease lease) {
        validateLease(lease);
        if (closed.get()) throw new WorkerException("RUN_INTERRUPTED", "Worker已停止接收运行");
        if (!slots.tryAcquire()) throw new WorkerException("QUOTA_EXCEEDED", "本Worker执行槽已满");
        try { return executeInSlot(lease, false); }
        finally { slots.release(); }
    }

    /** 先占用同一执行容量再领取；不保存任务、不重试已领取租约。 */
    public Result claimAndExecute() {
        if (closed.get() || !slots.tryAcquire()) return null;
        try {
            if (closed.get() || !deliveryRoom()) return null;
            Lease lease = center.claim();
            if (lease == null) return null;
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                throw new WorkerException("RUN_INTERRUPTED", "停止期间已领取租约，等待中心失效处理");
            }
            return executeInSlot(lease, true);
        } finally { slots.release(); }
    }

    private Result executeInSlot(Lease lease, boolean freshlyClaimed) {
        validateLease(lease);
        if (closed.get()) throw new WorkerException("RUN_INTERRUPTED", "Worker已停止接收运行");
        if (!inFlight.add(lease.runId())) throw new WorkerException("VERSION_CONFLICT", "本Worker正在执行此运行");
        try {
            admitDelivery(lease);
            Assignment assignment;
            try { assignment = center.verify(lease); }
            catch (WorkerException error) {
                // 仅新领取的首次attempt可确认本Worker尚未执行任何节点；旧attempt不能假定没有副作用。
                if (freshlyClaimed && lease.attempt() == 1 && "RUN_CANCELLED".equals(error.getCode())) {
                    Result cancelled = new Result(lease.leaseId(), lease.attempt(), "CANCELLED", null,
                            new Error("RUN_CANCELLED", "中心在首次执行前请求取消，尚未执行节点"));
                    center.result(lease, cancelled);
                    return cancelled;
                }
                throw error;
            }
            validateAssignment(lease, assignment);
            synchronized (admittedDeliveries) { admittedDeliveries.put(lease, assignment.deadline()); }
            JsonDslSnapshotVerifier.verify(assignment.dslCanonicalBase64(), assignment.dslHash(), assignment.dsl(), mapper);
            WorkflowDSL dsl = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .convertValue(assignment.dsl(), WorkflowDSL.class);
            if (dsl.getNodes() == null || dsl.getNodes().size() > 1000 || dsl.getNodes().stream().anyMatch(node -> node.getNodeType() == null
                    || !List.of(NodeTypeEnum.START, NodeTypeEnum.END, NodeTypeEnum.LLM, NodeTypeEnum.PLUGIN).contains(node.getNodeType()))) {
                throw new WorkerException("ENGINE_UNSUPPORTED", "Java Worker只执行原有四类节点");
            }
            requireCanExecute(assignment);
            try { center.beginExecution(lease, assignment); }
            catch (RuntimeException error) {
                // 许可可能已写入中心；重复方不得写终态终止持许可的执行者，也不得重试begin。
                if ("approved_write".equals(assignment.effectPolicy().mode())) {
                    throw new WorkerException("EFFECT_UNKNOWN", "中心一次性执行许可未确认，等待中心核对，禁止执行或重试", error);
                }
                throw error;
            }
            requireCanExecute(assignment);
            return executeVerified(lease, assignment, dsl);
        } finally {
            inFlight.remove(lease.runId());
        }
    }

    private void requireCanExecute(Assignment assignment) {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new WorkerException("RUN_INTERRUPTED", "停止期间不得申请或使用执行许可");
        }
        if (!clock.instant().isBefore(assignment.deadline())) {
            throw new WorkerException("LEASE_LOST", "本地预检或许可响应后中心任务期限已失效");
        }
    }

    private boolean deliveryRoom() {
        synchronized (admittedDeliveries) {
            Instant current = clock.instant();
            admittedDeliveries.entrySet().removeIf(entry -> !current.isBefore(entry.getValue()));
            return admittedDeliveries.size() < MAX_ADMITTED_DELIVERIES;
        }
    }

    private void admitDelivery(Lease lease) {
        synchronized (admittedDeliveries) {
            boolean room = deliveryRoom();
            if (admittedDeliveries.containsKey(lease)) throw new WorkerException("VERSION_CONFLICT", "相同租约已接收，不得重复执行，等待中心处置");
            if (!room) throw new WorkerException("QUOTA_EXCEEDED", "本Worker租约接收保护已满，等待中心期限失效");
            // 未取得有效Assignment时保守保留24小时；取得后缩至中心固定deadline，不推断执行状态。
            admittedDeliveries.put(lease, clock.instant().plusSeconds(86400));
        }
    }

    private Result executeVerified(Lease lease, Assignment assignment, WorkflowDSL dsl) {
        ExecutionScope scope = new ExecutionScope(center, assignment, clock);
        VariablePool variables = new VariablePool();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                Heartbeat status = center.heartbeat(lease);
                if (status == null || !status.valid()) scope.loseLease();
                else if (status.cancelRequested()) scope.cancel();
            } catch (RuntimeException e) { scope.loseLease(); }
        }, 0, heartbeatSeconds, TimeUnit.SECONDS);
        AtomicInteger activity = new AtomicInteger(0); // 0排队、1实际执行、2实际退出。
        CountDownLatch stopped = new CountDownLatch(1);
        Future<?> task = runs.submit(() -> {
            if (!activity.compareAndSet(0, 1)) return;
            try (scope) {
                scope.bind();
                StreamCallback callback = new CenterStreamCallback(center, assignment, clock, scope);
                parallel.execute(dsl, variables, assignment.input(), callback);
                scope.check();
            } catch (Exception e) { throw new WorkerException("EXECUTION_FAILED", "节点执行未完成", e); }
            finally { activity.set(2); stopped.countDown(); }
        });
        Result result;
        try {
            long remaining = Math.min(maxRunSeconds * 1000, Duration.between(clock.instant(), assignment.deadline()).toMillis());
            task.get(Math.max(1, remaining), TimeUnit.MILLISECONDS);
            if (!scope.awaitChildrenStopped(Duration.ofSeconds(2)) || scope.hasUnconfirmedEffect()) result = failed(lease, scope, "EFFECT_UNKNOWN");
            else {
                // 主执行与已登记并行节点均退出后，再核对中心最后一次取消。
                try {
                    Assignment latest = center.verify(lease);
                    validateAssignment(lease, latest);
                    if (!assignment.workflowVersionId().equals(latest.workflowVersionId()) || !assignment.dslHash().equals(latest.dslHash())) {
                        throw new WorkerException("LEASE_LOST", "完成核对的版本或DSL哈希发生变化");
                    }
                    result = new Result(lease.leaseId(), lease.attempt(), "COMPLETED", finalOutput(dsl, variables), null);
                } catch (WorkerException error) { result = failed(lease, scope, error.getCode()); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = stopAndFail(lease, scope, task, activity, stopped, "RUN_CANCELLED");
        } catch (TimeoutException e) {
            result = stopAndFail(lease, scope, task, activity, stopped, "DEPENDENCY_TIMEOUT");
        } catch (ExecutionException e) {
            String code = findCode(e);
            result = stopAndFail(lease, scope, task, activity, stopped, code);
        } finally { heartbeat.cancel(true); }
        // verify与提交间仍可能取消；409或未知ACK传播，不将其转为假CANCELLED/COMPLETED，也不重试。
        center.result(lease, result);
        return result;
    }

    private Result failed(Lease lease, ExecutionScope scope, String code) {
        String state = scope.hasUnconfirmedEffect() || "LEASE_LOST".equals(code) || "EFFECT_UNKNOWN".equals(code) ? "EFFECT_UNKNOWN"
                : "RUN_CANCELLED".equals(code) ? "CANCELLED" : "FAILED";
        return new Result(lease.leaseId(), lease.attempt(), state, null,
                new Error("EFFECT_UNKNOWN".equals(state) ? "EFFECT_UNKNOWN" : code, "运行未完成，请从中心查看节点证据和恢复动作"));
    }

    private Result stopAndFail(Lease lease, ExecutionScope scope, Future<?> task, AtomicInteger activity,
            CountDownLatch stopped, String code) {
        scope.cancel();
        task.cancel(true);
        if (activity.compareAndSet(0, 2)) stopped.countDown();
        boolean interrupted = Thread.interrupted();
        boolean confirmed = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            if (stopped.await(2, TimeUnit.SECONDS)) {
                long remaining = deadline - System.nanoTime();
                confirmed = remaining > 0 && scope.awaitChildrenStopped(Duration.ofNanos(remaining));
            }
        } catch (InterruptedException error) { interrupted = true; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        return failed(lease, scope, confirmed ? code : "EFFECT_UNKNOWN");
    }

    private static String findCode(Throwable error) {
        String result = "EXECUTION_FAILED";
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof WorkerException exception) result = exception.getCode();
        }
        return result;
    }

    private Map<String, Object> finalOutput(WorkflowDSL dsl, VariablePool variables) {
        Map<String, Object> output = new LinkedHashMap<>();
        dsl.getNodes().stream().filter(node -> node.getNodeType() == NodeTypeEnum.END)
                .forEach(node -> output.put(node.getId(), variables.get(node.getId())));
        return output;
    }

    private void validateAssignment(Lease lease, Assignment assignment) {
        if (assignment == null || !"paiflow.worker.v1".equals(assignment.schemaVersion())
                || !Objects.equals(lease.runId(), assignment.runId()) || !Objects.equals(lease.leaseId(), assignment.leaseId())
                || lease.attempt() != assignment.attempt() || !"java".equals(assignment.engineProfile())
                || assignment.workflowVersionId() == null || assignment.workflowVersionId().isBlank()
                || assignment.dslHash() == null || !assignment.dslHash().matches("[a-fA-F0-9]{64}")
                || assignment.dsl() == null || assignment.input() == null || assignment.resourceSnapshots() == null
                || assignment.deadline() == null || !clock.instant().isBefore(assignment.deadline()) || assignment.lastEventSeq() < 0
                || assignment.lastEventSeq() == Long.MAX_VALUE
                || assignment.effectPolicy() == null || !Set.of("read_only", "approved_write").contains(assignment.effectPolicy().mode())
                || assignment.effectPolicy().allowedOperations() == null) {
            throw new WorkerException("LEASE_LOST", "中心分配的任务或租约无效");
        }
        if ("approved_write".equals(assignment.effectPolicy().mode())
                && (assignment.effectPolicy().approvalRef() == null || assignment.effectPolicy().approvalRef().isBlank())) {
            throw new WorkerException("TOOL_POLICY_DENIED", "正式副作用缺少中心批准记录");
        }
        if (assignment.attempt() > 1 && "approved_write".equals(assignment.effectPolicy().mode())) {
            throw new WorkerException("EFFECT_UNKNOWN", "Java Worker尚未实现副作用检查点恢复，不能重跑写操作");
        }
    }

    private static void validateLease(Lease lease) {
        if (lease == null || lease.runId() == null || !lease.runId().matches("[a-zA-Z0-9_-]{1,128}")
                || lease.leaseId() == null || !lease.leaseId().matches("[a-zA-Z0-9_-]{1,128}") || lease.attempt() < 1) {
            throw new WorkerException("LEASE_REQUIRED", "必须提供中心运行标识、租约和attempt");
        }
    }

    @Override public void close() { closed.set(true); runs.shutdownNow(); heartbeats.shutdownNow(); }

    private static final class CenterStreamCallback implements StreamCallback {
        private final CenterClient center;
        private final Assignment assignment;
        private final Clock clock;
        private final ExecutionScope scope;
        private long seq;

        private CenterStreamCallback(CenterClient center, Assignment assignment, Clock clock, ExecutionScope scope) {
            this.center = center; this.assignment = assignment; this.clock = clock; this.scope = scope; seq = assignment.lastEventSeq();
        }

        @Override public synchronized void callback(String eventType, Object data) {
            scope.check();
            String type = eventType;
            String nodeId = null;
            if (data instanceof Map<?, ?> payload && payload.get("nodeId") instanceof String id) nodeId = id;
            if (data instanceof LLMGenerate frame && frame.getWorkflowStep() != null && frame.getWorkflowStep().getNode() != null) {
                NodeInfo node = frame.getWorkflowStep().getNode();
                if ("flow_obj".equals(node.getId())) return;
                nodeId = node.getId();
                String text = frame.getChoices().isEmpty() ? "" : frame.getChoices().get(0).getDelta().getContent();
                String reasoning = frame.getChoices().isEmpty() ? "" : frame.getChoices().get(0).getDelta().getReasoningContent();
                if (text != null && !text.isEmpty()) type = "text.delta";
                else if (reasoning != null && !reasoning.isEmpty()) type = "reasoning.delta";
                else if (frame.getUsage() != null) type = "usage";
                else return;
            }
            if ("stream".equals(type)) type = "text.delta";
            // 原对象帧保全；未来启用字符串流分支时也必须符合中心对象payload契约。
            Object payload = data instanceof String delta ? Map.of("delta", delta) : data;
            try {
                center.event(new Event("paiflow.events.v1", assignment.runId(), assignment.leaseId(), assignment.attempt(),
                        ++seq, nodeId, null, type, clock.instant(), payload));
            } catch (RuntimeException e) { scope.loseLease(); throw e; }
        }
    }
}
