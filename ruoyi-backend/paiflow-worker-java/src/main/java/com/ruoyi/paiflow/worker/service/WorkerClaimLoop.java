package com.ruoyi.paiflow.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 单线程短轮询；容量与执行使用同一Runner，持久租约和重新派发只由中心管理。 */
public final class WorkerClaimLoop implements SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerClaimLoop.class);
    private final WorkerTaskRunner runner;
    private final long intervalMillis;
    private final long maxBackoffMillis;
    private final Supplier<ScheduledExecutorService> schedulers;
    private volatile boolean running;
    private long generation;
    private int failures;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pending;

    public WorkerClaimLoop(WorkerTaskRunner runner, long intervalMillis, long maxBackoffMillis) {
        this(runner, intervalMillis, maxBackoffMillis, () -> Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "paiflow-worker-claim");
            thread.setDaemon(true);
            return thread;
        }));
    }

    WorkerClaimLoop(WorkerTaskRunner runner, long intervalMillis, long maxBackoffMillis,
            Supplier<ScheduledExecutorService> schedulers) {
        if (intervalMillis < 100 || intervalMillis > 60_000 || maxBackoffMillis < intervalMillis || maxBackoffMillis > 60_000) {
            throw new IllegalArgumentException("任务轮询间隔须为100至60000ms，最大退避不得超过60000ms");
        }
        this.runner = runner;
        this.intervalMillis = intervalMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.schedulers = schedulers;
    }

    @Override public synchronized void start() {
        if (running) return;
        scheduler = schedulers.get();
        failures = 0;
        running = true;
        schedule(++generation, 0);
    }

    private synchronized void schedule(long current, long delay) {
        if (running && generation == current) {
            pending = scheduler.schedule(() -> tick(current), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void tick(long current) {
        synchronized (this) { if (!running || generation != current) return; }
        long delay = intervalMillis;
        try {
            runner.claimAndExecute();
            synchronized (this) { if (generation == current) failures = 0; }
        } catch (RuntimeException error) {
            synchronized (this) {
                if (!running || generation != current) return;
                failures = Math.min(failures + 1, 20);
                delay = Math.min(maxBackoffMillis, intervalMillis * (1L << failures));
            }
            String code = error instanceof WorkerException failure ? failure.getCode() : "EXECUTION_UNCONFIRMED";
            // 不记录远端正文、凭据或节点内容；持有的租约不在本地重跑，等待中心裁决/失效。
            LOG.warn("任务领取或执行未获确认(code={})；本地不重试该租约，等待中心处置，下一次领取退避{}ms", code, delay);
        }
        schedule(current, delay);
    }

    @Override public synchronized void stop() {
        if (!running) return;
        running = false;
        generation++;
        if (pending != null) pending.cancel(true);
        scheduler.shutdownNow();
    }

    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void close() { stop(); }
}
