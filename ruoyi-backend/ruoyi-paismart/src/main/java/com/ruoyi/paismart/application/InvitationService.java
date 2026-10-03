package com.ruoyi.paismart.application;

import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 有次数与过期约束的资格邀请；消费和初始额度必须原子完成。 */
@Service
@PaismartEnabled
public class InvitationService {
    public record Invite(String id,String code,String spaceId,int maxUses,int usedCount,
                         Instant expiresAt,boolean enabled,long initialLlmTokens,
                         long initialEmbeddingTokens,long rowVersion) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final QuotaService quotas;
    private final SpaceService spaces;
    public InvitationService(@Qualifier("paismartJdbc") JdbcTemplate jdbc,@Qualifier("paismartTransactionTemplate") TransactionTemplate tx,QuotaService quotas,SpaceService spaces) { this.jdbc=jdbc;this.tx=tx;this.quotas=quotas;this.spaces=spaces; }
    public Invite create(Map<String,Object> body,long operator) {
        String code=Objects.toString(body.get("code"),"").trim().toUpperCase(Locale.ROOT);
        if(code.isEmpty()) code=UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Za-z0-9_-]{6,64}")) throw new IllegalArgumentException("邀请码必须为6至64位字母、数字、下划线或短横线");
        int max=integer(body,"maxUses",1);
        if(max<1 || max>1_000_000) throw new IllegalArgumentException("邀请码次数无效");
        long llm=number(body,"initialLlmTokens",0), emb=number(body,"initialEmbeddingTokens",0);
        QuotaService.nonnegative(llm);QuotaService.nonnegative(emb);
        String space=Objects.toString(body.get("spaceId"),"");
        if(!space.isBlank() && !spaces.canManageSpace(new SpaceService.Scope(operator,SecurityUtils.isAdmin(operator)),space)) throw new org.springframework.security.access.AccessDeniedException("无权在该组织创建邀请");
        if(!space.isBlank() && !"ORGANIZATION".equals(spaces.get(space).type()))throw new IllegalArgumentException("邀请码只允许绑定组织空间");
        Timestamp expiry=expiry(body.get("expiresAt"));
        if(expiry!=null&&!expiry.toInstant().isAfter(Instant.now()))throw new IllegalArgumentException("邀请码有效期必须在未来");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ps_invite(id,code,space_id,max_uses,expires_at,enabled,initial_llm_tokens,initial_embedding_tokens,created_by) VALUES(?,?,?,?,?,?,?,?,?)",id,code,space.isBlank()?null:space,max,expiry,enabled(body),llm,emb,operator);
        return get(id);
    }
    /** 平台身份行串行化同一操作者的请求；整批失败回滚，不留下部分邀请码。 */
    public List<Invite> createIdempotent(Map<String,Object> body,long operator,String requestKey,int count) {
        QuotaService.key(requestKey,128);
        if(count<1 || count>100) throw new IllegalArgumentException("批量数量为1至100");
        if(count>1 && !Objects.toString(body.get("code"),"").isBlank()) throw new IllegalArgumentException("批量创建不能指定自定义邀请码");
        Map<String,Object> input=new TreeMap<>(body);input.put("count",count);
        String fingerprint=Jsons.write(input);
        return tx.execute(status -> {
            List<Long> users=jdbc.query("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",(r,n)->r.getLong(1),operator);
            if(users.isEmpty()) throw new IllegalArgumentException("平台账户不存在或不可用");
            var old=jdbc.queryForList("SELECT input_json,response_json FROM ps_mutation_request WHERE user_id=? AND kind='INVITE_CREATE' AND request_key=?",operator,requestKey);
            if(!old.isEmpty()) {
                if(!fingerprint.equals(old.get(0).get("input_json"))) throw new IllegalStateException("同一邀请码请求的输入不同");
                List<Invite> replay=Arrays.asList(Jsons.read(old.get(0).get("response_json").toString(),Invite[].class));for(Invite invite:replay)requireManage(invite,operator);return replay;
            }
            List<Invite> result=new ArrayList<>();
            for(int i=0;i<count;i++) {Map<String,Object> next=new LinkedHashMap<>(body);if(count>1) next.remove("code");result.add(create(next,operator));}
            jdbc.update("INSERT INTO ps_mutation_request(user_id,kind,request_key,input_json,response_json) VALUES(?,'INVITE_CREATE',?,?,?)",operator,requestKey,fingerprint,Jsons.write(result));
            return result;
        });
    }
    public Invite update(String id,Map<String,Object> body,long version) {
        Invite old=get(id);
        int max=integer(body,"maxUses",old.maxUses());
        if(max<old.usedCount() || max<1 || max>1_000_000) throw new IllegalArgumentException("次数不能小于已消费次数");
        long llm=number(body,"initialLlmTokens",old.initialLlmTokens()),emb=number(body,"initialEmbeddingTokens",old.initialEmbeddingTokens());
        QuotaService.nonnegative(llm);QuotaService.nonnegative(emb);
        String nextCode=body.containsKey("code")?normalizeCode(Objects.toString(body.get("code"),"")):old.code();
        // 原项目允许编辑未消费的邀请码；已消费后身份不再重绑，CAS同时检查消费次数。
        if(old.usedCount()>0&&!old.code().equals(nextCode))throw new IllegalArgumentException("已使用的邀请码不可改写，请新建");
        if(body.containsKey("spaceId") && !Objects.equals(old.spaceId(),body.get("spaceId"))) throw new IllegalArgumentException("邀请码组织不可改写，请新建");
        Timestamp nextExpiry=body.containsKey("expiresAt")?expiry(body.get("expiresAt")):old.expiresAt()==null?null:Timestamp.from(old.expiresAt());
        if(body.containsKey("expiresAt")&&nextExpiry!=null&&!nextExpiry.toInstant().isAfter(Instant.now()))throw new IllegalArgumentException("邀请码有效期必须在未来");
        boolean nextEnabled=body.containsKey("enabled")?enabled(body):old.enabled();
        try {
            if(jdbc.update("UPDATE ps_invite SET code=?,max_uses=?,expires_at=?,enabled=?,initial_llm_tokens=?,initial_embedding_tokens=?,row_version=row_version+1 WHERE id=? AND row_version=? AND used_count=?",nextCode,max,nextExpiry,nextEnabled,llm,emb,id,version,old.usedCount())!=1)throw new IllegalStateException("邀请码已更新或已被消费，请刷新");
        }catch(org.springframework.dao.DuplicateKeyException duplicate){throw new IllegalArgumentException("邀请码已经存在");}
        return get(id);
    }
    public void validate(String code) { valid(byCode(code)); }
    public void redeem(long userId,String code) {
        tx.executeWithoutResult(status -> {
            // 与成员分配统一先锁平台用户，避免资格行与平台行形成反向等待。
            List<Long> users=jdbc.query("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",(rs,n)->rs.getLong(1),userId);
            if(users.isEmpty()) throw new IllegalArgumentException("平台账户不存在或不可用");
            List<Invite> rows=jdbc.query("SELECT * FROM ps_invite WHERE UPPER(code)=? FOR UPDATE",(rs,n)->row(rs),normalizeCode(code));
            if(rows.isEmpty()) throw new IllegalArgumentException("邀请码无效");
            Invite invite=rows.get(0);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ps_invite_redemption WHERE invite_id=? AND user_id=?",Integer.class,invite.id(),userId)>0) return;
            valid(invite);
            jdbc.update("INSERT INTO ps_member(user_id,status) VALUES(?,'ACTIVE') ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=CURRENT_TIMESTAMP",userId);
            if(invite.spaceId()!=null) spaces.grantInvitedMembership(userId,invite.spaceId());
            jdbc.update("UPDATE ps_invite SET used_count=used_count+1,row_version=row_version+1 WHERE id=?",invite.id());
            jdbc.update("INSERT INTO ps_invite_redemption(id,invite_id,user_id) VALUES(?,?,?)",UUID.randomUUID().toString(),invite.id(),userId);
            quotas.credit(userId,"LLM",invite.initialLlmTokens(),"invite:"+invite.id()+":"+userId+":LLM","邀请码初始额度",userId);
            quotas.credit(userId,"EMBEDDING",invite.initialEmbeddingTokens(),"invite:"+invite.id()+":"+userId+":EMBEDDING","邀请码初始额度",userId);
            audit(userId,"INVITE_REDEEM",invite.id(),"消费知识库邀请资格");
        });
    }
    public Invite get(String id) { List<Invite> rows=jdbc.query("SELECT * FROM ps_invite WHERE id=?",(rs,n)->row(rs),id); if(rows.isEmpty()) throw new IllegalArgumentException("邀请码不存在");return rows.get(0); }
    public List<Invite> list(int offset,int size) { return jdbc.query("SELECT * FROM ps_invite ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->row(rs),Math.min(100,Math.max(1,size)),Math.max(0,offset)); }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM ps_invite",Long.class); }
    public List<Invite> listAuthorized(long operator,int offset,int size){return listAuthorized(operator,offset,size,null);}
    public long countAuthorized(long operator){return countAuthorized(operator,null);}
    public List<Invite> listAuthorized(long operator,int offset,int size,Boolean enabled){var visible=authorized(operator,enabled);int start=Math.min(visible.size(),Math.max(0,offset));return visible.subList(start,Math.min(visible.size(),start+Math.max(1,Math.min(100,size))));}
    public long countAuthorized(long operator,Boolean enabled){return authorized(operator,enabled).size();}
    private List<Invite> authorized(long operator,Boolean enabled){return jdbc.query("SELECT * FROM ps_invite"+(enabled==null?"":" WHERE enabled=?")+" ORDER BY created_at DESC,id",(rs,n)->row(rs),enabled==null?new Object[]{}:new Object[]{enabled}).stream().filter(i->canManage(i,operator)).toList();}
    private boolean canManage(Invite invite,long operator){if(SecurityUtils.isAdmin(operator))return true;if(invite.spaceId()!=null)return spaces.canManageSpace(new SpaceService.Scope(operator,false),invite.spaceId());return jdbc.queryForObject("SELECT created_by FROM ps_invite WHERE id=?",Long.class,invite.id())==operator;}
    private void requireManage(Invite invite,long operator){if(!canManage(invite,operator))throw new org.springframework.security.access.AccessDeniedException("无权管理该组织邀请码");}
    public Invite updateAuthorized(String id,Map<String,Object> body,long version,long operator){requireManage(get(id),operator);return update(id,body,version);}
    public void deleteAuthorized(String id,long version,long operator){requireManage(get(id),operator);delete(id,version);}
    public void delete(String id,long version) {
        Invite old=get(id);
        if(old.usedCount()>0) { if(jdbc.update("UPDATE ps_invite SET enabled=FALSE,row_version=row_version+1 WHERE id=? AND row_version=?",id,version)!=1) throw new IllegalStateException("邀请码已更新"); }
        else if(jdbc.update("DELETE FROM ps_invite WHERE id=? AND row_version=?",id,version)!=1) throw new IllegalStateException("邀请码已更新");
    }
    public void audit(long uid,String action,String resource,String reason) { jdbc.update("INSERT INTO ps_audit_event(id,user_id,action,resource_id,reason) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),uid,action,resource,reason); }
    private Invite byCode(String code) { List<Invite> rows=jdbc.query("SELECT * FROM ps_invite WHERE UPPER(code)=?",(rs,n)->row(rs),normalizeCode(code));if(rows.isEmpty()) throw new IllegalArgumentException("邀请码无效");return rows.get(0); }
    private static String normalizeCode(String code){String normalized=code==null?"":code.trim().toUpperCase(Locale.ROOT);if(!normalized.matches("[A-Z0-9_-]{6,64}"))throw new IllegalArgumentException("邀请码必须为6至64位字母、数字、下划线或短横线");return normalized;}
    private void valid(Invite i) { if(!i.enabled() || i.usedCount()>=i.maxUses() || (i.expiresAt()!=null && !i.expiresAt().isAfter(Instant.now()))) throw new IllegalArgumentException("邀请码已停用、过期或次数用完"); }
    private Invite row(java.sql.ResultSet r) throws java.sql.SQLException { Timestamp time=r.getTimestamp("expires_at");return new Invite(r.getString("id"),r.getString("code"),r.getString("space_id"),r.getInt("max_uses"),r.getInt("used_count"),time==null?null:time.toInstant(),r.getBoolean("enabled"),r.getLong("initial_llm_tokens"),r.getLong("initial_embedding_tokens"),r.getLong("row_version")); }
    private static Timestamp expiry(Object value) { if(value==null || value.toString().isBlank()) return null;try {return Timestamp.from(Instant.parse(value.toString()));}catch(Exception ex) {throw new IllegalArgumentException("邀请码有效期必须包含时区");} }
    private static boolean enabled(Map<String,Object> b) { return !Boolean.FALSE.equals(b.get("enabled")); }
    private static int integer(Map<String,Object> b,String k,int d) { Object v=b.get(k);return v==null?d:com.ruoyi.paismart.infrastructure.Numbers.smallInteger(v); }
    private static long number(Map<String,Object> b,String k,long d) { Object v=b.get(k);return v==null?d:com.ruoyi.paismart.infrastructure.Numbers.integer(v); }
}
