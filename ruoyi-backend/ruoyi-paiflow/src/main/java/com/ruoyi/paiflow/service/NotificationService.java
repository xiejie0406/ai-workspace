package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.NotificationMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 站内消息真相保存在中心，不将邀请记录或第三方发送请求冒充送达。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class NotificationService {
    private static final Set<String> TYPES=Set.of("PERSONAL","BROADCAST","SYSTEM","PROMOTION");
    private final NotificationMapper mapper;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    public NotificationService(NotificationMapper mapper,ResourceAccessService access,IdentityMappingService identities) {
        this.mapper=mapper;this.access=access;this.identities=identities;
    }
    private long actor(){return access.requirePermission("paiflow:notification:query");}
    private void same(long user){if(actor()!=user)throw new ServiceException("通知主体已变化",403);}
    private static Object time(Object value){if(value instanceof LocalDateTime t)return t.toString();if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toString();return value;}
    private static JSONObject view(Map<String,Object> row) {
        var out=new JSONObject();
        for(String key:List.of("id","type","title","body","templateCode","payload","meta","createdAt","expireAt","readAt","receivedAt"))out.put(key,time(row.get(key)));
        Object read=row.get("isRead");out.put("isRead",Boolean.TRUE.equals(read) || read instanceof Number n && n.intValue()==1);
        Object creator=row.get("creatorUserId");out.put("creatorUid",creator instanceof Number n?IdentityMappingService.legacyUid(n.longValue()):null);return out;
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public JSONObject list(String type,boolean unreadOnly,int pageIndex,int pageSize) {
        long user=actor();
        if(type==null || type.isBlank() || "0".equals(type))type=null;
        else {type=type.toUpperCase(Locale.ROOT);if(!TYPES.contains(type))throw new ServiceException("通知类型无效",400);}
        if(pageIndex<1 || pageSize<1 || pageSize>100 || (long)(pageIndex-1)*pageSize>Integer.MAX_VALUE)throw new ServiceException("通知分页无效",400);
        var rows=mapper.page(user,type,unreadOnly,(long)(pageIndex-1)*pageSize,pageSize).stream().map(NotificationService::view).toList();
        long total=mapper.count(user,type,unreadOnly),unread=mapper.count(user,null,true);var grouped=new JSONObject();
        for(String kind:TYPES)grouped.put(kind,rows.stream().filter(row->kind.equals(row.getString("type"))).toList());same(user);
        return JSONObject.of("notifications",rows,"notificationsByType",grouped,"pageIndex",pageIndex,"pageSize",pageSize,"totalCount",total,"totalPages",(total+pageSize-1)/pageSize,"unreadCount",unread);
    }
    public long unread(){long user=actor();long count=mapper.count(user,null,true);same(user);return count;}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public boolean read(List<Long> ids,Boolean all) {
        long user=actor();if(all==null || ids==null || ids.size()>100 || ids.stream().anyMatch(id->id==null || id<1))throw new ServiceException("通知已读参数无效",400);
        List<Long> selected=all?mapper.unreadIds(user,10001):new ArrayList<>(new LinkedHashSet<>(ids));
        if(selected.size()>10000)throw new ServiceException("未读通知超过单次操作预算，请按列表分批处理",413);
        // 固定本次ID集合；并发到达的通知不会把计数检查后的写入扩成无界操作。
        if(!selected.isEmpty())mapper.markRead(user,selected);same(user);return true;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public boolean remove(long id){long user=actor();if(id<1)throw new ServiceException("通知ID无效",400);if(mapper.visible(user,id)==null)throw new ServiceException("通知不存在或不可访问",404);mapper.remove(user,id);same(user);return true;}
    /** 仅供已授权中心业务事务调用；不增加广播或任意收件人的HTTP发送入口。 */
    public long recordPersonal(long receiver,String title,String text,String template,JSONObject payload,LocalDateTime expires) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new ServiceException("站内通知必须与所属业务在同一事务保存",500);
        var current=identities.current();
        if(receiver<1 || title==null || title.isBlank() || title.length()>200 || text==null || text.length()>65535 || template==null || template.length()>100)throw new ServiceException("站内通知内容无效",400);
        if(payload!=null)RunJsonBoundary.check(payload,65536);
        var row=new HashMap<String,Object>();row.put("type","PERSONAL");row.put("title",title);row.put("body",text);row.put("templateCode",template);row.put("payload",payload==null?null:RunJsonBoundary.canonicalFull(payload));row.put("meta",null);row.put("creatorUserId",current.getUserId());row.put("expireAt",expires);
        if(mapper.insert(row)!=1 || !(row.get("id") instanceof Number id) || mapper.recipient(id.longValue(),receiver)!=1)throw new ServiceException("站内通知保存失败",500);
        return ((Number)row.get("id")).longValue();
    }
}
