package com.paicli.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 任务与PTY共用物理目录互斥；锁名不包含owner、会话或业务目录ID。 */
final class PhysicalDirectoryLease implements AutoCloseable {
    private static final Set<Path> ACTIVE = new HashSet<>();
    private final Path root;
    private final WorkspaceLockDirectory.Held held;
    private boolean closed;
    private PhysicalDirectoryLease(Path root, WorkspaceLockDirectory.Held held) { this.root = root; this.held = held; }

    static PhysicalDirectoryLease acquire(Path requested, Path lockHome) throws IOException {
        Path root = new ResourceFingerprint(requested).root();
        synchronized (ACTIVE) {
            for (Path active : ACTIVE)
                if (root.startsWith(active) || active.startsWith(root)) throw new IOException("该目录已有执行或终端，请先结束当前操作");
            WorkspaceLockDirectory.Held held = WorkspaceLockDirectory.acquire(WorkspaceLockDirectory.prepare(lockHome),
                new ResourceFingerprint(root).rootFingerprint() + ".lock");
            ACTIVE.add(root);
            return new PhysicalDirectoryLease(root, held);
        }
    }

    @Override public void close() throws IOException {
        synchronized (ACTIVE) { if (closed) return; closed = true; try { held.close(); } finally { ACTIVE.remove(root); } }
    }
}
