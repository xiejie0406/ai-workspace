package com.paicli.image;

import com.paicli.llm.LlmClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

public final class ImageProcessor {
    public static final long API_IMAGE_MAX_BASE64_SIZE = 5L * 1024 * 1024;
    public static final long IMAGE_TARGET_RAW_SIZE = API_IMAGE_MAX_BASE64_SIZE * 3L / 4L;
    public static final long MAX_SOURCE_IMAGE_BYTES = 50L * 1024 * 1024;
    public static final long MAX_IMAGE_PIXELS = 40_000_000L;
    public static final int IMAGE_MAX_WIDTH = 2000;
    public static final int IMAGE_MAX_HEIGHT = 2000;

    private static final float[] JPEG_QUALITIES = new float[]{0.85f, 0.70f, 0.55f, 0.40f, 0.25f};

    private ImageProcessor() {}

    public static ProcessedImage fromPath(Path path, String mimeType) throws IOException {
        long size = Files.size(path);
        if (size == 0) {
            throw new IOException("图片文件为空");
        }
        if (size > MAX_SOURCE_IMAGE_BYTES) {
            throw new IOException("图片超过 " + (MAX_SOURCE_IMAGE_BYTES / 1024 / 1024) + "MB 处理上限");
        }
        // 文件可能在 size 检查后增长；读取期同样限制，不先分配无限数组。
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes((int) MAX_SOURCE_IMAGE_BYTES + 1);
        }
        return process(bytes, mimeType, path);
    }

    public static ProcessedImage fromBase64(String base64, String mimeType) throws IOException {
        if (base64 == null || base64.isBlank()) {
            throw new IOException("图片数据为空");
        }
        if (base64.length() > maxSourceBase64Length()) {
            throw new IOException("图片超过 " + (MAX_SOURCE_IMAGE_BYTES / 1024 / 1024) + "MB 处理上限");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("图片 base64 数据无效");
        }
        return process(bytes, mimeType, null);
    }

    public static ProcessedImage process(byte[] bytes, String mimeType, Path sourcePath) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("图片数据为空");
        }
        if (bytes.length > MAX_SOURCE_IMAGE_BYTES) {
            throw new IOException("图片超过 " + (MAX_SOURCE_IMAGE_BYTES / 1024 / 1024) + "MB 处理上限");
        }
        DecodedImage decoded = readChecked(bytes, mimeType);
        String normalizedMime = decoded.mimeType();
        BufferedImage image = decoded.image();
        if (image == null) {
            long base64Size = estimateBase64Size(bytes.length);
            if (base64Size <= API_IMAGE_MAX_BASE64_SIZE) {
                return new ProcessedImage(
                        Base64.getEncoder().encodeToString(bytes),
                        normalizedMime,
                        bytes.length,
                        bytes.length,
                        new Dimensions(decoded.width(), decoded.height(), decoded.width(), decoded.height()),
                        sourcePath,
                        false);
            }
            throw new IOException("图片超过 5MB API 上限，且当前运行时无法解码压缩该格式");
        }

        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();
        boolean overSize = estimateBase64Size(bytes.length) > API_IMAGE_MAX_BASE64_SIZE;
        boolean hasAlpha = image.getColorModel() != null && image.getColorModel().hasAlpha();

        // 1) 字节已经在 API 上限内 + 不需要 flatten alpha → 直通原始 PNG/JPEG bytes，
        //    不动尺寸。即便宽高超过 IMAGE_MAX_WIDTH/HEIGHT，2000 这条线只是一个关于
        //    token 成本的"软建议"，provider 自己会按 tile 计费/缩放；强行 resize + JPEG
        //    会破坏小字渲染（Chinese 字 + JPEG 8x8 块伪影），得不偿失。
        if (!overSize && !hasAlpha) {
            return new ProcessedImage(
                    Base64.getEncoder().encodeToString(bytes),
                    normalizedMime,
                    bytes.length,
                    bytes.length,
                    new Dimensions(originalWidth, originalHeight, originalWidth, originalHeight),
                    sourcePath,
                    false);
        }

        // 2) 有 alpha 但字节在 API 上限内：白底 flatten 后用 PNG 输出，保持尺寸。
        //    部分 provider 对带 alpha 的 PNG 处理不一致（边缘色重映射差异），
        //    这里统一铺白底再交付。
        if (!overSize && hasAlpha) {
            byte[] flattened = writePng(flattenAlpha(image, originalWidth, originalHeight));
            if (estimateBase64Size(flattened.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                return new ProcessedImage(
                        Base64.getEncoder().encodeToString(flattened),
                        "image/png",
                        bytes.length,
                        flattened.length,
                        new Dimensions(originalWidth, originalHeight, originalWidth, originalHeight),
                        sourcePath,
                        true);
            }
            // alpha flatten 后还是过大 → 落到 3) 走 resize
        }

        // 3) 原始 / flatten 后仍 > 5MB：等比缩放进 IMAGE_MAX_WIDTH × IMAGE_MAX_HEIGHT，
        //    优先尝试 PNG（lossless），过大才退到 JPEG 多档质量兜底。
        ResizeSize target = fitWithin(originalWidth, originalHeight, IMAGE_MAX_WIDTH, IMAGE_MAX_HEIGHT);
        BufferedImage resized = resize(image, target.width(), target.height());

        byte[] resizedPng = writePng(resized);
        if (estimateBase64Size(resizedPng.length) <= API_IMAGE_MAX_BASE64_SIZE) {
            return new ProcessedImage(
                    Base64.getEncoder().encodeToString(resizedPng),
                    "image/png",
                    bytes.length,
                    resizedPng.length,
                    new Dimensions(originalWidth, originalHeight, target.width(), target.height()),
                    sourcePath,
                    true);
        }

        byte[] encoded = null;
        for (float quality : JPEG_QUALITIES) {
            byte[] candidate = writeJpeg(resized, quality);
            if (estimateBase64Size(candidate.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                encoded = candidate;
                break;
            }
        }
        if (encoded == null && (target.width() > 512 || target.height() > 512)) {
            ResizeSize smaller = fitWithin(originalWidth, originalHeight, 1200, 1200);
            resized = resize(image, smaller.width(), smaller.height());
            target = smaller;
            for (float quality : JPEG_QUALITIES) {
                byte[] candidate = writeJpeg(resized, quality);
                if (estimateBase64Size(candidate.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                    encoded = candidate;
                    break;
                }
            }
        }
        if (encoded == null) {
            throw new IOException("图片压缩后仍超过 5MB API 上限");
        }

        return new ProcessedImage(
                Base64.getEncoder().encodeToString(encoded),
                "image/jpeg",
                bytes.length,
                encoded.length,
                new Dimensions(originalWidth, originalHeight, target.width(), target.height()),
                sourcePath,
                true);
    }

    public static String createMetadataText(ProcessedImage image) {
        if (image == null) {
            return null;
        }
        Dimensions dims = image.dimensions();
        if (dims == null) {
            return image.sourcePath() == null ? null : "[Image source: " + image.sourcePath() + "]";
        }
        boolean wasResized = dims.originalWidth() != dims.displayWidth()
                || dims.originalHeight() != dims.displayHeight();
        boolean hasSource = image.sourcePath() != null;
        if (!hasSource && !wasResized && !image.reencoded()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[Image: ");
        boolean needsComma = false;
        if (hasSource) {
            sb.append("source: ").append(image.sourcePath());
            needsComma = true;
        }
        if (wasResized) {
            if (needsComma) {
                sb.append(", ");
            }
            double scale = (double) dims.originalWidth() / Math.max(1, dims.displayWidth());
            sb.append("original ")
                    .append(dims.originalWidth()).append("x").append(dims.originalHeight())
                    .append(", displayed at ")
                    .append(dims.displayWidth()).append("x").append(dims.displayHeight())
                    .append(". Multiply coordinates by ")
                    .append(String.format(Locale.ROOT, "%.2f", scale))
                    .append(" to map to original image.");
        } else if (image.reencoded()) {
            if (needsComma) {
                sb.append(", ");
            }
            sb.append("re-encoded for API size limit without changing dimensions.");
        }
        sb.append("]");
        return sb.toString();
    }

    public static LlmClient.ContentPart toContentPart(ProcessedImage image) {
        return LlmClient.ContentPart.imageBase64(image.base64(), image.mimeType());
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            return "image/jpeg";
        }
        return normalized;
    }

    private static DecodedImage readChecked(byte[] bytes, String declaredMime) throws IOException {
        WebpHeader webp = isFourCC(bytes, 0, "RIFF") && isFourCC(bytes, 8, "WEBP")
                ? webpHeader(bytes) : null;
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                if (webp == null) throw new IOException("图片数据损坏或当前运行时不支持该格式");
                requireMime(declaredMime, "image/webp");
                return new DecodedImage(null, "image/webp", webp.width(), webp.height());
            }
            ImageReader reader = readers.next();
            try {
                String actualMime = readerMime(reader);
                requireMime(declaredMime, actualMime);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requirePixels(width, height);
                if (webp != null && (width != webp.width() || height != webp.height())) {
                    throw new IOException("WebP 尺寸头不一致");
                }
                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) {
                    throw new IOException("图片解码尺寸不一致");
                }
                return new DecodedImage(image, actualMime, width, height);
            } catch (RuntimeException invalid) {
                throw new IOException("图片格式或数据无效");
            } finally {
                reader.dispose();
            }
        }
    }

    private static String readerMime(ImageReader reader) throws IOException {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        return switch (format) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "wbmp" -> "image/vnd.wap.wbmp";
            case "tif", "tiff" -> "image/tiff";
            default -> throw new IOException("当前运行时无法核验该图片格式");
        };
    }

    private static void requireMime(String declared, String actual) throws IOException {
        String normalized = normalizeMimeType(declared);
        if (normalized != null && !normalized.equals(actual)) {
            throw new IOException("图片 MIME 与实际格式不一致");
        }
    }

    private static void requirePixels(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new IOException("图片像素超过四千万上限或尺寸无效");
        }
    }

    // 没有 WebP ImageReader 时只核验 RFC 9649 的静态容器及尺寸头，不声称完成解码。
    // 支持简单 VP8/VP8L、VP8X 静态 alpha；动画、元数据和未知扩展须由完整解码器处理。
    private static WebpHeader webpHeader(byte[] bytes) throws IOException {
        if (bytes.length < 26 || littleEndian(bytes, 4, 4) != bytes.length - 8L) {
            throw new IOException("WebP RIFF 长度无效");
        }
        WebpHeader canvas = null;
        WebpHeader image = null;
        int flags = 0;
        int alphaOffset = -1;
        int alphaLength = 0;
        boolean lossless = false;
        int chunks = 0;
        int offset = 12;
        while (offset < bytes.length) {
            if (bytes.length - offset < 8) throw new IOException("WebP 分块头不完整");
            long length = littleEndian(bytes, offset + 4, 4);
            long end = (long) offset + 8 + length;
            long next = end + (length & 1);
            if (length == 0 || next > bytes.length || ((length & 1) != 0 && bytes[(int) end] != 0)) {
                throw new IOException("WebP 分块长度或填充无效");
            }
            int data = offset + 8;
            if (isFourCC(bytes, offset, "VP8X")) {
                if (chunks != 0 || length != 10) throw new IOException("WebP VP8X 结构无效");
                flags = bytes[data] & 0xff;
                if ((flags & ~0x10) != 0 || littleEndian(bytes, data + 1, 3) != 0) {
                    throw new IOException("当前运行时不支持此 WebP 扩展变体");
                }
                canvas = new WebpHeader((int) littleEndian(bytes, data + 4, 3) + 1,
                        (int) littleEndian(bytes, data + 7, 3) + 1);
                requirePixels(canvas.width(), canvas.height());
            } else if (isFourCC(bytes, offset, "ALPH")) {
                if (canvas == null || flags != 0x10 || alphaOffset >= 0 || image != null || length < 2) {
                    throw new IOException("WebP alpha 结构无效");
                }
                int header = bytes[data] & 0xff;
                if ((header & 0xc0) != 0 || ((header >>> 4) & 3) > 1 || (header & 3) > 1) {
                    throw new IOException("WebP alpha 编码无效");
                }
                alphaOffset = data;
                alphaLength = (int) length;
            } else if (isFourCC(bytes, offset, "VP8 ") || isFourCC(bytes, offset, "VP8L")) {
                if (image != null || (canvas == null && chunks != 0)) throw new IOException("WebP 图像分块重复或顺序无效");
                lossless = isFourCC(bytes, offset, "VP8L");
                if (lossless) {
                    if (length < 6 || bytes[data] != 0x2f || (littleEndian(bytes, data + 1, 4) >>> 29) != 0) {
                        throw new IOException("WebP VP8L 尺寸头无效");
                    }
                    long bits = littleEndian(bytes, data + 1, 4);
                    image = new WebpHeader((int) (bits & 0x3fff) + 1, (int) ((bits >>> 14) & 0x3fff) + 1);
                    if (alphaOffset >= 0 || (canvas != null && ((bits & 0x10000000L) != 0) != (flags == 0x10))) {
                        throw new IOException("WebP alpha 标记不一致");
                    }
                } else {
                    if (length < 11) throw new IOException("WebP VP8 尺寸头不完整");
                    long tag = littleEndian(bytes, data, 3);
                    int widthBits = (int) littleEndian(bytes, data + 6, 2);
                    int heightBits = (int) littleEndian(bytes, data + 8, 2);
                    if ((tag & 1) != 0 || ((tag >>> 1) & 7) > 3 || (tag & 0x10) == 0
                            || bytes[data + 3] != (byte) 0x9d || bytes[data + 4] != 1 || bytes[data + 5] != 0x2a
                            || (widthBits & 0xc000) != 0 || (heightBits & 0xc000) != 0
                            || (tag >>> 5) == 0 || (tag >>> 5) + 10 >= length) {
                        throw new IOException("WebP VP8 尺寸头或分区无效");
                    }
                    image = new WebpHeader(widthBits, heightBits);
                }
                requirePixels(image.width(), image.height());
            } else {
                throw new IOException("当前运行时不支持此 WebP 扩展分块");
            }
            chunks++;
            offset = (int) next;
        }
        if (image == null || (canvas != null && !canvas.equals(image))
                || (canvas != null && !lossless && (flags == 0x10) != (alphaOffset >= 0))) {
            throw new IOException("WebP 图像与画布尺寸或 alpha 标记不一致");
        }
        if (alphaOffset >= 0 && (bytes[alphaOffset] & 3) == 0
                && alphaLength != 1L + (long) image.width() * image.height()) {
            throw new IOException("WebP 原始 alpha 长度无效");
        }
        return image;
    }

    private static boolean isFourCC(byte[] bytes, int offset, String value) {
        if (offset < 0 || bytes.length - offset < 4) return false;
        for (int i = 0; i < 4; i++) if ((bytes[offset + i] & 0xff) != value.charAt(i)) return false;
        return true;
    }

    private static long littleEndian(byte[] bytes, int offset, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) value |= (long) (bytes[offset + i] & 0xff) << (8 * i);
        return value;
    }

    private static long estimateBase64Size(long rawBytes) {
        return ((rawBytes + 2L) / 3L) * 4L;
    }

    private static long maxSourceBase64Length() {
        return estimateBase64Size(MAX_SOURCE_IMAGE_BYTES);
    }

    private static ResizeSize fitWithin(int width, int height, int maxWidth, int maxHeight) {
        double scale = Math.min(1.0d, Math.min((double) maxWidth / width, (double) maxHeight / height));
        return new ResizeSize(
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage flattenAlpha(BufferedImage image, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前运行时缺少 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            imageOut.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private record ResizeSize(int width, int height) {}

    private record WebpHeader(int width, int height) {}

    private record DecodedImage(BufferedImage image, String mimeType, int width, int height) {}

    public record Dimensions(int originalWidth, int originalHeight, int displayWidth, int displayHeight) {}

    public record ProcessedImage(String base64, String mimeType, long originalBytes, long outputBytes,
                                 Dimensions dimensions, Path sourcePath, boolean reencoded) {}
}
