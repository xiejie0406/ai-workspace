package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.Values;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** 调用指纹、重放响应与未知远端结果正式落库；结算过的调用不再次发送。 */
@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class ModelCallService {
    public record Call(String id,String status,String reservation,String response){}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final QuotaService quota;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.paismart.application.billing.RateLimitService limits;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.paismart.application.MembershipService members;
    public ModelCallService(@Qualifier("paismartJdbc")JdbcTemplate db,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx,QuotaService quota){this.db=db;this.tx=tx;this.quota=quota;}
    private String fingerprint(Object request){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Jsons.write(canonical(request)).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private Object canonical(Object value){if(value instanceof Map<?,?> m){Map<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted;}if(value instanceof List<?> l)return l.stream().map(this::canonical).toList();return value;}
    public Call begin(long user,String operation,String scope,Object request,long estimate){String hash=fingerprint(request);return tx.execute(s->{db.queryForObject("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",Long.class,user);if(members==null)throw new IllegalStateException("模型业务资格守卫未装配");members.requireEligible(user);List<Map<String,Object>> prior=db.queryForList("SELECT * FROM ps_model_call WHERE user_id=? AND scope=? AND operation_id=? FOR UPDATE",user,scope,operation);if(!prior.isEmpty()){Map<String,Object> row=prior.get(0);if(!hash.equals(row.get("request_hash")))throw new IllegalStateException("同一模型调用的输入指纹不同");String state=Values.text(row.get("status"));if(Set.of("COMPLETED","STOPPED").contains(state))return new Call(Values.text(row.get("id")),state,Values.text(row.get("reservation_id")),Values.text(row.get("response_json")));throw new IllegalStateException("MODEL_CALL_UNCERTAIN:该调用已发出或结果未核对，禁止自动重送；请查调用记录");}if("EMBEDDING".equals(scope)&&!operation.startsWith("embed:")){if(limits==null)throw new IllegalStateException("Embedding查询限流未装配");limits.acquireEmbeddingQuery(user);}var reserved=quota.reserve(user,"model:"+operation,scope,estimate);if(!"RESERVED".equals(reserved.state()))throw new IllegalStateException("额度已结算但缺少可重放模型响应，拒绝再次发送");String id=Values.id();db.update("INSERT INTO ps_model_call(id,user_id,operation_id,scope,request_hash,status,reservation_id,created_at,updated_at) VALUES(?,?,?,?,?,'STARTED',?,?,?)",id,user,operation,scope,hash,reserved.id(),LocalDateTime.now(),LocalDateTime.now());return new Call(id,"STARTED",reserved.id(),"");});}
    public void complete(Call call,Object response,long actual,boolean estimated,boolean stopped){tx.executeWithoutResult(s->{String state=db.queryForObject("SELECT status FROM ps_model_call WHERE id=? FOR UPDATE",String.class,call.id());if(!"STARTED".equals(state))throw new IllegalStateException("模型调用状态已变化");quota.settle(call.reservation(),actual);db.update("UPDATE ps_model_call SET status=?,response_json=?,actual_tokens=?,estimated=?,updated_at=? WHERE id=?",stopped?"STOPPED":"COMPLETED",Jsons.write(response),actual,estimated,LocalDateTime.now(),call.id());});}
    public void uncertain(Call call,String reason,Object partial){db.update("UPDATE ps_model_call SET status='UNCERTAIN',reason=?,response_json=?,updated_at=? WHERE id=? AND status='STARTED'",reason,Jsons.write(partial),LocalDateTime.now(),call.id());}
    public List<Map<String,Object>> unresolved(long user,boolean admin){return db.queryForList("SELECT id,user_id AS userId,operation_id AS operationId,scope,status,reason,created_at AS createdAt FROM ps_model_call WHERE status IN ('STARTED','UNCERTAIN') AND (?=TRUE OR user_id=?) ORDER BY created_at DESC LIMIT 500",admin,user);}
    public Map<String,Object> reconcile(String id,long actual,String reason){if(actual<0||reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("核对用量与原因无效");return tx.execute(s->{Map<String,Object> row=db.queryForMap("SELECT * FROM ps_model_call WHERE id=? FOR UPDATE",id);if(!Set.of("STARTED","UNCERTAIN").contains(Values.text(row.get("status"))))throw new IllegalStateException("调用已核对");quota.settle(Values.text(row.get("reservation_id")),actual);db.update("UPDATE ps_model_call SET status='RECONCILED',actual_tokens=?,estimated=FALSE,reason=?,updated_at=? WHERE id=?",actual,reason,LocalDateTime.now(),id);return Map.of("id",id,"status","RECONCILED","actualTokens",actual);});}
}

