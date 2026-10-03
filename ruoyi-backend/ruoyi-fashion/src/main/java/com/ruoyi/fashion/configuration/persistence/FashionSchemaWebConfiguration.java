package com.ruoyi.fashion.configuration.persistence;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 复用若依统一异常处理，护栏只覆盖已启用Fashion业务路径。 */
@Configuration(proxyBeanMethods = false)
@FashionModuleEnabled
public class FashionSchemaWebConfiguration implements WebMvcConfigurer {
    private final FashionSchemaReadiness readiness;
    public FashionSchemaWebConfiguration(FashionSchemaReadiness readiness) { this.readiness = readiness; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!readiness.isReady()) throw new ServiceException(readiness.reason(), 503);
                return true;
            }
        }).addPathPatterns("/fashion/**").excludePathPatterns("/fashion/readiness");
    }
}
