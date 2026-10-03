package com.ruoyi.aps.api.controller;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 站点显示信息和最小用户候选；不返回平台用户敏感资料。 */
@RestController
@RequestMapping("/api/aps/v1")
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.api", name = "enabled", havingValue = "true")
public class ApsContextController {
    private final ISysUserService users;
    private final String code;
    private final String zoneId;
    public ApsContextController(ISysUserService users, @Value("${aps.site.code:}") String code,
            @Value("${aps.site.zone-id:Asia/Shanghai}") String zoneId) {
        this.users = users; this.code = code; this.zoneId = zoneId;
    }
    @GetMapping("/site")
    @PreAuthorize("isAuthenticated()")
    public SiteContext site() {
        if (code.isBlank()) throw new ApsBusinessException(ApsErrorCode.PERSISTENCE_UNAVAILABLE, "尚未配置排产站点，请恢复原站点配置");
        return new SiteContext(code, ZoneId.of(zoneId).getId());
    }
    @GetMapping("/resources/user-candidates")
    @PreAuthorize("@ss.hasPermi('aps:resource:add') or @ss.hasPermi('aps:resource:edit')")
    public List<UserCandidate> candidates(@RequestParam(defaultValue = "") String keyword) {
        if (keyword.length() > 64) throw new ApsBusinessException(ApsErrorCode.INVALID_REQUEST, "用户查询关键字不能超过64字");
        if (!SecurityUtils.isAdmin() && !SecurityUtils.hasPermi("aps:scope:manage"))
            return List.of(candidate(SecurityUtils.getLoginUser().getUser()));
        SysUser query = new SysUser(); query.setStatus("0");
        String term = keyword.trim().toLowerCase(Locale.ROOT);
        // 平台服务自身执行部门/用户数据范围；APS仅映射必要候选字段。
        return users.selectUserList(query).stream().filter(user ->
            (user.getUserName() + " " + user.getNickName()).toLowerCase(Locale.ROOT).contains(term))
            .limit(100).map(this::candidate).toList();
    }
    private UserCandidate candidate(SysUser user) {
        return new UserCandidate(user.getUserId().toString(), user.getUserName(), user.getNickName());
    }
    public record SiteContext(String siteCode, String zoneId) { }
    public record UserCandidate(String userId, String userName, String displayName) { }
}
