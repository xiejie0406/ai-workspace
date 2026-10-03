package com.ruoyi.aipicture.tool;

import java.util.*;
import com.ruoyi.aipicture.domain.*;

/** 执行本项目导出的 JSON Schema 2020-12 子集，规则取唯一契约，不复制工具参数范围。 */
public final class SchemaValidator {
    public void validate(Object value,Map<String,Object> schema) { validate(value,schema,schema,"参数"); }
    private void validate(Object value,Map<String,Object> s,Map<String,Object> root,String path) {
        if(s.containsKey("$ref")) {
            String ref=Json.string(s.get("$ref"));
            if(!ref.startsWith("#/")) throw AiPictureException.invalid("不允许远程 Schema 引用");
            Object resolved=root;
            for(String part:ref.substring(2).split("/")) resolved=Json.map(resolved).get(part.replace("~1","/").replace("~0","~"));
            if(resolved==null) throw AiPictureException.invalid("Schema 引用不存在");
            validate(value,Json.map(resolved),root,path);
        }
        for(Object child:Json.list(s.get("allOf"))) validate(value,Json.map(child),root,path);
        for(String keyword:List.of("anyOf","oneOf")) if(s.containsKey(keyword)) {
            int matched=0;
            for(Object child:Json.list(s.get(keyword))) try { validate(value,Json.map(child),root,path); matched++; } catch(AiPictureException ignored) { }
            if(matched==0 || keyword.equals("oneOf")&&matched!=1) throw AiPictureException.invalid(path+" 不符合 "+keyword);
        }
        if(s.containsKey("const")&&!Objects.equals(value,s.get("const"))) throw AiPictureException.invalid(path+" 固定值不匹配");
        if(s.containsKey("enum")&&!Json.list(s.get("enum")).contains(value)) throw AiPictureException.invalid(path+" 不在允许选项内");
        Object type=s.get("type");
        if(type!=null) {
            var types=type instanceof List<?> ? Json.list(type) : List.of(type);
            if(types.stream().noneMatch(t->isType(value,Json.string(t)))) throw AiPictureException.invalid(path+" 类型错误");
        }
        if(value instanceof Map<?,?>) {
            var object=Json.map(value); var properties=Json.map(s.get("properties"));
            for(Object key:Json.list(s.get("required"))) if(!object.containsKey(Json.string(key))) throw AiPictureException.invalid(path+" 缺少 "+key);
            for(var entry:object.entrySet()) {
                if(properties.containsKey(entry.getKey())) validate(entry.getValue(),Json.map(properties.get(entry.getKey())),root,path+"."+entry.getKey());
                else if(Boolean.FALSE.equals(s.get("additionalProperties"))) throw AiPictureException.invalid(path+" 未知字段 "+entry.getKey());
                else if(s.get("additionalProperties") instanceof Map<?,?>) validate(entry.getValue(),Json.map(s.get("additionalProperties")),root,path+"."+entry.getKey());
            }
        }
        if(value instanceof List<?> list) {
            range(list.size(),s,"minItems","maxItems",path);
            if(Boolean.TRUE.equals(s.get("uniqueItems"))&&list.stream().map(Json::write).distinct().count()!=list.size()) throw AiPictureException.invalid(path+" 不能重复");
            if(s.get("items") instanceof Map<?,?>) for(int i=0;i<list.size();i++) validate(list.get(i),Json.map(s.get("items")),root,path+"["+i+"]");
        }
        if(value instanceof String str) {
            range(str.codePointCount(0,str.length()),s,"minLength","maxLength",path);
            if(s.containsKey("pattern")&&!java.util.regex.Pattern.compile(Json.string(s.get("pattern"))).matcher(str).find()) throw AiPictureException.invalid(path+" 格式错误");
            if("uuid".equals(s.get("format"))) Json.uuid(str);
        }
        if(value instanceof Number n) {
            if(!Double.isFinite(n.doubleValue())) throw AiPictureException.invalid(path+" 必须为有限数值");
            range(n.doubleValue(),s,"minimum","maximum",path);
            if(s.get("exclusiveMinimum") instanceof Number min&&n.doubleValue()<=min.doubleValue()) throw AiPictureException.invalid(path+" 过小");
            if(s.get("exclusiveMaximum") instanceof Number max&&n.doubleValue()>=max.doubleValue()) throw AiPictureException.invalid(path+" 过大");
        }
    }
    private void range(double v,Map<String,Object> s,String min,String max,String path) {
        if(s.get(min) instanceof Number lower&&v<lower.doubleValue()||s.get(max) instanceof Number upper&&v>upper.doubleValue()) throw AiPictureException.invalid(path+" 超出范围");
    }
    private boolean isType(Object v,String type) {
        return switch(type) {
            case "null"->v==null; case "object"->v instanceof Map<?,?>; case "array"->v instanceof List<?>;
            case "string"->v instanceof String; case "boolean"->v instanceof Boolean; case "number"->v instanceof Number;
            case "integer"->v instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()==Math.rint(n.doubleValue());
            default->false;
        };
    }
}
