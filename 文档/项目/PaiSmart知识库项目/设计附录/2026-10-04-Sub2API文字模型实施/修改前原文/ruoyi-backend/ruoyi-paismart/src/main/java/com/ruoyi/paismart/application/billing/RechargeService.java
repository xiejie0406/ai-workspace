package com.ruoyi.paismart.application.billing;

import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 订单快照与支付唯一结算事务；退款或消耗不能通过删账回滚。 */
@Service
@PaismartEnabled
public class RechargeService {
    public record Package(String id,String name,long amountFen,long llmTokens,long embeddingTokens,String description,String benefit,boolean enabled,int sortOrder,long rowVersion) { }
    public record Order(String id,String tradeNo,long userId,String packageId,long amountFen,long llmTokens,long embeddingTokens,String state,String codeUrl,Instant expiresAt,String errorMessage,Map<String,Object> snapshot) { }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final QuotaService quotas;private final WechatPaymentGateway gateway;
    public RechargeService(@Qualifier("paismartJdbc")JdbcTemplate jdbc,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx,QuotaService quotas,WechatPaymentGateway gateway) {this.jdbc=jdbc;this.tx=tx;this.quotas=quotas;this.gateway=gateway;}
    public List<Package> packages(boolean admin) {return jdbc.query("SELECT * FROM ps_recharge_package WHERE deleted=FALSE"+(admin?"":" AND enabled=TRUE AND amount_fen>1")+" ORDER BY sort_order,id",(r,n)->pack(r));}
    public Package save(String id,Map<String,Object>b,Long version) {
        String name=Objects.toString(b.get("name"),"");QuotaService.text(name,128);
        long amount=num(b,"amountFen",0),llm=num(b,"llmTokens",0),emb=num(b,"embeddingTokens",0);QuotaService.nonnegative(llm);QuotaService.nonnegative(emb);
        if(amount<1 || amount>Integer.MAX_VALUE || llm+emb==0) throw new IllegalArgumentException("套餐金额和权益无效");
        String description=Objects.toString(b.get("description"),"");if(description.length()>500) throw new IllegalArgumentException("套餐说明过长");
        String benefit=Objects.toString(b.get("benefit"),"");if(benefit.length()>4000)throw new IllegalArgumentException("套餐权益说明过长");
        boolean enabled=!Boolean.FALSE.equals(b.get("enabled"));int sort=com.ruoyi.paismart.infrastructure.Numbers.smallInteger(b.getOrDefault("sortOrder",0));
        if(id==null) {id=UUID.randomUUID().toString();jdbc.update("INSERT INTO ps_recharge_package(id,name,amount_fen,llm_tokens,embedding_tokens,description,benefit,enabled,sort_order) VALUES(?,?,?,?,?,?,?,?,?)",id,name,amount,llm,emb,description,benefit,enabled,sort);}
        else if(version==null || jdbc.update("UPDATE ps_recharge_package SET name=?,amount_fen=?,llm_tokens=?,embedding_tokens=?,description=?,benefit=?,enabled=?,sort_order=?,row_version=row_version+1 WHERE id=? AND row_version=? AND deleted=FALSE",name,amount,llm,emb,description,benefit,enabled,sort,id,version)!=1) throw new IllegalStateException("套餐已变更，请刷新");
        return getPackage(id);
    }
    public void removePackage(String id,long version) {if(jdbc.update("UPDATE ps_recharge_package SET deleted=TRUE,enabled=FALSE,row_version=row_version+1 WHERE id=? AND row_version=?",id,version)!=1) throw new IllegalStateException("套餐已变更");}
    public Order create(long userId,String packageId,Long customFen,String requestKey) {
        QuotaService.key(requestKey,128);
        if(!gateway.enabled()) throw new IllegalStateException("真实支付尚未启用，暂不能创建充值订单");
        if(packageId!=null&&packageId.isBlank())packageId=null;
        if(packageId!=null&&customFen!=null)throw new IllegalArgumentException("请选择套餐或自定义金额之一");
        if(packageId==null&&(customFen==null||customFen<1||customFen>Integer.MAX_VALUE))throw new IllegalArgumentException("自定义金额无效");
        final String selectedPackage=packageId;
        Order order=tx.execute(status -> {
            var identity=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",userId);if(identity.isEmpty())throw new IllegalArgumentException("平台账户不存在或不可用");
            List<Order> prior=jdbc.query("SELECT * FROM ps_recharge_order WHERE user_id=? AND request_key=? FOR UPDATE",(r,n)->order(r),userId,requestKey);
            if(!prior.isEmpty()) {Order old=prior.get(0);if(!Objects.equals(old.packageId(),selectedPackage) || (customFen!=null && old.amountFen()!=customFen)) throw new IllegalStateException("同一订单请求参数不同");return old;}
            Package p;long amount,llm,emb;
            if(selectedPackage!=null) {p=getPackage(selectedPackage);amount=p.amountFen();llm=p.llmTokens();emb=p.embeddingTokens();}
            else {
                if(customFen==null || customFen<1 || customFen>Integer.MAX_VALUE) throw new IllegalArgumentException("自定义金额无效");
                List<Package> bases=jdbc.query("SELECT * FROM ps_recharge_package WHERE amount_fen=1 AND enabled=TRUE AND deleted=FALSE FOR UPDATE",(r,n)->pack(r));
                if(bases.size()!=1) throw new IllegalStateException("自定义充值基准套餐未唯一配置");
                p=bases.get(0);amount=customFen;llm=Math.multiplyExact(amount,p.llmTokens());emb=Math.multiplyExact(amount,p.embeddingTokens());
            }
            if(!p.enabled()) throw new IllegalArgumentException("套餐已停用");QuotaService.nonnegative(llm);QuotaService.nonnegative(emb);
            String id=UUID.randomUUID().toString(),trade="PS"+UUID.randomUUID().toString().replace("-","");Instant expiry=Instant.now().plusSeconds(6000);
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("package",p);snapshot.put("amountFen",amount);snapshot.put("llmTokens",llm);snapshot.put("embeddingTokens",emb);
            jdbc.update("INSERT INTO ps_recharge_order(id,user_id,request_key,trade_no,package_id,snapshot_json,amount_fen,llm_tokens,embedding_tokens,state,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'CREATING',?)",id,userId,requestKey,trade,selectedPackage,Jsons.write(snapshot),amount,llm,emb,Timestamp.from(expiry));
            return get(trade,userId,false);
        });
        if(order.codeUrl()!=null || !"CREATING".equals(order.state())) return order;
        if(jdbc.update("UPDATE ps_recharge_order SET state='PREPAY_STARTED' WHERE id=? AND state='CREATING'",order.id())!=1)return get(order.tradeNo(),userId,false);
        try {
            String code=gateway.prepay(order.tradeNo(),"知识库充值",order.amountFen(),order.expiresAt());
            // 查单可在预支付响应前到达；保存地址但不回退已确认终态。
            jdbc.update("UPDATE ps_recharge_order SET code_url=?,state=CASE WHEN state IN ('PREPAY_STARTED','UNCERTAIN') THEN 'NOT_PAY' ELSE state END,error_message=NULL WHERE id=?",code,order.id());
        } catch(RuntimeException ex) {
            jdbc.update("UPDATE ps_recharge_order SET state='UNCERTAIN',error_message='支付平台响应未确认，请查询此原订单，不自动重发预支付' WHERE id=? AND state='PREPAY_STARTED'",order.id());
        }
        return get(order.tradeNo(),userId,false);
    }
    public Order get(String trade,long userId,boolean administrator) {
        List<Order> rows=jdbc.query("SELECT * FROM ps_recharge_order WHERE trade_no=?"+(administrator?"":" AND user_id=?"),(r,n)->order(r),administrator?new Object[]{trade}:new Object[]{trade,userId});
        if(rows.isEmpty()) throw new IllegalArgumentException("订单不存在或不可访问");return rows.get(0);
    }
    public List<Order> orders(long userId,int offset,int size){return orders(userId,offset,size,"");}
    public long orderCount(long userId){return orderCount(userId,"");}
    public List<Order> orders(long userId,int offset,int size,String state){
        String normalized=orderState(state);
        var args=new ArrayList<Object>();args.add(userId);if(!normalized.isEmpty())args.add(normalized);
        args.add(Math.max(1,Math.min(100,size)));args.add(Math.max(0,offset));
        return jdbc.query("SELECT * FROM ps_recharge_order WHERE user_id=?"+(normalized.isEmpty()?"":" AND state=?")+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->order(r),args.toArray());
    }
    public long orderCount(long userId,String state){String normalized=orderState(state);return jdbc.queryForObject("SELECT COUNT(*) FROM ps_recharge_order WHERE user_id=?"+(normalized.isEmpty()?"":" AND state=?"),Long.class,normalized.isEmpty()?new Object[]{userId}:new Object[]{userId,normalized});}
    public static String orderState(String state){
        String value=state==null?"":state.trim().toUpperCase(Locale.ROOT);
        if(value.equals("SUCCEED"))value="SUCCEEDED";else if(value.equals("FAIL"))value="FAILED";
        if(!value.isEmpty()&&!Set.of("CREATING","PREPAY_STARTED","UNCERTAIN","NOT_PAY","PAYING","SUCCEEDED","FAILED","CLOSED","REVOKED","PAYERROR").contains(value))throw new IllegalArgumentException("订单状态无效");
        return value;
    }
    public Order query(String trade,long userId) {
        Order o=get(trade,userId,false);
        if(gateway.enabled() && !Set.of("SUCCEEDED","CLOSED","FAILED","REVOKED","PAYERROR").contains(o.state())) accept(gateway.query(trade));
        return get(trade,userId,false);
    }
    public void accept(WechatPaymentGateway.Payment event) {
        gateway.validate(event);
        tx.executeWithoutResult(status -> {
            List<Order> orders=jdbc.query("SELECT * FROM ps_recharge_order WHERE trade_no=? FOR UPDATE",(r,n)->order(r),event.tradeNo());
            if(orders.isEmpty()) throw new IllegalArgumentException("未知支付订单");Order o=orders.get(0);
            if(o.amountFen()!=event.amountFen()) throw new IllegalArgumentException("支付金额与订单快照不符");
            if(!"SUCCESS".equals(event.state())) {
                if(Set.of("SUCCEEDED","CLOSED","FAILED","REVOKED","PAYERROR").contains(o.state())) return;
                // 微信NOTPAY与USERPAYING含义不同，不能把查单中的未付款写成正在支付。
                String state=switch(event.state()){
                    case "NOTPAY" -> "NOT_PAY";
                    case "USERPAYING" -> "PAYING";
                    case "CLOSED","REVOKED","PAYERROR" -> event.state();
                    case "REFUND" -> "FAILED"; // 沿用原非成功状态；此处不执行退款或撤销已入账订单。
                    default -> throw new IllegalArgumentException("支付状态无法识别，请核对原订单");
                };
                jdbc.update("UPDATE ps_recharge_order SET state=? WHERE id=? AND state<>'SUCCEEDED'",state,o.id());return;
            }
            QuotaService.key(event.transactionId(),128);
            if("SUCCEEDED".equals(o.state())) {
                String old=jdbc.queryForObject("SELECT transaction_id FROM ps_recharge_order WHERE id=?",String.class,o.id());
                if(!event.transactionId().equals(old)) throw new IllegalStateException("订单支付交易号冲突");return;
            }
            jdbc.update("INSERT INTO ps_payment_event(id,event_id,trade_no,transaction_id,amount_fen,payload_hash) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),event.eventId(),o.tradeNo(),event.transactionId(),event.amountFen(),hash(Jsons.write(event)));
            quotas.credit(o.userId(),"LLM",o.llmTokens(),"payment:"+o.tradeNo()+":LLM","充值订单入账",o.userId());
            quotas.credit(o.userId(),"EMBEDDING",o.embeddingTokens(),"payment:"+o.tradeNo()+":EMBEDDING","充值订单入账",o.userId());
            jdbc.update("UPDATE ps_recharge_order SET state='SUCCEEDED',transaction_id=?,paid_at=?,error_message=NULL WHERE id=?",event.transactionId(),event.paidAt()==null?Timestamp.from(Instant.now()):Timestamp.from(OffsetDateTime.parse(event.paidAt()).toInstant()),o.id());
        });
    }
    private Package getPackage(String id) {List<Package> rows=jdbc.query("SELECT * FROM ps_recharge_package WHERE id=? AND deleted=FALSE",(r,n)->pack(r),id);if(rows.isEmpty())throw new IllegalArgumentException("套餐不存在");return rows.get(0);}
    private Package pack(ResultSet r)throws SQLException {return new Package(r.getString("id"),r.getString("name"),r.getLong("amount_fen"),r.getLong("llm_tokens"),r.getLong("embedding_tokens"),r.getString("description"),r.getString("benefit"),r.getBoolean("enabled"),r.getInt("sort_order"),r.getLong("row_version"));}
    private Order order(ResultSet r)throws SQLException {return new Order(r.getString("id"),r.getString("trade_no"),r.getLong("user_id"),r.getString("package_id"),r.getLong("amount_fen"),r.getLong("llm_tokens"),r.getLong("embedding_tokens"),r.getString("state"),r.getString("code_url"),r.getTimestamp("expires_at").toInstant(),r.getString("error_message"),Jsons.map(r.getString("snapshot_json")));}
    private static long num(Map<String,Object>b,String k,long d){Object v=b.get(k);return v==null?d:com.ruoyi.paismart.infrastructure.Numbers.integer(v);}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
