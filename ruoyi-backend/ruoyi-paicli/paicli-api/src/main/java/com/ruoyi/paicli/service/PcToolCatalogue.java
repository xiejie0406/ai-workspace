package com.ruoyi.paicli.service;
import com.ruoyi.paicli.domain.PcException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import java.util.*;

/** 原17工具的真实schema是登记依据；动态MCP必须使用当前批准登记的schema。 */
public final class PcToolCatalogue {
    private static final Map<String,Map<String,Object>> BUILTIN=load();
    private static Map<String,Map<String,Object>> load(){try(var in=PcToolCatalogue.class.getResourceAsStream("/paicli/tools-v1.json")){var rows=new ObjectMapper().readValue(in,new TypeReference<List<Map<String,Object>>>(){});Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(var row:rows)result.put(PcJson.text(row,"name"),row);return Collections.unmodifiableMap(result);}catch(Exception e){throw new IllegalStateException("原工具登记schema缺失",e);}}
    public static Map<String,Object> definition(String name){return BUILTIN.get(name);}
    public static void validate(Map<String,Object> arguments,Map<String,Object> schema){validateValue(arguments,schema,"arguments");}
    private static void validateValue(Object value,Map<String,Object> schema,String path){String type=PcJson.text(schema,"type");boolean valid=switch(type){case "string"->value instanceof String;case "integer"->value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger;case "number"->value instanceof Number;case "boolean"->value instanceof Boolean;case "object"->value instanceof Map<?,?>;case "array"->value instanceof List<?>;case "null"->value==null;case ""->true;default->false;};if(!valid)throw PcException.invalid("工具字段类型非法："+path);if(schema.get("enum") instanceof List<?> allowed&&!allowed.contains(value))throw PcException.invalid("工具字段不在允许值中："+path);
        if(value instanceof Map<?,?> map){Map<String,Object> props=schema.get("properties") instanceof Map<?,?>?PcJson.object(schema,"properties"):Map.of();if(schema.get("required") instanceof List<?> required)for(Object key:required)if(!map.containsKey(key))throw PcException.invalid("工具缺少必填字段："+path+"."+key);for(var entry:map.entrySet()){String key=String.valueOf(entry.getKey());if(!props.containsKey(key)){if(Boolean.FALSE.equals(schema.get("additionalProperties")))throw PcException.invalid("工具包含未登记字段："+path+"."+key);continue;}if(props.get(key) instanceof Map<?,?>)validateValue(entry.getValue(),PcJson.object(props,key),path+"."+key);}}
        if(value instanceof List<?> list&&schema.get("items") instanceof Map<?,?>){for(int i=0;i<list.size();i++)validateValue(list.get(i),PcJson.object(schema,"items"),path+"["+i+"]");}
        if(value instanceof String text&&text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>5*1024*1024)throw new PcException(413,"PC_TOOL_FIELD_LIMIT","单个工具文本字段超过原5MB上限");
    }
}
