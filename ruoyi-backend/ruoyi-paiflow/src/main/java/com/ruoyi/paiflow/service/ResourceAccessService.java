package com.ruoyi.paiflow.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;

/** 平台功能权限与业务成员关系同时成立；请求space-id只作为待核对资源ID。 */
@Service
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class ResourceAccessService {
    private final IdentityMappingService identities;
    private final PermissionService permissions;
    private final SpaceMapper spaces;
    private SqlSessionTemplate currentSession;
    public ResourceAccessService(IdentityMappingService identities, PermissionService permissions, SpaceMapper spaces) {
        this.identities = identities; this.permissions = permissions; this.spaces = spaces;
    }
    @Autowired public void useCurrentSession(SqlSessionTemplate session) { this.currentSession = session; }
    private void fresh() {
        if(currentSession==null)throw new ServiceException("当前资源权限读取尚未装配",503);
        currentSession.clearCache();
    }
    public long requirePermission(String permission) {
        var actor = identities.current();
        if (!permissions.hasPermi(permission)) throw new ServiceException("没有此操作的平台权限", 403);
        return actor.getUserId();
    }
    public SpaceRecord requireSpace(long id, String permission, int maximumRole, boolean lock) {
        long actor = requirePermission(permission);
        fresh();
        SpaceRecord space = lock ? spaces.lock(id) : spaces.find(id);
        // 平台账号/菜单可能在等待业务锁时变化；锁后再次按原平台权限核对。
        if(actor!=requirePermission(permission))throw new ServiceException("平台当前主体已变化",403);
        fresh();
        var member = spaces.member(id, actor);
        if (space == null || space.getDeleted() != 0 || member == null || !"active".equals(member.getStatus())) {
            throw new ServiceException("空间不存在或不可访问", 404);
        }
        // 即使错误导入了其他成员，原默认私域也只能由当前所有者访问。
        if (space.isDefaultPersonal() && (!java.util.Objects.equals(space.getOwnerUserId(),actor) ||
            space.getTeamId()!=null || !java.util.Objects.equals(member.getRole(),1))) {
            throw new ServiceException("默认个人域不存在或不可访问",404);
        }
        if (member.getRole() == null || member.getRole() < 1 || member.getRole() > maximumRole) {
            throw new ServiceException("当前空间角色不能执行此操作", 403);
        }
        return space;
    }
    public static SpaceRecord requireCollaborative(SpaceRecord space) {
        if(space.isDefaultPersonal())throw new ServiceException("默认个人域不提供具名空间的成员、邀请、容量或转让操作",400);
        return space;
    }
}
