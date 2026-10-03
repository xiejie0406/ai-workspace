package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.util.List;

/**
 * 原Toolkit WorkflowService的纯画布字段转换。仅修改执行副本，不读库、配置、凭据或Provider。
 * 保留原数组/属性/描述语义，不把原未实现的嵌套required或资源参数绑定补成假能力。
 */
public final class CanvasExecutionCompiler {
    private static final List<String> EMPTY_KEYS=List.of("text","content","value","title","name","message","prompt","url","fileUrl","path");
    private CanvasExecutionCompiler() {}
    static JSONObject data(JSONObject original,boolean canvas) {
        if(original==null)return null;
        JSONObject result=JSON.parseObject(original.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        if(canvas && result.containsKey("label") && result.get("nodeMeta") instanceof JSONObject meta)meta.put("aliasName",result.get("label"));
        if(canvas)result.remove("label");
        for(String direction:List.of("inputs","outputs"))if(result.get(direction) instanceof JSONArray values) {
            JSONArray converted=new JSONArray();
            for(Object raw:values) {
                if(!(raw instanceof JSONObject item) || !(item.get("schema") instanceof JSONObject schema)){converted.add(raw);continue;}
                if(direction.equals("inputs") && canvas && schema.get("value") instanceof JSONObject value && empty(value.get("content"),0))continue;
                boolean business=canvas || schema.get("type") instanceof String type && (type.startsWith("array-") || "time".equalsIgnoreCase(type)) || schema.get("properties") instanceof JSONArray;
                convertSchema(schema,direction.equals("inputs"),business);converted.add(item);
            }
            result.put(direction,converted);
        }
        if(result.get("nodeParam") instanceof JSONObject params)params.remove("configs");
        if(result.get("retryConfig") instanceof JSONObject retry && retry.get("customOutput") instanceof String custom && !custom.isBlank()) {
            try {JSONObject parsed=JSON.parseObject(custom);if(parsed==null)throw new IllegalArgumentException();retry.put("customOutput",parsed);}
            catch(RuntimeException ex){throw new ServiceException("原重试customOutput必须为JSON对象字符串",422);}
        }
        return result;
    }
    static String handle(String handle,boolean canvas) {
        if(canvas && handle!=null && !handle.isBlank() && (handle.contains("intent-one-of") || handle.contains("branch_one_of") || handle.contains("fail_one_of")))return "intent_chain|"+handle;
        return handle;
    }
    private static void convertSchema(JSONObject schema,boolean input,boolean business) {
        Object rawType=schema.get("type");String type=rawType instanceof String t?t:null;
        if(input && type!=null && "time".equalsIgnoreCase(type)){type="string";schema.put("type",type);}
        boolean typedArray=type!=null && type.startsWith("array-");
        if(typedArray) {
            String[] parts=type.split("-");String itemType=parts.length>1?parts[1]:"object";schema.put("type","array");
            JSONObject items=JSONObject.of("type",itemType);
            if(!input && itemType.equals("object")) {
                JSONObject properties=properties(schema.get("properties"),0);if(properties!=null)items.put("properties",properties);
                JSONArray required=new JSONArray();if(schema.get("properties") instanceof JSONArray list)for(Object raw:list){JSONObject property=property(raw);if(Boolean.TRUE.equals(property.get("required")))required.add(property.get("name"));}
                items.put("required",required);
            }
            schema.put("items",items);schema.remove("properties");
        } else if(input && schema.get("properties") instanceof JSONArray) {
            schema.remove("properties"); // 原List<BizProperty>不能BeanUtils复制到core Map，input不递归转换。
        } else if(!input && schema.get("properties") instanceof JSONArray list) {
            JSONObject map=properties(list,0);if(map==null)schema.remove("properties");else schema.put("properties",map);
        }
        // 原outputCopy仅当description==null时把default转为描述，并清除default；空描述不触发。
        if(business && !input && schema.get("description")==null && schema.containsKey("default")) {
            Object defaultValue=schema.remove("default");if(defaultValue!=null)schema.put("description",String.valueOf(defaultValue));
        }
    }
    private static JSONObject properties(Object raw,int depth) {
        if(depth>64)throw new ServiceException("原画布属性嵌套超过编译预算",413);
        if(raw==null)return null;
        if(raw instanceof JSONObject map)return JSON.parseObject(map.toJSONString(JSONWriter.Feature.WriteMapNullValue)); // 已编译JSON Schema不再当BizProperty列表转换。
        if(!(raw instanceof JSONArray list))throw new ServiceException("原画布properties须为属性列表",422);
        if(list.isEmpty())return null;JSONObject result=new JSONObject();
        for(Object item:list) {
            JSONObject source=property(item);if(!(source.get("name") instanceof String name) || name.isBlank() || !(source.get("type") instanceof String type))throw new ServiceException("原画布属性须有名称与类型",422);
            JSONObject property=new JSONObject();
            if(type.startsWith("array-")) {
                String[] parts=type.split("-");if(parts.length<2)throw new ServiceException("原嵌套数组属性缺少元素类型",422);JSONObject arrayItems=JSONObject.of("type",parts[1]);
                JSONObject child=properties(source.get("properties"),depth+1);if(child!=null)arrayItems.put("properties",child);property.put("type","array");property.put("items",arrayItems);
            } else {property.put("type",type);JSONObject child=properties(source.get("properties"),depth+1);if(child!=null)property.put("properties",child);}
            result.put(name,property); // 原Map按name后项覆盖；不复制BizProperty default/id/description/required。
        }
        return result;
    }
    private static JSONObject property(Object raw){if(!(raw instanceof JSONObject object))throw new ServiceException("原画布属性必须为对象",422);return object;}
    private static boolean empty(Object content,int depth) {
        if(depth>64)throw new ServiceException("原画布输入嵌套超过编译预算",413);
        if(content==null)return true;if(content instanceof String text)return text.isBlank();
        if(content instanceof JSONArray array){for(Object value:array)if(!empty(value,depth+1))return false;return true;}
        if(content instanceof JSONObject object) {
            if(object.isEmpty())return true;
            for(String key:EMPTY_KEYS)if(object.get(key)!=null && !String.valueOf(object.get(key)).isBlank())return false;
            if(object.get("default")!=null && !empty(object.get("default"),depth+1))return false;
            for(Object value:object.values())if(value instanceof String text && !text.isBlank())return false;return true;
        }
        return String.valueOf(content).isBlank();
    }
}
