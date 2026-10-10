package com.ruoyi.paismart.application.billing;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.Values;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原五组政策；Redis原子计用户请求，Token正式预算由同事务MySQL账本承载。 */
@Service
@PaismartEnabled
public class RateLimitService {
    private static final DefaultRedisScript<Long> SINGLE=new DefaultRedisScript<>("local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>=tonumber(ARGV[1]) then local t=redis.call('TTL',KEYS[1]); return -math.max(1,t) end; n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[2]) end; return n",Long.class);
    private static final DefaultRedisScript<Long> DUAL=new DefaultRedisScript<>("local a=tonumber(redis.call('GET',KEYS[1]) or '0'); local b=tonumber(redis.call('GET',KEYS[2]) or '0'); if a>=tonumber(ARGV[1]) then return -math.max(1,redis.call('TTL',KEYS[1])) end; if b>=tonumber(ARGV[3]) then return -math.max(1,redis.call('TTL',KEYS[2])) end; a=redis.call('INCR',KEYS[1]); b=redis.call('INCR',KEYS[2]); if a==1 then redis.call('EXPIRE',KEYS[1],ARGV[2]) end; if b==1 then redis.call('EXPIRE',KEYS[2],ARGV[4]) end; return a",Long.class);
    private final JdbcTemplate jdbc;private final StringRedisTemplate redis;
    public RateLimitService(@Qualifier("paismartJdbc")JdbcTemplate jdbc,StringRedisTemplate redis){this.jdbc=jdbc;this.redis=redis;}
    public static Map<String,Object> defaultRules(){Map<String,Object> rules=new LinkedHashMap<>();rules.put("chatMessage",Map.of("max",30L,"windowSeconds",60L));rules.put("llmGlobalToken",budget(120000,8000000));rules.put("embeddingUploadToken",budget(200000,20000000));rules.put("embeddingQueryRequest",budget(60,5000));rules.put("embeddingQueryGlobalToken",budget(60000,4000000));return rules;}
    private static Map<String,Object> budget(long minute,long day){return Map.of("minuteMax",minute,"minuteWindowSeconds",60L,"dayMax",day,"dayWindowSeconds",86400L);}
    public Map<String,Object> configuration(){return configuration(jdbc);}
    public static Map<String,Object> configuration(JdbcTemplate db){List<Map<String,Object>> rows=db.queryForList("SELECT chat_per_minute AS chatPerMinute,upload_per_minute AS uploadPerMinute,daily_chat_tokens AS dailyChatTokens,max_file_bytes AS maxFileBytes,row_version AS rowVersion,rules_json FROM ps_rate_limit_policy WHERE id=1");
        Map<String,Object> result=new LinkedHashMap<>();Object raw=null;
        if(rows.isEmpty())result.putAll(Map.of("chatPerMinute",30,"uploadPerMinute",10,"dailyChatTokens",1000000L,"maxFileBytes",104857600L,"rowVersion",0L));
        else {Map<String,Object> row=rows.get(0);for(String key:List.of("chatPerMinute","uploadPerMinute","dailyChatTokens","maxFileBytes","rowVersion"))result.put(key,row.get(key));raw=row.get("rules_json");}
        Map<String,Object> rules=raw==null?new LinkedHashMap<>(defaultRules()):Jsons.map(raw.toString());
        if(raw==null)rules.put("chatMessage",Map.of("max",result.get("chatPerMinute"),"windowSeconds",60L));
        result.put("rules",normalizeRules(rules));return result;
    }
    private static Map<String,Object> normalizeRules(Map<String,Object> input){Map<String,Object> rules=new LinkedHashMap<>(input);Map<String,Object> chat=new LinkedHashMap<>(Values.map(rules.get("chatMessage")));if(!chat.containsKey("max")&&chat.containsKey("maxRequests"))chat.put("max",chat.get("maxRequests"));chat.remove("maxRequests");rules.put("chatMessage",chat);return rules;}
    public static void validateRules(Map<String,Object> rules){if(!rules.keySet().equals(defaultRules().keySet()))throw new IllegalArgumentException("必须完整提供原五组窗口政策");
        Map<String,Object> chat=Values.map(rules.get("chatMessage"));range(chat,"max",1,1000000);range(chat,"windowSeconds",1,86400);
        for(String key:List.of("llmGlobalToken","embeddingUploadToken","embeddingQueryRequest","embeddingQueryGlobalToken")){Map<String,Object> item=Values.map(rules.get(key));range(item,"minuteMax",1,1000000000000L);range(item,"dayMax",1,1000000000000L);long min=range(item,"minuteWindowSeconds",1,86400),day=range(item,"dayWindowSeconds",1,604800);if(day<min)throw new IllegalArgumentException("长窗口秒数不能小于短窗口");if(day==min&&number(item,"dayMax")!=number(item,"minuteMax"))throw new IllegalArgumentException("相同秒数窗口必须采用同一预算上限");}
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Map<String,Object> save(Map<String,Object> body,long version){Map<String,Object> existing=configuration();Map<String,Object> rules=body.containsKey("rules")?normalizeRules(Values.map(body.get("rules"))):Values.map(existing.get("rules"));validateRules(rules);
        int chat=(int)range(body,"chatPerMinute",1,1000000),upload=(int)range(body,"uploadPerMinute",1,10000);long daily=range(body,"dailyChatTokens",1,1000000000000L),bytes=range(body,"maxFileBytes",1,2147483648L);
        if(body.containsKey("rules"))chat=(int)number(Values.map(rules.get("chatMessage")),"max");else {rules=new LinkedHashMap<>(rules);Map<String,Object> c=new LinkedHashMap<>(Values.map(rules.get("chatMessage")));c.put("max",chat);rules.put("chatMessage",c);}
        if(version==0)jdbc.update("INSERT INTO ps_rate_limit_policy(id,chat_per_minute,upload_per_minute,daily_chat_tokens,max_file_bytes,rules_json) VALUES(1,?,?,?,?,?)",chat,upload,daily,bytes,Jsons.write(rules));
        else if(jdbc.update("UPDATE ps_rate_limit_policy SET chat_per_minute=?,upload_per_minute=?,daily_chat_tokens=?,max_file_bytes=?,rules_json=?,row_version=row_version+1 WHERE id=1 AND row_version=?",chat,upload,daily,bytes,Jsons.write(rules),version)!=1)throw new IllegalStateException("限流配置已更新，请刷新");
        return configuration();
    }
    public void acquire(long user,String operation){Map<String,Object> p=configuration();Map<String,Object> chat=Values.map(Values.map(p.get("rules")).get("chatMessage"));long max="CHAT".equals(operation)?number(chat,"max"):number(p,"uploadPerMinute"),seconds="CHAT".equals(operation)?number(chat,"windowSeconds"):60;
        request(SINGLE,List.of("paismart:rate:"+operation+":"+user+":"+seconds),String.valueOf(max),String.valueOf(seconds));}
    public void acquireEmbeddingQuery(long user){Map<String,Object> p=Values.map(Values.map(configuration().get("rules")).get("embeddingQueryRequest"));long min=number(p,"minuteWindowSeconds"),day=number(p,"dayWindowSeconds");
        request(DUAL,List.of("paismart:rate:embedding-query:short:"+user+":"+min,"paismart:rate:embedding-query:long:"+user+":"+day),String.valueOf(number(p,"minuteMax")),String.valueOf(min),String.valueOf(number(p,"dayMax")),String.valueOf(day));}
    private void request(DefaultRedisScript<Long> script,List<String> keys,Object... args){try{Long result=redis.execute(script,keys,args);if(result==null)throw new IllegalStateException("限流服务未返回结果");if(result<0)throw new RateLimitExceededException("请求窗口已达到上限",Math.negateExact(result));}catch(org.springframework.dao.DataAccessException unavailable){throw new IllegalStateException("限流服务未就绪，暂不能发起处理请求");}}
    private static long number(Map<String,Object> body,String key){Object value=body.get(key);if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("限流字段必须是整数："+key);return n.longValue();}
    private static long range(Map<String,Object> body,String key,long min,long max){long value=number(body,key);if(value<min||value>max)throw new IllegalArgumentException("限流字段超出允许范围："+key);return value;}
}
