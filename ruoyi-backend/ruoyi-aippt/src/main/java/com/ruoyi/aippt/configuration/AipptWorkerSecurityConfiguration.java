package com.ruoyi.aippt.configuration;
import com.ruoyi.aippt.security.AipptWorkerAuthenticationFilter;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
/** 即使模块关闭也占用 internal 路径并默认拒绝，不能被用户安全链意外接纳。 */
@Configuration(proxyBeanMethods=false) public class AipptWorkerSecurityConfiguration  {
    @Bean @Order(1) public SecurityFilterChain aipptWorkerChain(HttpSecurity http,AipptProperties p)throws Exception  {
        http.securityMatcher("/internal/aippt/**").csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) .requestCache(c->c.disable()).authorizeHttpRequests(c->  {
            if(p.isEnabled()&&p.getWorker().isEnabled())c.anyRequest().hasAuthority("AIPPT_WORKER");
            else c.anyRequest().denyAll();
        }
        ) .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->  {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":401,\"msg\":\"Worker凭据无效\"}");
        }
        )) .addFilterBefore(new AipptWorkerAuthenticationFilter(p),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
