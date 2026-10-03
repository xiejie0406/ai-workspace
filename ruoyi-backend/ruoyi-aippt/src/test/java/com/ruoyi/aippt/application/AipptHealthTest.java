package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.AipptProperties;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 完全Fake连接；验证只读SQL、状态分离、有界等待与线程释放，不连接真实服务。 */
class AipptHealthTest {
    private final DataSource ds=mock(DataSource.class);
    private final RedisConnectionFactory redis=mock(RedisConnectionFactory.class);
    private final RedisConnection redisConnection=mock(RedisConnection.class);
    private final List<String> sql=Collections.synchronizedList(new ArrayList<>());
    private String schemaMode="ready";
    private AipptHealthService service(Duration cache) throws Exception {
        return service(cache,Duration.ofSeconds(5));
    }
    private AipptHealthService service(Duration cache,Duration wait) throws Exception {
        Connection connection=mock(Connection.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenAnswer(invocation->{
            Statement statement=mock(Statement.class);
            when(statement.executeQuery(anyString())).thenAnswer(query->{
                String command=query.getArgument(0);sql.add(command);
                assertTrue(command.startsWith("SELECT "),command);
                if(command.contains("aippt_schema_history")&&"missing".equals(schemaMode))throw new SQLException("jdbc://private?password=do-not-expose","42S02");
                ResultSet rows=mock(ResultSet.class);
                when(rows.next()).thenReturn(true,false);
                when(rows.getInt(1)).thenReturn(1);
                if(command.contains("aippt_schema_history")) {
                    when(rows.getString(1)).thenReturn("mismatch".equals(schemaMode)?"0".repeat(64):AipptHealthService.schemaChecksum());
                    when(rows.getString(2)).thenReturn("failed".equals(schemaMode)?"failed":"applied");
                }
                return rows;
            });
            return statement;
        });
        when(redis.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        AipptProperties props=new AipptProperties();props.setEnabled(true);
        return new AipptHealthService(ds,redis,props,new CatalogService(props),wait,cache);
    }
    @Test void healthyResultUsesOnlyFixedReadQueriesAndPingAndCachesProbe() throws Exception {
        try(AipptHealthService health=service(Duration.ofSeconds(5))) {
            JSONObject result=health.snapshot();
            assertEquals("ok",result.getString("status"));assertEquals("ok",result.getString("database"));
            assertEquals("ready",result.getString("schema"));assertEquals("ok",result.getString("redis"));
            int count=sql.size();assertEquals(18,count);
            JSONObject repeated=health.snapshot();repeated.put("schema","changed");
            assertEquals("ready",health.snapshot().getString("schema"));
            assertEquals(count,sql.size());
            verify(redisConnection,times(1)).ping();
            verify(redisConnection,times(1)).close();
        }
    }
    @Test void schemaMissingAndChecksumOrFailedHistoryDoNotClaimDatabaseIsDown() throws Exception {
        for(String mode:List.of("missing","mismatch","failed")) {
            schemaMode=mode;
            try(AipptHealthService health=service(Duration.ZERO)) {
                JSONObject result=health.snapshot();
                assertEquals("ok",result.getString("database"));
                assertEquals("missing".equals(mode)?"missing":"mismatch",result.getString("schema"));
                assertEquals("degraded",result.getString("status"));
                assertFalse(result.toJSONString().contains("password"));
                assertFalse(result.toJSONString().contains("jdbc:"));
            }
        }
    }
    @Test void connectionFailuresExposeOnlyStableCodesAndNeverAttemptSchemaOrProvider() throws Exception {
        try(AipptHealthService health=service(Duration.ZERO)) {
            when(ds.getConnection()).thenThrow(new SQLException("secret connection url"));
            when(redis.getConnection()).thenThrow(new IllegalStateException("password=secret"));
            JSONObject result=health.snapshot();
            assertEquals("down",result.getString("database"));assertEquals("down",result.getString("redis"));
            assertEquals("unavailable",result.getString("schema"));
            assertTrue(sql.isEmpty());
            assertFalse(result.toJSONString().contains("secret"));
            assertFalse(result.toJSONString().contains("password"));
        }
    }
    @Test void blockedConnectionAcquisitionHasBoundedResponseAndCannotGrowExecutor() throws Exception {
        AtomicBoolean release=new AtomicBoolean();
        CountDownLatch started=new CountDownLatch(2);
        AipptHealthService health=service(Duration.ZERO,Duration.ofMillis(150));
        when(ds.getConnection()).thenAnswer(invocation->{waitUntilReleased(release,started);throw new SQLException("fake released");});
        when(redis.getConnection()).thenAnswer(invocation->{waitUntilReleased(release,started);throw new IllegalStateException("fake released");});
        try {
            JSONObject result=assertTimeoutPreemptively(Duration.ofSeconds(2),health::snapshot);
            assertEquals("timeout",result.getString("database"));assertEquals("timeout",result.getString("redis"));
            assertEquals(0,started.getCount());
            JSONObject busy=assertTimeoutPreemptively(Duration.ofMillis(500),health::snapshot);
            assertEquals("busy",busy.getString("database"));assertEquals("busy",busy.getString("redis"));
            verify(ds,times(1)).getConnection();verify(redis,times(1)).getConnection();
        }
        finally { release.set(true);health.close(); }
    }
    private static void waitUntilReleased(AtomicBoolean release,CountDownLatch started) {
        started.countDown();
        while(!release.get()) {
            try { Thread.sleep(5); } catch(InterruptedException ignored) { }
        }
    }
}
