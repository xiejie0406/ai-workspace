package com.ruoyi.paismart.configuration;
import com.ruoyi.paismart.application.PaismartIdentity;
import com.ruoyi.paismart.application.billing.RateLimitService;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** 上传启动和普通聊天HTTP入口使用同一业务限流；WS发送在业务服务内补裁决。 */
@Configuration(proxyBeanMethods=false)
@PaismartEnabled
public class PaismartRateLimitConfiguration implements WebMvcConfigurer {
    private final RateLimitService limits;
    public PaismartRateLimitConfiguration(RateLimitService limits){this.limits=limits;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){@Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){if("POST".equals(request.getMethod())&&request.getRequestURI().endsWith("/paismart/uploads"))limits.acquire(PaismartIdentity.userId(),"UPLOAD");return true;}}).addPathPatterns("/paismart/uploads");}
}
