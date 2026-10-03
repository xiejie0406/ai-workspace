package com.ruoyi.framework.web.service;
import java.util.function.BooleanSupplier;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.RegisterBody;
/** 业务资格扩展复用已通过平台注册验证的写入，不另设账号和密码流程。 */
public interface RegistrationExtension {
    boolean supports(RegisterBody request);
    boolean register(SysUser user, RegisterBody request, BooleanSupplier platformInsert);
}
