package com.paicli.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerEngineLifecycleTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path home;

    @Test void closesOwnedExecutorOnceBeforeReleasingTheEngine()throws Exception{
        Path root=Files.createDirectory(home.resolve("workspace")),locks=Files.createDirectory(home.resolve("locks"));
        ClosingExecutor executor=new ClosingExecutor(false);
        RunnerEngine engine=new RunnerEngine(new CenterHttpClient("http://127.0.0.1:1","fixture",true),root,home.resolve("state"),executor,null,null,locks);
        engine.close();engine.close();assertEquals(1,executor.closes);
        try(var reopened=new RunnerEngine(new CenterHttpClient("http://127.0.0.1:1","fixture",true),root,home.resolve("state"),(lease,tools)->"",null,null,locks)){
            assertNotNull(reopened);
        }
    }

    @Test void failingExecutorCloseStillReleasesBothWorkspaceAndStateLocks()throws Exception{
        Path root=Files.createDirectory(home.resolve("workspace")),other=Files.createDirectory(home.resolve("other-workspace")),locks=Files.createDirectory(home.resolve("locks"));
        Path state=home.resolve("state");ClosingExecutor executor=new ClosingExecutor(true);
        RunnerEngine engine=new RunnerEngine(new CenterHttpClient("http://127.0.0.1:1","fixture",true),root,state,executor,null,null,locks);
        IOException failure=assertThrows(IOException.class,engine::close);assertEquals("Runner执行资源关闭失败",failure.getMessage());
        assertNotNull(failure.getCause());engine.close();assertEquals(1,executor.closes);
        try(var rootAgain=new RunnerEngine(new CenterHttpClient("http://127.0.0.1:1","fixture",true),root,home.resolve("other-state"),(lease,tools)->"",null,null,locks);
            var stateAgain=new RunnerEngine(new CenterHttpClient("http://127.0.0.1:1","fixture",true),other,state,(lease,tools)->"",null,null,locks)){
            assertNotNull(rootAgain);assertNotNull(stateAgain);
        }
    }

    private static final class ClosingExecutor implements RunnerEngine.TaskExecutor,AutoCloseable{
        private final boolean fail;private int closes;
        private ClosingExecutor(boolean fail){this.fail=fail;}
        public String execute(RunnerLease lease,CenterToolRegistry tools){return "";}
        public void close()throws IOException{closes++;if(fail)throw new IOException("fixture close failure");}
    }
}
