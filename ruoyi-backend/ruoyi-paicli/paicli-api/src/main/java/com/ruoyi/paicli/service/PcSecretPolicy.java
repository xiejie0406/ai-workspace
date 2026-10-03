package com.ruoyi.paicli.service;

import java.util.*;
import java.util.regex.Pattern;

/** 对不可信配置和离线bundle独立检查，不能依赖导出端已经脱敏。 */
public final class PcSecretPolicy {
    private static final Set<String> KEYS=Set.of("apikey","apisecret","accesskey","authkey","secretkey","clientkey","accesstoken","refreshtoken","bottoken","password","passwd","privatekey","devicetoken","sessiontoken","bridgetoken","token","secret","clientsecret","credential","credentials","authorization","proxyauthorization","cookie","setcookie","env","environment");
    private static final Pattern TEXT=Pattern.compile("(?i)(?:\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}|\\b(?:sk-|pc[dsw]_)[A-Za-z0-9_-]{12,}|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|-----BEGIN(?: [A-Z]+)? PRIVATE KEY-----)");
    private static final Pattern FLAG=Pattern.compile("(?i)^--?(?:(?:api|access|auth|client|secret|private)[-_]?(?:key|secret|token)|(?:refresh|bearer)[-_]?token|token|secret|password|passwd|credentials?)(?:[=\\s].*)?$");
    private static final Pattern QUERY=Pattern.compile("[?&]([^&=#\\s]+)=");
    private PcSecretPolicy() {}
    public static boolean contains(Object value){
        if(value instanceof Map<?,?> map){for(var entry:map.entrySet()){String key=entry.getKey().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");Object child=entry.getValue();if(KEYS.contains(key)&&child!=null&&!child.toString().isBlank()&&!child.equals(Map.of())&&!child.equals(List.of()))return true;if(contains(child))return true;}}
        else if(value instanceof List<?> list){for(Object child:list)if(child instanceof String text&&FLAG.matcher(text.strip()).matches()||contains(child))return true;}
        else if(value instanceof String text){if(TEXT.matcher(text).find())return true;var queries=QUERY.matcher(text);while(queries.find()){String key=queries.group(1);try{for(int decode=0;decode<2;decode++)key=java.net.URLDecoder.decode(key,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException ignored){continue;}if(KEYS.contains(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","")))return true;}}
        return false;
    }
}
