package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.model.LoginBody;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只适配原登录请求和返回；密码、验证码、账号状态和令牌均由若依决定。 */
@RestController
@RequestMapping("/paiflow/legacy")
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class LegacyLoginController {
    private final SysLoginService platformLogin;
    public LegacyLoginController(SysLoginService platformLogin) { this.platformLogin = platformLogin; }

    @Anonymous
    @PostMapping("/api/login")
    public LegacyResult<Map<String, String>> login(@RequestBody LoginBody body) {
        String token = platformLogin.login(body.getUsername(), body.getPassword(), body.getCode(), body.getUuid());
        // 平台不发refreshToken；不能为兼容字段另生成或继续刷新Casdoor凭据。
        return LegacyResult.success(Map.of("accessToken", token));
    }

    public record SessionIdentity(Long userId, String username) {}

    @GetMapping("/api/session")
    public LegacyResult<SessionIdentity> session() {
        // 身份来自平台过滤器，客户端传来的uid或空间头不能建立会话。
        var user = SecurityUtils.getLoginUser();
        return LegacyResult.success(new SessionIdentity(user.getUserId(), user.getUsername()));
    }
}
