package com.ruoyi.aipicture.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.ruoyi.aipicture.persistence.AiPictureRepository;
import com.ruoyi.aipicture.worker.AiPictureWorkerAuthFilter;

@Configuration(proxyBeanMethods=false) @AiPictureEnabled
public class AiPictureWorkerSecurityConfiguration {
    @Bean public AiPictureWorkerAuthFilter aipictureWorkerAuthFilter(AiPictureProperties p,AiPictureRepository db) {return new AiPictureWorkerAuthFilter(p,db);}
    @Bean public FilterRegistrationBean<AiPictureWorkerAuthFilter> disableWorkerServletFilter(AiPictureWorkerAuthFilter filter) {var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;}
    @Bean @Order(1)
    public SecurityFilterChain aipictureWorkerChain(HttpSecurity http,AiPictureWorkerAuthFilter filter) throws Exception {
        String prefix=AiPictureWorkerAuthFilter.PREFIX;
        return http.securityMatcher(prefix+"/**").csrf(csrf->csrf.disable())
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth.requestMatchers(HttpMethod.POST,prefix+"/claim",prefix+"/heartbeat",prefix+"/receipt",prefix+"/artifacts").hasRole("AIPICTURE_WORKER")
                .requestMatchers(HttpMethod.GET,prefix+"/assets/*").hasRole("AIPICTURE_WORKER").anyRequest().denyAll())
            .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class).build();
    }
}
