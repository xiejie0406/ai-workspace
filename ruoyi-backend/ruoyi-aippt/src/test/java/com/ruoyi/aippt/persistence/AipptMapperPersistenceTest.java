package com.ruoyi.aippt.persistence;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.persistence.*;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
/** 真正 MyBatis SQL/CAS/事务回滚；H2隔离内存，仅证明共同SQL路径，不代替实MySQL验收。 */
public class AipptMapperPersistenceTest  {
    private AipptRepository db;
    private TransactionTemplate tx;
    public static LocalDateTime utcTimestamp(int precision)  {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
    @BeforeEach void prepare() throws Exception  {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:aippt-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = ds.getConnection();
        var sql = connection.createStatement())  {
            sql.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.persistence.AipptMapperPersistenceTest.utcTimestamp'");
            sql.execute("CREATE TABLE aippt_project(id CHAR(36) PRIMARY KEY, project_id CHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP,updated_at TIMESTAMP)");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(AipptMapper.class);
        factory.setConfiguration(configuration);
        db = new AipptRepository(new SqlSessionTemplate(factory.getObject()).getMapper(AipptMapper.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    private JSONObject project()  {
        String id = UUID.randomUUID().toString();
        JSONObject project = JSONObject.of("id", id, "project_id", id, "owner_user_id", "42", "status", "draft", "revision", 1, "title", "正式项目");
        tx.execute(status ->  {
            db.insert("project", project);
            return null;
        }
        );
        return project;
    }
    @Test void dataSurvivesSessionAndFailedTransactionDoesNotCommit()  {
        JSONObject original = project();
        JSONObject reread = db.get("project", original.getString("id"));
        assertEquals("正式项目", reread.getString("title"));
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->  {
            JSONObject locked = db.lock("project", original.getString("id"));
            locked.put("title", "必须回滚");
            db.save("project", locked, 1);
            throw new IllegalStateException("注入事务失败");
        }
        ));
        assertEquals("正式项目", db.get("project", original.getString("id")).getString("title"));
        assertEquals(1, db.get("project", original.getString("id")).getLongValue("revision"));
    }
    @Test void twoConcurrentStaleSavesAcceptExactlyOne() throws Exception  {
        JSONObject original = project();
        String id = original.getString("id");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Boolean> save = () ->  {
            JSONObject current = db.get("project", id);
            start.await();
            try  {
                tx.execute(status ->  {
                    current.put("title", Thread.currentThread().getName());
                    db.save("project", current, 1);
                    return null;
                }
                );
                return true;
            }
            catch (AipptException conflict)  {
                return false;
            }
        }
        ;
        try  {
            Future<Boolean> one = executor.submit(save), two = executor.submit(save);
            start.countDown();
            assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertEquals(2, db.get("project", id).getLongValue("revision"));
        }
        finally  {
            executor.shutdownNow();
        }
    }
    @Test void sqlTableNamesAreWhitelistedBeforeInterpolation()  {
        assertThrows(IllegalArgumentException.class, () -> db.get("project; DROP TABLE sys_user", "id"));
    }
}
