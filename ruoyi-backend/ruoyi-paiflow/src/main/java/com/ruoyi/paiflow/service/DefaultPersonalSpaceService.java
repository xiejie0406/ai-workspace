package com.ruoyi.paiflow.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原空space-id私域的物理映射；只读不创建，明确写操作才建立内部正ID。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class DefaultPersonalSpaceService {
    /** defaultPersonal时spaceId为空；owner只取平台主体，不由客户端提供。 */
    public record Selection(long ownerUserId,Long spaceId,boolean defaultPersonal) {}
    private final SpaceMapper spaces;
    private final IdentityMappingService identities;
    private final ResourceAccessService access;
    public DefaultPersonalSpaceService(SpaceMapper spaces,IdentityMappingService identities,ResourceAccessService access) {
        this.spaces=spaces;this.identities=identities;this.access=access;
    }
    /** 适用于原头部作用域路由。原WF显式query空间选择另由该路由核对，不能通配null。 */
    public Selection select(String header,Object echo,String permission) {
        long actor=access.requirePermission(permission);Long selected=parse(header);
        Long echoed=echo==null?null:parse(String.valueOf(echo));
        if(selected==null && echoed!=null || selected!=null && echoed!=null && !selected.equals(echoed))
            throw new ServiceException("请求空间标识与当前空间不一致",400);
        return new Selection(actor,selected,selected==null);
    }
    public Selection defaultSelection(String permission) { return new Selection(access.requirePermission(permission),null,true); }
    public static Long parse(String value) {
        if(value==null || value.isEmpty())return null;
        if(!value.matches("[1-9][0-9]{0,18}"))throw new ServiceException("空间标识无效",400);
        try{return Long.valueOf(value);}catch(NumberFormatException ex){throw new ServiceException("空间标识无效",400);}
    }
    /** 缺映射的新用户没有任何该域正式资源；返回null由列表适配为真实空页，不选其他空间。 */
    public Long existing(Selection selection,String permission) {
        long actor=actor(selection,permission);
        if(!selection.defaultPersonal())return access.requireSpace(selection.spaceId(),permission,3,false).getId();
        var value=spaces.defaultPersonal(actor);
        if(value==null)return null;
        return access.requireSpace(value.getId(),permission,3,false).getId();
    }
    /** WF/Agent/草稿的创建复用调用方事务，失败连同首次物理映射一起回滚。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public long materialize(Selection selection,String permission) { return materializeInside(selection,permission); }
    /** Model仅在全部纯检查/端口存在检查通过后调用；返回时已释放锁，随后authorize/Hello不得在事务中。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW)
    public long materializeIndependent(Selection selection,String permission) { return materializeInside(selection,permission); }
    private long materializeInside(Selection selection,String permission) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new ServiceException("默认个人域建立须经中心事务入口",500);
        long actor=actor(selection,permission);
        if(!selection.defaultPersonal())return locked(selection.spaceId(),selection,permission);
        // 与既有个人邀请/容量写共用owner锁，顺序为owner→space→后续资源；读路径不获取此写锁。
        spaces.lockOwnerScope(actor);actor(selection,permission);
        var row=spaces.defaultPersonal(actor);
        if(row!=null)return locked(row.getId(),selection,permission);
        var login=identities.current();row=new SpaceRecord();row.setName("__default_personal__");row.setKind(SpaceRecord.DEFAULT_PERSONAL);
        row.setOwnerUserId(actor);row.setDeptId(login.getDeptId());row.setRevision(0L);row.setDeleted(0);row.setCreateBy(login.getUsername());row.setUpdateBy(login.getUsername());
        if(spaces.insert(row)!=1 || row.getId()==null || spaces.insertOwner(row.getId(),actor,login.getUsername())!=1)
            throw new ServiceException("默认个人域建立失败",500);
        actor(selection,permission);return access.requireSpace(row.getId(),permission,3,false).getId();
    }
    private long actor(Selection selection,String permission) {
        long actor=access.requirePermission(permission);
        if(selection==null || selection.ownerUserId()!=actor || selection.defaultPersonal()!=(selection.spaceId()==null))
            throw new ServiceException("空间选择与当前平台主体不一致",403);
        if(!selection.defaultPersonal() && selection.spaceId()<1)throw new ServiceException("空间标识无效",400);
        return actor;
    }
    private long locked(long id,Selection selection,String permission) {
        access.requireSpace(id,permission,3,true);actor(selection,permission);
        return access.requireSpace(id,permission,3,false).getId();
    }
    public static Long legacySpaceId(SpaceRecord space) { return space.isDefaultPersonal()?null:space.getId(); }
}
