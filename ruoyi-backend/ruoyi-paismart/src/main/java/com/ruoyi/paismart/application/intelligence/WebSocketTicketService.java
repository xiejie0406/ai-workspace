package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** 60秒单用票据绑定已有若依登录会话；每条WS请求再查撤销与业务资格。 */
@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class WebSocketTicketService {
    public record TicketIdentity(long userId,String platformSession){}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final RedisCache cache;private final IntelligenceSettings settings;private final MembershipService members;
    public WebSocketTicketService(@Qualifier("paismartJdbc")JdbcTemplate db,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx,RedisCache cache,IntelligenceSettings settings,MembershipService members){this.db=db;this.tx=tx;this.cache=cache;this.settings=settings;this.members=members;}
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public List<String> origins(){return Arrays.stream(settings.text("chat.allowed-origins","").split(",")).map(String::trim).filter(v->!v.isBlank()).toList();}
    public Map<String,Object> issue(String origin){LoginUser user=SecurityUtils.getLoginUser();members.requireEligible(user.getUserId());if(!origins().contains(origin))throw new IllegalArgumentException("WS Origin未被批准");byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);db.update("INSERT INTO ps_ws_ticket(ticket_hash,user_id,origin,platform_session,expires_at,created_at) VALUES(?,?,?,?,?,?)",hash(token),user.getUserId(),origin,user.getToken(),LocalDateTime.now().plusSeconds(60),LocalDateTime.now());return Map.of("ticket",token,"expiresIn",60);}
    public TicketIdentity consume(String token,String origin){if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("WS票据无效");return tx.execute(s->{List<Map<String,Object>> rows=db.queryForList("SELECT * FROM ps_ws_ticket WHERE ticket_hash=? AND origin=? AND expires_at>CURRENT_TIMESTAMP AND consumed_at IS NULL FOR UPDATE",hash(token),origin);if(rows.size()!=1)throw new IllegalArgumentException("WS票据失效、已使用或Origin不符");var row=rows.get(0);TicketIdentity identity=new TicketIdentity(Values.number(row.get("user_id"),0),Values.text(row.get("platform_session")));verify(identity);db.update("UPDATE ps_ws_ticket SET consumed_at=? WHERE ticket_hash=?",LocalDateTime.now(),hash(token));return identity;});}
    public void verify(TicketIdentity identity){LoginUser user=cache.getCacheObject(CacheConstants.LOGIN_TOKEN_KEY+identity.platformSession());if(user==null||user.getUserId()!=identity.userId()||user.getExpireTime()<=System.currentTimeMillis())throw new IllegalStateException("若依登录已过期或撤销");members.requireEligible(identity.userId());Set<String> permissions=user.getPermissions();if(!SecurityUtils.isAdmin(identity.userId())&&(permissions==null||(!permissions.contains("*:*:*")&&!permissions.contains("paismart:chat:send"))))throw new IllegalStateException("知识问答权限已撤销");}
}
