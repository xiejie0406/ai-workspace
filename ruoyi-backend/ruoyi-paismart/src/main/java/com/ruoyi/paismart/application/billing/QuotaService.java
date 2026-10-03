package com.ruoyi.paismart.application.billing;

import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式余额/日额度账本；预留、结算、释放及追加在共享数据源事务中完成。 */
@Service
@PaismartEnabled
public class QuotaService {
    public record Account(String scope, long available, long frozen, long consumed, long debt) { }
    public record Reservation(String id, long userId, String scope, String operationId, String mode,
                              long reservedTokens, Long actualTokens, String state, LocalDate day) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ModelBudgetService globalBudget;
    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(@Qualifier("paismartJdbc") JdbcTemplate jdbc,
                        @Qualifier("paismartTransactionTemplate") TransactionTemplate tx) {
        this(jdbc, tx, Clock.systemUTC());
    }
    public QuotaService(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.clock = clock;this.globalBudget=new ModelBudgetService(jdbc,clock);
    }
    public Reservation reserve(long userId, String operationId, String scope, long tokens) {
        scope(scope); nonnegative(tokens); key(operationId, 160);
        return tx.execute(status -> {
            Account account = lockAccount(userId, scope);
            List<Reservation> old = reservations(userId, scope, operationId);
            if (!old.isEmpty()) {
                Reservation r = old.get(0);
                if (r.reservedTokens() != tokens) throw new IllegalStateException("同一调用的额度预留参数不一致");
                return r;
            }
            if (account.debt() > 0) throw new IllegalStateException("存在已发生用量欠额，请补充额度后重试");
            LocalDate day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
            Map<String, Object> policy = policy(userId);
            String mode = (String) policy.get("mode");
            jdbc.update("INSERT INTO ps_daily_usage(user_id,business_day,scope) VALUES(?,?,?) ON DUPLICATE KEY UPDATE user_id=user_id", userId, day, scope);
            Map<String, Object> daily = jdbc.queryForMap("SELECT * FROM ps_daily_usage WHERE user_id=? AND business_day=? AND scope=? FOR UPDATE", userId, day, scope);
            if("LLM".equals(scope)) {
                long dailyProtection=((Number)RateLimitService.configuration(jdbc).get("dailyChatTokens")).longValue();
                if(Math.addExact(Math.addExact(number(daily.get("used_tokens")),number(daily.get("reserved_tokens"))),tokens)>dailyProtection) {
                    long nextDay=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond()-clock.instant().getEpochSecond();
                    throw new RateLimitExceededException("每日个人对话Token保护上限已达到",nextDay);
                }
            }
            if ("DAILY".equals(mode)) {
                long limit = number(policy.get(scope.equals("LLM") ? "dailyLlmTokens" : "dailyEmbeddingTokens"));
                long proposed = Math.addExact(Math.addExact(number(daily.get("used_tokens")), number(daily.get("reserved_tokens"))), tokens);
                if (proposed > limit) throw new IllegalStateException("当日额度不足");
            } else {
                if (account.available() < tokens) throw new IllegalStateException("可用额度不足");
                jdbc.update("UPDATE ps_token_account SET available=available-?,frozen=frozen+?,row_version=row_version+1 WHERE user_id=? AND scope=?", tokens, tokens, userId, scope);
            }
            String id = UUID.randomUUID().toString();
            globalBudget.reserve(id,scope,operationId,tokens);
            jdbc.update("INSERT INTO ps_token_reservation(id,user_id,scope,operation_id,mode,reserved_tokens,state,business_day) VALUES(?,?,?,?,?,?,'RESERVED',?)", id, userId, scope, operationId, mode, tokens, day);
            jdbc.update("UPDATE ps_daily_usage SET reserved_tokens=reserved_tokens+?,request_count=request_count+1 WHERE user_id=? AND business_day=? AND scope=?", tokens, userId, day, scope);
            ledger(userId, scope, "reserve:" + id, "RESERVE", tokens, "调用额度预留", userId);
            return reservation(id);
        });
    }
    public void settle(String id, long actualTokens) {
        nonnegative(actualTokens);
        tx.executeWithoutResult(status -> {
            Reservation r = lockReservation(id);
            Account a = lockAccount(r.userId(), r.scope());
            if ("SETTLED".equals(r.state())) {
                if (!Objects.equals(r.actualTokens(), actualTokens)) throw new IllegalStateException("结算用量与原结算不一致");
                return;
            }
            if (!"RESERVED".equals(r.state())) throw new IllegalStateException("预留已释放，不能结算");
            globalBudget.settle(id,actualTokens);
            if ("BALANCE".equals(r.mode())) {
                long returned = r.reservedTokens() - actualTokens;
                long remaining = Math.addExact(Math.subtractExact(a.available(),a.debt()), returned);
                long debt = remaining < 0 ? Math.negateExact(remaining) : 0;
                jdbc.update("UPDATE ps_token_account SET available=?,frozen=frozen-?,consumed=consumed+?,debt=?,row_version=row_version+1 WHERE user_id=? AND scope=?", Math.max(0, remaining), r.reservedTokens(), actualTokens, debt, r.userId(), r.scope());
            } else {
                jdbc.update("UPDATE ps_token_account SET consumed=consumed+?,row_version=row_version+1 WHERE user_id=? AND scope=?", actualTokens, r.userId(), r.scope());
            }
            jdbc.update("UPDATE ps_daily_usage SET used_tokens=used_tokens+?,reserved_tokens=reserved_tokens-? WHERE user_id=? AND business_day=? AND scope=?", actualTokens, r.reservedTokens(), r.userId(), r.day(), r.scope());
            jdbc.update("UPDATE ps_token_reservation SET state='SETTLED',actual_tokens=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", actualTokens, id);
            ledger(r.userId(), r.scope(), "settle:" + id, "SETTLE", actualTokens, "供应商实际用量结算", r.userId());
        });
    }
    public void release(String id) {
        tx.executeWithoutResult(status -> {
            Reservation r = lockReservation(id); Account account=lockAccount(r.userId(), r.scope());
            if (!"RESERVED".equals(r.state())) return;
            globalBudget.release(id);
            if ("BALANCE".equals(r.mode())) {long repay=Math.min(account.debt(),r.reservedTokens());jdbc.update("UPDATE ps_token_account SET available=available+?,debt=debt-?,frozen=frozen-?,row_version=row_version+1 WHERE user_id=? AND scope=?", r.reservedTokens()-repay,repay,r.reservedTokens(), r.userId(), r.scope());}
            jdbc.update("UPDATE ps_daily_usage SET reserved_tokens=reserved_tokens-? WHERE user_id=? AND business_day=? AND scope=?", r.reservedTokens(), r.userId(), r.day(), r.scope());
            jdbc.update("UPDATE ps_token_reservation SET state='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            ledger(r.userId(), r.scope(), "release:" + id, "RELEASE", r.reservedTokens(), "未发生用量释放", r.userId());
        });
    }
    public void credit(long userId, String scope, long tokens, String operationKey, String reason, long operator) {
        scope(scope); nonnegative(tokens); key(operationKey, 192); text(reason, 500);
        tx.executeWithoutResult(status -> {
            Account a = lockAccount(userId, scope);
            List<Map<String, Object>> old = jdbc.queryForList("SELECT user_id,scope,tokens,reason FROM ps_token_ledger WHERE operation_key=?", operationKey);
            if (!old.isEmpty()) {
                Map<String, Object> row = old.get(0);
                if (number(row.get("user_id")) != userId || !scope.equals(row.get("scope")) || number(row.get("tokens")) != tokens || !reason.equals(row.get("reason"))) throw new IllegalStateException("同一额度追加请求参数不一致");
                return;
            }
            long repay = Math.min(tokens, a.debt());
            long next = Math.addExact(a.available(), tokens - repay);
            jdbc.update("UPDATE ps_token_account SET available=?,debt=debt-?,row_version=row_version+1 WHERE user_id=? AND scope=?", next, repay, userId, scope);
            ledger(userId, scope, operationKey, "CREDIT", tokens, reason, operator);
        });
    }
    public List<Account> accounts(long userId) {
        return jdbc.query("SELECT * FROM ps_token_account WHERE user_id=? ORDER BY scope", (rs,n) -> account(rs), userId);
    }
    public Map<String, Object> policy(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT mode,daily_llm_tokens,daily_embedding_tokens,row_version FROM ps_quota_policy WHERE user_id=?", userId);
        if (rows.isEmpty()) return Map.of("mode", "BALANCE", "dailyLlmTokens", 0L, "dailyEmbeddingTokens", 0L, "rowVersion", 0L);
        Map<String, Object> r = rows.get(0);
        return Map.of("mode", r.get("mode"), "dailyLlmTokens", r.get("daily_llm_tokens"), "dailyEmbeddingTokens", r.get("daily_embedding_tokens"), "rowVersion", r.get("row_version"));
    }
    public void savePolicy(long userId, String mode, long llm, long embedding, long rowVersion) {
        if (!Set.of("BALANCE", "DAILY").contains(mode)) throw new IllegalArgumentException("额度策略无效");
        nonnegative(llm); nonnegative(embedding);
        tx.executeWithoutResult(status -> {
            lockAccount(userId, "LLM");
            if (rowVersion == 0) jdbc.update("INSERT INTO ps_quota_policy(user_id,mode,daily_llm_tokens,daily_embedding_tokens) VALUES(?,?,?,?)", userId, mode, llm, embedding);
            else if (jdbc.update("UPDATE ps_quota_policy SET mode=?,daily_llm_tokens=?,daily_embedding_tokens=?,row_version=row_version+1 WHERE user_id=? AND row_version=?", mode,llm,embedding,userId,rowVersion) != 1) throw new IllegalStateException("额度策略已更新，请刷新");
        });
    }
    public List<Map<String, Object>> daily(long userId, int days) {
        LocalDate today=businessToday(),after=today.minusDays(Math.max(1,Math.min(days,90)) - 1);
        return jdbc.queryForList("SELECT business_day AS businessDay,scope,used_tokens AS usedTokens,reserved_tokens AS reservedTokens,request_count AS requestCount FROM ps_daily_usage WHERE user_id=? AND business_day>=? AND business_day<=? ORDER BY business_day,scope", userId,after,today);
    }
    public List<Map<String, Object>> records(long userId, int offset, int size) {
        return jdbc.queryForList("SELECT id,scope,action,tokens,available_after AS availableAfter,frozen_after AS frozenAfter,debt_after AS debtAfter,reason,created_at AS createdAt FROM ps_token_ledger WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",userId,Math.max(1,Math.min(size,100)),Math.max(0,offset));
    }
    /** 同一正式账本兼容余额与日额度视图，不以余额账户的0替代每日剩余额度。 */
    public Map<String,Object> snapshot(long userId) {
        var policy=policy(userId);var accounts=accounts(userId);LocalDate today=businessToday();
        Map<String,Object> result=new LinkedHashMap<>();result.put("userId",userId);result.put("policy",policy);result.put("accounts",accounts);
        result.put("daily",daily(userId,30));result.put("records",records(userId,0,20));result.put("businessDay",today.toString());
        result.put("llm",scopeSnapshot(userId,"LLM",policy,accounts,today));result.put("embedding",scopeSnapshot(userId,"EMBEDDING",policy,accounts,today));
        result.put("chatRequestCount",chatCount(userId,today));
        result.put("totalChatRequestCount",jdbc.queryForObject("SELECT COUNT(*) FROM ps_chat_generation WHERE user_id=?",Long.class,userId));
        return result;
    }
    private Map<String,Object> scopeSnapshot(long userId,String scope,Map<String,Object> policy,List<Account> accounts,LocalDate today) {
        Account account=accounts.stream().filter(a->a.scope().equals(scope)).findFirst().orElse(new Account(scope,0,0,0,0));
        var usage=jdbc.queryForList("SELECT used_tokens,reserved_tokens,request_count FROM ps_daily_usage WHERE user_id=? AND scope=? AND business_day=?",userId,scope,today);
        long used=usage.isEmpty()?0:number(usage.get(0).get("used_tokens")),reserved=usage.isEmpty()?0:number(usage.get(0).get("reserved_tokens")),requests=usage.isEmpty()?0:number(usage.get(0).get("request_count"));
        boolean daily="DAILY".equals(policy.get("mode"));long limit=daily?number(policy.get(scope.equals("LLM")?"dailyLlmTokens":"dailyEmbeddingTokens")):0;
        long remaining=daily?Math.max(0,limit-used-reserved):account.available();
        Map<String,Object> view=new LinkedHashMap<>();view.put("scope",scope);view.put("mode",daily?"DAILY":"BALANCE");view.put("enabled",daily&&limit>0);view.put("limitTokens",daily?limit:null);
        view.put("usedTokens",used);view.put("reservedTokens",reserved);view.put("remainingTokens",remaining);view.put("requestCount",requests);
        view.put("totalRequestCount",jdbc.queryForObject("SELECT COALESCE(SUM(request_count),0) FROM ps_daily_usage WHERE user_id=? AND scope=?",Long.class,userId,scope));
        view.put("available",account.available());view.put("frozen",account.frozen());view.put("consumed",account.consumed());view.put("debt",account.debt());return view;
    }
    /** 原管理总览的7/30日趋势、今日排行和额度预警，SQL聚合避免读取全部用户历史。 */
    public Map<String,Object> overview(int days) {
        int normalized=days<=7?7:30;LocalDate today=businessToday(),after=today.minusDays(normalized-1);
        Map<LocalDate,Map<String,Object>> trends=new LinkedHashMap<>();
        for(int i=0;i<normalized;i++){LocalDate day=after.plusDays(i);Map<String,Object> point=new LinkedHashMap<>();point.put("day",day.toString());for(String k:List.of("chatRequestCount","llmUsedTokens","llmRequestCount","embeddingUsedTokens","embeddingRequestCount"))point.put(k,0L);trends.put(day,point);}
        var daily=jdbc.queryForList("SELECT business_day AS businessDay,scope,SUM(used_tokens) AS usedTokens,SUM(reserved_tokens) AS reservedTokens,SUM(request_count) AS requestCount FROM ps_daily_usage WHERE business_day>=? AND business_day<=? GROUP BY business_day,scope ORDER BY business_day,scope",after,today);
        for(var row:daily){LocalDate day=((java.sql.Date)row.get("businessDay")).toLocalDate();Map<String,Object> point=trends.get(day);if(point==null)continue;String scope=Objects.toString(row.get("scope"),"");if(!Set.of("LLM","EMBEDDING").contains(scope))continue;String prefix=scope.equals("LLM")?"llm":"embedding";point.put(prefix+"UsedTokens",number(row.get("usedTokens")));point.put(prefix+"RequestCount",number(row.get("requestCount")));}
        var chats=jdbc.query("SELECT CAST(created_at AS DATE) AS business_day,COUNT(*) AS requests FROM ps_chat_generation WHERE created_at>=? AND created_at<? GROUP BY CAST(created_at AS DATE)",(r,n)->Map.entry(r.getDate("business_day").toLocalDate(),r.getLong("requests")),java.sql.Timestamp.valueOf(after.atStartOfDay()),java.sql.Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
        for(var entry:chats)if(trends.containsKey(entry.getKey()))trends.get(entry.getKey()).put("chatRequestCount",entry.getValue());
        var accounts=jdbc.queryForList("SELECT scope,SUM(available) AS available,SUM(frozen) AS frozen,SUM(consumed) AS consumed,SUM(debt) AS debt FROM ps_token_account GROUP BY scope");
        Map<String,Object> result=new LinkedHashMap<>();result.put("days",normalized);result.put("today",trends.get(today));result.put("trends",List.copyOf(trends.values()));result.put("accounts",accounts);result.put("daily",daily);
        result.put("llmRankings",rankings("LLM",today));result.put("embeddingRankings",rankings("EMBEDDING",today));result.put("alerts",alerts(today));result.put("lowBalanceThreshold",9300L);return result;
    }
    private List<Map<String,Object>> rankings(String scope,LocalDate today) {
        var rows=jdbc.queryForList("SELECT d.user_id AS userId,u.user_name AS username,d.used_tokens AS usedTokens,d.request_count AS requestCount FROM ps_daily_usage d JOIN sys_user u ON u.user_id=d.user_id WHERE d.business_day=? AND d.scope=? AND d.used_tokens>0 ORDER BY d.used_tokens DESC,d.user_id LIMIT 5",today,scope);
        for(var row:rows){long userId=number(row.get("userId"));var view=scopeSnapshot(userId,scope,policy(userId),accounts(userId),today);row.put("scope",scope.toLowerCase(Locale.ROOT));row.put("mode",view.get("mode"));row.put("limitTokens",view.get("limitTokens"));row.put("remainingTokens",view.get("remainingTokens"));row.put("reservedTokens",view.get("reservedTokens"));}return rows;
    }
    private List<Map<String,Object>> alerts(LocalDate today) {
        // 原余额告警阈值9300与日额度80%预警分开，冻结与欠额必须有可见影响。
        var rows=jdbc.queryForList("SELECT a.user_id,u.user_name,a.scope,a.available,a.frozen,a.debt,COALESCE(p.mode,'BALANCE') AS mode,COALESCE(p.daily_llm_tokens,0) AS llm_limit,COALESCE(p.daily_embedding_tokens,0) AS embedding_limit,COALESCE(d.used_tokens,0) AS used,COALESCE(d.reserved_tokens,0) AS reserved,COALESCE(d.request_count,0) AS requests FROM ps_token_account a JOIN sys_user u ON u.user_id=a.user_id LEFT JOIN ps_quota_policy p ON p.user_id=a.user_id LEFT JOIN ps_daily_usage d ON d.user_id=a.user_id AND d.scope=a.scope AND d.business_day=? WHERE (COALESCE(p.mode,'BALANCE')='BALANCE' AND (a.available<=9300 OR a.debt>0)) OR (p.mode='DAILY' AND ((a.scope='LLM' AND p.daily_llm_tokens>0 AND COALESCE(d.used_tokens,0)+COALESCE(d.reserved_tokens,0)>=p.daily_llm_tokens*0.8) OR (a.scope='EMBEDDING' AND p.daily_embedding_tokens>0 AND COALESCE(d.used_tokens,0)+COALESCE(d.reserved_tokens,0)>=p.daily_embedding_tokens*0.8)))",today);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows){boolean daily="DAILY".equals(row.get("mode"));String scope=Objects.toString(row.get("scope"),"");long used=number(row.get("used")),reserved=number(row.get("reserved")),limit=daily?number(row.get(scope.equals("LLM")?"llm_limit":"embedding_limit")):0,remaining=daily?Math.max(0,limit-used-reserved):number(row.get("available")),debt=number(row.get("debt"));boolean critical=remaining==0||debt>0;
            Map<String,Object> alert=new LinkedHashMap<>();alert.put("userId",number(row.get("user_id")));alert.put("username",row.get("user_name"));alert.put("scope",scope.toLowerCase(Locale.ROOT));alert.put("mode",daily?"DAILY":"BALANCE");alert.put("level",critical?"critical":"warning");alert.put("usedTokens",used);alert.put("reservedTokens",reserved);alert.put("limitTokens",daily?limit:null);alert.put("remainingTokens",remaining);alert.put("requestCount",number(row.get("requests")));alert.put("debt",debt);alert.put("usageRatio",daily?(used+(double)reserved)/limit:null);alert.put("message",debt>0?"存在已发生用量欠额":daily?(critical?"今日额度已耗尽":"今日额度已接近上限"):(critical?"可用余额已耗尽":"可用余额低于预警阈值"));result.add(alert);
        }
        result.sort(Comparator.<Map<String,Object>,Integer>comparing(a->"critical".equals(a.get("level"))?0:1).thenComparingLong(a->number(a.get("remainingTokens"))).thenComparingLong(a->number(a.get("userId"))));return result;
    }
    private LocalDate businessToday(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));}
    private long chatCount(long userId,LocalDate day){return jdbc.queryForObject("SELECT COUNT(*) FROM ps_chat_generation WHERE user_id=? AND created_at>=? AND created_at<?",Long.class,userId,java.sql.Timestamp.valueOf(day.atStartOfDay()),java.sql.Timestamp.valueOf(day.plusDays(1).atStartOfDay()));}
    private Account lockAccount(long userId,String scope) {
        if (userId <= 0) throw new IllegalArgumentException("用户标识无效");
        jdbc.update("INSERT INTO ps_token_account(user_id,scope) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=user_id",userId,scope);
        return jdbc.queryForObject("SELECT * FROM ps_token_account WHERE user_id=? AND scope=? FOR UPDATE",(rs,n) -> account(rs),userId,scope);
    }
    private Account account(ResultSet r) throws SQLException { return new Account(r.getString("scope"),r.getLong("available"),r.getLong("frozen"),r.getLong("consumed"),r.getLong("debt")); }
    private Reservation reservation(String id) { return jdbc.queryForObject("SELECT * FROM ps_token_reservation WHERE id=?",(rs,n)->reservation(rs),id); }
    private Reservation lockReservation(String id) { return jdbc.queryForObject("SELECT * FROM ps_token_reservation WHERE id=? FOR UPDATE",(rs,n)->reservation(rs),id); }
    private List<Reservation> reservations(long uid,String scope,String op) { return jdbc.query("SELECT * FROM ps_token_reservation WHERE user_id=? AND scope=? AND operation_id=?",(rs,n)->reservation(rs),uid,scope,op); }
    private Reservation reservation(ResultSet r) throws SQLException { Number actual=(Number)r.getObject("actual_tokens"); return new Reservation(r.getString("id"),r.getLong("user_id"),r.getString("scope"),r.getString("operation_id"),r.getString("mode"),r.getLong("reserved_tokens"),actual == null ? null : actual.longValue(),r.getString("state"),r.getDate("business_day").toLocalDate()); }
    private void ledger(long uid,String scope,String op,String action,long tokens,String reason,long operator) {
        Account a=lockAccount(uid,scope);
        jdbc.update("INSERT INTO ps_token_ledger(id,user_id,scope,operation_key,action,tokens,available_after,frozen_after,debt_after,reason,operator_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),uid,scope,op,action,tokens,a.available(),a.frozen(),a.debt(),reason,operator);
    }
    public static void scope(String value) { if (!Set.of("LLM","EMBEDDING").contains(value)) throw new IllegalArgumentException("用量类型无效"); }
    public static void nonnegative(long n) { if(n<0 || n>1_000_000_000_000L) throw new IllegalArgumentException("额度值超出允许范围"); }
    public static void key(String value,int max) { if(value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("请求标识无效"); }
    public static void text(String value,int max) { if(value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("说明不能为空或超过长度限制"); }
    private static long number(Object v) { return ((Number)v).longValue(); }
}
