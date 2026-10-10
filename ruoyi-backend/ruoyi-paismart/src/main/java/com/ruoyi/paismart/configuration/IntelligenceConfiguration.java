package com.ruoyi.paismart.configuration;
import com.ruoyi.paismart.application.intelligence.*;
import com.ruoyi.paismart.infrastructure.intelligence.PaismartChatWebSocket;
import org.springframework.context.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.Map;

@Configuration
@PaismartEnabled
@EnableWebSocket
public class IntelligenceConfiguration implements WebSocketConfigurer {
    private final ChatService chat;private final WebSocketTicketService tickets;
    public IntelligenceConfiguration(ChatService chat,WebSocketTicketService tickets){this.chat=chat;this.tickets=tickets;}
    @Bean(destroyMethod="close") public PaismartChatWebSocket paismartChatWebSocket(){return new PaismartChatWebSocket(chat,tickets);}
    // 召回线程池须先于聊天配置装配，避免 SearchService -> 配置类 -> ChatService 的循环。
    @Bean public static org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor paismartRecallExecutor(){var pool=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();pool.setCorePoolSize(2);pool.setMaxPoolSize(8);pool.setQueueCapacity(100);pool.setThreadNamePrefix("paismart-recall-");pool.setWaitForTasksToCompleteOnShutdown(false);pool.initialize();return pool;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(paismartChatWebSocket(),"/paismart/ws/chat").setAllowedOrigins(tickets.origins().toArray(String[]::new)).addInterceptors(new HandshakeInterceptor(){@Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){try{String token=UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");attributes.put("paismartIdentity",tickets.consume(token,request.getHeaders().getOrigin()));return true;}catch(RuntimeException rejected){response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);return false;}}@Override public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e){}});}
}
