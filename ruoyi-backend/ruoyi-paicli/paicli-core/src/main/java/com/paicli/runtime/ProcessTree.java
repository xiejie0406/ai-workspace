package com.paicli.runtime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/** 取消等待有界；结束父进程和已观察的全部后代后才返回。 */
public final class ProcessTree {
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.Map<Long, ProcessHandle>> OBSERVED = new java.util.concurrent.ConcurrentHashMap<>();
    private ProcessTree() {}
    public static void observe(Process process) {
        java.util.Map<Long, ProcessHandle> descendants = OBSERVED.computeIfAbsent(process.pid(), key -> new java.util.concurrent.ConcurrentHashMap<>());
        process.descendants().forEach(handle -> descendants.put(handle.pid(), handle));
    }

    public static boolean waitFor(Process process, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            observe(process);
            if (CancellationContext.isCancelled()) throw new InterruptedException("任务已取消");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return !process.isAlive();
            if (process.waitFor(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS)) return true;
        }
    }

    public static void terminate(Process process) throws IOException {
        if (process == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            var observed = new LinkedHashMap<Long, ProcessHandle>();
            var prior = OBSERVED.remove(process.pid()); if (prior != null) observed.putAll(prior);
            process.descendants().forEach(handle -> observed.put(handle.pid(), handle));
            process.destroyForcibly();
            // 先终止父进程，阻止继续派生；重复结束已观察后代，不能只 destroy shell。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                process.descendants().forEach(handle -> observed.put(handle.pid(), handle));
                for (var handle : observed.values()) if (handle.isAlive()) handle.destroyForcibly();
                if (!process.isAlive() && observed.values().stream().noneMatch(ProcessHandle::isAlive)) break;
                try { Thread.sleep(20); } catch (InterruptedException failure) { interrupted = true; }
            } while (System.nanoTime() < deadline);
            if (process.isAlive() || observed.values().stream().anyMatch(ProcessHandle::isAlive))
                throw new IOException("进程树尚未完全停止，执行结果必须按不确定状态处理");
        } finally {
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
