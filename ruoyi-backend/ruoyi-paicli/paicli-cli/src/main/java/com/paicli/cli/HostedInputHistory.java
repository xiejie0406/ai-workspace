package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runner.RunnerPrivateDirectory;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 本人/工作区输入历史：原过滤规则，私有JSONL原子保存，跨子进程锁避免trim/clear覆盖。 */
final class HostedInputHistory extends DefaultHistory {
    private static final int MAX_ENTRIES=10000,MAX_JSON_LINE=64*1024;
    private static final long MAX_BYTES=256L*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<Path,ReentrantLock> LOCAL_LOCKS=new ConcurrentHashMap<>();
    private record Saved(Instant time,String line){}
    private final Path file,lockFile;
    private boolean loading;

    static HostedInputHistory fromEnvironment()throws IOException{
        String configured=System.getenv("PAICLI_HISTORY_FILE");
        if(configured==null||configured.isBlank())throw new IOException("网页托管终端缺少可信输入历史路径");
        return new HostedInputHistory(Path.of(configured));
    }
    private HostedInputHistory(Path requested)throws IOException{
        file=requested.toAbsolutePath().normalize();
        if(!file.getFileName().toString().equals("input.history"))throw new IOException("输入历史名称无效");
        RunnerPrivateDirectory.requireRegularFile(file);checkSize();
        lockFile=file.resolveSibling("input.history.lock");ensurePrivateFile(lockFile);
    }
    private static void ensurePrivateFile(Path path)throws IOException{
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
            try(FileChannel ignored=RunnerPrivateDirectory.createFile(path)){}
            catch(FileAlreadyExistsException concurrent){RunnerPrivateDirectory.requireRegularFile(path);}
        }
        RunnerPrivateDirectory.requireRegularFile(path);
    }
    @Override public void attach(LineReader reader){
        reader.getVariables().remove(LineReader.HISTORY_FILE);
        reader.setVariable(LineReader.HISTORY_SIZE,2000);
        reader.setVariable(LineReader.HISTORY_FILE_SIZE,MAX_ENTRIES);
        reader.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
        reader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
        reader.setOpt(LineReader.Option.HISTORY_REDUCE_BLANKS);
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        super.attach(reader);
    }
    @Override public synchronized void load()throws IOException{
        List<Saved> entries;
        try{entries=locked(this::readEntries);}catch(IOException|RuntimeException invalid){throw new IOException("私有输入历史读取失败，请核对权限、格式或支持限额；终端尚未接收新输入");}
        loading=true;
        try{super.purge();for(Saved entry:entries)internalAdd(entry.time(),entry.line(),false);moveToEnd();}
        finally{loading=false;}
    }
    @Override public synchronized void add(Instant time,String line){
        if(PaiCliHistory.shouldSkip(line))return;
        History.Entry before=lastEntry();super.add(time,line);History.Entry accepted=lastEntry();
        if(loading||accepted==null||accepted==before)return;
        try{locked(()->{
            List<Saved> entries=readEntries();
            if(entries.isEmpty()||!entries.get(entries.size()-1).line().equals(accepted.line()))entries.add(new Saved(accepted.time(),accepted.line()));
            while(entries.size()>MAX_ENTRIES)entries.remove(0);
            replace(entries);return null;
        });}catch(IOException failure){throw new UncheckedIOException("输入历史安全持久化失败，输入尚未提交中心",failure);}
    }
    private History.Entry lastEntry(){return isEmpty()?null:iterator(last()).next();}
    /** 每个被接受的输入已同步force，禁止JLine默认宽权限临时文件与退出时重写旧内存。 */
    @Override public void save()throws IOException{RunnerPrivateDirectory.requireRegularFile(file);checkSize();}
    @Override public synchronized void purge()throws IOException{
        locked(()->{replace(List.of());return null;});super.purge();
    }
    @Override public void read(Path target,boolean checkDuplicates)throws IOException{sameFile(target);load();}
    @Override public void write(Path target,boolean incremental)throws IOException{sameFile(target);save();}
    @Override public void append(Path target,boolean incremental)throws IOException{sameFile(target);save();}
    @Override protected void trimHistory(Path target,int max)throws IOException{throw new IOException("网页输入历史仅允许私有原子trim");}
    private void sameFile(Path target)throws IOException{if(target==null||!target.toAbsolutePath().normalize().equals(file))throw new IOException("网页输入历史不能切换路径");}
    private void checkSize()throws IOException{if(Files.size(file)>MAX_BYTES)throw new IOException("输入历史超过256MiB支持限额");}
    @FunctionalInterface private interface Operation<T>{T run()throws IOException;}
    private <T>T locked(Operation<T> operation)throws IOException{
        ReentrantLock local=LOCAL_LOCKS.computeIfAbsent(lockFile,ignored->new ReentrantLock());local.lock();
        try{
            RunnerPrivateDirectory.requireRegularFile(lockFile);
            try(FileChannel channel=FileChannel.open(lockFile,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock ignored=channel.lock()){
                RunnerPrivateDirectory.requireRegularFile(lockFile);RunnerPrivateDirectory.requireRegularFile(file);checkSize();return operation.run();
            }
        }finally{local.unlock();}
    }
    private List<Saved> readEntries()throws IOException{
        List<Saved> entries=new ArrayList<>();
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var channel=Files.newByteChannel(file,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));BufferedReader reader=new BufferedReader(Channels.newReader(channel,decoder,-1))){
            String line;
            while((line=boundedLine(reader))!=null){
                if(entries.size()>=MAX_ENTRIES)throw new IOException("输入历史超过10000条支持限额");
                JsonNode value=JSON.readTree(line);
                if(value==null||!value.isObject()||!value.path("line").isTextual()||!value.path("time").isTextual()||PaiCliHistory.shouldSkip(value.path("line").asText()))throw new IOException("输入历史格式或原秘密过滤校验失败");
                Instant time;try{time=Instant.parse(value.path("time").asText());}catch(RuntimeException invalid){throw new IOException("输入历史时间格式非法",invalid);}
                entries.add(new Saved(time,value.path("line").asText()));
            }
        }
        RunnerPrivateDirectory.requireRegularFile(file);return entries;
    }
    private String boundedLine(Reader reader)throws IOException{
        StringBuilder line=new StringBuilder();int ch;
        while((ch=reader.read())!=-1){if(ch=='\n')return line.toString();if(line.length()>=MAX_JSON_LINE)throw new IOException("输入历史单记录超限");line.append((char)ch);}
        if(line.isEmpty())return null;
        // 私有writer总带换行；半个记录不是完整已持久输入。
        throw new IOException("输入历史末记录不完整");
    }
    private void replace(List<Saved> entries)throws IOException{
        Path temporary=RunnerPrivateDirectory.createTempFile(file.getParent(),".history-",".tmp");boolean published=false;long bytes=0;
        try{
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                for(Saved entry:entries){
                    String jsonLine=JSON.createObjectNode().put("time",entry.time().toString()).put("line",entry.line()).toString();
                    if(jsonLine.length()>MAX_JSON_LINE)throw new IOException("输入历史单记录超限");
                    byte[] encoded=(jsonLine+"\n").getBytes(StandardCharsets.UTF_8);
                    bytes+=encoded.length;if(bytes>MAX_BYTES)throw new IOException("输入历史超过256MiB支持限额");
                    ByteBuffer buffer=ByteBuffer.wrap(encoded);while(buffer.hasRemaining())channel.write(buffer);
                }
                channel.force(true);
            }
            RunnerPrivateDirectory.requireRegularFile(file);RunnerPrivateDirectory.requireRegularFile(temporary);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);published=true;
            RunnerPrivateDirectory.requireRegularFile(file);checkSize();
        }finally{if(!published&&Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){RunnerPrivateDirectory.requireRegularFile(temporary);Files.delete(temporary);}}
    }
}
