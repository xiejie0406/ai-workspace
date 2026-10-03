package com.paicli.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProcessTreeTest {
    @TempDir Path directory;
    @Test void tokenCancellationStopsJavaParentAndChildWithoutWaitingForTimeout() throws Exception {
        Path pid = directory.resolve("child.pid");
        Process parent = new ProcessBuilder(command("parent", pid)).start();
        CancellationToken cancellation = CancellationContext.startRun();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!Files.exists(pid) || Files.size(pid) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(pid), "fixture must start child");
            long childPid = Long.parseLong(Files.readString(pid));
            cancellation.cancel(); long start = System.nanoTime();
            assertThrows(InterruptedException.class, () -> ProcessTree.waitFor(parent, 60_000));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
            ProcessTree.terminate(parent);
            assertFalse(parent.isAlive());
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        } finally { CancellationContext.clear(cancellation); ProcessTree.terminate(parent); }
    }
    private static List<String> command(String mode, Path pid) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-cp", System.getProperty("java.class.path"), ProcessTreeTest.class.getName(), mode, pid.toString());
    }
    public static void main(String[] args) throws Exception {
        if ("parent".equals(args[0])) new ProcessBuilder(command("child", Path.of(args[1]))).start();
        else Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}
