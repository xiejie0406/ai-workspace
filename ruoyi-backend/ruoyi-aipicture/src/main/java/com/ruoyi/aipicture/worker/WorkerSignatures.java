package com.ruoyi.aipicture.worker;

import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WorkerSignatures {
    private WorkerSignatures() { }
    public static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static String sign(String secret,String method,String path,String timestamp,String nonce,byte[] body) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal((method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+sha256(body)).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean equals(String a,String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
}
