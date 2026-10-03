package com.qcloud.cos.internal.crypto;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用实际 COS SDK 加密流验证 Admin 统一 Provider；全部数据为内存固定样本。 */
class CosRuntimeCompatibilityTest {
    @Test
    void sdkGcmStreamRoundTripsRejectsTamperingAndWrapsRsaKeyWithUnifiedProvider() throws Exception {
        Provider original = Security.getProvider("BC");
        Provider active = null;
        try {
            if (original == null) {
                // 由 SDK 自身按类名装载 provider，覆盖其唯一 BC 引用。
                CryptoRuntime.enableBouncyCastle();
            }
            active = Security.getProvider("BC");
            assertNotNull(active, "COS SDK 必须装载唯一 BC provider");
            assertEquals("1.85", active.getVersionStr(), "既有不同版本不能被测试静默替换");
            CryptoRuntime.recheck();
            assertTrue(CryptoRuntime.isAesGcmAvailable());
            assertTrue(CryptoRuntime.isRsaKeyWrapAvailable());

            // 固定、公开的样本密钥与 IV，仅用于本次内存往返。
            byte[] rawKey = new byte[32];
            for (int i = 0; i < rawKey.length; i++) rawKey[i] = (byte) i;
            var key = new SecretKeySpec(rawKey, "AES");
            byte[] iv = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
            byte[] content = "本地 COS SDK 加密正文与完整标签校验。0123456789\n"
                .repeat(80).getBytes(StandardCharsets.UTF_8);
            assertTrue(content.length > 1024);
            byte[] encrypted = throughSdkStream(content, key, iv, Cipher.ENCRYPT_MODE, active);
            assertEquals(content.length + 16, encrypted.length);
            assertArrayEquals(content, throughSdkStream(encrypted, key, iv, Cipher.DECRYPT_MODE, active));

            byte[] tampered = encrypted.clone();
            tampered[tampered.length - 1] ^= 1;
            Provider verifiedProvider = active;
            assertThrows(SecurityException.class,
                () -> throughSdkStream(tampered, key, iv, Cipher.DECRYPT_MODE, verifiedProvider));

            var keyPairGenerator = KeyPairGenerator.getInstance("RSA", active);
            keyPairGenerator.initialize(2048);
            var keyPair = keyPairGenerator.generateKeyPair();
            String wrapAlgorithm = new COSKeyWrapScheme().getKeyWrapAlgorithm(keyPair.getPublic());
            assertEquals(COSKeyWrapScheme.RSA_ECB_OAEPWithSHA256AndMGF1Padding, wrapAlgorithm);
            var wrapper = Cipher.getInstance(wrapAlgorithm, active);
            wrapper.init(Cipher.WRAP_MODE, keyPair.getPublic());
            byte[] wrapped = wrapper.wrap(key);
            var unwrapper = Cipher.getInstance(wrapAlgorithm, active);
            unwrapper.init(Cipher.UNWRAP_MODE, keyPair.getPrivate());
            assertArrayEquals(rawKey, unwrapper.unwrap(wrapped, "AES", Cipher.SECRET_KEY).getEncoded());
        } finally {
            // 已有 provider 始终保留；只移除本用例通过 SDK 新增且仍为同一实例的对象。
            if (original == null && active != null && Security.getProvider("BC") == active) {
                Security.removeProvider("BC");
            }
        }
    }

    private static byte[] throughSdkStream(byte[] input, SecretKeySpec key, byte[] iv,
                                           int mode, Provider provider) throws Exception {
        var cipher = ContentCryptoScheme.AES_GCM.createCipherLite(key, iv, mode, provider);
        try (var stream = new CipherLiteInputStream(new ByteArrayInputStream(input), cipher, 512)) {
            return stream.readAllBytes();
        }
    }
}
