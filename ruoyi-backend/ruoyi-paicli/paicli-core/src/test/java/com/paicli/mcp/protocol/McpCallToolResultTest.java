package com.paicli.mcp.protocol;

import com.paicli.tool.ToolOutput;
import com.paicli.image.ImageReferenceParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCallToolResultTest {

    @Test
    void smallImageBecomesImagePart() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, base64, "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertTrue(output.hasImageParts(), "小图片应进入 imageParts");
        assertEquals(1, output.imageParts().size());
        assertEquals(base64, output.imageParts().get(0).imageBase64());
        assertTrue(output.text().contains("base64Length=" + base64.length()));
        assertFalse(output.text().contains("超过"));
    }

    @Test
    void oversizedImageFallsBackToTextOnly() {
        // 与处理器无WebP decoder分支一致：核验过的静态结构超过API字节上限不能无损压缩。
        byte[] seed = java.util.Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAYAAAAdQlOJVof+BiOh/AAA=");
        int payload = (int) com.paicli.image.ImageProcessor.IMAGE_TARGET_RAW_SIZE + 1;
        byte[] oversized = java.util.Arrays.copyOf(seed, 20 + payload + (payload & 1));
        java.nio.ByteBuffer.wrap(oversized).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(4, oversized.length - 8).putInt(16, payload);
        String base64 = java.util.Base64.getEncoder().encodeToString(oversized);

        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, base64, "image/webp")),
                false);

        ToolOutput output = result.toToolOutput();

        assertFalse(output.hasImageParts(), "超过上限的图片不应进入 imageParts");
        assertTrue(output.text().contains("超过"));
        assertTrue(output.text().contains("take_snapshot"));
    }

    @Test
    void emptyImageDataKeepsFallbackOnly() {
        McpCallToolResult result = new McpCallToolResult(
                List.of(new McpContent("image", null, "", "image/png")),
                false);

        ToolOutput output = result.toToolOutput();

        assertFalse(output.hasImageParts());
        assertTrue(output.text().contains("base64Length=0"));
    }

    @Test
    void errorResultIsTypedAsUnsuccessful() {
        ToolOutput output = new McpCallToolResult(
                List.of(new McpContent("text", "failure", null, null)),
                true).toToolOutput();

        assertFalse(output.successful());
        assertTrue(output.discoveredUrls().isEmpty());
    }

    @Test
    void strictImageConversionRejectsIncompleteImagesInsteadOfReturningSuccessfulWarnings() {
        var result = new McpCallToolResult(List.of(new McpContent("image", null, "invalid!", "image/png")), false);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, result::toToolOutputStrict);
        var empty = new McpCallToolResult(List.of(new McpContent("image", null, "", "image/png")), false);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, empty::toToolOutputStrict);
    }
}
