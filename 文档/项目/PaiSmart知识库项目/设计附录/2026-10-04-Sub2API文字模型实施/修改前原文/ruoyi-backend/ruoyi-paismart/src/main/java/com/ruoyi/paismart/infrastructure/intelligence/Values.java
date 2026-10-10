package com.ruoyi.paismart.infrastructure.intelligence;
import java.util.*;

/** 有限 JSON 协议取值，拒绝静默截断关键参数。 */
public final class Values {
    private Values() {}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) { return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of(); }
    @SuppressWarnings("unchecked") public static List<Object> list(Object value) { return value instanceof List<?> l?(List<Object>)l:List.of(); }
    public static String text(Object value) { return value==null?"":String.valueOf(value); }
    public static String required(Map<String,Object> body,String key,int max) { String v=text(body.get(key)).trim(); if(v.isBlank() || v.length()>max) throw new IllegalArgumentException(key+"缺失或过长"); return v; }
    public static long number(Object value,long fallback) {if(value==null)return fallback;try{return value instanceof Number?new java.math.BigDecimal(value.toString()).longValueExact():Long.parseLong(value.toString());}catch(NumberFormatException|ArithmeticException invalid){throw new IllegalArgumentException("参数必须为精确整数且未超出范围",invalid);}}
    public static int range(Object value,int fallback,int min,int max) { long n=number(value,fallback); if(n<min||n>max)throw new IllegalArgumentException("参数超出范围");return (int)n; }
    public static boolean bool(Object value) { return Boolean.TRUE.equals(value)||"true".equals(value); }
    public static String id() { return UUID.randomUUID().toString(); }
    public static String safeError(Exception e) { String value=e instanceof IllegalArgumentException||e instanceof IllegalStateException?text(e.getMessage()).replaceAll("(?i)(bearer|api[_-]?key|token)[=: ]+[^\\s]+","$1=***"):"智能链路执行失败，请按任务编号查询诊断";return value.substring(0,Math.min(450,value.length())); }
}
