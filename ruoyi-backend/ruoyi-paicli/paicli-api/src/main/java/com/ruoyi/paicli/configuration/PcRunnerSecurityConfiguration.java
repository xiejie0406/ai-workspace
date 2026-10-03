package com.ruoyi.paicli.configuration;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/** 仅设备协议专属chain。没有permitAll；平台JWT不能伪装为设备。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcRunnerSecurityConfiguration {
    @Bean @Order(1)
    public SecurityFilterChain paicliRunnerChain(HttpSecurity http,PcDeviceService devices,PcJson json)throws Exception{
        return http.securityMatcher("/paicli/runner/v1/**").csrf(c->c.disable())
          .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(r->r.requestMatchers("/paicli/runner/v1/sessions").hasAuthority("PC_RUNNER_DEVICE")
          .requestMatchers("/paicli/runner/v1/wechat/context").hasAnyAuthority("PC_RUNNER_SESSION","PC_RUNNER_WECHAT")
          .requestMatchers("/paicli/runner/v1/wechat/account").hasAuthority("PC_RUNNER_SESSION")
          .requestMatchers("/paicli/runner/v1/wechat/**").hasAuthority("PC_RUNNER_WECHAT").anyRequest().hasAuthority("PC_RUNNER_SESSION"))
          .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->error(res,json,401,"PC_RUNNER_CREDENTIAL","需要有效设备凭证"))
          .accessDeniedHandler((req,res,ex)->error(res,json,403,"PC_RUNNER_SCOPE","设备凭证类型或协议范围不匹配")))
          .addFilterBefore(new RunnerFilter(devices,json),UsernamePasswordAuthenticationFilter.class).build();
    }
    static void error(HttpServletResponse response,PcJson json,int status,String code,String message)throws IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(json.write(java.util.Map.of("code",status,"errorCode",code,"msg",message)));}
    static final class RunnerFilter extends OncePerRequestFilter {
        private final PcDeviceService devices;private final PcJson json;
        RunnerFilter(PcDeviceService d,PcJson j){devices=d;json=j;}
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            if(req.getHeader("Authorization")!=null){error(res,json,401,"PC_RUNNER_TOKEN_SCHEME","设备协议不接受平台Authorization凭证");return;}
            try{PcRunnerPrincipal p=devices.authenticate(req.getHeader("X-PaiCLI-Runner-Token"));var auth=new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("PC_RUNNER_"+p.kind())));var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);}
            catch(PcException e){error(res,json,e.status(),e.code(),e.getMessage());return;}
            try{chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
        }
    }
}
