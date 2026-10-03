package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.TextConfigRecord;
import com.ruoyi.paiflow.mapper.TextConfigMapper;
import java.util.Date;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 复用平台身份/工作流按钮权限；只管理原个人分隔符目录，不改已保存画布的字面值。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class TextConfigService {
    public record View(Long id,Object uid,String separator,String comment,boolean deleted,Date createTime,Date updateTime,long revision) {}
    private final TextConfigMapper mapper;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    public TextConfigService(TextConfigMapper mapper,ResourceAccessService access,IdentityMappingService identities) {
        this.mapper=mapper;this.access=access;this.identities=identities;
    }
    private long actor(String permission) {
        long value=access.requirePermission(permission);
        if(value<1)throw new ServiceException("请使用有效的平台用户",401);
        return value;
    }
    private static View view(TextConfigRecord row) {
        // 原React以number -1判断默认项是否显示删除；系统归属不接受请求UID声明。
        Object uid=row.getOwnerUserId()==0?Long.valueOf(-1):IdentityMappingService.legacyUid(row.getOwnerUserId());
        return new View(row.getId(),uid,row.getSeparator(),row.getComment(),false,row.getCreateTime(),row.getUpdateTime(),row.getRevision());
    }
    public List<View> list() { return mapper.list(actor("paiflow:workflow:query")).stream().map(TextConfigService::view).toList(); }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public View create(JSONObject body) {
        checkBody(body);long owner=actor("paiflow:workflow:edit");String separator=text(body.get("separator"),"分隔符",false);
        duplicate(owner,separator,null);var row=new TextConfigRecord();row.setOwnerUserId(owner);row.setSeparator(separator);
        // 原saveInfo以分隔符自身作为新条目的comment，不读取伪造的owner/uid/id/audit字段。
        row.setComment(separator);row.setRevision(0L);String username=identities.current().getUsername();row.setCreateBy(username);row.setUpdateBy(username);
        try { if(mapper.insert(row)!=1 || row.getId()==null)throw new ServiceException("分隔符保存失败",500); }
        catch(DuplicateKeyException ex) { throw new ServiceException("当前用户或默认目录已有相同分隔符",409); }
        return view(mapper.find(row.getId()));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public View update(long id,JSONObject body) {
        checkBody(body);if(body.containsKey("id") && number(body.get("id"),true)!=id)throw new ServiceException("请求id与分隔符不一致",400);
        long owner=actor("paiflow:workflow:edit");var row=owned(id,owner);sameActor(owner);revision(body.get("revision"),body.containsKey("revision"),row);
        if(body.containsKey("separator"))row.setSeparator(text(body.get("separator"),"分隔符",false));
        if(body.containsKey("comment"))row.setComment(text(body.get("comment"),"分隔符说明",true));
        duplicate(owner,row.getSeparator(),id);row.setUpdateBy(identities.current().getUsername());
        try { if(mapper.update(row)!=1)throw new ServiceException("分隔符已被修改，请刷新",409); }
        catch(DuplicateKeyException ex) { throw new ServiceException("当前用户或默认目录已有相同分隔符",409); }
        return view(mapper.find(id));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void delete(long id,Long expectedRevision) {
        long owner=actor("paiflow:workflow:edit");var row=owned(id,owner);sameActor(owner);
        if(expectedRevision!=null && !expectedRevision.equals(row.getRevision()))throw new ServiceException("分隔符已被修改，请刷新",409);
        if(mapper.delete(id,owner,row.getRevision())!=1)throw new ServiceException("分隔符已变化或不存在",409);
    }
    private TextConfigRecord owned(long id,long owner) {
        if(id<1)throw new ServiceException("分隔符标识无效",400);var row=mapper.find(id);
        if(row!=null && row.getOwnerUserId()==0)throw new ServiceException("默认分隔符不可修改或删除",403);
        if(row==null || row.getOwnerUserId()!=owner)throw new ServiceException("分隔符不存在或不可访问",404);
        row=mapper.lockOwned(id,owner);if(row==null)throw new ServiceException("分隔符不存在或不可访问",404);return row;
    }
    private void duplicate(long owner,String separator,Long excludeId) {
        if(mapper.countSeparator(owner,separator,excludeId)>0)throw new ServiceException("当前用户或默认目录已有相同分隔符",409);
    }
    private void sameActor(long owner) {
        if(actor("paiflow:workflow:edit")!=owner)throw new ServiceException("当前身份已变化，请重新打开目录",401);
    }
    private static void checkBody(JSONObject body) {
        if(body==null)throw new ServiceException("分隔符请求不能为空",400);RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
    }
    private static String text(Object raw,String name,boolean optional) {
        if(raw==null && optional)return null;
        if(!(raw instanceof String value) || (!optional && value.isEmpty()) || value.codePointCount(0,value.length())>255)throw new ServiceException(name+"须为不超过255字的字符串",400);
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(Character.isHighSurrogate(c)) {
                if(i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i)))throw new ServiceException(name+"Unicode无效",400);
            } else if(Character.isLowSurrogate(c))throw new ServiceException(name+"Unicode无效",400);
        }
        return value;
    }
    private static void revision(Object raw,boolean provided,TextConfigRecord row) {
        if(provided && number(raw,false)!=row.getRevision())throw new ServiceException("分隔符已被修改，请刷新",409);
    }
    public static long number(Object raw,boolean positive) {
        if(!(raw instanceof String || raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) || !raw.toString().matches("[0-9]{1,19}"))throw new ServiceException("分隔符id/revision须为整数",400);
        try { long value=Long.parseLong(raw.toString());if(value<(positive?1:0))throw new NumberFormatException();return value; }
        catch(NumberFormatException ex) { throw new ServiceException("分隔符id/revision超出范围",400); }
    }
}
