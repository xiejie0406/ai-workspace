package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 固定Java/CLI制品与私有环境；中心不提供可执行路径、Shell或任意参数。 */
final class PtyProcessManager {
    static final String VERSION="0.13.4";
    record Started(PtyProcess process,Path directory,Path renewal,String artifactHash,String mode){}
    private final Path root,state,javaExecutable,cli;
    private final String centerUrl,artifactHash;
    private final boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
    PtyProcessManager(Path root,Path state,String centerUrl)throws IOException{
        this.root=root.toRealPath();this.state=RunnerPrivateDirectory.createDirectories(state.resolve("terminals"));this.centerUrl=centerUrl;
        javaExecutable=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toRealPath();
        if(!Files.isRegularFile(javaExecutable)||!Files.isExecutable(javaExecutable))throw new IOException("缺少固定Java执行文件");
        try{
            Class<?> main=Class.forName("com.paicli.cli.Main",false,getClass().getClassLoader());
            cli=Path.of(main.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        }catch(Exception missing){throw new IOException("Runner分发缺少同包CLI入口",missing);}
        if(!Files.isRegularFile(cli)||!cli.getFileName().toString().endsWith(".jar"))throw new IOException("网页终端仅启动已打包CLI JAR，不执行源码classpath");
        artifactHash=CanonicalJson.sha256(Files.readAllBytes(cli));
        // 类存在只证明分发包含候选库，实际启动后还必须验证返回的ConPTY类型。
        if(windows&&!hasNative("resources/com/pty4j/native/win/x86-64/winpty.dll")
                &&!hasNative("resources/com/pty4j/native/win/x86-64/pty4j.dll")){
            // ConPTY采用系统Kernel32/JNA；不要求winpty DLL作为ConPTY前提。
            try{Class.forName("com.pty4j.windows.conpty.WinConPtyProcess");}catch(ClassNotFoundException e){throw new IOException("PTY分发未包含ConPTY",e);}
        }
    }
    private boolean hasNative(String path){return getClass().getClassLoader().getResource(path)!=null;}
    String candidateMode(){return windows?"ConPTY":"POSIX_PTY";}
    Started start(JsonNode frame)throws IOException{
        String terminal=id(frame,"terminalId"),session=id(frame,"sessionId"),workspace=id(frame,"workspaceId"),runner=id(frame,"runnerId");
        long epoch=frame.path("hostEpoch").asLong();if(epoch<1)throw new IOException("终端宿主版本无效");
        if(frame.hasNonNull("centerUrl")&&!centerUrl.equals(frame.path("centerUrl").asText()))throw new IOException("启动中心地址不符");
        String renderer=frame.path("renderer").asText("inline");if(!List.of("inline","lanterna","plain").contains(renderer))throw new IOException("终端界面非法");
        int cols=dimension(frame,"cols",20,400),rows=dimension(frame,"rows",5,120);
        if(!artifactHash.equals(CanonicalJson.sha256(Files.readAllBytes(cli))))throw new IOException("CLI制品在Runner启动后已改变");
        Path directory=RunnerPrivateDirectory.createDirectories(state.resolve(terminal+"-"+epoch+"-"+UUID.randomUUID()));
        Path home=RunnerPrivateDirectory.createDirectories(directory.resolve("home"));Path temp=RunnerPrivateDirectory.createDirectories(directory.resolve("tmp"));
        ObjectNode capability=capability(frame,centerUrl,terminal,session);
        Path initial=RunnerPrivateDirectory.writeAtomic(directory,"capability-once.json",capability.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,String> environment=new LinkedHashMap<>();
        for(String name:List.of("SystemRoot","WINDIR","LANG","LC_ALL")){String value=System.getenv(name);if(value!=null)environment.put(name,value);}
        environment.put("TERM","xterm-256color");environment.put("COLORTERM","truecolor");environment.put("HOME",home.toString());environment.put("USERPROFILE",home.toString());
        environment.put("TMP",temp.toString());environment.put("TEMP",temp.toString());environment.put("TMPDIR",temp.toString());
        environment.put("PATH",javaExecutable.getParent().toString()+(windows?";"+System.getenv("SystemRoot")+"\\System32":":/usr/bin:/bin"));
        environment.put("PAICLI_CENTER_URL",centerUrl);environment.put("PAICLI_TERMINAL_CAPABILITY_FILE",initial.toString());environment.put("PAICLI_RENDERER",renderer);
        environment.put("PAICLI_WEB_TERMINAL","true");environment.put("PAICLI_WORKSPACE_ROOT",root.toString());
        String[] command={javaExecutable.toString(),"-Dfile.encoding=UTF-8","-Duser.home="+home,"-Djava.io.tmpdir="+temp,"-cp",cli.toString(),"com.paicli.runner.TerminalChildMain",
                "--workspace",workspace,"--runner",runner,"--session",session};
        PtyProcess process=null;
        try{
            process=new PtyProcessBuilder(command).setDirectory(root.toString()).setEnvironment(environment).setConsole(false).setRedirectErrorStream(true)
                    .setUseWinConPty(windows).setInitialColumns(cols).setInitialRows(rows).setWindowsAnsiColorEnabled(true).start();
            if(windows&&!process.getClass().getName().equals("com.pty4j.windows.conpty.WinConPtyProcess")){
                process.destroyForcibly();throw new IOException("ConPTY启动失败，拒绝静默回退winpty");
            }
            return new Started(process,directory,directory.resolve("capability-renewal.json"),artifactHash,candidateMode());
        }catch(Throwable failed){if(process!=null)process.destroyForcibly();deletePrivate(initial);if(failed instanceof IOException e)throw e;throw new IOException("真实PTY启动失败",failed);}
    }
    static void renew(Started started,JsonNode frame,String centerUrl,String terminalId,String sessionId)throws IOException{
        ObjectNode value=capability(frame,centerUrl,terminalId,sessionId);Path file=started.renewal();
        Path temporary=RunnerPrivateDirectory.writeAtomic(file.getParent(),"renew-"+UUID.randomUUID()+".json",value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(Files.exists(file))RunnerPrivateDirectory.requireRegularFile(file);
        Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);RunnerPrivateDirectory.requireRegularFile(file);
    }
    static void resize(PtyProcess process,JsonNode frame)throws IOException{process.setWinSize(new WinSize(dimension(frame,"cols",20,400),dimension(frame,"rows",5,120)));}
    static ObjectNode capability(JsonNode frame,String centerUrl,String terminal,String session)throws IOException{
        String token=frame.path("capabilityToken").asText(),expiry=frame.path("capabilityExpiresAt").asText();
        if(token.isBlank()||token.length()>4096||token.chars().anyMatch(c->c<32))throw new IOException("终端短期能力无效");
        try{if(!Instant.parse(expiry).isAfter(Instant.now()))throw new IOException("终端短期能力已过期");}catch(RuntimeException invalid){throw new IOException("终端短期能力有效期无效",invalid);}
        ObjectNode value=CanonicalJson.MAPPER.createObjectNode();value.put("centerUrl",centerUrl);value.put("terminalId",terminal);value.put("sessionId",session);value.put("token",token);value.put("expiresAt",expiry);return value;
    }
    static int dimension(JsonNode value,String key,int minimum,int maximum)throws IOException{int n=value.path(key).asInt();if(n<minimum||n>maximum)throw new IOException("终端尺寸超出范围");return n;}
    static String id(JsonNode value,String key)throws IOException{String id=value.path(key).asText();if(!id.matches("[1-9][0-9]{0,18}"))throw new IOException("终端标识非法");return id;}
    static void deletePrivate(Path file)throws IOException{if(Files.exists(file)){RunnerPrivateDirectory.requireRegularFile(file);Files.delete(file);}}
    void purge(Started started)throws IOException{
        if(started.process().isAlive())throw new IOException("仍运行的终端缓存不能清理");
        Path directory=started.directory().toAbsolutePath().normalize();
        if(!directory.getParent().equals(state)||!directory.startsWith(state))throw new IOException("终端清理路径不属于本宿主私有目录");
        RunnerPrivateDirectory.requireDirectory(directory);var owner=Files.getOwner(directory,LinkOption.NOFOLLOW_LINKS);
        List<Path> nodes;
        try(var walk=Files.walk(directory)){nodes=walk.sorted(Comparator.reverseOrder()).toList();}
        // 私有根隔离所有子节点。先核完整快照，任何链接/未知所有者均保留现场，不穿越删除。
        for(Path node:nodes)if(Files.isSymbolicLink(node)||!node.toRealPath().startsWith(directory)
                ||!Files.getOwner(node,LinkOption.NOFOLLOW_LINKS).equals(owner)
                ||!Files.isDirectory(node,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(node,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("终端缓存节点无法安全清理");
        for(Path node:nodes){if(Files.isSymbolicLink(node)||!node.toRealPath().startsWith(directory))throw new IOException("终端缓存清理身份变化");Files.delete(node);}
    }
}
