package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;

/** 账号由平台拥有；旧UID只显式映射，不按同名猜测、不默认关联管理员。 */
@Service
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class IdentityMappingService {
    private final IdentityMapper mapper;
    private final ISysUserService users;
    private SqlSessionTemplate currentSession;
    private SysPermissionService currentPermissions;
    public IdentityMappingService(IdentityMapper mapper, ISysUserService users) {
        this.mapper = mapper; this.users = users;
    }
    @Autowired public void useCurrentSession(SqlSessionTemplate session) { this.currentSession = session; }
    @Autowired public void useCurrentPermissions(SysPermissionService permissions) { this.currentPermissions = permissions; }
    private void fresh() {
        if(currentSession==null)throw new ServiceException("平台当前主体读取尚未装配",503);
        // 仅清本次PaiFlow检查的SqlSession一级缓存，保持共享Mapper和全局配置不变。
        currentSession.clearCache();
    }
    public LoginUser current() {
        LoginUser login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserId() == null) throw new ServiceException("请先登录", 401);
        fresh();
        SysUser user = users.selectUserById(login.getUserId());
        if (user == null || !java.util.Objects.equals(user.getUserId(),login.getUserId()) || !"0".equals(user.getDelFlag()) || !"0".equals(user.getStatus())) {
            throw new ServiceException("平台账号已停用或不存在，请重新登录", 401);
        }
        if(currentPermissions==null)throw new ServiceException("平台当前菜单读取尚未装配",503);
        var granted=currentPermissions.getMenuPermission(user);
        if(granted==null)throw new ServiceException("平台当前菜单读取不可用",503);
        // 只刷新已认证主体的数据，不将任何请求UID设为当前主体。
        login.setUser(user);
        login.setPermissions(java.util.Set.copyOf(granted));
        return login;
    }
    public static String legacyUid(long platformUserId) { return "ruoyi:" + platformUserId; }
    public long resolveReference(String uid) {
        if (uid == null || uid.isBlank() || uid.length() > 255) throw new ServiceException("用户标识无效", 400);
        fresh();
        Long mapped = mapper.findPlatformUserId("paiflow", uid);
        if (mapped == null && uid.matches("ruoyi:[1-9][0-9]{0,18}")) {
            try { mapped = Long.valueOf(uid.substring(6)); }
            catch (NumberFormatException ex) { throw new ServiceException("用户标识无效", 400); }
        }
        if (mapped == null) throw new ServiceException("原用户尚未关联平台账号", 404);
        SysUser user = users.selectUserById(mapped);
        if (user == null || !java.util.Objects.equals(user.getUserId(),mapped) || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) {
            throw new ServiceException("关联的平台用户不可用", 404);
        }
        return mapped;
    }
}
