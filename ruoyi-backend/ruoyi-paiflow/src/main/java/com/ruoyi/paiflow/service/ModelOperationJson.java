package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 模型原帧/许可规范字节明确保留null；不修改既有DSL规范协议。 */
public final class ModelOperationJson {
    private ModelOperationJson() {}
    public static final int REQUEST_BYTES=2*1024*1024,FRAME_BYTES=65536,STREAM_BYTES=8*1024*1024,MAX_FRAMES=10000;
    private static Object ordered(Object value) {
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((key,item)->result.put((String)key,ordered(item)));return result;}
        if(value instanceof List<?> list)return list.stream().map(ModelOperationJson::ordered).toList();return value;
    }
    public static String canonical(Object value,int bytes) {
        RunJsonBoundary.checkModel(value,bytes);return encoded(value,bytes);
    }
    static String canonicalFunctions(Object value,int bytes) {
        RunJsonBoundary.checkFunctions(value,bytes);return encoded(value,bytes);
    }
    static JSONObject copySchema(JSONObject value) {
        RunJsonBoundary.checkSchema(value,REQUEST_BYTES);return JSON.parseObject(encoded(value,REQUEST_BYTES));
    }
    static String canonicalNode(JSONObject value,int bytes) {
        RunJsonBoundary.checkNode(value,bytes);return encoded(value,bytes);
    }
    static String canonicalWorkflow(JSONObject value,int bytes) {
        RunJsonBoundary.checkSchemaAware(value,bytes);return encoded(value,bytes);
    }
    static String canonicalSparkWire(JSONObject value,int bytes) {
        RunJsonBoundary.checkSparkWire(value,bytes);return encoded(value,bytes);
    }
    private static String encoded(Object value,int bytes) {
        String result=JSON.toJSONString(ordered(value),JSONWriter.Feature.WriteMapNullValue);
        if(result.getBytes(StandardCharsets.UTF_8).length>bytes)throw error("模型协议JSON超出允许大小",413);return result;
    }
    public static JSONObject copy(JSONObject value) {return JSON.parseObject(canonical(value,REQUEST_BYTES));}
    public static String sha(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256不可用",e);}
    }
    public static long integer(Object value,long min,long max,String field) {
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))throw error(field+"必须为JSON整数",400);
        long number=((Number)value).longValue();if(number<min || number>max)throw error(field+"超出范围",400);return number;
    }
    public static String text(Object value,int max,String field) {
        if(!(value instanceof String string) || string.isEmpty() || string.codePointCount(0,string.length())>max || string.codePoints().anyMatch(c->Character.isISOControl(c) || (c>=0xD800 && c<=0xDFFF)))throw error(field+"无效",400);return string;
    }
    public static ServiceException error(String message,int code){return new ServiceException(message,code);}
}
