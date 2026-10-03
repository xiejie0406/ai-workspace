package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** 中心和 Runner 共同使用的参数摘要：对象键递归排序，数组顺序保持。 */
public final class CanonicalJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private CanonicalJson() { }
    public static JsonNode parseObject(String input) throws IOException {
        // edit的old/new各可5MB，控制字符JSON转义最多6倍；实际文件仍由原工具限制5MB。
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 64 * 1024 * 1024) throw new IOException("工具参数超过上限");
        JsonNode value = MAPPER.readTree(input);
        if (value == null || !value.isObject()) throw new IOException("工具参数必须是 JSON 对象");
        return value;
    }
    public static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) result.set(key, sorted(value.get(key)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            value.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return value.deepCopy();
    }
    public static String hash(JsonNode value) { return sha256(sorted(value).toString().getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
