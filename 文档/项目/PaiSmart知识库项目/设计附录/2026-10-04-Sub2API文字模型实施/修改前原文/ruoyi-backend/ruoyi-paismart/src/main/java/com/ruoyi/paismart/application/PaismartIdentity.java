package com.ruoyi.paismart.application;

import com.ruoyi.common.utils.SecurityUtils;

/** 平台身份适配；不接受客户端传入的 userId 或独立 JWT。 */
public final class PaismartIdentity {
    public record Actor(long userId, boolean administrator) { }
    private PaismartIdentity() { }
    public static Actor current() {
        long id = SecurityUtils.getUserId();
        return new Actor(id, SecurityUtils.isAdmin(id));
    }
    public static long userId() { return current().userId(); }
}
