package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 一次性检查入口；编译到独立Temp，不写工程target，不构造运行/Provider。 */
public final class ModelNodeOptionsIndependent {
    private static String category(Object value) {
        if (value == null) return "NoneType";
        if (value instanceof Boolean) return "bool";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) return "int";
        if (value instanceof Number) return "float";
        if (value instanceof String) return "str";
        if (value instanceof Map<?, ?>) return "dict";
        if (value instanceof List<?>) return "list";
        return value.getClass().getName();
    }
    private static Object types(Object value) {
        if (value instanceof Map<?, ?> map) {
            JSONObject result = new JSONObject();
            map.forEach((key, item) -> result.put((String) key, types(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ModelNodeOptionsIndependent::types).toList();
        return category(value);
    }
    private static String className(Object value) {return value == null ? "null" : value.getClass().getName();}
    private static JSONObject keyCase(String name, String input, boolean expected) {
        JSONObject result = JSONObject.of("name", name, "expectedAccepted", expected,
                "codePoints", input.codePointCount(0, input.length()), "utf16Length", input.length(),
                "utf8Bytes", input.getBytes(StandardCharsets.UTF_8).length);
        try {
            String value = ModelOperationJson.text(input, 4096, "idempotencyKey");
            result.put("actualAccepted", true);
            result.put("unchanged", input.equals(value));
        } catch (ServiceException error) {
            result.put("actualAccepted", false);
            result.put("errorClass", error.getClass().getName());
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        JSONObject fixture = JSON.parseObject(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        JSONArray results = new JSONArray();
        JSONArray cases = fixture.getJSONArray("cases");
        for (int index = 0; index < cases.size(); index++) {
            JSONObject source = cases.getJSONObject(index);
            String field = source.getString("field");
            Object input = source.get("input");
            JSONObject raw = JSONObject.of(field, input);
            JSONObject result = JSONObject.of("index", index, "field", field, "input", input,
                    "javaInputClass", className(input), "javaInputCategory", category(input));
            if (input instanceof String text) result.put("inputCodePoints", text.codePoints().boxed().toList());
            try {
                RunJsonBoundary.check(raw, ModelOperationJson.REQUEST_BYTES);
                Object value = ModelNodeOptions.effective(raw).get(field);
                result.put("actualAccepted", true);
                result.put("actualEffective", value);
                result.put("actualEffectiveClass", className(value));
                result.put("actualEffectiveCategory", category(value));
                result.put("actualEffectiveTypes", types(value));
            } catch (ServiceException error) {
                result.put("actualAccepted", false);
                result.put("errorClass", error.getClass().getName());
            } catch (Exception unexpected) {
                result.put("unexpectedErrorClass", unexpected.getClass().getName());
            }
            results.add(result);
        }
        JSONArray keys = new JSONArray();
        keys.add(keyCase("4096 supplementary code points", "\uD83D\uDE00".repeat(4096), true));
        keys.add(keyCase("4097 supplementary code points", "\uD83D\uDE00".repeat(4097), false));
        keys.add(keyCase("C0 control", "a\n", false));
        keys.add(keyCase("C1 start", "a\u0080", false));
        keys.add(keyCase("C1 end", "a\u009f", false));
        keys.add(keyCase("unpaired high surrogate", "a\uD800", false));
        keys.add(keyCase("unpaired low surrogate", "a\uDC00", false));
        keys.add(keyCase("empty key", "", false));
        JSONObject defaults = ModelNodeOptions.effective(new JSONObject());
        JSONObject output = JSONObject.of("cases", results, "keys", keys,
                "defaults", defaults, "defaultTypes", types(defaults));
        Files.writeString(Path.of(args[1]), JSON.toJSONString(output, JSONWriter.Feature.WriteMapNullValue), StandardCharsets.UTF_8);
        System.out.println("Actual source probe: " + results.size() + " fixture cases; " + keys.size() + " key cases.");
    }
}
