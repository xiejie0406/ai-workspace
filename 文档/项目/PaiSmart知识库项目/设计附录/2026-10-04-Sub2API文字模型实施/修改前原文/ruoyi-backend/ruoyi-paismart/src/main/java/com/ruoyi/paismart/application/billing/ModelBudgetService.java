package com.ruoyi.paismart.application.billing;
import com.ruoyi.paismart.infrastructure.intelligence.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.util.*;

/** 仅在Quota事务内调用；MySQL持有正式全局预算，Redis只计请求。 */
public final class ModelBudgetService {
    private final JdbcTemplate db;private final Clock clock;
    public ModelBudgetService(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}
    public static String kind(String scope,String operation){if("LLM".equals(scope))return "llmGlobalToken";String op=operation.startsWith("model:")?operation.substring(6):operation;return op.startsWith("embed:")?"embeddingUploadToken":"embeddingQueryGlobalToken";}
    public void reserve(String reservation,String scope,String operation,long tokens){String kind=kind(scope,operation);Map<String,Object> config=Values.map(Values.map(RateLimitService.configuration(db).get("rules")).get(kind));long minSeconds=Values.number(config.get("minuteWindowSeconds"),60),daySeconds=Values.number(config.get("dayWindowSeconds"),86400),now=clock.instant().getEpochSecond();long minStart=now/minSeconds*minSeconds,dayStart=now/daySeconds*daySeconds;
        lockAndReserve(kind,minSeconds,minStart,tokens,Values.number(config.get("minuteMax"),0),now);
        // 相同窗口配置只冻结一次，避免重复计入同一物理行。
        if(minSeconds!=daySeconds)lockAndReserve(kind,daySeconds,dayStart,tokens,Values.number(config.get("dayMax"),0),now);else if(Values.number(config.get("dayMax"),0)<Values.number(config.get("minuteMax"),0))throw new IllegalArgumentException("相同窗口必须采用一致预算上限");
        db.update("INSERT INTO ps_model_budget_reservation(reservation_id,budget_kind,minute_seconds,minute_start,day_seconds,day_start,reserved_tokens,state) VALUES(?,?,?,?,?,?,?,'RESERVED')",reservation,kind,minSeconds,minStart,daySeconds,dayStart,tokens);
    }
    private void lockAndReserve(String kind,long seconds,long start,long tokens,long max,long now){db.update("INSERT INTO ps_model_budget_window(budget_kind,window_seconds,window_start) VALUES(?,?,?) ON DUPLICATE KEY UPDATE window_start=window_start",kind,seconds,start);Map<String,Object> row=db.queryForMap("SELECT * FROM ps_model_budget_window WHERE budget_kind=? AND window_seconds=? AND window_start=? FOR UPDATE",kind,seconds,start);long proposed=Math.addExact(Math.addExact(Values.number(row.get("used_tokens"),0),Values.number(row.get("reserved_tokens"),0)),tokens);if(proposed>max)throw new RateLimitExceededException("全局模型Token窗口预算已达上限",start+seconds-now);db.update("UPDATE ps_model_budget_window SET reserved_tokens=reserved_tokens+? WHERE budget_kind=? AND window_seconds=? AND window_start=?",tokens,kind,seconds,start);}
    public void settle(String reservation,long actual){finish(reservation,actual,false);}
    public void release(String reservation){finish(reservation,0,true);}
    private void finish(String id,long actual,boolean release){Map<String,Object> row=db.queryForMap("SELECT * FROM ps_model_budget_reservation WHERE reservation_id=? FOR UPDATE",id);String state=Values.text(row.get("state"));if(!"RESERVED".equals(state)){if(!release&&"SETTLED".equals(state)&&Values.number(row.get("actual_tokens"),0)!=actual)throw new IllegalStateException("全局预算重复结算不一致");return;}String kind=Values.text(row.get("budget_kind"));long tokens=Values.number(row.get("reserved_tokens"),0),minSeconds=Values.number(row.get("minute_seconds"),0),daySeconds=Values.number(row.get("day_seconds"),0);updateWindow(kind,minSeconds,Values.number(row.get("minute_start"),0),tokens,release?0:actual);if(minSeconds!=daySeconds)updateWindow(kind,daySeconds,Values.number(row.get("day_start"),0),tokens,release?0:actual);db.update("UPDATE ps_model_budget_reservation SET state=?,actual_tokens=? WHERE reservation_id=?",release?"RELEASED":"SETTLED",release?null:actual,id);}
    private void updateWindow(String kind,long seconds,long start,long reserved,long actual){db.queryForMap("SELECT * FROM ps_model_budget_window WHERE budget_kind=? AND window_seconds=? AND window_start=? FOR UPDATE",kind,seconds,start);if(db.update("UPDATE ps_model_budget_window SET reserved_tokens=reserved_tokens-?,used_tokens=used_tokens+? WHERE budget_kind=? AND window_seconds=? AND window_start=? AND reserved_tokens>=?",reserved,actual,kind,seconds,start,reserved)!=1)throw new IllegalStateException("全局预算冻结状态不一致");}
}
