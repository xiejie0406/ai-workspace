package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.application.intelligence.OcrService;
import com.ruoyi.system.secret.ManagedSecretService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真实本地父/子Java进程验证中断与超时；不加载解析库、账号、网络或业务数据库。 */
class IsolatedParserLifecycleTest {
    @TempDir Path directory;
    private Path classes;
    private MockEnvironment environment;
    private IsolatedDocumentParser parser;
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final AtomicBoolean interrupted=new AtomicBoolean();

    @BeforeEach void prepare() throws Exception {
        classes=Files.createDirectory(directory.resolve("fixture-classes"));
        Path source=directory.resolve("ParserWorker.java");
        // 只在本次JUnit临时classpath内替代Worker，以真实启动树检查包装器。
        Files.writeString(source, """
            package com.ruoyi.paismart.infrastructure.intelligence;
            import java.nio.file.*;
            public final class ParserWorker {
                public static void main(String[] args) throws Exception {
                    if ("999".equals(args[3])) {
                        Files.writeString(Path.of(args[1]), "{\\"pages\\":[{\\"number\\":1,\\"text\\":\\"approved lifecycle fixture\\"}]}");
                        return;
                    }
                    if ("997".equals(args[3])) { System.exit(7); return; }
                    Path classes=Path.of(ParserWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    if ("998".equals(args[3])) {
                        byte[] bytes=new byte[65536];
                        java.util.Arrays.fill(bytes,(byte)'x');
                        for(int i=0;i<128;i++) { System.out.write(bytes); System.err.write(bytes); }
                        System.out.flush(); System.err.flush();
                        Files.writeString(classes.resolve("logging-ready-new"), ProcessHandle.current().pid()+"\\n"+Path.of(args[0]).getParent());
                        Files.move(classes.resolve("logging-ready-new"),classes.resolve("logging-ready"),StandardCopyOption.ATOMIC_MOVE);
                        while(!Files.exists(classes.resolve("logging-release"))) Thread.sleep(10);
                        Files.writeString(Path.of(args[1]), "{\\"pages\\":[{\\"number\\":1,\\"text\\":\\"approved lifecycle fixture\\"}]}");
                        return;
                    }
                    String exe=Path.of(System.getProperty("java.home"),"bin",
                        System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                    Process child=new ProcessBuilder(exe,"-cp",classes.toString(),Child.class.getName())
                        .inheritIO().start();
                    Files.writeString(classes.resolve("pids-new"), ProcessHandle.current().pid()+"\\n"+child.pid()+"\\n"+Path.of(args[0]).getParent());
                    Files.move(classes.resolve("pids-new"),classes.resolve("pids"),StandardCopyOption.ATOMIC_MOVE);
                    child.waitFor();
                }
                public static final class Child {
                    public static void main(String[] args) throws Exception { Thread.sleep(30000); }
                }
            }
            """, StandardCharsets.UTF_8);
        var compiler=ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"本地技术检查需要既有JDK编译器");
        var diagnostics=new ByteArrayOutputStream();
        assertEquals(0,compiler.run(null,diagnostics,diagnostics,"--release","17","-encoding","UTF-8",
            "-d",classes.toString(),source.toString()),diagnostics.toString(StandardCharsets.UTF_8));
        environment=new MockEnvironment()
            .withProperty("paismart.intelligence.parse.process-enabled","true")
            .withProperty("paismart.intelligence.parse.worker-classpath",classes.toString())
            .withProperty("paismart.intelligence.parse.concurrency","1")
            .withProperty("paismart.intelligence.parse.timeout-seconds","15")
            .withProperty("paismart.intelligence.parse.max-characters","1000");
        parser=new IsolatedDocumentParser(new IntelligenceSettings(environment,mock(ManagedSecretService.class)),
            mock(OcrService.class));
    }

    @Test void interruptionTerminatesOwnedProcessTreeBeforeCleanupAndReleasesPermit() throws Exception {
        Thread parsing=startParsing();
        ProcessTree tree=null;
        try {
            tree=awaitProcessTree();
            assertTrue(tree.parent().isAlive());
            assertTrue(tree.child().isAlive());
            parsing.interrupt();
            parsing.join(5000);
            assertFalse(parsing.isAlive(),"解析调用应完成中断退出");
            assertInstanceOf(IllegalStateException.class,failure.get());
            assertTrue(interrupted.get(),"调用方中断标记必须保留");
            assertTreeStopped(tree);
            assertInstanceOf(InterruptedException.class,failure.get().getCause());
            assertFalse(Files.exists(tree.work()),"进程退出后才能回收临时目录");
            assertPermitReusable();
        } finally { cleanupOwned(tree,parsing); }
    }

    @Test void timeoutTerminatesOwnedProcessTreeBeforeCleanupAndReleasesPermit() throws Exception {
        environment.withProperty("paismart.intelligence.parse.timeout-seconds","2");
        Thread parsing=startParsing();
        ProcessTree tree=null;
        try {
            tree=awaitProcessTree();
            parsing.join(6000);
            assertFalse(parsing.isAlive());
            assertInstanceOf(IllegalStateException.class,failure.get());
            assertEquals("解析超时",failure.get().getMessage());
            assertFalse(interrupted.get());
            assertTreeStopped(tree);
            assertFalse(Files.exists(tree.work()));
            assertPermitReusable();
        } finally { cleanupOwned(tree,parsing); }
    }

    @Test void discardedWorkerOutputDoesNotConsumeTemporaryDiskAndPreservesResultsAndExitCode() throws Exception {
        environment.withProperty("paismart.intelligence.parse.max-characters","998");
        var result=new AtomicReference<List<IsolatedDocumentParser.Page>>();
        Thread parsing=startParsing(result);
        LoggingProcess owned=null;
        try {
            owned=awaitLoggingProcess();
            assertTrue(owned.handle().isAlive(),"输出完成后夹具必须仍停在测试门闩前");
            long temporaryBytes=0;
            var temporaryFiles=new java.util.ArrayList<String>();
            try(var files=Files.walk(owned.work())) {
                for(Path file:files.filter(Files::isRegularFile).toList()) {
                    long size=Files.size(file);
                    temporaryBytes+=size;
                    temporaryFiles.add(owned.work().relativize(file)+":"+size);
                }
            }
            assertTrue(temporaryBytes<64*1024,"16 MiB stdout/stderr不能写入任务临时磁盘，实际字节="+temporaryBytes+", files="+temporaryFiles);
            Files.writeString(classes.resolve("logging-release"),"approved release");
            parsing.join(5000);
            assertFalse(parsing.isAlive());
            assertNull(failure.get());
            assertFalse(interrupted.get());
            assertEquals(List.of(new IsolatedDocumentParser.Page(1,"approved lifecycle fixture")),result.get());
            owned.handle().onExit().get(3,java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(Files.exists(owned.work()),"输出被丢弃后仍必须回收任务目录");
            assertPermitReusable();
            environment.withProperty("paismart.intelligence.parse.max-characters","997");
            var nonzero=assertThrows(IllegalStateException.class,()->parser.parse(
                new ByteArrayInputStream("approved fixture".getBytes(StandardCharsets.UTF_8)),"text/plain",null));
            assertEquals("解析独立进程失败，退出码7",nonzero.getMessage());
            assertPermitReusable();
        } finally { cleanupLoggingFixture(owned,parsing); }
    }

    private Thread startParsing() {
        return startParsing(null);
    }

    private Thread startParsing(AtomicReference<List<IsolatedDocumentParser.Page>> result) {
        Thread thread=new Thread(()->{
            try {
                var pages=parser.parse(new ByteArrayInputStream("approved fixture".getBytes(StandardCharsets.UTF_8)),"text/plain",null);
                if(result!=null)result.set(pages);
            }
            catch(Throwable e) { failure.set(e); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        },"paismart-lifecycle-test");
        thread.start();
        return thread;
    }

    private LoggingProcess awaitLoggingProcess() throws Exception {
        Path ready=classes.resolve("logging-ready");
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!Files.exists(ready) && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(Files.exists(ready),"夹具必须实际输出全部16 MiB后再发布进程和任务目录");
        List<String> values=Files.readAllLines(ready);
        assertEquals(2,values.size());
        var handle=ProcessHandle.of(Long.parseLong(values.get(0))).orElseThrow();
        assertEquals(ProcessHandle.current(),handle.parent().orElseThrow(),"只观察本次测试JVM启动的夹具");
        Path work=Path.of(values.get(1)).toAbsolutePath().normalize();
        Path temp=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertEquals(temp,work.getParent());
        assertTrue(work.getFileName().toString().startsWith("paismart-parse-"));
        return new LoggingProcess(handle,work);
    }

    private void cleanupLoggingFixture(LoggingProcess owned,Thread parsing) throws Exception {
        Files.writeString(classes.resolve("logging-release"),"approved cleanup release");
        parsing.join(5000);
        if(parsing.isAlive())parsing.interrupt();
        if(owned!=null) {
            if(owned.handle().isAlive())owned.handle().destroyForcibly();
            owned.handle().onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        parsing.join(5000);
        if(owned!=null && Files.isDirectory(owned.work())) {
            Path temp=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            assertEquals(temp,owned.work().getParent());
            assertTrue(owned.work().getFileName().toString().startsWith("paismart-parse-"));
            List<Path> paths;
            try(var files=Files.walk(owned.work())) { paths=files.sorted(java.util.Comparator.reverseOrder()).toList(); }
            for(Path path:paths)Files.deleteIfExists(path);
        }
    }

    private ProcessTree awaitProcessTree() throws Exception {
        Path pids=classes.resolve("pids");
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!Files.exists(pids) && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(Files.exists(pids),"夹具应在预算内写入其实际进程标识");
        List<String> values=Files.readAllLines(pids);
        assertEquals(3,values.size());
        var parent=ProcessHandle.of(Long.parseLong(values.get(0))).orElseThrow();
        var child=ProcessHandle.of(Long.parseLong(values.get(1))).orElseThrow();
        assertEquals(ProcessHandle.current(),parent.parent().orElseThrow(),"夹具父进程必须属于本次测试JVM");
        assertEquals(parent,child.parent().orElseThrow(),"只控制已确认属于夹具的子进程");
        return new ProcessTree(parent,child,Path.of(values.get(2)));
    }

    private void assertTreeStopped(ProcessTree tree) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while((tree.parent().isAlive()||tree.child().isAlive())&&System.nanoTime()<deadline) Thread.sleep(20);
        assertFalse(tree.parent().isAlive(),"本次夹具父进程必须退出");
        assertFalse(tree.child().isAlive(),"本次夹具子进程不能在失败调用后残留");
    }

    private void assertPermitReusable() throws Exception {
        environment.withProperty("paismart.intelligence.parse.max-characters","999");
        var pages=parser.parse(new ByteArrayInputStream("approved fixture".getBytes(StandardCharsets.UTF_8)),"text/plain",null);
        assertEquals(List.of(new IsolatedDocumentParser.Page(1,"approved lifecycle fixture")),pages);
    }

    private void cleanupOwned(ProcessTree tree,Thread parsing) throws Exception {
        parsing.interrupt();
        if(tree!=null) for(ProcessHandle handle:List.of(tree.child(),tree.parent())) {
            if(handle.isAlive() && handle.equals(ProcessHandle.of(handle.pid()).orElse(null))) handle.destroyForcibly();
            handle.onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        parsing.join(5000);
        if(tree!=null) {
            // 仅回收夹具记下的本次解析临时目录，验证其真实父目录及固定前缀。
            Path work=tree.work().toAbsolutePath().normalize();
            Path temp=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if(work.getParent().equals(temp) && work.getFileName().toString().startsWith("paismart-parse-") && Files.isDirectory(work)) {
                Thread.sleep(100);
                try(var files=Files.walk(work)) {
                    for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        }
    }

    private record ProcessTree(ProcessHandle parent,ProcessHandle child,Path work) {}
    private record LoggingProcess(ProcessHandle handle,Path work) {}
}
