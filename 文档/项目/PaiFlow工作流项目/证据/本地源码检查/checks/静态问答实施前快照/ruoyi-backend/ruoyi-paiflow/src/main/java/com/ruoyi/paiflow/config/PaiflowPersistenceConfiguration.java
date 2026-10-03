package com.ruoyi.paiflow.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** 复用平台sqlSessionFactory和数据源，不另建业务连接或自动初始化库。 */
@Configuration
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
@MapperScan(basePackages = "com.ruoyi.paiflow.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
public class PaiflowPersistenceConfiguration {}
