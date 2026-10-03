package com.ruoyi.aipicture.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@AiPictureEnabled
@EnableConfigurationProperties(AiPictureProperties.class)
public class AiPictureConfiguration {
    @Bean("aipictureDataSource")
    public DataSource dataSource(AiPictureProperties p,@Qualifier("dynamicDataSource") DataSource existing) {
        // 用户已明确沿用现有 MySQL，只新增 aip_* 业务表。
        if(p.getJdbcUrl().isBlank()) return existing;
        if (!p.getJdbcUrl().startsWith("jdbc:mysql://") || p.getUsername().isBlank())
            throw new IllegalStateException("显式图片业务连接需要 MySQL URL 与账号");
        var ds = new DriverManagerDataSource(p.getJdbcUrl(),p.getUsername(),p.getPassword());
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }
    @Bean("aipictureJdbcTemplate")
    public NamedParameterJdbcTemplate jdbc(@Qualifier("aipictureDataSource") DataSource ds) {
        return new NamedParameterJdbcTemplate(ds);
    }
    @Bean("aipictureTransactionTemplate")
    public TransactionTemplate tx(@Qualifier("aipictureDataSource") DataSource ds) {
        return new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
}
