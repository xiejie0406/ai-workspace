package com.ruoyi.fashion.configuration;

import com.ruoyi.fashion.application.image.port.FashionImageProviderPort;
import com.ruoyi.fashion.infrastructure.image.AliyunFashionImageProviderAdapter;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@FashionModuleEnabled
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FashionImageProperties.class,FashionAliyunImageProperties.class})
@EnableScheduling
public class FashionImageConfiguration {
    @Bean
    @ConditionalOnMissingBean(FashionImageProviderPort.class)
    FashionImageProviderPort fashionImageProviderPort(FashionImageProperties properties,FashionAliyunImageProperties aliyun,
            FashionObjectStoragePort storage,ObjectMapper mapper) {
        return new AliyunFashionImageProviderAdapter(properties,aliyun,storage,mapper);
    }
}
