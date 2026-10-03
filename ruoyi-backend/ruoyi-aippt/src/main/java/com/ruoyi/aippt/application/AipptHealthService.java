package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.*;
import com.ruoyi.aippt.domain.AipptRules;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

/** 只读依赖探测，复用平台连接；固定有界线程/队列/等待，不触发DDL或Provider。 */
@Service @AipptEnabled
public class AipptHealthService implements AutoCloseable {
    private static final List<String> TABLES=List.of("project","source","source_section","outline","slide","task","proposal","export","asset","event","audit","outbox","idempotency","snapshot");
    private final DataSource dataSource;
    private final RedisConnectionFactory redis;
    private final AipptProperties props;
    private final CatalogService catalog;
    private final ThreadPoolExecutor executor;
    private final Duration timeout,cacheDuration;
    private volatile JSONObject cached;
    private volatile Instant expires=Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public AipptHealthService(@Qualifier("dynamicDataSource")DataSource ds,RedisConnectionFactory r,AipptProperties p,CatalogService c) {
        this(ds,r,p,c,Duration.ofSeconds(2),Duration.ofSeconds(5));
    }
    AipptHealthService(DataSource ds,RedisConnectionFactory r,AipptProperties p,CatalogService c,Duration wait,Duration cache) {
        dataSource=ds;redis=r;props=p;catalog=c;timeout=wait;cacheDuration=cache;
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),runnable->{
            Thread thread=new Thread(runnable,"aippt-health");
            thread.setDaemon(true);
            return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized JSONObject snapshot() {
        if(cached!=null&&Instant.now().isBefore(expires))return AipptRules.copy(cached);
        long deadline=System.nanoTime()+timeout.toNanos();
        Future<JSONObject> database=submit(this::databaseProbe);
        Future<JSONObject> redisResult=submit(this::redisProbe);
        JSONObject db=await(database,deadline,true),rd=await(redisResult,deadline,false);
        JSONObject result=JSONObject.of("enabled",props.isEnabled(),"worker_enabled",props.getWorker().isEnabled(),"provider_enabled",props.getProvider().isEnabled(),"catalog_version",catalog.version(),"checked_at",Instant.now().toString());
        result.putAll(db);result.putAll(rd);
        result.put("status","ok".equals(result.getString("database"))&&"ok".equals(result.getString("redis"))&&"ready".equals(result.getString("schema"))?"ok":"degraded");
        cached=result;expires=Instant.now().plus(cacheDuration);
        return AipptRules.copy(result);
    }
    private Future<JSONObject> submit(Callable<JSONObject> probe) {
        if(executor.getActiveCount()>=2)return null;
        try { return executor.submit(probe); }
        catch(RejectedExecutionException e) { return null; }
    }
    private JSONObject await(Future<JSONObject> future,long deadline,boolean database) {
        if(future==null)return unavailable(database,"busy");
        try { return future.get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
        catch(TimeoutException e) { future.cancel(true);executor.purge();return unavailable(database,"timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();future.cancel(true);return unavailable(database,"unknown"); }
        catch(ExecutionException e) { return unavailable(database,"down"); }
    }
    private static JSONObject unavailable(boolean database,String state) {
        return database?JSONObject.of("database",state,"database_code","DATABASE_"+state.toUpperCase(java.util.Locale.ROOT),"schema","unavailable","schema_code","SCHEMA_UNAVAILABLE")
            :JSONObject.of("redis",state,"redis_code","REDIS_"+state.toUpperCase(java.util.Locale.ROOT));
    }
    private JSONObject databaseProbe() {
        try(Connection connection=dataSource.getConnection()) {
            try(Statement query=connection.createStatement()) {
                query.setQueryTimeout(1);
                try(ResultSet row=query.executeQuery("SELECT 1")) {
                    if(!row.next()||row.getInt(1)!=1)return unavailable(true,"down");
                }
            }
            JSONObject result=JSONObject.of("database","ok","database_code","DATABASE_OK");
            try {
                for(String table:TABLES) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    try(Statement query=connection.createStatement()) {
                        query.setQueryTimeout(1);
                        try(ResultSet ignored=query.executeQuery("SELECT id,project_id,owner_user_id,status,revision,body,created_at,updated_at FROM aippt_"+table+" WHERE 1=0")) { }
                    }
                }
                try(Statement query=connection.createStatement()) {
                    query.setQueryTimeout(1);
                    try(ResultSet ignored=query.executeQuery("SELECT event_seq FROM aippt_project WHERE 1=0")) { }
                    try(ResultSet gate=query.executeQuery("SELECT id FROM aippt_dispatch_gate WHERE id=1")) {
                        if(!gate.next())return schema(result,"missing","DISPATCH_GATE_MISSING");
                    }
                    try(ResultSet history=query.executeQuery("SELECT checksum,status FROM aippt_schema_history WHERE version=1")) {
                        if(!history.next())return schema(result,"missing","MIGRATION_HISTORY_MISSING");
                        if(!"applied".equals(history.getString(2)))return schema(result,"mismatch","MIGRATION_NOT_APPLIED");
                        if(!schemaChecksum().equals(history.getString(1)))return schema(result,"mismatch","MIGRATION_CHECKSUM_MISMATCH");
                    }
                }
                return schema(result,"ready","SCHEMA_READY");
            }
            catch(SQLException e) {
                String state=e.getSQLState();
                return schema(result,state!=null&&state.startsWith("42")?"missing":"unavailable",state!=null&&state.startsWith("42")?"SCHEMA_MISSING":"SCHEMA_UNAVAILABLE");
            }
            catch(InterruptedException e) { Thread.currentThread().interrupt();return schema(result,"unavailable","SCHEMA_TIMEOUT"); }
            catch(RuntimeException e) { return schema(result,"unavailable","SCHEMA_UNAVAILABLE"); }
        }
        catch(Exception e) { return unavailable(true,"down"); }
    }
    private static JSONObject schema(JSONObject result,String state,String code) {
        result.put("schema",state);result.put("schema_code",code);return result;
    }
    static String schemaChecksum() {
        try(var stream=AipptHealthService.class.getResourceAsStream("/db/aippt-migration/V1__create_aippt_tables.sql")) {
            if(stream==null)throw new IllegalStateException("missing packaged schema");
            return AipptRules.hash(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
        catch(java.io.IOException e) { throw new IllegalStateException("packaged schema unavailable"); }
    }
    private JSONObject redisProbe() {
        try(var connection=redis.getConnection()) {
            return "PONG".equals(connection.ping())?JSONObject.of("redis","ok","redis_code","REDIS_OK"):unavailable(false,"down");
        }
        catch(RuntimeException e) { return unavailable(false,"down"); }
    }
    @jakarta.annotation.PreDestroy public void close() { executor.shutdownNow(); }
}
