package com.ruoyi.paiflow.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.domain.NodeRunResult;
import com.iflytek.astron.workflow.engine.domain.NodeState;
import com.iflytek.astron.workflow.engine.node.impl.EndNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.StartNodeExecutor;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.*;
import com.ruoyi.paiflow.worker.service.ExecutionScope;
import com.ruoyi.paiflow.worker.service.WorkerTaskRunner;
import com.ruoyi.paiflow.worker.service.WorkerException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerTaskRunnerTest {
    @Test
    void actualOriginalEmptyStartCompletionAndFailureFramesArePreservedAsOriginalObjects() throws Exception {
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();CenterClient center=mock(CenterClient.class);
        List<Event> events=new ArrayList<>();doAnswer(invocation->{events.add(invocation.getArgument(0));return null;}).when(center).event(any());
        var task=assignment(mapper);
        try(ExecutionScope scope=new ExecutionScope(center,task,Clock.systemUTC())) {
            scope.bind();
            Class<?> callbackClass=Class.forName("com.ruoyi.paiflow.worker.service.WorkerTaskRunner$CenterStreamCallback");
            var constructor=callbackClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
            var callback=(com.iflytek.astron.workflow.engine.node.StreamCallback)(constructor.getParameterCount()==5
                    ?constructor.newInstance(center,task,Clock.systemUTC(),scope,mapper):constructor.newInstance(center,task,Clock.systemUTC(),scope));
            var queue=new java.util.concurrent.ConcurrentLinkedQueue<com.iflytek.astron.workflow.engine.domain.callbacks.LLMGenerate>();
            var original=new com.iflytek.astron.workflow.engine.domain.callbacks.ChatCallBacks("actual-source-sid",queue,
                    com.iflytek.astron.workflow.engine.constants.EndNodeOutputModeEnum.VARIABLE_MODE,java.util.Set.of(),new java.util.concurrent.ConcurrentLinkedQueue<>());
            original.onSparkflowStart();original.onNodeStart(0,"node-start::1","原开始");
            NodeRunResult result=new NodeRunResult();result.setInputs(Map.of("input","原输入"));result.setOutputs(Map.of());original.onNodeEnd("node-start::1","原开始",result,null);
            original.onNodeStart(0,"plugin::2","原工具");original.onNodeEnd("plugin::2","原工具",null,
                    new com.iflytek.astron.workflow.exception.NodeCustomException(com.iflytek.astron.workflow.exception.ErrorCode.NODE_RUN_ERROR,"真实算法失败夹具"));
            original.onSparkflowEnd(result);
            List<com.iflytek.astron.workflow.engine.domain.callbacks.LLMGenerate> frames=new ArrayList<>(queue);
            for(var frame:frames)callback.callback("stream",frame);
            String export=System.getProperty("paiflow.worker.originalEmptyFramesPath");
            if(export!=null){var capture=mapper.createObjectNode();capture.set("actualOriginalFrames",mapper.valueToTree(frames));capture.set("actualEvents",mapper.valueToTree(events));java.nio.file.Files.writeString(java.nio.file.Path.of(export),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(capture),java.nio.charset.StandardCharsets.UTF_8);}
            var actualNodeFrames=frames.stream().filter(f->f.getWorkflowStep()!=null&&f.getWorkflowStep().getNode()!=null&&!"flow_obj".equals(f.getWorkflowStep().getNode().getId())).toList();
            assertEquals(4,events.size(),"原空内容开始/完成/失败帧不能被丢弃");assertEquals(actualNodeFrames.size(),events.size());
            for(int index=0;index<events.size();index++) {
                Event event=events.get(index);assertEquals(13+index,event.seq());assertEquals("text.delta",event.eventType());
                assertEquals(actualNodeFrames.get(index).getWorkflowStep().getNode().getId(),event.nodeId());
                assertEquals(mapper.valueToTree(actualNodeFrames.get(index)),mapper.valueToTree(event.payload()).path("legacyFrame"));
                assertFalse(event.eventType().startsWith("node."));
            }
            assertTrue(mapper.valueToTree(events.get(0).payload()).path("legacyFrame").has("usage"));
            assertTrue(mapper.valueToTree(events.get(3).payload()).path("legacyFrame").path("code").asInt()!=0);
        }
    }
    @Test
    void cancelledQueuedActionStillSignalsExitAndReleasesItsRegisteredChild() throws Exception {
        try (ExecutionScope scope = new ExecutionScope(mock(CenterClient.class),
                assignment(new ObjectMapper().findAndRegisterModules()), Clock.systemUTC())) {
            scope.bind();
            var entered = new java.util.concurrent.atomic.AtomicBoolean();
            var exited = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable queued = ExecutionScope.wrap(() -> entered.set(true), () -> exited.set(true));
            scope.cancel();
            assertEquals("RUN_CANCELLED", assertThrows(WorkerException.class, queued::run).getCode());
            assertFalse(entered.get()); assertTrue(exited.get());
            assertTrue(scope.awaitChildrenStopped(java.time.Duration.ZERO));
        }
    }

    @Test
    void callbackWhoseQueuedBodyIsCancelledBeforeEntryStillFinishes() throws Exception {
        try (ExecutionScope scope = new ExecutionScope(mock(CenterClient.class),
                assignment(new ObjectMapper().findAndRegisterModules()), Clock.systemUTC())) {
            scope.bind(); scope.cancel();
            var client = mock(com.iflytek.astron.workflow.engine.node.StreamCallback.class);
            var callback = new com.iflytek.astron.workflow.engine.node.callback.WorkflowMsgCallback("cancel-before-entry", client,
                    com.iflytek.astron.workflow.engine.constants.EndNodeOutputModeEnum.DIRECT_MODE,
                    new java.util.concurrent.ConcurrentLinkedQueue<>(), new java.util.concurrent.ConcurrentLinkedQueue<>());
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), callback::finished);
            assertTrue(scope.awaitChildrenStopped(java.time.Duration.ofSeconds(1)));
            verify(client, never()).callback(anyString(), any());
        }
    }

    @Test
    void interruptedFinishExitsButCannotPretendABlockedCallbackHasStopped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutionScope scope = new ExecutionScope(mock(CenterClient.class),
                assignment(new ObjectMapper().findAndRegisterModules()), Clock.systemUTC())) {
            scope.bind();
            var client = mock(com.iflytek.astron.workflow.engine.node.StreamCallback.class);
            doAnswer(invocation -> {
                entered.countDown();
                boolean waiting = true;
                while (waiting) { try { release.await(); waiting = false; } catch (InterruptedException ignored) {} }
                return null;
            }).when(client).callback(eq("stream"), any());
            var queue = new java.util.concurrent.ConcurrentLinkedQueue<com.iflytek.astron.workflow.engine.domain.callbacks.LLMGenerate>();
            queue.add(new com.iflytek.astron.workflow.engine.domain.callbacks.LLMGenerate());
            var callback = new com.iflytek.astron.workflow.engine.node.callback.WorkflowMsgCallback("blocked-child", client,
                    com.iflytek.astron.workflow.engine.constants.EndNodeOutputModeEnum.DIRECT_MODE, queue,
                    new java.util.concurrent.ConcurrentLinkedQueue<>());
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                Thread.currentThread().interrupt();
                assertEquals("RUN_CANCELLED", assertThrows(WorkerException.class, callback::finished).getCode());
                assertTrue(Thread.interrupted(), "等待退出须保留中断标志");
                assertFalse(scope.awaitChildrenStopped(java.time.Duration.ofMillis(20)), "真实回调未退出，仍须保持未知");
            } finally { Thread.interrupted(); release.countDown(); }
            assertTrue(scope.awaitChildrenStopped(java.time.Duration.ofSeconds(1)));
        } finally { Thread.interrupted(); release.countDown(); }
    }

    @Test
    void cancellationDuringCompletionIsConfirmedOnlyAfterActualEngineStopsAndNoEffectRemainsUnknown() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper)).thenThrow(new WorkerException("RUN_CANCELLED", "中心完成前同步核对发现取消"));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        var ended = new java.util.concurrent.atomic.AtomicInteger();
        EndNodeExecutor observedEnd = new EndNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                ended.incrementAndGet(); return super.executeNode(state, inputs);
            }
        };
        var nodes = List.of(new StartNodeExecutor(), observedEnd);
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            Result result = runner.execute(lease);
            assertEquals(1, ended.get()); assertEquals("CANCELLED", result.state()); assertNull(result.output());
            verify(center, times(2)).verify(lease); verify(center).result(lease, result);
            verify(center, never()).result(eq(lease), argThat(value -> "COMPLETED".equals(value.state())));
        } finally { parallel.close(); }
    }

    @Test
    void cancellationWithARealUnconfirmedGrantCannotPretendAllEffectsWereCancelled() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        when(center.authorizeEffect(eq(lease), any())).thenReturn(new EffectGrant(true, "pending-grant"));
        StartNodeExecutor pendingStart = new StartNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                ExecutionScope.current().authorize("model.infer", "https://approved.invalid/api/chat");
                ExecutionScope.current().cancel();
                return super.executeNode(state, inputs);
            }
        };
        var nodes = List.of(pendingStart, new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            Result result = runner.execute(lease);
            assertEquals("EFFECT_UNKNOWN", result.state()); assertNull(result.output());
            verify(center).result(lease, result); verify(center, never()).completeEffect(any(), any());
        } finally { parallel.close(); }
    }

    @Test
    void cancelledCenterWinsCompletionRaceWithoutWorkerInventingACancelledAckOrRetrying() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        doThrow(new WorkerException("CENTER_REJECTED", "HTTP409模拟取消先持久化")).when(center).result(eq(lease), any());
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            assertEquals("CENTER_REJECTED", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
            verify(center, times(2)).verify(lease);
            verify(center, times(1)).result(eq(lease), argThat(value -> "COMPLETED".equals(value.state())));
            verify(center, never()).result(eq(lease), argThat(value -> "CANCELLED".equals(value.state())));
        } finally { parallel.close(); }
    }
    @Test
    void separateRunnerProcessesCannotReplayAReadOnlyLeaseAfterCenterBeginsIt() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        var begun = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            if (!begun.compareAndSet(false, true)) throw new WorkerException("CENTER_REJECTED", "离线中心拒绝重复begin");
            return null;
        }).when(center).beginExecution(eq(lease), any());
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        StartNodeExecutor observedStart = new StartNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                assertTrue(begun.get(), "真实节点必须在中心begin确认后进入"); executions.incrementAndGet();
                return super.executeNode(state, inputs);
            }
        };
        var nodes = List.of(observedStart, new EndNodeExecutor());
        for (int process = 0; process < 2; process++) {
            ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
            try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                    Clock.systemUTC(), 30, 1, mapper)) {
                if (process == 0) assertEquals("COMPLETED", runner.execute(lease).state());
                else assertEquals("CENTER_REJECTED", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
            } finally { parallel.close(); }
        }
        assertEquals(1, executions.get());
        verify(center, times(2)).beginExecution(eq(lease), any());
        verify(center, times(1)).result(eq(lease), any());
    }

    @Test
    void failedOrUnknownBeginNeverStartsEngineHeartbeatOrEndsAnotherExecution() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        for (boolean write : new boolean[]{false, true}) {
            for (String errorCode : List.of("CENTER_REJECTED", "CENTER_UNAVAILABLE")) {
                CenterClient center = mock(CenterClient.class);
                Assignment original = assignment(mapper);
                Assignment task = new Assignment(original.schemaVersion(), original.runId(), original.workflowVersionId(), original.dslHash(),
                        original.dslCanonicalBase64(), original.engineProfile(), original.input(), original.resourceSnapshots(), original.attempt(),
                        original.leaseId(), original.deadline(), write ? new EffectPolicy("approved_write", List.of("object.write"), "approval-center")
                        : original.effectPolicy(), original.dsl(), original.lastEventSeq());
                when(center.verify(lease)).thenReturn(task);
                doThrow(new WorkerException(errorCode, "离线许可拒绝或ACK丢失")).when(center).beginExecution(eq(lease), any());
                var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
                ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
                try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                        Clock.systemUTC(), 30, 1, mapper)) {
                    assertEquals(write ? "EFFECT_UNKNOWN" : errorCode, assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
                    assertEquals("VERSION_CONFLICT", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
                    verify(center, times(1)).beginExecution(eq(lease), any());
                    verify(center, never()).heartbeat(any()); verify(center, never()).event(any()); verify(center, never()).result(any(), any());
                } finally { parallel.close(); }
            }
        }
    }

    @Test
    void tamperedSnapshotIsRejectedBeforeRequestingTheIrreversibleBeginPermit() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        Assignment task = assignment(mapper);
        when(center.verify(lease)).thenReturn(new Assignment(task.schemaVersion(), task.runId(), task.workflowVersionId(), task.dslHash(),
                task.dslCanonicalBase64(), task.engineProfile(), task.input(), task.resourceSnapshots(), task.attempt(), task.leaseId(),
                task.deadline(), task.effectPolicy(), Map.of(), task.lastEventSeq()));
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            assertEquals("DSL_INVALID", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
            verify(center, never()).beginExecution(any(), any()); verify(center, never()).event(any()); verify(center, never()).result(any(), any());
        } finally { parallel.close(); }
    }

    @Test
    void deadlineReachedDuringBeginAckCannotEnterEngineEvenWithAConfirmedPermit() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        Assignment task = assignment(mapper);
        when(center.verify(lease)).thenReturn(task);
        var now = new java.util.concurrent.atomic.AtomicReference<>(task.deadline().minusSeconds(10));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        doAnswer(invocation -> { now.set(task.deadline()); return null; }).when(center).beginExecution(eq(lease), any());
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                clock, 30, 1, mapper)) {
            assertEquals("LEASE_LOST", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
            verify(center, times(1)).beginExecution(eq(lease), any());
            verify(center, never()).heartbeat(any()); verify(center, never()).event(any()); verify(center, never()).result(any(), any());
        } finally { parallel.close(); }
    }

    @Test
    void deliveryGuardIsBoundedWithoutEvictingAnUnexpiredLeaseToAcceptMoreWork() {
        CenterClient center = mock(CenterClient.class);
        when(center.verify(any())).thenThrow(new WorkerException("LEASE_LOST", "离线无法取得可信快照"));
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                clock, 30, 1, new ObjectMapper().findAndRegisterModules())) {
            for (int index = 0; index < 4096; index++) {
                Lease lease = new Lease("run-" + index, "lease-" + index, 1);
                assertEquals("LEASE_LOST", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
            }
            assertNull(runner.claimAndExecute());
            verify(center, never()).claim();
            assertEquals("QUOTA_EXCEEDED", assertThrows(WorkerException.class,
                    () -> runner.execute(new Lease("run-extra", "lease-extra", 1))).getCode());
            assertEquals("VERSION_CONFLICT", assertThrows(WorkerException.class,
                    () -> runner.execute(new Lease("run-0", "lease-0", 1))).getCode());
            verify(center, times(4096)).verify(any());
            now.set(now.get().plusSeconds(2 * 86400));
            assertNull(runner.claimAndExecute());
            verify(center, times(1)).claim();
            verify(center, never()).event(any());
        } finally { parallel.close(); }
    }

    @Test
    void newlyClaimedFirstAttemptCancelledBeforeEngineReportsOnlyCenterAcknowledgedCancellation() {
        CenterClient center = mock(CenterClient.class);
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.claim()).thenReturn(lease, null);
        when(center.verify(lease)).thenThrow(new WorkerException("RUN_CANCELLED", "中心verify已核对有效租约并请求取消"));
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, new ObjectMapper().findAndRegisterModules())) {
            Result result = runner.claimAndExecute();
            assertEquals("CANCELLED", result.state());
            verify(center).result(lease, result);
            verify(center, never()).event(any());
            assertNull(runner.claimAndExecute());
            verify(center, times(1)).verify(lease);
        } finally { parallel.close(); }
    }

    @Test
    void cancellationOfReceivedOrLaterAttemptCannotPretendOldEffectsWereAbsent() {
        CenterClient center = mock(CenterClient.class);
        Lease received = new Lease("run-test", "lease-test", 1);
        Lease later = new Lease("run-later", "lease-later", 2);
        when(center.verify(any())).thenThrow(new WorkerException("RUN_CANCELLED", "中心请求取消"));
        when(center.claim()).thenReturn(later);
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, new ObjectMapper().findAndRegisterModules())) {
            assertThrows(WorkerException.class, () -> runner.execute(received));
            assertThrows(WorkerException.class, runner::claimAndExecute);
            verify(center, never()).event(any());
            verify(center, never()).result(any(), any());
        } finally { parallel.close(); }
    }

    @Test
    void claimedRunHonorsCenterCancellationBeforeExecutingEndNode() throws Exception {
        assertClaimedHeartbeatStopsRun(new Heartbeat(true, true), "CANCELLED");
    }

    @Test
    void claimedRunWithLostLeaseRemainsUnknownAndIsNotLocallyReexecuted() throws Exception {
        assertClaimedHeartbeatStopsRun(new Heartbeat(false, false), "EFFECT_UNKNOWN");
    }

    private void assertClaimedHeartbeatStopsRun(Heartbeat status, String expectedState) throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.claim()).thenReturn(lease, null);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        CountDownLatch enteredNode = new CountDownLatch(1);
        when(center.heartbeat(lease)).thenAnswer(invocation -> {
            assertTrue(enteredNode.await(5, TimeUnit.SECONDS));
            return status;
        });
        StartNodeExecutor cancellableStart = new StartNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                enteredNode.countDown();
                long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < waitUntil) {
                    ExecutionScope.current().check();
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                throw new AssertionError("中心心跳必须阻止后续执行");
            }
        };
        java.util.concurrent.atomic.AtomicInteger endCalls = new java.util.concurrent.atomic.AtomicInteger();
        EndNodeExecutor observedEnd = new EndNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                endCalls.incrementAndGet();
                return super.executeNode(state, inputs);
            }
        };
        var nodes = List.of(cancellableStart, observedEnd);
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 10, 1, mapper)) {
            Result result = runner.claimAndExecute();
            assertEquals(expectedState, result.state());
            assertEquals(0, endCalls.get());
            verify(center).result(lease, result);
            assertNull(runner.claimAndExecute());
            verify(center, times(1)).verify(lease);
        } finally { parallel.close(); }
    }

    @Test
    void claimReservesExistingExecutionCapacityBeforeCallingCenter() throws Exception {
        CenterClient center = mock(CenterClient.class);
        CountDownLatch claimed = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(center.claim()).thenAnswer(invocation -> { claimed.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; });
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, new ObjectMapper().findAndRegisterModules());
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0, 4).mapToObj(index -> callers.submit(runner::claimAndExecute)).toList();
            try {
                assertTrue(claimed.await(5, TimeUnit.SECONDS));
                assertNull(runner.claimAndExecute());
                verify(center, times(4)).claim();
            } finally { release.countDown(); }
            for (var call : calls) assertNull(call.get(5, TimeUnit.SECONDS));
            verify(center, never()).verify(any());
        } finally { parallel.close(); }
    }

    @Test
    void claimUsesFreshVerifyAndUnacknowledgedResultIsNeverReexecutedLocally() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.claim()).thenReturn(lease, lease, null);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        doThrow(new WorkerException("CENTER_UNAVAILABLE", "离线模拟终态回执丢失")).when(center).result(eq(lease), any());
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            assertEquals("CENTER_UNAVAILABLE", assertThrows(WorkerException.class, runner::claimAndExecute).getCode());
            assertEquals("VERSION_CONFLICT", assertThrows(WorkerException.class, runner::claimAndExecute).getCode());
            assertEquals("VERSION_CONFLICT", assertThrows(WorkerException.class, () -> runner.execute(lease)).getCode());
            assertNull(runner.claimAndExecute());
            verify(center, times(3)).claim();
            verify(center, times(2)).verify(lease);
            verify(center, times(1)).result(eq(lease), argThat(result -> "COMPLETED".equals(result.state())));
        } finally { parallel.close(); }
    }

    @Test
    void expiredClaimSnapshotIsRejectedAndClosedRunnerCannotClaimAgain() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.claim()).thenReturn(lease);
        Assignment task = assignment(mapper);
        when(center.verify(lease)).thenReturn(new Assignment(task.schemaVersion(), task.runId(), task.workflowVersionId(), task.dslHash(),
                task.dslCanonicalBase64(), task.engineProfile(), task.input(), task.resourceSnapshots(), task.attempt(), task.leaseId(),
                Instant.EPOCH, task.effectPolicy(), task.dsl(), task.lastEventSeq()));
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            assertEquals("LEASE_LOST", assertThrows(WorkerException.class, runner::claimAndExecute).getCode());
            verify(center, never()).event(any());
            verify(center, never()).result(any(), any());
            runner.close();
            assertNull(runner.claimAndExecute());
            verify(center, times(1)).claim();
        } finally { parallel.close(); }
    }

    @Test
    void actualRunnerUsesCenterSnapshotSequencesAndReturnsOnlyAcknowledgedTerminalResult() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        List<Event> events = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> { events.add(invocation.getArgument(0)); return null; }).when(center).event(any());
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            var result = runner.execute(lease);
            assertEquals("COMPLETED", result.state());
            assertEquals("结果：中心实际输入", ((Map<?, ?>) result.output().get("node-end::2")).get("content"));
            verify(center).result(lease, result);
            assertFalse(events.isEmpty());
            var order = inOrder(center); order.verify(center).verify(lease); order.verify(center).beginExecution(eq(lease), any());
            for (int index = 0; index < events.size(); index++) assertEquals(13 + index, events.get(index).seq());
            assertTrue(events.stream().anyMatch(event -> "node.started".equals(event.eventType())));
            assertTrue(events.stream().anyMatch(event -> "node.completed".equals(event.eventType())));
            assertTrue(events.stream().noneMatch(event -> event.eventType().startsWith("run.")));
        } finally { parallel.close(); }
    }

    @Test
    void futureStringCallbackIsAnObjectWithoutDroppingOriginalLifecycleFrames() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        List<Event> events = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> { events.add(invocation.getArgument(0)); return null; }).when(center).event(any());
        StartNodeExecutor originalStartWithFutureString = new StartNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                state.callback().callback("stream", "未来字符串片段");
                return super.executeNode(state, inputs);
            }
        };
        var nodes = List.of(originalStartWithFutureString, new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            assertEquals("COMPLETED", runner.execute(lease).state());
            Event delta = events.stream().filter(event -> "text.delta".equals(event.eventType())
                    && mapper.valueToTree(event.payload()).has("delta")).findFirst().orElseThrow();
            assertEquals(Map.of("delta", "未来字符串片段"), delta.payload());
            assertTrue(events.stream().allMatch(event -> mapper.valueToTree(event.payload()).isObject()));
            assertTrue(events.stream().anyMatch(event -> "node.completed".equals(event.eventType())
                    && mapper.valueToTree(event.payload()).has("result")));
        } finally { parallel.close(); }
    }

    @Test
    void completedEndNodeWithUnconfirmedGrantMustRemainEffectUnknown() throws Exception {
        CenterClient center = mock(CenterClient.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Lease lease = new Lease("run-test", "lease-test", 1);
        when(center.verify(lease)).thenReturn(assignment(mapper));
        when(center.heartbeat(lease)).thenReturn(new Heartbeat(true, false));
        when(center.authorizeEffect(eq(lease), any())).thenReturn(new EffectGrant(true, "pending-grant"));
        StartNodeExecutor originalStartWithPendingTransport = new StartNodeExecutor() {
            @Override protected NodeRunResult executeNode(NodeState state, Map<String, Object> inputs) {
                ExecutionScope.current().authorize("model.infer", "https://approved.invalid/api/chat");
                return super.executeNode(state, inputs);
            }
        };
        var nodes = List.of(originalStartWithPendingTransport, new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.systemUTC(), 30, 1, mapper)) {
            var result = runner.execute(lease);
            assertEquals("EFFECT_UNKNOWN", result.state());
            assertEquals("EFFECT_UNKNOWN", result.error().code());
            assertNull(result.output());
            verify(center).result(lease, result);
            verify(center, never()).completeEffect(any(), any());
        } finally { parallel.close(); }
    }

    @SuppressWarnings("unchecked")
    private Assignment assignment(ObjectMapper mapper) throws Exception {
        var dsl = mapper.convertValue(CoreEngineTest.dsl(), Map.class);
        byte[] canonical = mapper.writeValueAsBytes(dsl);
        return new Assignment("paiflow.worker.v1", "run-test", "version-test",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)), Base64.getEncoder().encodeToString(canonical),
                "java", Map.of("question", "中心实际输入"), Map.of("model", Map.of("endpoint", "https://approved.invalid/api")),
                1, "lease-test", Instant.now().plusSeconds(30), new EffectPolicy("read_only", List.of("model.infer"), null), dsl, 12);
    }
}
