package com.ruoyi.paismart.infrastructure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSAuthenticatedDataParser;
import org.bouncycastle.cms.CMSAuthenticatedDataStreamGenerator;
import org.bouncycastle.cms.KEKRecipientId;
import org.bouncycastle.cms.jcajce.JceCMSMacCalculatorBuilder;
import org.bouncycastle.cms.jcajce.JceKEKAuthenticatedRecipient;
import org.bouncycastle.cms.jcajce.JceKEKRecipientInfoGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 本地 CMS 真实读写覆盖解析运行依赖的类链接；不注册全局 Provider、不访问网络。 */
class RuntimeDependencyLinkageTest {
    @Test
    void definiteLengthCmsHmacSha256RoundTripsAndMatchesJdkMac() throws Exception {
        var provider = new BouncyCastleProvider();
        byte[] content = "知识库解析依赖的 CMS 认证正文".getBytes(StandardCharsets.UTF_8);
        byte[] recipientId = "local-fixture".getBytes(StandardCharsets.US_ASCII);
        // 固定、公开的测试包装密钥，不来源于任何账号或真实配置。
        var wrappingKey = new SecretKeySpec(new byte[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
        }, "AES");
        var calculator = new JceCMSMacCalculatorBuilder(PKCSObjectIdentifiers.id_hmacWithSHA256)
            .setProvider(provider).build();
        var generator = new CMSAuthenticatedDataStreamGenerator();
        generator.setEncoding(ASN1Encoding.DL);
        generator.addRecipientInfoGenerator(new JceKEKRecipientInfoGenerator(recipientId, wrappingKey)
            .setProvider(provider));
        var encoded = new ByteArrayOutputStream();
        // 明确长度路径会使用 CMSUtils 的 MAC 长度计算以及 DL ASN.1 生成器。
        try (var output = generator.open(encoded, content.length, calculator)) {
            output.write(content);
        }

        var parser = new CMSAuthenticatedDataParser(encoded.toByteArray());
        try {
            assertFalse(parser.isBEREncoded());
            assertEquals(PKCSObjectIdentifiers.id_hmacWithSHA256.getId(), parser.getMacAlgOID());
            var recipient = parser.getRecipientInfos().get(new KEKRecipientId(recipientId));
            assertNotNull(recipient);
            assertArrayEquals(content, recipient.getContent(
                new JceKEKAuthenticatedRecipient(wrappingKey).setProvider(provider)));
            byte[] encodedMac = parser.getMac();
            assertEquals(32, encodedMac.length);
            assertArrayEquals(encodedMac, recipient.getMac());
            // 使用 JDK 自带 MAC 独立核对输出，不通过 BC 再计算预期结果。
            byte[] macKey = assertInstanceOf(byte[].class, calculator.getKey().getRepresentation());
            var jdkMac = Mac.getInstance("HmacSHA256");
            jdkMac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            assertArrayEquals(jdkMac.doFinal(content), encodedMac);
        } finally {
            parser.close();
        }
    }
}
