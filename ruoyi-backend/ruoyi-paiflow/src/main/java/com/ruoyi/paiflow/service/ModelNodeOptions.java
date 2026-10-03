package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原Pydantic2.9.2 BaseLLMNode有效值；版本编译和逐次调用共用同一转换。 */
public final class ModelNodeOptions {
    private ModelNodeOptions() {}
    private static Object field(JSONObject values,String name,Object fallback){return values.containsKey(name)?values.get(name):fallback;}
    private static boolean underscores(String text){for(int i=0;i<text.length();i++)if(text.charAt(i)=='_' && (i==0 || i+1==text.length() || text.charAt(i-1)<'0' || text.charAt(i-1)>'9' || text.charAt(i+1)<'0' || text.charAt(i+1)>'9'))return false;return true;}
    static double floating(Object value,String name){
        try{
            double number;
            if(value instanceof Boolean b)number=b?1.0:0.0;
            else if(value instanceof Number n)number=n.doubleValue();
            else if(value instanceof String s){s=s.strip();if(!underscores(s))throw new NumberFormatException();s=s.replace("_","");if(!s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))throw new NumberFormatException();number=Double.parseDouble(s);}
            else throw new NumberFormatException();
            if(!Double.isFinite(number))throw new NumberFormatException();return number;
        }catch(NumberFormatException ex){throw error(name+"必须为有效数字",422);}
    }
    static BigInteger integer(Object value,String name){
        try{
            if(value instanceof Boolean b)return b?BigInteger.ONE:BigInteger.ZERO;
            if(value instanceof BigInteger n)return n;
            if(value instanceof Number n)return new BigDecimal(n.toString()).toBigIntegerExact();
            if(value instanceof String s){s=s.strip();if(!s.matches("[+-]?[0-9][0-9_]*(?:\\.[0-9][0-9_]*)?") || !underscores(s))throw new NumberFormatException();return new BigDecimal(s.replace("_","")).toBigIntegerExact();}
            throw new NumberFormatException();
        }catch(ArithmeticException|NumberFormatException ex){throw error(name+"必须为有效整数",422);}
    }
    private static Object jsonInteger(BigInteger number){if(number.bitLength()<32)return number.intValue();if(number.bitLength()<64)return number.longValue();return number;}
    static boolean bool(Object value,String name){
        if(value instanceof Boolean b)return b;
        if(value instanceof Number n){try{var d=new BigDecimal(n.toString());if(d.compareTo(BigDecimal.ZERO)==0)return false;if(d.compareTo(BigDecimal.ONE)==0)return true;}catch(NumberFormatException ignored){}}
        if(value instanceof String s){s=s.toLowerCase(Locale.ROOT);if(Set.of("0","off","f","false","n","no").contains(s))return false;if(Set.of("1","on","t","true","y","yes").contains(s))return true;}
        throw error(name+"必须为有效布尔值",422);
    }
    private static int responseFormat(Object value){
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger))throw error("respFormat必须为整数0、1或2",422);
        var n=integer(value,"respFormat");if(n.signum()<0 || n.compareTo(BigInteger.TWO)>0)throw error("respFormat必须为整数0、1或2",422);return n.intValue();
    }
    public static JSONObject effective(JSONObject raw){
        if(raw==null)throw error("模型节点参数必须为对象",422);
        double temperature=floating(field(raw,"temperature",1.0),"temperature");if(temperature<=0 || temperature>1)throw error("temperature必须大于0且不超过1",422);
        BigInteger max=integer(field(raw,"maxTokens",2048),"maxTokens"),top=integer(field(raw,"topK",3),"topK");if(max.signum()<=0)throw error("maxTokens必须大于零",422);
        boolean search=bool(field(raw,"searchDisable",true),"searchDisable"),history=bool(field(raw,"enableChatHistory",false),"enableChatHistory");
        Object value=field(raw,"enableChatHistoryV2",new JSONObject());if(!(value instanceof Map<?,?> v2))throw error("历史配置必须为对象",422);
        boolean enabled=bool(v2.containsKey("isEnabled")?v2.get("isEnabled"):false,"enableChatHistoryV2.isEnabled");BigInteger rounds=integer(v2.containsKey("rounds")?v2.get("rounds"):1,"rounds");if(rounds.signum()<=0)throw error("历史轮数须大于零",422);
        Object patch=field(raw,"patch_id",List.of()),extra=field(raw,"extraParams",new JSONObject());if(!(patch instanceof List<?>))throw error("patch_id必须为数组",422);if(!(extra instanceof Map<?,?>))throw error("extraParams必须为对象",422);
        return JSONObject.of("temperature",temperature,"maxTokens",jsonInteger(max),"topK",jsonInteger(top),"respFormat",responseFormat(field(raw,"respFormat",0)),"searchDisable",search,"enableChatHistory",history,"enableChatHistoryV2",JSONObject.of("isEnabled",enabled,"rounds",jsonInteger(rounds)),"patch_id",patch,"extraParams",extra);
    }
}
