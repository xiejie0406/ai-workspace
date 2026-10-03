package com.ruoyi.paiflow.worker.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class EffectReceipt {
    private EffectReceipt() { }
    public static Map<String, Object> text(String response) { return bytes(response.getBytes(StandardCharsets.UTF_8)); }
    public static Map<String, Object> bytes(byte[] response) {
        try {
            return Map.of("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response)), "byteLength", response.length);
        } catch (Exception error) { throw new WorkerException("EFFECT_UNKNOWN", "实际回执无法核对", error); }
    }
}
