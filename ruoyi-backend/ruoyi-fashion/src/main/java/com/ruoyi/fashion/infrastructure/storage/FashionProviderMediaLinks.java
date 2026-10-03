package com.ruoyi.fashion.infrastructure.storage;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只签发已验证AI许可的私有图；公网入口与密钥未配置时保持关闭。 */
@Component @FashionModuleEnabled
public class FashionProviderMediaLinks {
    private final String baseUrl;
    private final byte[] secret;
    private final Clock clock;
    @Autowired
    public FashionProviderMediaLinks(@Value("${fashion.image.media-base-url:}") String baseUrl,
            @Value("${fashion.image.media-signing-secret:}") String secret) {
        this(baseUrl, secret, Clock.systemUTC());
    }
    public FashionProviderMediaLinks(String baseUrl, String secret, Clock clock) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        if (!this.baseUrl.isBlank()) {
            var uri = URI.create(this.baseUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("试衣素材入口必须是无查询参数的HTTPS公网基地址");
            }
        }
    }
    public boolean available() { return !baseUrl.isBlank() && secret.length >= 32; }
    public String sign(String key, Duration validity) {
        return signedUrl(key, validity, null);
    }
    public String sign(String key, Duration validity, String taskId) {
        requireTask(taskId);
        return signedUrl(key, validity, taskId);
    }
    private String signedUrl(String key, Duration validity, String taskId) {
        if (!available()) throw new ServiceException("试衣素材临时访问入口未配置", 503);
        requireKey(key);
        if (validity == null || validity.toSeconds() < 60 || validity.toSeconds() > 1800)
            throw new ServiceException("素材链接有效期须为1～30分钟");
        long expires = clock.instant().getEpochSecond() + validity.toSeconds();
        return baseUrl + "/fashion/provider-media?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&expires=" + expires + (taskId == null ? "" : "&task=" + taskId)
                + "&signature=" + signature(key, expires, taskId);
    }
    public void verify(String key, long expires, String signature) {
        verifySignature(key, expires, signature, null);
    }
    public void verify(String key, long expires, String signature, String taskId) {
        requireTask(taskId);
        verifySignature(key, expires, signature, taskId);
    }
    private void verifySignature(String key, long expires, String signature, String taskId) {
        if (!available()) throw new ServiceException("试衣素材访问入口未启用", 404);
        requireKey(key);
        long now = clock.instant().getEpochSecond();
        if (expires <= now || expires > now + 1800 || signature == null || !signature.matches("[a-f0-9]{64}"))
            throw new ServiceException("素材访问凭据无效或已过期", 403);
        if (!MessageDigest.isEqual(signature(key, expires, taskId).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII)))
            throw new ServiceException("素材访问凭据无效或已过期", 403);
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("(materials/[a-f0-9]{64}/original|proposal-models/[0-9]+/[a-f0-9]{64})\\.(png|jpg|jpeg|webp)"))
            throw new ServiceException("素材对象不属于试衣访问范围", 403);
    }
    private static void requireTask(String taskId) {
        if (taskId == null || !taskId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new ServiceException("素材访问必须绑定有效试衣任务", 403);
    }
    private String signature(String key, long expires, String taskId) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((key + "\n" + expires + (taskId == null ? "" : "\n" + taskId)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("素材凭据签名失败", e); }
    }
}
