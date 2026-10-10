package com.ruoyi.paicli.configuration;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.service.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.SubProtocolCapable;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@EnableWebSocket @EnableScheduling
@EnableConfigurationProperties(PcTerminalProperties.class)
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcTerminalWebSocketConfiguration implements WebSocketConfigurer {
    private final PcTerminalService terminals;private final PcJson json;private final PcTerminalProperties properties;
    public PcTerminalWebSocketConfiguration(PcTerminalService t,PcJson j,PcTerminalProperties p){terminals=t;json=j;properties=p;}
    @Bean public ApplicationRunner paicliTerminalGuard(PcStore store){return args->{properties.validate();if(properties.isEnabled())store.mapper.list(PcTable.TERMINAL_SESSION,null,null,null,"STARTING");};}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(new Stream(false),"/paicli/v1/terminal-stream").addInterceptors(new Handshake(false)).setAllowedOrigins(properties.getAllowedOrigins().toArray(String[]::new));
        // 设备客户端没有Origin；握手仍由既有Runner专属SecurityChain核验。
        registry.addHandler(new Stream(true),"/paicli/runner/v1/terminal-stream").addInterceptors(new Handshake(true));
    }
    private final class Handshake implements HandshakeInterceptor {
        final boolean runner;Handshake(boolean r){runner=r;}
        @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
            if(!(request instanceof ServletServerHttpRequest servlet))return false;
            if(runner){if(!(request.getPrincipal() instanceof Authentication auth)||!(auth.getPrincipal() instanceof PcRunnerPrincipal principal)||!"SESSION".equals(principal.kind()))return false;attributes.put("runnerPrincipal",principal);attributes.put("runnerToken",Objects.toString(servlet.getServletRequest().getHeader("X-PaiCLI-Runner-Token"),""));}
            else{Object identity=servlet.getServletRequest().getAttribute("paicliTerminalBrowser");if(!(identity instanceof PcTerminalService.BrowserIdentity))return false;attributes.put("browserIdentity",identity);}
            return true;
        }
        @Override public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e){}
    }
    private final class Stream extends TextWebSocketHandler implements SubProtocolCapable {
        final boolean runner;Stream(boolean r){runner=r;}
        @Override public List<String> getSubProtocols(){return runner?List.of():List.of("paicli-terminal");}
        @Override public void afterConnectionEstablished(WebSocketSession session){session.setTextMessageSizeLimit(128*1024);session.setBinaryMessageSizeLimit(1);if(runner)terminals.runnerConnected(session,(String)session.getAttributes().get("runnerToken"),(PcRunnerPrincipal)session.getAttributes().get("runnerPrincipal"));else terminals.browserConnected(session,(PcTerminalService.BrowserIdentity)session.getAttributes().get("browserIdentity"));}
        @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){try{Map<String,Object> input=json.read(message.getPayload());if(runner)terminals.runnerFrame(session,input);else terminals.browserFrame(session,input);}catch(Exception failure){try{terminals.send(session,Map.of("type","ERROR","code",failure instanceof PcException e?e.code():"PC_TERMINAL_FRAME","message",failure instanceof PcException?failure.getMessage():"终端帧非法或处理失败"));}finally{terminals.close(session);}}}
        @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){if(runner)terminals.runnerClosed(session);else terminals.browserClosed(session);}
        @Override public void handleTransportError(WebSocketSession session,Throwable exception){terminals.close(session);}
    }
}
