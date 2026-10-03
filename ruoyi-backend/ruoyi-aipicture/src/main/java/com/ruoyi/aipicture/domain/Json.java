package com.ruoyi.aipicture.domain;

import com.alibaba.fastjson2.JSON;
import java.util.*;

/** 仅本模块的 JSON 边界；保留源 snake_case，不改变平台全局序列化。 */
public final class Json {
    private Json() { }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object v) {
        if (v instanceof Map<?,?>) return (Map<String,Object>)v;
        if (v instanceof String s) return JSON.parseObject(s,LinkedHashMap.class);
        return new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked") public static List<Object> list(Object v) { return v instanceof List<?> ? (List<Object>)v : new ArrayList<>(); }
    public static String string(Object v) { return v==null ? "" : String.valueOf(v); }
    public static long number(Object v) { return v instanceof Number n ? n.longValue() : Long.parseLong(string(v)); }
    public static double decimal(Object v,double fallback) { return v==null ? fallback : ((Number)v).doubleValue(); }
    public static boolean bool(Object v,boolean fallback) { return v instanceof Boolean b ? b : fallback; }
    public static String write(Object v) { return JSON.toJSONString(v); }
    public static Object canonical(Object value) {
        if(value instanceof Map<?,?>) {var ordered=new TreeMap<String,Object>();for(var e:map(value).entrySet()) ordered.put(e.getKey(),canonical(e.getValue()));return ordered;}
        if(value instanceof List<?>) return list(value).stream().map(Json::canonical).toList();
        if(value instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros();
        return value;
    }
    public static boolean equivalent(Object a,Object b) {return write(canonical(a)).equals(write(canonical(b)));}
    public static Map<String,Object> copy(Map<String,Object> v) { return map(write(v)); }
    public static String uuid(Object v) {
        try { return UUID.fromString(string(v)).toString(); } catch(Exception e) { throw AiPictureException.invalid("ID 必须为 UUID"); }
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public static Map<String,Object> of(Object... kv) {
        var m = new LinkedHashMap<String,Object>();
        for(int i=0;i<kv.length;i+=2) m.put((String)kv[i],kv[i+1]);
        return m;
    }
    public static long expected(Map<String,Object> m) {
        Object v=m.containsKey("expectedVersion") ? m.get("expectedVersion") : m.get("expected_version");
        if (!(v instanceof Number) || number(v)<0) throw AiPictureException.invalid("请提供 expectedVersion");
        return number(v);
    }
    public static String idem(Map<String,Object> m) {
        String s=string(m.getOrDefault("idempotencyKey",m.get("idempotency_key")));
        if(s.isBlank()||s.length()>128) throw AiPictureException.invalid("需要 1–128 字符幂等标识");
        return s;
    }
}
