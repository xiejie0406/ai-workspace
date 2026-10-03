package com.ruoyi.fashion.configuration.persistence;

import javax.sql.DataSource;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 迁移关闭仍只读核对业务就绪；失败仅阻止Fashion业务，不阻止共享平台启动。 */
@Component
@FashionModuleEnabled
@EnableConfigurationProperties(FashionMigrationProperties.class)
public class FashionSchemaReadiness implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FashionSchemaReadiness.class);
    private final DataSource dataSource;
    private final FashionMigrationProperties properties;
    private volatile boolean ready;

    public FashionSchemaReadiness(@Qualifier("dynamicDataSource") DataSource dataSource, FashionMigrationProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            FashionDatabasePreconditions.verifyCurrentSchema(dataSource, properties.getExpectedDatabase());
            FashionFlywayFactory.create(dataSource).validate();
            ready = true;
        } catch (RuntimeException ex) {
            ready = false;
            LOG.error("Fashion业务Schema未就绪，业务请求将返回503；请核对目标库、V4迁移及约束", ex);
        }
    }

    public boolean isReady() { return ready; }
    public String reason() { return ready ? null : "商品业务数据版本未就绪，请管理员核对原库V4迁移及约束后重启服务"; }
}
