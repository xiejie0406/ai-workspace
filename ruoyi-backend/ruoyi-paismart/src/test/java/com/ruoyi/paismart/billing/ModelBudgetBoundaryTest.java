package com.ruoyi.paismart.billing;

import com.ruoyi.paismart.application.billing.*;
import com.ruoyi.paismart.api.PaismartExceptionHandler;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 内存库只验证正式账本算法及事务边界，不替代真实MySQL/Redis联机验收。 */
class ModelBudgetBoundaryTest {
    JdbcTemplate db;QuotaService quota;TestClock clock;
    @BeforeEach void prepare() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:budget_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/paismart/V005__evaluation_datasets.sql"));}
        db=new JdbcTemplate(ds);clock=new TestClock(Instant.parse("2026-10-03T01:00:10Z"));quota=new QuotaService(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),clock);
        policy(50,100,1000);for(long u=2;u<10;u++)quota.savePolicy(u,"DAILY",1000,1000,0);
    }
    void policy(long shortMax,long longMax,long personal){Map<String,Object> rules=new LinkedHashMap<>(RateLimitService.defaultRules());for(String k:List.of("llmGlobalToken","embeddingUploadToken","embeddingQueryGlobalToken"))rules.put(k,Map.of("minuteMax",shortMax,"minuteWindowSeconds",60,"dayMax",longMax,"dayWindowSeconds",86400));RateLimitService.validateRules(rules);db.update("INSERT INTO ps_rate_limit_policy(id,chat_per_minute,upload_per_minute,daily_chat_tokens,max_file_bytes,rules_json) VALUES(1,30,10,?,100000,?) ON DUPLICATE KEY UPDATE daily_chat_tokens=VALUES(daily_chat_tokens),rules_json=VALUES(rules_json)",personal,Jsons.write(rules));}
    long total(String kind,String field,long seconds){return db.queryForObject("SELECT COALESCE(SUM("+field+"),0) FROM ps_model_budget_window WHERE budget_kind=? AND window_seconds=?",Long.class,kind,seconds);}
    @Test void differentUsersShareLockedGlobalBudget() throws Exception {var pool=Executors.newFixedThreadPool(6);try{List<Callable<Boolean>> tasks=new ArrayList<>();for(long u=2;u<8;u++){long user=u;tasks.add(()->{try{quota.reserve(user,"model:chat:"+user,"LLM",20);return true;}catch(RateLimitExceededException expected){return false;}});}long passed=0;for(Future<Boolean> result:pool.invokeAll(tasks))if(result.get())passed++;assertEquals(2,passed);assertEquals(40,total("llmGlobalToken","reserved_tokens",60));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ps_token_reservation",Long.class));}finally{pool.shutdownNow();}}
    @Test void failedLongWindowRollsBackShortFreezeAndIndividualLedger(){policy(100,30,1000);quota.reserve(2,"first","LLM",20);assertThrows(RateLimitExceededException.class,()->quota.reserve(3,"second","LLM",20));assertEquals(20,total("llmGlobalToken","reserved_tokens",60));assertEquals(20,total("llmGlobalToken","reserved_tokens",86400));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ps_daily_usage WHERE user_id=3",Long.class));}
    @Test void releaseRestoresCapacityAndCompletedReplayDoesNotFreezeAgain(){var a=quota.reserve(2,"same","LLM",40);assertEquals(a.id(),quota.reserve(2,"same","LLM",40).id());quota.release(a.id());quota.release(a.id());assertEquals(0,total("llmGlobalToken","reserved_tokens",60));var b=quota.reserve(3,"next","LLM",50);quota.settle(b.id(),30);quota.settle(b.id(),30);assertEquals(b.id(),quota.reserve(3,"next","LLM",50).id());assertEquals(30,total("llmGlobalToken","used_tokens",60));assertEquals(0,total("llmGlobalToken","reserved_tokens",60));}
    @Test void actualUsageAboveEstimateIsRecordedAndBlocksTheNextCall(){var a=quota.reserve(2,"first","LLM",10);quota.settle(a.id(),60);assertEquals(60,total("llmGlobalToken","used_tokens",60));assertThrows(RateLimitExceededException.class,()->quota.reserve(3,"next","LLM",1));assertEquals(60,quota.accounts(2).get(0).consumed());}
    @Test void lateSettlementUpdatesItsOriginalWindowWithoutPoisoningNewMinute(){var a=quota.reserve(2,"first","LLM",40);long firstStart=clock.instant().getEpochSecond()/60*60;clock.now=clock.now.plusSeconds(60);var b=quota.reserve(3,"second","LLM",40);quota.settle(a.id(),20);assertEquals(20,db.queryForObject("SELECT used_tokens FROM ps_model_budget_window WHERE budget_kind='llmGlobalToken' AND window_seconds=60 AND window_start=?",Long.class,firstStart));assertEquals(40,db.queryForObject("SELECT reserved_tokens FROM ps_model_budget_window WHERE budget_kind='llmGlobalToken' AND window_seconds=60 AND window_start=?",Long.class,firstStart+60));quota.release(b.id());assertEquals(20,total("llmGlobalToken","used_tokens",86400));}
    @Test void embeddingUploadAndQueryHaveIndependentGlobalWindows(){quota.reserve(2,"model:embed:doc:1","EMBEDDING",40);quota.reserve(2,"model:search:q","EMBEDDING",40);assertThrows(RateLimitExceededException.class,()->quota.reserve(3,"model:provider-test:e","EMBEDDING",20));assertEquals(40,total("embeddingUploadToken","reserved_tokens",60));assertEquals(40,total("embeddingQueryGlobalToken","reserved_tokens",60));assertEquals("embeddingQueryGlobalToken",ModelBudgetService.kind("EMBEDDING","model:provider-test:test"));}
    @Test void personalDailyProtectionCountsUsedAndFrozenAcrossGlobalMinutes(){policy(1000,1000,50);var a=quota.reserve(2,"first","LLM",40);clock.now=clock.now.plusSeconds(120);assertThrows(RateLimitExceededException.class,()->quota.reserve(2,"second","LLM",20));quota.settle(a.id(),30);quota.reserve(2,"third","LLM",20);assertThrows(RateLimitExceededException.class,()->quota.reserve(2,"fourth","LLM",1));}
    @Test void rateExceededMapsTo429WithRetryAfter(){var response=new PaismartExceptionHandler().rateLimited(new RateLimitExceededException("上限",37));assertEquals(429,response.getStatusCode().value());assertEquals("37",response.getHeaders().getFirst("Retry-After"));assertEquals("RATE_LIMITED",response.getBody().get("errorCode"));}
    @Test void returningAnotherFreezeRepaysDebtBeforeCreatingAvailableBalance(){for(boolean release:List.of(true,false)){long user=release?8:9;policy(1000,1000,1000);quota.savePolicy(user,"BALANCE",0,0,1);quota.credit(user,"LLM",100,"credit"+user,"测试授权",1);var a=quota.reserve(user,"a","LLM",50);var b=quota.reserve(user,"b","LLM",50);quota.settle(a.id(),100);assertEquals(50,quota.accounts(user).get(0).debt());if(release)quota.release(b.id());else quota.settle(b.id(),0);assertEquals(0,quota.accounts(user).get(0).debt());assertEquals(0,quota.accounts(user).get(0).available());assertEquals(100,quota.accounts(user).get(0).consumed());}}
    @Test void rulesRequireAllFivePoliciesAndCoherentSameWindows(){Map<String,Object> missing=new LinkedHashMap<>(RateLimitService.defaultRules());missing.remove("embeddingQueryRequest");assertThrows(IllegalArgumentException.class,()->RateLimitService.validateRules(missing));Map<String,Object> bad=new LinkedHashMap<>(RateLimitService.defaultRules());bad.put("llmGlobalToken",Map.of("minuteMax",10,"dayMax",20,"minuteWindowSeconds",60,"dayWindowSeconds",60));assertThrows(IllegalArgumentException.class,()->RateLimitService.validateRules(bad));}
    static final class TestClock extends Clock {volatile Instant now;TestClock(Instant i){now=i;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(now,z);}public Instant instant(){return now;}}
}
