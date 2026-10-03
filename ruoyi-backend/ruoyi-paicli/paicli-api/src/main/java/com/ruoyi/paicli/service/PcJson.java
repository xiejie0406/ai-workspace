package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.PcException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 稳定 JSON 哈希：对象键排序，数组保持顺序，输入大小在序列化边界限制。 */
public final class PcJson {
    private final ObjectMapper mapper;
    public PcJson(ObjectMapper mapper) { this.mapper=mapper; }
    public String write(Object value) {
        String result=mapper.writeValueAsString(canonical(value));
        if(result.getBytes(StandardCharsets.UTF_8).length>64*1024*1024) throw new PcException(413,"PC_PAYLOAD_TOO_LARGE","单条内容超过64MB，请使用产物引用");
        return result;
    }
    public Map<String,Object> read(String value) { return value==null?new LinkedHashMap<>():mapper.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}); }
    public String hash(Object value) { return digest(write(value)); }
    public static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { TreeMap<String,Object> sorted=new TreeMap<>(); map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v))); return sorted; }
        if(value instanceof Collection<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }
    public static long number(Map<String,Object> m,String key) {
        Object v=m.get(key); try { return v instanceof Number n?n.longValue():Long.parseLong(String.valueOf(v)); }
        catch(Exception e) { throw PcException.invalid("缺少或非法数值字段："+key); }
    }
    public static String text(Map<String,Object> m,String key) { return Objects.toString(m.get(key),""); }
    public static Map<String,Object> object(Map<String,Object> m,String key) {
        Object v=m.get(key); if(!(v instanceof Map<?,?>))throw PcException.invalid("缺少对象字段："+key);
        Map<String,Object> result=new LinkedHashMap<>(); ((Map<?,?>)v).forEach((k,val)->result.put(String.valueOf(k),val)); return result;
    }
}
