package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定预算、真实JSON与敏感字段边界；不把远端异常正文写入运行事件。 */
public final class RunJsonBoundary {
    private RunJsonBoundary() {}
    private static final Set<String> SECRET_KEYS=Set.of("password","passwd","secret","apikey","apisecret","token","accesstoken","refreshtoken","authorization","privatekey","clientsecret");
    public static void check(Object value,int maxBytes) {
        walk(value,0);
        if(com.alibaba.fastjson2.JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8).length>maxBytes) throw new ServiceException("运行JSON超出允许大小",413);
    }
    private static void walk(Object value,int depth) {
        if(depth>48) throw new ServiceException("运行JSON嵌套过深",400);
        if(value instanceof Map<?,?> map) {
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key)) throw new ServiceException("运行JSON字段名无效",400);
                String normalized=key.toLowerCase(java.util.Locale.ROOT).replace("_","").replace("-","");
                if(SECRET_KEYS.contains(normalized) && entry.getValue()!=null && !"".equals(entry.getValue())) throw new ServiceException("运行事件或快照不得携带凭据字段",400);
                walk(entry.getValue(),depth+1);
            }
        } else if(value instanceof List<?> list) { for(Object item:list) walk(item,depth+1); }
        else if(value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new ServiceException("运行字段必须为JSON值",400);
        if(value instanceof Double number && !Double.isFinite(number)) throw new ServiceException("运行数值无效",400);
        if(value instanceof Float number && !Float.isFinite(number)) throw new ServiceException("运行数值无效",400);
    }
    public static String canonical(JSONObject object) {
        return new String(Base64.getDecoder().decode(DslCodec.canonicalDslBase64(object)),StandardCharsets.UTF_8);
    }
    public static String hash(JSONObject object) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(object).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("运行环境缺少SHA-256",ex); }
    }
    public static void fields(JSONObject body,Set<String> required,Set<String> optional) {
        if(body==null || !body.keySet().containsAll(required) || body.keySet().stream().anyMatch(k->!required.contains(k)&&!optional.contains(k))) throw new ServiceException("运行协议字段不完整或包含未知字段",400);
    }
}
