package com.ruoyi.aps.infrastructure.mysql.execution;

import java.sql.Connection;
import java.util.UUID;
import com.ruoyi.aps.infrastructure.mysql.configuration.ApsDataSourceConfiguration;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsExecutionMapper;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsOrderMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 在明确指定的既有数据库中只读执行页面查询，不迁移、建库或写入测试数据。 */
class ApsPageReadQueryTest
{
    @Test
    void executionListAndOrderScopeUseExistingSchema() throws Exception
    {
        String url = System.getenv("APS_READONLY_MYSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需明确指定既有数据库的只读验证连接");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv("APS_READONLY_MYSQL_USERNAME"), System.getenv("APS_READONLY_MYSQL_PASSWORD"));
        SqlSessionFactory factory = new ApsDataSourceConfiguration().apsSqlSessionFactory(dataSource);
        factory.getConfiguration().setEnvironment(new Environment("readonly-regression",
                new JdbcTransactionFactory(), dataSource));
        factory.getConfiguration().addMapper(ApsExecutionMapper.class);
        factory.getConfiguration().addMapper(ApsOrderMapper.class);
        try (Connection connection = dataSource.getConnection())
        {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (SqlSession session = factory.openSession(connection))
            {
                ApsExecutionMapper executions = session.getMapper(ApsExecutionMapper.class);
                ApsOrderMapper orders = session.getMapper(ApsOrderMapper.class);
                String absentOrder = UUID.randomUUID().toString();
                for (boolean allScope : new boolean[] { true, false })
                {
                    assertThat(executions.listRuns("1", allScope, "", "", "")).isNotNull();
                    assertThat(executions.listRuns("1", allScope, absentOrder, "READY", absentOrder)).isEmpty();
                    assertThat(orders.isOrderTasksInScope("1", allScope, absentOrder)).isZero();
                }
                session.rollback();
            }
        }
    }
}
