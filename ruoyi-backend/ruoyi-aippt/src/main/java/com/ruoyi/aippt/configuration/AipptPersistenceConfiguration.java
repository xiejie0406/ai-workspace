package com.ruoyi.aippt.configuration;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration(proxyBeanMethods=false) @AipptEnabled @MapperScan(basePackages="com.ruoyi.aippt.infrastructure.persistence",sqlSessionFactoryRef="aipptSqlSessionFactory") public class AipptPersistenceConfiguration  {
    @Bean public SqlSessionFactory aipptSqlSessionFactory(@Qualifier("dynamicDataSource") DataSource ds)throws Exception  {
        SqlSessionFactoryBean bean=new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(false);
        bean.setConfiguration(config);
        return bean.getObject();
    }
    @Bean public DataSourceTransactionManager aipptTransactionManager(@Qualifier("dynamicDataSource") DataSource ds)  {
        return new DataSourceTransactionManager(ds);
    }
    @Bean public TransactionTemplate aipptTransactions(@Qualifier("aipptTransactionManager") DataSourceTransactionManager tm)  {
        return new TransactionTemplate(tm);
    }
}
