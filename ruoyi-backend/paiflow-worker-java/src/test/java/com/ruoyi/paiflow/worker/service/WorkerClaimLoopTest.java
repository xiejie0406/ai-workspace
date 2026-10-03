package com.ruoyi.paiflow.worker.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerClaimLoopTest {
    private record Tick(Runnable task, long delay) {}

    private ScheduledExecutorService scheduler(List<Tick> ticks, ScheduledFuture<?> future) {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            ticks.add(new Tick(invocation.getArgument(0), invocation.getArgument(1)));
            return future;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        return scheduler;
    }

    @Test
    void failureBackoffIsBoundedAndNewSuccessfulClaimResetsShortPolling() {
        WorkerTaskRunner runner = mock(WorkerTaskRunner.class);
        when(runner.claimAndExecute()).thenThrow(new WorkerException("LEASE_LOST", "离线失租约"),
                new WorkerException("CENTER_UNAVAILABLE", "离线失败"), new WorkerException("CENTER_UNAVAILABLE", "离线失败")).thenReturn(null);
        List<Tick> ticks = new ArrayList<>();
        ScheduledExecutorService scheduler = scheduler(ticks, mock(ScheduledFuture.class));
        try (WorkerClaimLoop loop = new WorkerClaimLoop(runner, 100, 300, () -> scheduler)) {
            loop.start();
            assertTrue(loop.isRunning());
            assertEquals(0, ticks.get(0).delay());
            ticks.get(0).task().run();
            assertEquals(200, ticks.get(1).delay());
            ticks.get(1).task().run();
            assertEquals(300, ticks.get(2).delay());
            ticks.get(2).task().run();
            assertEquals(300, ticks.get(3).delay());
            ticks.get(3).task().run();
            assertEquals(100, ticks.get(4).delay());
            verify(runner, times(4)).claimAndExecute();
        }
        verify(scheduler).shutdownNow();
    }

    @Test
    void stopCancelsPendingWorkAndOldTicksCannotRestartAfterLifecycleRestart() {
        WorkerTaskRunner runner = mock(WorkerTaskRunner.class);
        List<Tick> oldTicks = new ArrayList<>();
        List<Tick> newTicks = new ArrayList<>();
        ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
        ScheduledExecutorService oldScheduler = scheduler(oldTicks, oldFuture);
        ScheduledExecutorService newScheduler = scheduler(newTicks, mock(ScheduledFuture.class));
        var schedulers = List.of(oldScheduler, newScheduler).iterator();
        try (WorkerClaimLoop loop = new WorkerClaimLoop(runner, 100, 300, schedulers::next)) {
            loop.start();
            loop.stop();
            assertFalse(loop.isRunning());
            verify(oldFuture).cancel(true);
            verify(oldScheduler).shutdownNow();
            loop.start();
            oldTicks.getFirst().task().run();
            verifyNoInteractions(runner);
            newTicks.getFirst().task().run();
            verify(runner, times(1)).claimAndExecute();
            assertEquals(2, newTicks.size());
        }
    }

    @Test
    void stoppingActualSchedulerInterruptsCurrentInvocationWithoutRepeatingIt() throws Exception {
        WorkerTaskRunner runner = mock(WorkerTaskRunner.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(runner.claimAndExecute()).thenAnswer(invocation -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            throw new WorkerException("RUN_INTERRUPTED", "关闭中，等待中心处置");
        });
        try (WorkerClaimLoop loop = new WorkerClaimLoop(runner, 100, 300)) {
            loop.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            loop.stop();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            verify(runner, times(1)).claimAndExecute();
            assertFalse(loop.isRunning());
        }
    }
}
