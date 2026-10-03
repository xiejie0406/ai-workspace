package com.ruoyi.paismart.billing;

import com.ruoyi.paismart.application.billing.QuotaService;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 原7/30日总览及余额/日额度展示的固定预期，使用本地内存数据。 */
class UsageOverviewBoundaryTest {
    JdbcTemplate db;QuotaService service;
    final LocalDate today=LocalDate.of(2026,10,3);
    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"));ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/paismart/V005__evaluation_datasets.sql"));}
        db=new JdbcTemplate(source);db.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(50))");
        // 仅此查询夹具，无外部WS或模型执行；真实generation流程在ChatBoundaryTest单独验证。
        db.execute("CREATE TABLE ps_chat_generation(id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL)");
        for(long id=2;id<=12;id++)db.update("INSERT INTO sys_user VALUES(?,?)",id,"user"+id);
        service=new QuotaService(db,new TransactionTemplate(new DataSourceTransactionManager(source)),Clock.fixed(Instant.parse("2026-10-02T16:10:00Z"),ZoneOffset.UTC));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    static long n(Object value){return ((Number)value).longValue();}
    void grant(long user,long tokens){service.credit(user,"LLM",tokens,"grant:"+user,"固定测试额度",1);}
    void chat(long user,LocalDate day,int hour){db.update("INSERT INTO ps_chat_generation VALUES(?,?,?)",UUID.randomUUID().toString(),user,Timestamp.valueOf(day.atTime(hour,0)));}
    @Test void dailySnapshotShowsRemainingPolicyInsteadOfZeroBalance(){
        service.savePolicy(2,"DAILY",100,200,0);var first=service.reserve(2,"first","LLM",60);service.settle(first.id(),40);service.reserve(2,"second","LLM",20);
        chat(2,today,0);chat(2,today,23);chat(2,today.minusDays(1),23);chat(3,today,1);
        var snapshot=service.snapshot(2);var llm=map(snapshot.get("llm"));
        assertEquals("DAILY",llm.get("mode"));assertEquals(100,n(llm.get("limitTokens")));assertEquals(40,n(llm.get("usedTokens")));assertEquals(20,n(llm.get("reservedTokens")));assertEquals(40,n(llm.get("remainingTokens")));assertEquals(0,n(llm.get("available")));assertEquals(2,n(llm.get("requestCount")));assertEquals(2,n(snapshot.get("chatRequestCount")));assertEquals(3,n(snapshot.get("totalChatRequestCount")));assertEquals(200,n(map(snapshot.get("embedding")).get("remainingTokens")));
    }
    @Test void balanceSnapshotDoesNotInventDailyLimit(){
        grant(2,20000);var reservation=service.reserve(2,"consume","LLM",7000);service.settle(reservation.id(),7000);
        var view=map(service.snapshot(2).get("llm"));assertEquals("BALANCE",view.get("mode"));assertNull(view.get("limitTokens"));assertEquals(13000,n(view.get("remainingTokens")));assertEquals(7000,n(view.get("usedTokens")));assertEquals(1,n(view.get("totalRequestCount")));
    }
    @Test void overviewKeepsZeroDaysAndStrictDateBoundaries(){
        db.update("INSERT INTO ps_daily_usage(user_id,business_day,scope,used_tokens,request_count) VALUES(2,?,'LLM',30,2)",today.minusDays(6));
        db.update("INSERT INTO ps_daily_usage(user_id,business_day,scope,used_tokens,request_count) VALUES(2,?,'LLM',500,5)",today.minusDays(7));
        db.update("INSERT INTO ps_daily_usage(user_id,business_day,scope,used_tokens,request_count) VALUES(2,?,'LLM',999,9)",today.plusDays(1));
        chat(2,today,0);chat(2,today.plusDays(1),0);chat(2,today.minusDays(7),23);
        var weekly=service.overview(7);var trend=rows(weekly.get("trends"));assertEquals(7,trend.size());assertEquals("2026-09-27",trend.get(0).get("day"));assertEquals(30,n(trend.get(0).get("llmUsedTokens")));assertEquals(0,n(trend.get(1).get("llmUsedTokens")));assertEquals(1,n(map(weekly.get("today")).get("chatRequestCount")));assertEquals(0,n(map(weekly.get("today")).get("llmUsedTokens")));
        assertEquals(30,rows(service.overview(30).get("trends")).size());assertEquals(2,service.daily(2,30).size());
    }
    @Test void todayRankingsAreTopFiveNotAllTimeConsumers(){
        for(long id=2;id<=8;id++)db.update("INSERT INTO ps_daily_usage(user_id,business_day,scope,used_tokens,request_count) VALUES(?,?,'LLM',?,1)",id,today,id*10);
        db.update("INSERT INTO ps_daily_usage(user_id,business_day,scope,used_tokens,request_count) VALUES(12,?,'LLM',99999,3)",today.minusDays(1));
        db.update("INSERT INTO ps_daily_usage(user_id,business_day,scope,used_tokens,request_count) VALUES(3,?,'EMBEDDING',123,1)",today);
        var overview=service.overview(7);var rankings=rows(overview.get("llmRankings"));assertEquals(5,rankings.size());assertEquals(List.of(8L,7L,6L,5L,4L),rankings.stream().map(r->n(r.get("userId"))).toList());assertEquals(80,n(rankings.get(0).get("usedTokens")));assertEquals(123,n(rows(overview.get("embeddingRankings")).get(0).get("usedTokens")));
    }
    @Test void warningIncludesDailyFrozenAndExhaustionWithoutFakeBalanceRatio(){
        service.savePolicy(2,"DAILY",100,200,0);var used=service.reserve(2,"used","LLM",78);service.settle(used.id(),78);service.reserve(2,"pending","LLM",3);
        grant(3,9250);grant(5,10000);service.savePolicy(4,"DAILY",100,200,0);var exhausted=service.reserve(4,"full","LLM",100);service.settle(exhausted.id(),100);
        var alerts=rows(service.overview(7).get("alerts"));var daily=alerts.stream().filter(a->n(a.get("userId"))==2).findFirst().orElseThrow();assertEquals("warning",daily.get("level"));assertEquals(19,n(daily.get("remainingTokens")));assertEquals(0.81,(double)daily.get("usageRatio"),0.000001);
        var balance=alerts.stream().filter(a->n(a.get("userId"))==3).findFirst().orElseThrow();assertNull(balance.get("usageRatio"));assertNull(balance.get("limitTokens"));assertEquals("critical",alerts.get(0).get("level"));assertEquals(4,n(alerts.get(0).get("userId")));assertTrue(alerts.stream().noneMatch(a->n(a.get("userId"))==5));
    }
}
