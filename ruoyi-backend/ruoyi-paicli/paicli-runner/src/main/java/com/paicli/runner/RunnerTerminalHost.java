package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实CLI托管通道完全独立于Runner任务槽。输出来自同一PTY，按序保存完整VT流。 */
public final class RunnerTerminalHost implements AutoCloseable {
    private static final long MAX_VT=16L*1024*1024,MAX_RETIRED_BYTES=128L*1024*1024,HIGH=256*1024,LOW=64*1024;
    private static final int MAX_RETIRED=8;
    private final CenterHttpClient center;
    private final PtyProcessManager manager;
    private final String bootId=UUID.randomUUID().toString();
    private final Map<String,Terminal> terminals=new ConcurrentHashMap<>();
    private record PendingStart(JsonNode frame,Instant time){}
    private final Map<String,PendingStart> pendingStarts=new ConcurrentHashMap<>();
    private final Set<String> cancelledStarts=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(daemon("paicli-pty-clock"));
    private final ExecutorService processes=Executors.newCachedThreadPool(daemon("paicli-pty-process"));
    private final ThreadPoolExecutor commands=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),daemon("paicli-pty-command"),new ThreadPoolExecutor.AbortPolicy());
    private volatile WebSocket socket;
    private volatile Instant lastMessage=Instant.now(),lastConnect=Instant.MIN;
    private volatile Instant lastPing=Instant.MIN;
    private volatile boolean closed;
    private volatile boolean hostHelloSent;
    private long connectionGeneration;
    public RunnerTerminalHost(CenterHttpClient center,Path root,Path state)throws IOException{
        this(center,root,state,null);
    }
    public RunnerTerminalHost(CenterHttpClient center,Path root,Path state,PersonalDirectoryContext directories)throws IOException{
        this.center=center;manager=new PtyProcessManager(root,state,center.baseUrl(),directories);
    }
    private static ThreadFactory daemon(String name){return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};}
    public void start(){clock.scheduleWithFixedDelay(this::tick,0,5,TimeUnit.SECONDS);}
    synchronized void refreshDeviceConnection(){WebSocket previous=socket;socket=null;hostHelloSent=false;++connectionGeneration;lastConnect=Instant.MIN;for(Terminal t:terminals.values())synchronized(t){t.streamReady=false;t.notifyAll();}if(previous!=null)previous.close(1000,"DEVICE_SESSION_RENEWED");}
    public String candidateMode(){return manager.candidateMode();}
    public synchronized boolean ready(){return !closed&&socket!=null&&hostHelloSent&&lastMessage.isAfter(Instant.now().minusSeconds(45));}
    private void tick(){
        if(closed)return;
        try{
            if(socket==null){if(lastConnect.isBefore(Instant.now().minusSeconds(5)))connect();}
            else if(lastMessage.isBefore(Instant.now().minusSeconds(45))){WebSocket stale=socket;socket=null;hostHelloSent=false;detachStreams();stale.cancel();}
            else if(lastPing.isBefore(Instant.now().minusSeconds(15))){send(CanonicalJson.MAPPER.createObjectNode().put("type","PING").put("bootId",bootId));lastPing=Instant.now();}
            for(Terminal terminal:terminals.values()){
                if(!terminal.alive())continue;
                if(terminal.capabilityExpiry.isBefore(Instant.now().plusSeconds(45)))send(terminal.frame("RENEW"));
                if(terminal.capabilityExpiry.isBefore(Instant.now()))terminal.stop("CAPABILITY_EXPIRED");
                else if(terminal.created.isBefore(Instant.now().minus(Duration.ofHours(8))))terminal.stop("MAX_LIFETIME");
            }
            for(var entry:pendingStarts.entrySet())if(entry.getValue().time().isBefore(Instant.now().minusSeconds(20))&&cancelledStarts.add(entry.getKey()))
                send(failure(entry.getValue().frame(),"PTY_START_TIMEOUT"));
            trimRetired();
        }catch(Exception ignored){/* 凭证和输出不写日志；下轮连接继续，但不重启CLI或重交按键。 */}
    }
    private synchronized void connect(){
        if(closed||socket!=null)return;lastConnect=Instant.now();long generation=++connectionGeneration;
        socket=center.terminalSocket(new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){
                synchronized(RunnerTerminalHost.this){if(closed||generation!=connectionGeneration){ws.close(1000,"SUPERSEDED");return;}socket=ws;lastMessage=Instant.now();}
                ObjectNode hello=CanonicalJson.MAPPER.createObjectNode().put("type","HOST_HELLO").put("bootId",bootId).put("ptyMode",manager.candidateMode()).put("ptyVersion",PtyProcessManager.VERSION);
                var list=hello.putArray("terminals");for(Terminal t:terminals.values())if(!t.exited)list.add(t.metadata());
                synchronized(RunnerTerminalHost.this){if(generation==connectionGeneration&&socket==ws&&!closed)hostHelloSent=send(hello);}
            }
            @Override public void onMessage(WebSocket ws,String text){
                if(generation!=connectionGeneration||closed||socket!=ws)return;lastMessage=Instant.now();
                try{if(text.getBytes(StandardCharsets.UTF_8).length>128*1024)throw new IOException("宿主帧超限");JsonNode frame=CanonicalJson.MAPPER.readTree(text);receive(frame);}
                catch(Exception invalid){ws.close(1008,"INVALID_TERMINAL_FRAME");}
            }
            @Override public void onFailure(WebSocket ws,Throwable failure,Response response){disconnected(generation,ws);}
            @Override public void onClosed(WebSocket ws,int code,String reason){disconnected(generation,ws);}
        });
    }
    private synchronized void disconnected(long generation,WebSocket ws){
        if(generation==connectionGeneration){socket=null;hostHelloSent=false;for(Terminal t:terminals.values())synchronized(t){t.streamReady=false;t.notifyAll();}}
    }
    private void receive(JsonNode frame)throws IOException{
        if(frame==null||!frame.isObject())throw new IOException("宿主帧非法");String type=frame.path("type").asText();
        if("PING".equals(type)){send(CanonicalJson.MAPPER.createObjectNode().put("type","PONG").put("bootId",bootId));return;}
        if("PONG".equals(type))return;
        if("START".equals(type)){
            String id=PtyProcessManager.id(frame,"terminalId");PendingStart pending=pendingStarts.get(id);
            if(pending!=null){if(!pending.frame().path("startNonce").asText().equals(frame.path("startNonce").asText())||pending.frame().path("hostEpoch").asLong()!=frame.path("hostEpoch").asLong())throw new IOException("启动幂等冲突");return;}
            pendingStarts.put(id,new PendingStart(frame,Instant.now()));
            try{enqueue(()->{try{startTerminal(frame);}finally{pendingStarts.remove(id);cancelledStarts.remove(id);}});}catch(IOException failure){pendingStarts.remove(id);throw failure;}
            return;
        }
        String id=PtyProcessManager.id(frame,"terminalId");Terminal terminal=terminals.get(id);
        if(terminal==null){
            PendingStart pending=pendingStarts.get(id);
            if("STOP".equals(type)&&pending!=null&&frame.path("hostEpoch").asLong()==pending.frame().path("hostEpoch").asLong()){cancelledStarts.add(id);return;}
            throw new IOException("终端不存在");
        }
        if(frame.path("hostEpoch").asLong()!=terminal.epoch)throw new IOException("宿主版本不符");
        switch(type){
            case "STOP" -> terminal.stop(frame.path("reason").asText("USER_STOP"));
            case "OUTPUT_ACK" -> terminal.ack(frame.path("outputSeq").asLong());
            case "CAPABILITY" -> terminal.renew(frame);
            case "INPUT" -> enqueue(()->{
                try{terminal.input(frame);}
                catch(IOException failed){
                    terminal.rejectInput(frame.path("writerEpoch").asLong(),frame.path("inputSeq").asLong(),"PTY_INPUT_WRITE_UNCERTAIN");
                    terminal.fail("PTY_INPUT_WRITE_FAILED");
                }
            });
            case "RESIZE" -> enqueue(()->PtyProcessManager.resize(terminal.started.process(),frame));
            case "REPLAY" -> processes.submit(()->{try{terminal.replay(frame.path("afterOutputSeq").asLong());}catch(Exception failure){terminal.fail("REPLAY_FAILED");}});
            default -> throw new IOException("未知宿主帧");
        }
    }
    @FunctionalInterface private interface Operation{void execute()throws Exception;}
    private void enqueue(Operation operation)throws IOException{
        try{commands.execute(()->{try{operation.execute();}catch(Exception failed){/* 启动及输入错误由所属终端的FAILURE报告；不回显秘密异常正文。 */}});}
        catch(RejectedExecutionException full){throw new IOException("终端命令队列已满",full);}
    }
    private void startTerminal(JsonNode frame)throws IOException{
        String id=PtyProcessManager.id(frame,"terminalId"),nonce=frame.path("startNonce").asText();long epoch=frame.path("hostEpoch").asLong();
        if(nonce.isBlank()||nonce.length()>128||epoch<1)throw new IOException("启动幂等标识非法");
        if(cancelledStarts.contains(id)){send(failure(frame,"START_CANCELLED"));return;}
        Terminal previous=terminals.get(id);
        if(previous!=null){if(previous.epoch!=epoch||!previous.nonce.equals(nonce))throw new IOException("启动标识与已有进程不符");send(previous.metadata());return;}
        trimRetired(true);
        if(terminals.size()>=MAX_RETIRED){send(failure(frame,"RUNNER_TERMINAL_CACHE_CAPACITY"));return;}
        if(terminals.values().stream().filter(t->!t.exited).count()>=2){send(failure(frame,"RUNNER_TERMINAL_QUOTA"));return;}
        PtyProcessManager.Started owned=null;
        try{
            owned=manager.start(frame);Terminal terminal=new Terminal(id,nonce,epoch,frame,owned);terminals.put(id,terminal);
            if(cancelledStarts.contains(id)){terminal.outputComplete.countDown();terminal.stop("START_CANCELLED");processes.submit(terminal::waitExit);return;}
            send(terminal.metadata());processes.submit(terminal::read);processes.submit(terminal::waitExit);
        }catch(Exception failed){
            if(owned!=null&&!terminals.containsKey(id)){owned.process().destroyForcibly();try{if(owned.process().waitFor(3,TimeUnit.SECONDS))manager.purge(owned);}catch(Exception cleanup){if(cleanup instanceof InterruptedException)Thread.currentThread().interrupt();}}
            send(failure(frame,"PTY_START_FAILED"));
        }
    }
    /** 仅清理本boot明确退出的已拥有目录；清理失败时拒绝继续增长，不删除活进程。 */
    private void trimRetired()throws IOException{trimRetired(false);}
    private synchronized void trimRetired(boolean reserveSlot)throws IOException{
        var retired=terminals.values().stream().filter(t->t.exited).sorted(Comparator.comparing(t->t.exitedAt)).toList();
        long total=0;for(Terminal t:retired)total+=t.cacheBytes;int remaining=retired.size();
        for(Terminal t:retired){
            if(remaining<=MAX_RETIRED&&total<=MAX_RETIRED_BYTES&&(!reserveSlot||terminals.size()<MAX_RETIRED)&&!t.exitedAt.isBefore(Instant.now().minus(Duration.ofHours(24))))break;
            synchronized(t){
                if(t.started.process().isAlive()||t.outputComplete.getCount()!=0||t.replaying)continue;
                manager.purge(t.started);if(terminals.remove(t.id,t)){remaining--;total-=t.cacheBytes;}
            }
        }
    }
    private ObjectNode failure(JsonNode frame,String reason){return CanonicalJson.MAPPER.createObjectNode().put("type","FAILURE").put("terminalId",frame.path("terminalId").asText()).put("hostEpoch",frame.path("hostEpoch").asLong()).put("reason",reason);}
    private boolean send(JsonNode value){
        WebSocket current=socket;if(closed||current==null)return false;
        if(current.send(value.toString()))return true;
        if(socket==current){socket=null;hostHelloSent=false;detachStreams();current.cancel();}return false;
    }
    private void detachStreams(){for(Terminal terminal:terminals.values())terminal.streamReady=false;}

    private final class Terminal{
        final String id,nonce,sessionId;final long epoch;final PtyProcessManager.Started started;final Instant created=Instant.now();
        final List<Integer> outputSizes=new ArrayList<>();final LinkedHashMap<Long,String> recentInputs=new LinkedHashMap<>();
        final CountDownLatch outputComplete=new CountDownLatch(1);
        final Path vtFile;volatile Instant capabilityExpiry;volatile String stopReason="PROCESS_EXIT";
        long seq,acked,sent,bytes,outstanding,writerEpoch,inputSeq;volatile long cacheBytes;boolean paused,replaying;volatile boolean exited;volatile Instant exitedAt=Instant.MAX;volatile boolean streamReady=true;
        Terminal(String id,String nonce,long epoch,JsonNode frame,PtyProcessManager.Started started)throws IOException{
            this.id=id;this.nonce=nonce;this.epoch=epoch;this.started=started;sessionId=PtyProcessManager.id(frame,"sessionId");
            capabilityExpiry=Instant.parse(frame.path("capabilityExpiresAt").asText());
            vtFile=RunnerPrivateDirectory.writeAtomic(started.directory(),"vt-stream.ndjson",new byte[0]);
        }
        boolean alive(){return !exited&&started.process().isAlive();}
        ObjectNode frame(String type){return CanonicalJson.MAPPER.createObjectNode().put("type",type).put("terminalId",id).put("hostEpoch",epoch);}
        synchronized ObjectNode metadata(){return frame(exited?"EXIT":"READY").put("startNonce",nonce).put("sessionId",sessionId).put("pid",started.process().pid())
                .put("bootId",bootId).put("ptyMode",started.mode()).put("artifactHash",started.artifactHash()).put("outputSeq",seq).put("inputSeq",inputSeq).put("state",exited?"EXITED":"RUNNING");}
        void read(){
            CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try(Reader reader=new InputStreamReader(started.process().getInputStream(),decoder)){
                char[] buffer=new char[8192];char pending=0;int count;
                while((count=reader.read(buffer))!=-1){String text=(pending==0?"":String.valueOf(pending))+new String(buffer,0,count);pending=0;
                    if(!text.isEmpty()&&Character.isHighSurrogate(text.charAt(text.length()-1))){pending=text.charAt(text.length()-1);text=text.substring(0,text.length()-1);}
                    if(!text.isEmpty())append(text);
                }
                if(pending!=0)throw new IOException("PTY输出以不完整Unicode结束");
            }catch(Exception failed){if(alive())fail("PTY_OUTPUT_FAILED");}finally{outputComplete.countDown();}
        }
        synchronized void append(String text)throws IOException,InterruptedException{
            while(!closed&&!exited&&replaying)wait(1000);
            if(closed||exited)return;
            int length=text.getBytes(StandardCharsets.UTF_8).length;
            if(bytes+length>MAX_VT||seq>=65536){stop("OUTPUT_LIMIT");return;}
            ObjectNode chunk=frame("OUTPUT").put("outputSeq",seq+1).put("data",text);
            String encoded=chunk.toString()+"\n";int stored=encoded.getBytes(StandardCharsets.UTF_8).length;
            if(cacheBytes+stored>MAX_VT){stop("OUTPUT_LIMIT");return;}
            RunnerPrivateDirectory.requireRegularFile(vtFile);Files.writeString(vtFile,encoded,StandardCharsets.UTF_8,StandardOpenOption.APPEND);seq++;cacheBytes+=stored;
            outputSizes.add(length);bytes+=length;
            // 保留一个完整分块，连接断开/流控时阻塞PTY读取，限制内存及socket待发队列。
            while(!closed&&!exited&&socket!=null&&streamReady&&(replaying||paused||outstanding+length>HIGH))wait(1000);
            if(closed||exited||chunk.path("outputSeq").asLong()<=sent)return;
            if(socket==null||!streamReady)return;
            if(send(chunk)){sent=seq;outstanding+=length;if(outstanding>=HIGH)paused=true;}
        }
        synchronized void ack(long sequence)throws IOException{
            if(sequence<acked||sequence>sent)throw new IOException("输出确认游标非法");
            for(long i=acked+1;i<=sequence;i++)outstanding-=outputSizes.get((int)i-1);
            acked=sequence;if(outstanding<=LOW)paused=false;notifyAll();
        }
        void replay(long after)throws IOException,InterruptedException{
            long end;
            synchronized(this){if(after<0||after>seq)throw new IOException("回放游标非法");if(replaying)return;replaying=true;streamReady=false;acked=after;sent=after;outstanding=0;paused=false;end=seq;}
            RunnerPrivateDirectory.requireRegularFile(vtFile);
            try(BufferedReader reader=Files.newBufferedReader(vtFile,StandardCharsets.UTF_8)){
                long cursor=after;
                for(long skip=0;skip<after;skip++)if(reader.readLine()==null)throw new IOException("回放缓存序列缺失");
                while(cursor<end){String line=reader.readLine();if(line==null)throw new IOException("完整VT缓存缺失");JsonNode chunk=CanonicalJson.MAPPER.readTree(line);
                    if(chunk.path("outputSeq").asLong()!=cursor+1||!id.equals(chunk.path("terminalId").asText())||chunk.path("hostEpoch").asLong()!=epoch)throw new IOException("VT缓存归属或顺序不符");
                    int length=chunk.path("data").asText().getBytes(StandardCharsets.UTF_8).length;
                    synchronized(this){
                        while(!closed&&socket!=null&&(paused||outstanding+length>HIGH))wait(1000);
                        if(closed||socket==null)throw new IOException("回放连接已断开");
                        if(!send(chunk))throw new IOException("回放队列已满");sent=++cursor;outstanding+=length;if(outstanding>=HIGH)paused=true;
                    }
                }
                synchronized(this){streamReady=send(frame("REPLAY_COMPLETE").put("outputSeq",cursor));}
            }finally{synchronized(this){replaying=false;notifyAll();}}
        }
        synchronized void input(JsonNode frame)throws IOException{
            long writer=frame.path("writerEpoch").asLong(),sequence=frame.path("inputSeq").asLong();String data=frame.path("data").asText();
            byte[] encoded;
            String encoding=frame.path("encoding").asText("utf8");
            if(!List.of("utf8","base64").contains(encoding)){rejectInput(writer,sequence,"INVALID_INPUT_ENCODING");return;}
            if("base64".equals(frame.path("encoding").asText())){
                try{encoded=Base64.getDecoder().decode(frame.path("dataBase64").asText());}catch(IllegalArgumentException invalid){rejectInput(writer,sequence,"INVALID_INPUT_BASE64");return;}
            }else encoded=data.getBytes(StandardCharsets.UTF_8);
            if(!alive()||replaying||writer<1||writer<writerEpoch||sequence<1||encoded.length<1||encoded.length>16*1024){rejectInput(writer,sequence,"INPUT_REJECTED");return;}
            if(writer>writerEpoch){writerEpoch=writer;inputSeq=0;recentInputs.clear();}
            String hash=CanonicalJson.sha256(encoded);
            if(sequence<=inputSeq){if(!hash.equals(recentInputs.get(sequence))){rejectInput(writer,sequence,"INPUT_SEQUENCE_CONFLICT");return;}send(this.frame("INPUT_ACK").put("writerEpoch",writer).put("inputSeq",sequence));return;}
            if(sequence!=inputSeq+1){rejectInput(writer,sequence,"INPUT_SEQUENCE_GAP");return;}
            started.process().getOutputStream().write(encoded);started.process().getOutputStream().flush();inputSeq=sequence;recentInputs.put(sequence,hash);
            if(recentInputs.size()>256)recentInputs.remove(recentInputs.keySet().iterator().next());
            send(this.frame("INPUT_ACK").put("writerEpoch",writer).put("inputSeq",sequence));
        }
        void rejectInput(long writer,long sequence,String reason){send(frame("INPUT_REJECTED").put("writerEpoch",writer).put("inputSeq",sequence).put("reason",reason));}
        void renew(JsonNode frame)throws IOException{PtyProcessManager.renew(started,frame,center.baseUrl(),id,sessionId);capabilityExpiry=Instant.parse(frame.path("capabilityExpiresAt").asText());}
        void fail(String reason){send(frame("FAILURE").put("reason",reason));stop(reason);}
        void stop(String reason){stopReason=reason;started.process().destroy();processes.submit(()->{try{if(!started.process().waitFor(3,TimeUnit.SECONDS))started.process().destroyForcibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});synchronized(this){notifyAll();}}
        void waitExit(){try{
            int exit=started.process().waitFor();outputComplete.await(30,TimeUnit.SECONDS);synchronized(this){exitedAt=Instant.now();exited=true;notifyAll();}
            send(frame("EXIT").put("exitCode",exit).put("reason",stopReason).put("outputSeq",seq));
            PtyProcessManager.deletePrivate(started.directory().resolve("capability-once.json"));PtyProcessManager.deletePrivate(started.renewal());trimRetired();
        }catch(Exception interrupted){if(interrupted instanceof InterruptedException)Thread.currentThread().interrupt();}}
    }
    @Override public void close(){
        if(closed)return;closed=true;hostHelloSent=false;for(Terminal terminal:terminals.values())terminal.stop("RUNNER_SHUTDOWN");
        WebSocket current=socket;socket=null;if(current!=null)current.close(1000,"RUNNER_SHUTDOWN");clock.shutdownNow();commands.shutdownNow();processes.shutdown();
    }
}
