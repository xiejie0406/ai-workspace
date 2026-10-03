package com.ruoyi.aipicture.config;

import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 默认关闭，未批准连接目标业务库前不会装配业务运行设施。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "aipicture", name = "enabled", havingValue = "true")
public @interface AiPictureEnabled { }
