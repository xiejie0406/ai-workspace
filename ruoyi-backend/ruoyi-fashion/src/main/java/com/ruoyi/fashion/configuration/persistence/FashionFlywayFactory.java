package com.ruoyi.fashion.configuration.persistence;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** 固定 Fashion Flyway 的位置、history 表和禁止 clean/baseline 自动猜测策略。 */
public final class FashionFlywayFactory {
    public static final String LOCATION = "classpath:db/fashion-migration";
    public static final String HISTORY_TABLE = "fashion_flyway_schema_history";
    public static final String BASELINE_VERSION = "0";
    public static final String EXPECTED_VERSION = "4";

    private FashionFlywayFactory() {
    }

    public static Flyway create(DataSource dataSource) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                .target(EXPECTED_VERSION)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .baselineVersion(BASELINE_VERSION)
                .baselineDescription("Existing RuoYi schema before Fashion");
        return configuration.load();
    }
}
