package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.PcException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/** 每连接独立有界发送；业务锁内仅offer，不等待网络写入、慢窗或Runner。 */
@Component
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcTerminalOutbound {
    private static final int MAX_CONNECTIONS=128,MAX_BYTES=512*1024,MAX_FRAMES=128,SEND_MILLIS=5000;
    private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    private final PcJson json;
    private record Packet(String text,int bytes){}
    private final class Connection {
        final WebSocketSession original;final ConcurrentWebSocketSessionDecorator socket;
        final BlockingQueue<Packet> queue=new ArrayBlockingQueue<>(MAX_FRAMES);
        volatile boolean ended;volatile long sendingSince;int queuedBytes;
        Connection(WebSocketSession original){this.original=original;socket=new ConcurrentWebSocketSessionDecorator(original,SEND_MILLIS,MAX_BYTES);if(original instanceof StandardWebSocketSession standard&&standard.getNativeSession()!=null)standard.getNativeSession().getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",(long)SEND_MILLIS);}
        void start(){Thread thread=new Thread(this::run,"paicli-terminal-outbound");thread.setDaemon(true);thread.start();}
        void offer(Packet p){synchronized(this){if(ended||!original.isOpen()||queuedBytes+p.bytes>MAX_BYTES||!queue.offer(p)){terminate();throw new PcException(503,"PC_TERMINAL_SLOW_STREAM","终端连接发送缓冲已满或已分离");}queuedBytes+=p.bytes;}}
        void run(){try{while(!ended){Packet p=queue.poll(1,TimeUnit.SECONDS);if(p==null){if(!original.isOpen())break;continue;}synchronized(this){queuedBytes=Math.max(0,queuedBytes-p.bytes);if(ended)break;}sendingSince=System.currentTimeMillis();try{socket.sendMessage(new TextMessage(p.text));}finally{sendingSince=0;}}}catch(Exception unavailable){/* 不打印异常正文，避免协议内容或凭证进入日志。 */}finally{terminate();connections.remove(original.getId(),this);}}
        void terminate(){synchronized(this){if(ended)return;ended=true;queue.clear();queuedBytes=0;}Thread closer=new Thread(()->{try{original.close(CloseStatus.SESSION_NOT_RELIABLE);}catch(Exception ignored){}},"paicli-terminal-close");closer.setDaemon(true);closer.start();}
    }
    public PcTerminalOutbound(PcJson json){this.json=json;}
    public void send(WebSocketSession session,Map<String,Object> frame){String text=json.write(frame);Packet p=new Packet(text,text.getBytes(StandardCharsets.UTF_8).length);if(p.bytes>MAX_BYTES)throw new PcException(413,"PC_TERMINAL_FRAME_SIZE","终端控制帧超出发送缓冲上限");Connection connection;
        synchronized(connections){connection=connections.get(session.getId());if(connection==null){if(connections.size()>=MAX_CONNECTIONS)throw new PcException(429,"PC_TERMINAL_CONNECTION_LIMIT","终端连接数量达到128个全局上限");connection=new Connection(session);connections.put(session.getId(),connection);connection.start();}}
        connection.offer(p);
    }
    public void closed(WebSocketSession session){Connection c=connections.get(session.getId());if(c!=null)c.terminate();}
    public void closeSession(WebSocketSession session){Connection c=connections.get(session.getId());if(c!=null){c.terminate();return;}if(!session.isOpen())return;Thread close=new Thread(()->{try{session.close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}},"paicli-terminal-close-unattached");close.setDaemon(true);close.start();}
    @Scheduled(fixedDelay=1000)
    public void expireSlowConnections(){long now=System.currentTimeMillis();for(Connection c:connections.values())if(c.sendingSince>0&&now-c.sendingSince>SEND_MILLIS||!c.original.isOpen())c.terminate();}
}
