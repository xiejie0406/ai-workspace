package com.paicli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTest {
    // Pillow/libwebp 生成的 2×3 静态图片；保留真实编码数据，不能以文本冒充图片。
    private static final String WEBP_VP8 = "UklGRjgAAABXRUJQVlA4ICwAAACwAQCdASoCAAMAAUAmJaACdLoABDAAAP73WS/8Buvi2mX/zNH6TfSbuYAAAA==";
    private static final String WEBP_VP8L = "UklGRh4AAABXRUJQVlA4TBEAAAAvAYAAAAdQlOJVof+BiOh/AAA=";
    private static final String WEBP_ALPHA = "UklGRloAAABXRUJQVlA4WAoAAAAQAAAAAQAAAgAAQUxQSAcAAAAAQEBAQEBAAFZQOCAsAAAAsAEAnQEqAgADAAFAJiWgAnS6AAQwAAD+91kv/Abr4tpl/8zR+k30m7mAAAA=";
    private static final String WEBP_LOSSLESS_ALPHA = "UklGRh4AAABXRUJQVlA4TBEAAAAvAYAAEAdQlOJVoUCBiOh/AAA=";

    @Test
    void validatesActualPngJpegAndGifBeforeReturningImageData() throws Exception {
        for (String format : new String[]{"png", "jpeg", "gif"}) {
            byte[] bytes = encodedImage(format);
            ImageProcessor.ProcessedImage image = ImageProcessor.fromBase64(
                    Base64.getEncoder().encodeToString(bytes), "image/" + format);
            assertEquals("image/" + format, image.mimeType());
            assertEquals(2, image.dimensions().originalWidth());
            assertEquals(3, image.dimensions().originalHeight());
            assertTrue(image.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
        }
        assertEquals("image/jpeg", ImageProcessor.process(encodedImage("jpeg"), "image/jpg", null).mimeType());
        assertEquals("image/jpeg", ImageProcessor.process(encodedImage("jpeg"), null, null).mimeType());
    }

    @Test
    void rejectsWrongMimeInEveryPublicByteEntry(@TempDir Path temporary) throws Exception {
        byte[] bytes = encodedImage("png");
        Path file = temporary.resolve("wrong.jpeg");
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> ImageProcessor.process(bytes, "image/jpeg", null));
        assertThrows(IOException.class, () -> ImageProcessor.fromPath(file, "image/jpeg"));
        assertThrows(IOException.class, () -> ImageProcessor.fromBase64(Base64.getEncoder().encodeToString(bytes), "image/jpeg"));
        assertThrows(IOException.class, () -> ImageProcessor.process(bytes, "image/webp", null));
    }

    @Test
    void rejectsInvalidBase64AndUnrecognizedBytesRatherThanPassingThemThrough() {
        IOException invalid = assertThrows(IOException.class, () -> ImageProcessor.fromBase64("private-value%%%", "image/png"));
        assertFalse(invalid.getMessage().contains("private-value"));
        assertThrows(IOException.class, () -> ImageProcessor.fromBase64("aGVsbG8=", "image/png"));
        assertThrows(IOException.class, () -> ImageProcessor.process(new byte[]{1, 2, 3}, "image/webp", null));
    }

    @Test
    void rejectsSmallPngHeaderWithHugePixelDimensionsBeforeDecoding(@TempDir Path temporary) throws Exception {
        byte[] bytes = encodedImage("png");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(16, 10_000).putInt(20, 10_000);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        buffer.putInt(29, (int) crc.getValue());
        assertTrue(bytes.length < 1024);
        assertPixelRejection(() -> ImageProcessor.process(bytes, "image/png", null));
        assertPixelRejection(() -> ImageProcessor.fromBase64(Base64.getEncoder().encodeToString(bytes), "image/png"));
        Path file = temporary.resolve("pixels.png");
        Files.write(file, bytes);
        assertPixelRejection(() -> ImageProcessor.fromPath(file, "image/png"));
    }

    @Test
    void rejectsSmallJpegHeaderWithHugePixelDimensionsBeforeDecoding() throws Exception {
        byte[] bytes = encodedImage("jpeg");
        boolean patched = false;
        for (int i = 0; i + 8 < bytes.length; i++) {
            if ((bytes[i] & 0xff) == 0xff && ((bytes[i + 1] & 0xff) == 0xc0 || (bytes[i + 1] & 0xff) == 0xc2)) {
                ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putShort(i + 5, (short) 10_000).putShort(i + 7, (short) 10_000);
                patched = true;
                break;
            }
        }
        assertTrue(patched, "技术图片必须实际含 JPEG SOF 尺寸头");
        assertTrue(bytes.length < 2048);
        assertPixelRejection(() -> ImageProcessor.process(bytes, "image/jpeg", null));
    }

    @Test
    void rejectsSmallGifFrameHeaderWithHugePixelDimensionsBeforeDecoding() throws Exception {
        // 只有 GIF 头、帧描述和最小数据块，不创建对应像素阵列。
        byte[] bytes = new byte[]{'G', 'I', 'F', '8', '9', 'a', 0x10, 0x27, 0x10, 0x27, 0, 0, 0,
                ',', 0, 0, 0, 0, 0x10, 0x27, 0x10, 0x27, 0, 2, 2, 0x44, 0x01, 0, ';'};
        assertPixelRejection(() -> ImageProcessor.process(bytes, "image/gif", null));
    }

    @Test
    void appliesSourceByteLimitToDirectProcessEntry() {
        byte[] oversized = new byte[(int) ImageProcessor.MAX_SOURCE_IMAGE_BYTES + 1];
        assertThrows(IOException.class, () -> ImageProcessor.process(oversized, "image/png", null));
    }

    @Test
    void acceptsRealStillWebpContainersWithCheckedDimensions() throws Exception {
        for (String fixture : new String[]{WEBP_VP8, WEBP_VP8L, WEBP_ALPHA, WEBP_LOSSLESS_ALPHA}) {
            byte[] bytes = Base64.getDecoder().decode(fixture);
            ImageProcessor.ProcessedImage image = ImageProcessor.process(bytes, "image/webp", null);
            assertEquals("image/webp", image.mimeType());
            assertEquals(2, image.dimensions().originalWidth());
            assertEquals(3, image.dimensions().originalHeight());
            if (!ImageIO.getImageReadersByFormatName("webp").hasNext()) {
                assertArrayEquals(bytes, Base64.getDecoder().decode(image.base64()));
                assertFalse(image.reencoded(), "没有解码器的 WebP 只进行已核验的小图直通");
            }
        }
    }

    @Test
    void rejectsWebpWrongMime() {
        byte[] bytes = Base64.getDecoder().decode(WEBP_VP8L);
        assertThrows(IOException.class, () -> ImageProcessor.process(bytes, "image/png", null));
    }

    @Test
    void rejectsWebpHugePixelsAndUnsupportedAnimationWithoutAllocatingPixels() {
        byte[] lossless = Base64.getDecoder().decode(WEBP_VP8L);
        int dimensions = 9999 | (9999 << 14);
        ByteBuffer.wrap(lossless).order(ByteOrder.LITTLE_ENDIAN).putInt(21, dimensions);
        assertPixelRejection(() -> ImageProcessor.process(lossless, "image/webp", null));
        byte[] extended = Base64.getDecoder().decode(WEBP_ALPHA);
        // VP8X canvas: 两个 uint24 小端值，存储真实尺寸减一。
        put24(extended, 24, 9999);
        put24(extended, 27, 9999);
        assertPixelRejection(() -> ImageProcessor.process(extended, "image/webp", null));
        byte[] animated = Base64.getDecoder().decode(WEBP_ALPHA);
        animated[20] |= 2;
        assertThrows(IOException.class, () -> ImageProcessor.process(animated, "image/webp", null));
    }

    @Test
    void rejectsMalformedWebpLengthsSignaturesPaddingAndDuplicateFrames() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(WEBP_VP8L);
        byte[] riff = bytes.clone();
        riff[4]--;
        assertThrows(IOException.class, () -> ImageProcessor.process(riff, "image/webp", null));
        byte[] length = bytes.clone();
        Arrays.fill(length, 16, 20, (byte) 0xff);
        assertThrows(IOException.class, () -> ImageProcessor.process(length, "image/webp", null));
        byte[] signature = bytes.clone();
        signature[20] = 0;
        assertThrows(IOException.class, () -> ImageProcessor.process(signature, "image/webp", null));
        byte[] padding = bytes.clone();
        padding[padding.length - 1] = 1;
        assertThrows(IOException.class, () -> ImageProcessor.process(padding, "image/webp", null));
        byte[] truncated = Arrays.copyOf(bytes, 25);
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(4, truncated.length - 8);
        assertThrows(IOException.class, () -> ImageProcessor.process(truncated, "image/webp", null));
        byte[] duplicate = Arrays.copyOf(bytes, bytes.length + bytes.length - 12);
        System.arraycopy(bytes, 12, duplicate, bytes.length, bytes.length - 12);
        ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicate.length - 8);
        assertThrows(IOException.class, () -> ImageProcessor.process(duplicate, "image/webp", null));
    }

    @Test
    void rejectsWebpAboveApiLimitWhenNoDecoderCanCompressIt() {
        assertFalse(ImageIO.getImageReadersByFormatName("webp").hasNext(),
                "当前分发运行时无 WebP 编解码依赖；若增加解码器，需单独核验压缩分支");
        byte[] bytes = Base64.getDecoder().decode(WEBP_VP8L);
        int payload = (int) ImageProcessor.IMAGE_TARGET_RAW_SIZE + 1;
        byte[] oversized = Arrays.copyOf(bytes, 20 + payload + (payload & 1));
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(4, oversized.length - 8).putInt(16, payload);
        IOException failure = assertThrows(IOException.class, () -> ImageProcessor.process(oversized, "image/webp", null));
        assertTrue(failure.getMessage().contains("5MB"));
    }

    private static byte[] encodedImage(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, output));
            return output.toByteArray();
        }
    }

    private static void put24(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 3; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
    }

    private static void assertPixelRejection(org.junit.jupiter.api.function.Executable action) {
        IOException failure = assertThrows(IOException.class, action);
        assertTrue(failure.getMessage().contains("像素"), "应在解码前按像素头拒绝，不因损坏的压缩数据偶然失败");
    }

    @Test
    void keepsSmallImageAsImageBlock(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("small.png");
        BufferedImage buffered = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/png", processed.mimeType());
        assertEquals(20, processed.dimensions().displayWidth());
        assertEquals(10, processed.dimensions().displayHeight());
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test
    void flattensSmallAlphaPngBeforeAttaching(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("alpha.png");
        BufferedImage buffered = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("Hi", 2, 8);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/png", processed.mimeType());
        assertTrue(processed.reencoded(), "带 alpha 的 PNG 应铺白底重编码，避免 provider 透明通道处理不一致");
        assertEquals(20, processed.dimensions().displayWidth());
        assertEquals(10, processed.dimensions().displayHeight());
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test
    void keepsLargeButUnderApiLimitImageAsLosslessPng(@TempDir Path tempDir) throws Exception {
        // 2600x1200 RGB PNG 单色压缩后只有几十 KB，远低于 5MB API 上限。
        // 新策略：尺寸超过 IMAGE_MAX_WIDTH 不再触发 resize+JPEG，直通原始 PNG bytes，
        // 让 provider 自己按 tile 计费/缩放，避免小字被 JPEG 块伪影糊掉。
        Path image = tempDir.resolve("large.png");
        BufferedImage buffered = new BufferedImage(2600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 2600, 1200);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/png", processed.mimeType(), "字节在 API 上限内应保持 PNG，不转 JPEG");
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
        assertEquals(2600, processed.dimensions().originalWidth());
        assertEquals(2600, processed.dimensions().displayWidth(), "尺寸超过 2000 但字节够小时不应 resize");
        assertEquals(1200, processed.dimensions().displayHeight());
        String metadata = ImageProcessor.createMetadataText(processed);
        assertNotNull(metadata);
        assertTrue(metadata.contains("source: " + image), "图片元信息应保留本地路径，供 MCP media 工具兜底读取");
    }

    @Test
    void fallsBackToJpegOnlyWhenPngExceedsApiLimit(@TempDir Path tempDir) throws Exception {
        // 大尺寸 + 高熵噪声让 PNG 无法压缩进 5MB，强制走 JPEG fallback 分支。
        Path image = tempDir.resolve("noisy.png");
        BufferedImage buffered = new BufferedImage(3000, 3000, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageProcessor.ProcessedImage processed = ImageProcessor.fromPath(image, "image/png");

        assertEquals("image/jpeg", processed.mimeType(),
                "PNG 无法压进 5MB 时退到 JPEG（resize 也无能为力的高熵图）");
        assertTrue(processed.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
        assertEquals(3000, processed.dimensions().originalWidth());
        assertEquals(2000, processed.dimensions().displayWidth(), "JPEG 兜底前会 resize 到 IMAGE_MAX_WIDTH");
    }
}
