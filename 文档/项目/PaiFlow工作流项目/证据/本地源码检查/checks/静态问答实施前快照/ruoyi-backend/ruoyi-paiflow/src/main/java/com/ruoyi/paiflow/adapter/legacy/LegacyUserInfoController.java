package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.UserProfileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原页面契约；不会返回平台密码、角色内部对象或令牌缓存。 */
@RestController
@RequestMapping("/paiflow/legacy/user-info")
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class LegacyUserInfoController {
    private final UserProfileService profiles;
    public LegacyUserInfoController(UserProfileService profiles) { this.profiles = profiles; }
    public record BasicInfo(String nickname, String avatar) {}
    @GetMapping("/me")
    public LegacyResult<UserProfileService.Profile> me() { return LegacyResult.success(profiles.current()); }
    @PostMapping("/update")
    @Log(title = "PaiFlow个人资料", businessType = BusinessType.UPDATE)
    public LegacyResult<UserProfileService.Profile> update(@RequestBody BasicInfo body) {
        return LegacyResult.success(profiles.update(body.nickname(), body.avatar()));
    }
    @PostMapping("/agreement")
    @Log(title = "PaiFlow协议同意", businessType = BusinessType.UPDATE)
    public LegacyResult<Boolean> agreement() { return LegacyResult.success(profiles.agree()); }
}
