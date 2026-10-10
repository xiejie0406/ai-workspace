package com.ruoyi.paismart.infrastructure;

import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

/** 与 Boot 4 一致的 JSON 编解码，仅持久化业务负载和版本快照。 */
public final class Jsons {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Jsons() { }
    public static String write(Object value) { return JSON.writeValueAsString(value); }
    public static <T> T read(String text, Class<T> type) { return JSON.readValue(text, type); }
    public static Map<String, Object> map(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        return JSON.readValue(text, new TypeReference<Map<String, Object>>() { });
    }
    public static List<Object> list(String text) {
        if (text == null || text.isBlank()) return List.of();
        return JSON.readValue(text, new TypeReference<List<Object>>() { });
    }
}
