package com.ruoyi.paiflow.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.paiflow.mapper.IdentityMapper;
import com.ruoyi.system.service.ISysUserService;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 兼容字段从平台资料和真实协议同意记录组成，不复制账号表。 */
@Service
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class UserProfileService {
    public record Profile(Long id, String uid, String username, String avatar, String nickname,
                          String mobile, int accountStatus, int userAgreement,
                          @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
                          @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime updateTime, int deleted) {}
    private final IdentityMappingService identities;
    private final IdentityMapper mapper;
    private final ISysUserService users;
    private final TokenService tokens;
    public UserProfileService(IdentityMappingService identities, IdentityMapper mapper, ISysUserService users, TokenService tokens) {
        this.identities = identities; this.mapper = mapper; this.users = users; this.tokens = tokens;
    }
    public Profile current() {
        var login = identities.current();
        SysUser user = login.getUser();
        var agreement = mapper.findAgreement(login.getUserId());
        return new Profile(user.getUserId(), IdentityMappingService.legacyUid(user.getUserId()), user.getUserName(),
            user.getAvatar(), user.getNickName(), user.getPhonenumber(), 1,
            agreement == null ? 0 : agreement.getUserAgreement(), displayTime(user.getCreateTime()), displayTime(user.getUpdateTime()), 0);
    }
    private static LocalDateTime displayTime(java.util.Date value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneId.of("Asia/Shanghai"));
    }
    @Transactional(rollbackFor = Exception.class)
    public Profile update(String nickname, String avatar) {
        var login = identities.current();
        SysUser user = login.getUser();
        if (nickname != null && !nickname.isBlank()) {
            if (nickname.length() > 30) throw new ServiceException("昵称不能超过30个字符", 400);
            // 平台动态SQL以deptId!=0为更新条件，显式0保证仅改昵称。
            SysUser patch = new SysUser();patch.setDeptId(0L);patch.setUserId(user.getUserId());patch.setNickName(nickname.trim());
            patch.setUpdateBy(user.getUserName());
            if (users.updateUserProfile(patch) != 1) throw new ServiceException("平台资料更新失败", 409);
        }
        if (avatar != null && !avatar.isBlank()) {
            validateAvatar(avatar);
            if (!users.updateUserAvatar(user.getUserId(), avatar)) throw new ServiceException("平台头像更新失败", 409);
        }
        login.setUser(users.selectUserById(login.getUserId()));
        tokens.setLoginUser(login);
        return current();
    }
    private static void validateAvatar(String value) {
        if (value.length() > 2048) throw new ServiceException("头像地址过长", 400);
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || value.startsWith("//") || value.contains("\\") ||
                (uri.isAbsolute() && !"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) ||
                (!uri.isAbsolute() && !value.startsWith("/"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new ServiceException("头像须使用受控文件地址或HTTP图片地址", 400); }
    }
    @Transactional(rollbackFor = Exception.class)
    public boolean agree() {
        var user = identities.current();
        mapper.agree(user.getUserId(), user.getUsername());
        var saved = mapper.findAgreement(user.getUserId());
        if (saved == null || saved.getUserAgreement() != 1) throw new ServiceException("协议同意保存失败", 500);
        return true;
    }
}
