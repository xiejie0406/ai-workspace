package com.ruoyi.paiflow.worker.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 同一接收服务身份供过滤器和执行入口复用；不承载平台用户登录或权限。 */
public final class WorkerServiceIdentity {
    private final byte[] expected;
    public WorkerServiceIdentity(String token) {
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("必须配置中心调用Worker的有效服务身份");
        }
        expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }
    public void require(String authorization) {
        if (authorization == null || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new WorkerException("SERVICE_UNAUTHORIZED", "仅允许中心服务调用");
        }
    }
}
