package com.ruoyi.paicli.configuration;

import com.ruoyi.paicli.domain.PcException;
import com.ruoyi.paicli.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** 精确专属路径认证，先验证一次性票据/有限能力再Upgrade，无匿名permitAll。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcTerminalSecurityConfiguration {
    @Bean @Order(0)
    public SecurityFilterChain paicliTerminalChain(HttpSecurity http,PcTerminalService terminals,PcJson json)throws Exception{
        return http.securityMatcher("/paicli/v1/terminal-stream","/paicli/terminal-client/v1/**").csrf(c->c.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(r->r.anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->PcRunnerSecurityConfiguration.error(res,json,401,"PC_TERMINAL_CREDENTIAL","需要有效终端票据或单会话能力")))
            .addFilterBefore(new TerminalFilter(terminals,json),UsernamePasswordAuthenticationFilter.class).build();
    }
    private static final class TerminalFilter extends OncePerRequestFilter {
        private final PcTerminalService terminals; private final PcJson json;
        TerminalFilter(PcTerminalService t,PcJson j){terminals=t;json=j;}
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException {
            try{
                if(req.getHeader("Authorization")!=null)throw PcException.deny("终端专属协议不接受平台Authorization");
                com.ruoyi.common.core.domain.model.LoginUser user;
                if(req.getRequestURI().endsWith("/terminal-stream")){
                    String ticket=null;String protocols=req.getHeader("Sec-WebSocket-Protocol");if(protocols!=null)for(String p:protocols.split(",")){String value=p.strip();if(value.startsWith("paicli-ticket.")){if(ticket!=null)throw PcException.deny("重复票据协议");ticket=value.substring("paicli-ticket.".length());}}
                    var identity=terminals.consumeTicket(ticket,req.getHeader("Origin"));req.setAttribute("paicliTerminalBrowser",identity);user=terminals.login(identity.loginRef());
                }else{if(!req.getRequestURI().endsWith("/gateway")||!"POST".equals(req.getMethod()))throw PcException.deny("终端能力只允许有限Gateway");var identity=terminals.capability(req.getHeader("X-PaiCLI-Terminal-Capability"));req.setAttribute("paicliTerminalCapability",identity);user=identity.user();}
                var auth=new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities());var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);
            }catch(PcException denied){PcRunnerSecurityConfiguration.error(res,json,denied.status(),denied.code(),denied.getMessage());return;}
            try{chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
        }
    }
}
