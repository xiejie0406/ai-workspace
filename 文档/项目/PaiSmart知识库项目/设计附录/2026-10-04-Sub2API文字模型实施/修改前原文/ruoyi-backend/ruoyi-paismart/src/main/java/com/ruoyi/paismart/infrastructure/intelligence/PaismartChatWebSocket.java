package com.ruoyi.paismart.infrastructure.intelligence;
import com.ruoyi.paismart.application.intelligence.ChatService;
import com.ruoyi.paismart.application.intelligence.WebSocketTicketService;
import com.ruoyi.paismart.infrastructure.Jsons;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.*;
import java.util.concurrent.*;

/** WS只传输持久事件，断线不重发提问，中心Kafka执行模型任务。 */
public class PaismartChatWebSocket extends TextWebSocketHandler implements AutoCloseable {
    private record Subscription(WebSocketSession session,WebSocketTicketService.TicketIdentity identity,ConcurrentMap<String,Long> offsets){}
    private final ChatService chat;private final WebSocketTicketService tickets;private final ConcurrentMap<String,Subscription> clients=new ConcurrentHashMap<>();
    private final ScheduledExecutorService replay=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"paismart-ws-replay");t.setDaemon(true);return t;});
    public PaismartChatWebSocket(ChatService chat,WebSocketTicketService tickets){this.chat=chat;this.tickets=tickets;replay.scheduleWithFixedDelay(this::replay,250,250,TimeUnit.MILLISECONDS);}
    @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{var identity=(WebSocketTicketService.TicketIdentity)session.getAttributes().get("paismartIdentity");tickets.verify(identity);session.setTextMessageSizeLimit(65536);clients.put(session.getId(),new Subscription(session,identity,new ConcurrentHashMap<>()));send(session,Map.of("kind","connected","protocolVersion",1));}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message)throws Exception{Subscription s=clients.get(session.getId());tickets.verify(s.identity());if("__chat_ping__".equals(message.getPayload())){send(session,Map.of("kind","heartbeat"));return;}Map<String,Object> body=Jsons.map(message.getPayload());String kind=Values.text(body.getOrDefault("kind",body.get("type")));switch(kind){case "submit","chat","message"->{body.put("_platformSession",s.identity().platformSession());if(!body.containsKey("text"))body.put("text",body.get("message"));Map<String,Object> generation=chat.submit(s.identity().userId(),body);String id=Values.text(generation.get("generationId"));s.offsets().put(id,0L);send(session,generation);}case "resume"->{String id=Values.required(body,"generationId",36);Map<String,Object> snapshot=chat.generation(s.identity().userId(),id);long offset=Values.number(body.getOrDefault("afterSequence",body.getOrDefault("lastSequence",body.get("sequence"))),0);if(offset<0||offset>Values.number(snapshot.get("lastSequence"),0))throw new IllegalArgumentException("恢复序号无效");send(session,snapshot);if(Set.of("COMPLETE","STOPPED","FAILED","INTERRUPTED").contains(Values.text(snapshot.get("state")))){s.offsets().remove(id);return;}if(s.offsets().size()>=2&&!s.offsets().containsKey(id))throw new IllegalArgumentException("恢复订阅超过上限");s.offsets().put(id,offset);}case "stop"->chat.stop(s.identity().userId(),Values.required(body,"generationId",36));case "ping"->send(session,Map.of("kind","heartbeat"));default->throw new IllegalArgumentException("WS操作无效");}}
    private void replay(){for(Subscription s:clients.values())try{tickets.verify(s.identity());for(var sub:s.offsets().entrySet()){List<Map<String,Object>> events=chat.events(s.identity().userId(),sub.getKey(),sub.getValue());for(var event:events){send(s.session(),event);s.offsets().put(sub.getKey(),Values.number(event.get("sequence"),sub.getValue()));if(Set.of("state","done","stopped","error").contains(Values.text(event.get("kind")))&&Set.of("COMPLETE","STOPPED","FAILED","INTERRUPTED").contains(Values.text(event.get("state"))))s.offsets().remove(sub.getKey());}}}catch(Exception denied){clients.remove(s.session().getId());try{s.session().close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}}}
    private void send(WebSocketSession session,Object message)throws Exception{synchronized(session){if(session.isOpen())session.sendMessage(new TextMessage(Jsons.write(message)));}}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){clients.remove(session.getId());}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception)throws Exception{clients.remove(session.getId());session.close(CloseStatus.SERVER_ERROR);}
    @Override public void close(){replay.shutdownNow();for(Subscription s:clients.values())try{s.session().close(CloseStatus.GOING_AWAY);}catch(Exception ignored){}clients.clear();}
}

