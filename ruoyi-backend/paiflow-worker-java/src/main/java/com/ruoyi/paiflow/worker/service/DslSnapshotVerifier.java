package com.ruoyi.paiflow.worker.service;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

public final class DslSnapshotVerifier {
    private static final int MAX_DSL_BYTES = 2 * 1024 * 1024;
    private DslSnapshotVerifier() { }

    public static byte[] verify(String canonicalBase64, String hash) {
        if (canonicalBase64 == null || canonicalBase64.isBlank() || canonicalBase64.length() > MAX_DSL_BYTES * 4 / 3 + 8
                || hash == null || !hash.matches("[a-fA-F0-9]{64}")) {
            throw new WorkerException("DSL_INVALID", "DSL缺少中心规范化字节或完整哈希");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(canonicalBase64);
            if (bytes.length == 0 || bytes.length > MAX_DSL_BYTES) throw new WorkerException("DSL_INVALID", "DSL字节超过预算");
            byte[] expected = HexFormat.of().parseHex(hash);
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(bytes);
            if (!MessageDigest.isEqual(expected, actual)) throw new WorkerException("DSL_INVALID", "DSL规范化字节与中心哈希不一致");
            return bytes;
        } catch (WorkerException error) { throw error; }
        catch (Exception error) { throw new WorkerException("DSL_INVALID", "DSL规范化字节无法核对", error); }
    }
}
