package com.paicli.cli;

import com.paicli.render.inline.TerminalCapabilities;
import com.paicli.runner.RunnerPrivateDirectory;
import com.paicli.runner.FixedTerminalNativeLibrary;
import com.pty4j.*;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实ConPTY子JVM验证，无中心连接、账号、模型和业务副作用。 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named="paicli.test.conpty",matches="true")
public class HostedTerminalConPtyTest {
    /** runner的测试factory位于另一模块test源码；此入口同样使用生产私有目录创建器。 */
    public static final class PrivateTempFactory implements org.junit.jupiter.api.io.TempDirFactory{
        public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,org.junit.jupiter.api.extension.ExtensionContext context)throws Exception{
            return RunnerPrivateDirectory.createDirectories(Path.of(System.getenv("USERPROFILE")).resolve("paicli-hosted-terminal-test-"+UUID.randomUUID()));
        }
    }
    @TempDir(factory=PrivateTempFactory.class) Path temporary;
    public static void main(String[] args)throws Exception{
        try(Terminal terminal=CenterCli.openInteractiveTerminal(true)){
            System.out.println("HOSTED_NATIVE_TYPE="+terminal.getType()+" ANSI="+TerminalCapabilities.supportsAnsi(terminal));
            System.out.println("HOSTED_PINNED_NATIVE="+Path.of(org.jline.nativ.JLineNativeLoader.getNativeLibraryPath()).equals(Path.of(System.getProperty("library.jline.path"),"jlinenative.dll")));
            var reader=LineReaderBuilder.builder().terminal(terminal).build();System.out.println("HOSTED_READY");
            for(int i=0;i<5;i++)try{String line=reader.readLine("hosted> ");System.out.println("HOSTED_LINE="+line);if("/exit".equals(line))return;}catch(UserInterruptException interrupted){System.out.println("HOSTED_CTRL_C_HANDLED");}
        }
    }
    @Test void fixedNativeAccessTerminalSupportsAnsiAndKeepsReadingAfterCtrlC()throws Exception{
        Path nativeDirectory=prepareNative();
        Path privateTemp=RunnerPrivateDirectory.createDirectories(temporary.resolve("terminal-tmp"));
        String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Map<String,String> environment=new HashMap<>();for(String name:List.of("SystemRoot","WINDIR","PATH"))if(System.getenv(name)!=null)environment.put(name,System.getenv(name));
        environment.put("TERM","xterm-256color");environment.put("COLORTERM","truecolor");environment.put("TMP",privateTemp.toString());environment.put("TEMP",privateTemp.toString());
        PtyProcess process=new PtyProcessBuilder(new String[]{executable,"--enable-native-access=ALL-UNNAMED","-Dfile.encoding=UTF-8","-Djava.io.tmpdir="+privateTemp,"-Dlibrary.jline.path="+nativeDirectory,"-cp",classpath,HostedTerminalConPtyTest.class.getName()})
            .setDirectory(temporary.toString()).setEnvironment(environment).setRedirectErrorStream(true).setUseWinConPty(true).setInitialColumns(100).setInitialRows(30).start();
        StringBuffer output=new StringBuffer();CountDownLatch ready=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        Thread pump=new Thread(()->{try(var input=process.getInputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){output.append(new String(buffer,0,count,StandardCharsets.UTF_8));if(output.indexOf("HOSTED_READY")>=0)ready.countDown();if(output.indexOf("HOSTED_CTRL_C_HANDLED")>=0)interrupted.countDown();}}catch(Exception ignored){}});pump.setDaemon(true);pump.start();
        try{
            assertTrue(ready.await(10,TimeUnit.SECONDS),output.toString());Thread.sleep(200);process.getOutputStream().write(3);process.getOutputStream().flush();
            assertTrue(interrupted.await(5,TimeUnit.SECONDS),output.toString());assertTrue(process.isAlive());
            process.getOutputStream().write("/help\r/exit\r".getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();assertTrue(process.waitFor(5,TimeUnit.SECONDS));pump.join(3000);
            assertEquals(0,process.exitValue());assertTrue(output.indexOf("ANSI=true")>=0,output.toString());assertTrue(output.indexOf("HOSTED_PINNED_NATIVE=true")>=0,output.toString());assertTrue(output.indexOf("HOSTED_LINE=/help")>=0,output.toString());assertTrue(output.indexOf("HOSTED_LINE=/exit")>=0,output.toString());
            try(var files=Files.list(privateTemp)){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".dll")),"JLine DLL must not be extracted into personal temporary state");}
        }finally{if(process.isAlive())process.destroyForcibly();pump.join(3000);}
    }
    @Test void fixedDllTamperingIsRejectedAndOrdinaryCapabilityRemainsNonExecutable()throws Exception{
        Path nativeDirectory=prepareNative(),dll=nativeDirectory.resolve("jlinenative.dll"),artifact=fixtureArtifact();
        String artifactHash=sha256(Files.readAllBytes(artifact));assertEquals(nativeDirectory,FixedTerminalNativeLibrary.prepare(artifact,artifactHash,nativeRoot()));
        assertThrows(java.io.IOException.class,()->RunnerPrivateDirectory.requireRegularFile(dll));
        byte[] changed=Files.readAllBytes(dll);changed[0]^=1;Files.write(dll,changed);
        assertThrows(java.io.IOException.class,()->FixedTerminalNativeLibrary.prepare(artifact,artifactHash,nativeRoot()));assertArrayEquals(changed,Files.readAllBytes(dll));
        Path capability=RunnerPrivateDirectory.writeAtomic(temporary,"capability-fixture.json","{}".getBytes(StandardCharsets.UTF_8));RunnerPrivateDirectory.requireRegularFile(capability);
    }
    private Path prepareNative()throws Exception{
        if(System.getProperty("paicli.test.pinnedCli")!=null){Path artifact=fixtureArtifact();return FixedTerminalNativeLibrary.prepare(artifact,sha256(Files.readAllBytes(artifact)),nativeRoot());}
        Path launcher=RunnerPrivateDirectory.createDirectories(temporary.resolve("launcher"));
        Path source=Path.of(org.jline.nativ.JLineLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI()),artifact=launcher.resolve("jline-fixed.jar");
        Files.copy(source,artifact);return FixedTerminalNativeLibrary.prepare(artifact,sha256(Files.readAllBytes(artifact)),nativeRoot());
    }
    private Path fixtureArtifact(){String pinned=System.getProperty("paicli.test.pinnedCli");return pinned==null?temporary.resolve("launcher/jline-fixed.jar"):Path.of(pinned);}
    private Path nativeRoot()throws Exception{return RunnerPrivateDirectory.createDirectories(temporary.resolve("personal/state")).resolveSibling("native");}
    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
