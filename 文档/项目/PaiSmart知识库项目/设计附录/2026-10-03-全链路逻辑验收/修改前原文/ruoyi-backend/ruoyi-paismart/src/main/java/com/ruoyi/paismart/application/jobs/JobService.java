package com.ruoyi.paismart.application.jobs;

import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 任务真相，Kafka 只传递通知；租约结果需 CAS，重复通知不重复执行。 */
@Service
@PaismartEnabled
public class JobService {
    public record Job(String id,long userId,String documentId,String type,String state,
                      Map<String,Object> payload,Map<String,Object> result,String leaseToken,
                      Instant leaseUntil,int retries,String errorMessage,long rowVersion) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private org.springframework.beans.factory.ObjectProvider<PaismartJobHandler> handlers;
    @org.springframework.beans.factory.annotation.Autowired
    public void handlers(org.springframework.beans.factory.ObjectProvider<PaismartJobHandler> handlers){this.handlers=handlers;}
    public JobService(@Qualifier("paismartJdbc") JdbcTemplate jdbc,@Qualifier("paismartTransactionTemplate") TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public Job submit(long userId,String type,String documentId,String idempotencyKey) {
        return enqueue(userId,type,"DOCUMENT_PROCESS".equals(type)?Map.of("documentId",documentId,"contentVersion",1):Map.of("documentId",documentId),idempotencyKey);
    }
    public Job enqueue(long userId,String type,Map<String,Object> payload,String key) {
        if(userId<=0 || type==null || !type.matches("[A-Z_]{3,64}") || key==null || key.isBlank() || key.length()>160) throw new IllegalArgumentException("任务参数无效");
        String json=Jsons.write(payload);
        if(json.length()>1_000_000) throw new IllegalArgumentException("任务负载过大");
        return tx.execute(status -> {
            // 只锁既有平台身份，不通过提交任务创建或启用业务资格。
            Long existing=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' AND status='0' FOR UPDATE",Long.class,userId);
            if(existing==null) throw new IllegalArgumentException("平台账户不存在或不可用");
            List<Job> old=jdbc.query("SELECT * FROM ps_job WHERE user_id=? AND type=? AND request_key=?",(rs,n)->row(rs),userId,type,key);
            if(!old.isEmpty()) {
                if(!old.get(0).payload().equals(payload)) throw new IllegalStateException("同一任务请求的输入不同");
                return old.get(0);
            }
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ps_job(id,user_id,document_id,type,state,payload_json,request_key) VALUES(?,?,?,?,'QUEUED',?,?)",id,userId,payload.get("documentId"),type,json,key);
            outbox(id);
            return get(id);
        });
    }
    public Job get(String id) {
        List<Job> jobs=jdbc.query("SELECT * FROM ps_job WHERE id=?",(rs,n)->row(rs),id);
        if(jobs.isEmpty()) throw new IllegalArgumentException("任务不存在");
        return jobs.get(0);
    }
    /** 在调用者创建事务内锁既有平台身份并读幂等回执，派生对象生成 UUID 前先恢复原任务。 */
    public Optional<Job> findRequest(long userId,String type,String key){
        if(userId<=0||type==null||!type.matches("[A-Z_]{3,64}")||key==null||key.isBlank()||key.length()>160)throw new IllegalArgumentException("任务参数无效");
        return tx.execute(status->{jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' AND status='0' FOR UPDATE",Long.class,userId);return jdbc.query("SELECT * FROM ps_job WHERE user_id=? AND type=? AND request_key=?",(rs,n)->row(rs),userId,type,key).stream().findFirst();});
    }
    public Optional<Job> claim(String type,int leaseSeconds) {
        return tx.execute(status -> {
            List<Job> jobs=jdbc.query("SELECT * FROM ps_job WHERE type=? AND ((state IN ('QUEUED','RETRY') AND next_retry_at<=CURRENT_TIMESTAMP) OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP)) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",(rs,n)->row(rs),type);
            return jobs.isEmpty()?Optional.empty():claimLocked(jobs.get(0),leaseSeconds);
        });
    }
    public Optional<Job> claimById(String id,int leaseSeconds) {
        return tx.execute(status -> {
            List<Job> jobs=jdbc.query("SELECT * FROM ps_job WHERE id=? FOR UPDATE",(rs,n)->row(rs),id);
            if(jobs.isEmpty()) return Optional.empty();
            Job j=jobs.get(0);
            if(!Set.of("QUEUED","RETRY").contains(j.state()) && !("RUNNING".equals(j.state()) && j.leaseUntil()!=null && j.leaseUntil().isBefore(Instant.now()))) return Optional.empty();
            Number ready=jdbc.queryForObject("SELECT COUNT(*) FROM ps_job WHERE id=? AND next_retry_at<=CURRENT_TIMESTAMP",Integer.class,id);
            return ready.intValue()==0?Optional.empty():claimLocked(j,leaseSeconds);
        });
    }
    private Optional<Job> claimLocked(Job j,int seconds) {
        if(j.retries()>=4) { jdbc.update("UPDATE ps_job SET state='FAILED',error_message='任务恢复次数超过上限',lease_token=NULL,lease_until=NULL,row_version=row_version+1 WHERE id=?",j.id()); deadLetter(j.id(),"任务恢复次数超过上限");return Optional.empty(); }
        String token=UUID.randomUUID().toString();
        Instant until=Instant.now().plusSeconds(Math.max(30,Math.min(seconds,3600)));
        jdbc.update("UPDATE ps_job SET state='RUNNING',lease_token=?,lease_until=?,retries=retries+1,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",token,Timestamp.from(until),j.id());
        return Optional.of(get(j.id()));
    }
    public void heartbeat(String id,String leaseToken,int seconds) {
        if(jdbc.update("UPDATE ps_job SET lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>=CURRENT_TIMESTAMP",Timestamp.from(Instant.now().plusSeconds(Math.max(30,Math.min(seconds,3600)))),id,leaseToken)!=1) throw new IllegalStateException("任务租约失效");
    }
    public void requireLease(String id,String leaseToken) {
        // 行锁使用当前读，外层事务即使采用 REPEATABLE_READ 也不能沿用取消前快照。
        if(jdbc.queryForList("SELECT id FROM ps_job WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>=CURRENT_TIMESTAMP FOR UPDATE",id,leaseToken).size()!=1)throw new IllegalStateException("任务租约失效或任务已取消");
    }
    public void complete(String id,String leaseToken,Map<String,Object> result) {
        if(jdbc.update("UPDATE ps_job SET state='SUCCEEDED',result_json=?,lease_token=NULL,lease_until=NULL,error_message=NULL,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>=CURRENT_TIMESTAMP",Jsons.write(result),id,leaseToken)!=1) throw new IllegalStateException("任务租约失效或任务已取消");
    }
    public void fail(String id,String leaseToken,String error) {
        tx.executeWithoutResult(status -> {
            Job j=get(id);
            String next=j.retries()>=3?"FAILED":"RETRY";
            String safe=error==null?"处理失败":error.substring(0,Math.min(error.length(),500));
            if(jdbc.update("UPDATE ps_job SET state=?,error_message=?,next_retry_at=?,lease_token=NULL,lease_until=NULL,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>=CURRENT_TIMESTAMP",next,safe,Timestamp.from(Instant.now().plusSeconds(Math.min(300,10L<<j.retries()))),id,leaseToken)!=1) throw new IllegalStateException("任务租约失效");
            if("RETRY".equals(next)) {
                jdbc.update("UPDATE ps_outbox SET state='PENDING',next_attempt_at=? WHERE job_id=? AND topic='paismart.jobs'",Timestamp.from(Instant.now().plusSeconds(Math.min(300,10L<<j.retries()))),id);
            } else deadLetter(id,safe);
        });
    }
    public void retry(String id,long expectedVersion) {
        tx.executeWithoutResult(status -> {
            if(jdbc.update("UPDATE ps_job SET state='QUEUED',retries=0,error_message=NULL,lease_token=NULL,lease_until=NULL,next_retry_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=? AND row_version=? AND state IN ('FAILED','CANCELED')",id,expectedVersion)!=1) throw new IllegalStateException("任务已变化或状态不允许重试");
            jdbc.update("UPDATE ps_outbox SET state='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP WHERE job_id=? AND topic='paismart.jobs'",id);
            if(handlers!=null) {Job job=get(id);handlers.orderedStream().filter(h->h.supports(job.type())).forEach(h->h.onRetry(job));}
        });
    }
    public void cancel(String id,long expectedVersion) {
        tx.executeWithoutResult(s->{if(jdbc.update("UPDATE ps_job SET state='CANCELED',lease_token=NULL,lease_until=NULL,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND row_version=? AND state IN ('QUEUED','RETRY','RUNNING')",id,expectedVersion)!=1) throw new IllegalStateException("任务已完成或已变化");if(handlers!=null){Job job=get(id);handlers.orderedStream().filter(h->h.supports(job.type())).forEach(h->h.onCancelled(job));}});
    }
    public void checkpoint(String id,String token,String step,Map<String,Object> payload) {
        tx.executeWithoutResult(s -> {
            requireLease(id,token);
            jdbc.update("INSERT INTO ps_job_checkpoint(job_id,step,payload_json) VALUES(?,?,?) ON DUPLICATE KEY UPDATE payload_json=?,updated_at=CURRENT_TIMESTAMP",id,step,Jsons.write(payload),Jsons.write(payload));
        });
    }
    private void outbox(String id) {
        jdbc.update("INSERT INTO ps_outbox(id,job_id,topic,payload_json) VALUES(?,?,'paismart.jobs',?)",UUID.randomUUID().toString(),id,Jsons.write(Map.of("jobId",id)));
    }
    private void deadLetter(String id,String reason) {
        // 死信仅包含任务标识与脱敏原因；正文、密钥和对象内容仍在业务权限边界内。
        jdbc.update("INSERT INTO ps_outbox(id,job_id,topic,payload_json) VALUES(?,?,'paismart.jobs.dlq',?) ON DUPLICATE KEY UPDATE payload_json=?,state='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP",UUID.randomUUID().toString(),id,Jsons.write(Map.of("jobId",id,"reason",reason)),Jsons.write(Map.of("jobId",id,"reason",reason)));
    }
    private Job row(ResultSet r) throws SQLException { Timestamp lease=r.getTimestamp("lease_until"); return new Job(r.getString("id"),r.getLong("user_id"),r.getString("document_id"),r.getString("type"),r.getString("state"),Jsons.map(r.getString("payload_json")),Jsons.map(r.getString("result_json")),r.getString("lease_token"),lease==null?null:lease.toInstant(),r.getInt("retries"),r.getString("error_message"),r.getLong("row_version")); }
}
