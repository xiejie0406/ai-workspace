package com.paicli.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 向量空间与分块规则身份；凭据轮换不改变数学索引身份。 */
public record EmbeddingIdentity(String provider, String baseUrl, String model,
                                int expectedDimensions, String chunkerVersion) {
    public String fingerprint() {
        try {
            String canonical = new ObjectMapper().writeValueAsString(
                    java.util.List.of(provider, baseUrl, model, expectedDimensions, chunkerVersion));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("无法计算向量身份", failure); }
    }
}
