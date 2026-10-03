package com.ruoyi.paismart.configuration;

import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 缺省关闭；不创建连接、消费线程、业务表或默认管理员。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "paismart", name = "enabled", havingValue = "true")
public @interface PaismartEnabled { }
