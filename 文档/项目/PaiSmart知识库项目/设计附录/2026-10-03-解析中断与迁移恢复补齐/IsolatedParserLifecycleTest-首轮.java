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
                    Path classes=Path.of(ParserWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    String exe=Path.of(System.getProperty("java.home"),"bin",
                        System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                    Process child=new ProcessBuilder(exe,"-cp",classes.toString(),Child.class.getName())
                        .inheritIO().start();
                    Files.writeString(classes.resolve("pids"), ProcessHandle.current().pid()+"\\n"+child.pid()+"\\n"+Path.of(args[0]).getParent());
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
            assertInstanceOf(InterruptedException.class,failure.get().getCause());
            assertTrue(interrupted.get(),"调用方中断标记必须保留");
            assertTreeStopped(tree);
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

    private Thread startParsing() {
        Thread thread=new Thread(()->{
            try { parser.parse(new ByteArrayInputStream("approved fixture".getBytes(StandardCharsets.UTF_8)),"text/plain",null); }
            catch(Throwable e) { failure.set(e); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        },"paismart-lifecycle-test");
        thread.start();
        return thread;
    }

    private ProcessTree awaitProcessTree() throws Exception {
        Path pids=classes.resolve("pids");
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!Files.exists(pids) && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(Files.exists(pids),"夹具应在预算内写入其实际进程标识");
        List<String> values=Files.readAllLines(pids);
        assertEquals(3,values.size());
        return new ProcessTree(ProcessHandle.of(Long.parseLong(values.get(0))).orElseThrow(),
            ProcessHandle.of(Long.parseLong(values.get(1))).orElseThrow(),Path.of(values.get(2)));
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
            if(handle.isAlive() && handle.info().commandLine().map(line->
                line.contains(classes.toString()) && line.contains("ParserWorker")).orElse(false)) handle.destroyForcibly();
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
}
