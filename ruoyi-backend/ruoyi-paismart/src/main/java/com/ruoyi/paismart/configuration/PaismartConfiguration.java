package com.ruoyi.paismart.configuration;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用共享若依数据源；DDL 由经批准的脚本显式执行。 */
@Configuration(proxyBeanMethods = false)
@PaismartEnabled
public class PaismartConfiguration {
    @Bean("paismartJdbc")
    public JdbcTemplate jdbc(@Qualifier("dynamicDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }
    @Bean("paismartTransactionManager")
    public PlatformTransactionManager transactionManager(@Qualifier("dynamicDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }
    @Bean("paismartTransactionTemplate")
    public TransactionTemplate transactions(@Qualifier("paismartTransactionManager") PlatformTransactionManager manager) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
        return tx;
    }
}
