package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;

/** 按原VariablePool解析固定输入；值只能来自固定根输入、字面量或中心已接受的完成事件。 */
final class WorkflowInputSources {
    private WorkflowInputSources() {}

    static LinkedHashMap<String,Object> values(JSONObject execution,JSONObject node,JSONObject input,JSONArray events) {
        return values(execution,node,input,events,null);
    }

    /** verifiedOutputs只能由CompletedOutputSources生成：包含真实完成或已证明未选支路的原池默认。 */
    static LinkedHashMap<String,Object> values(JSONObject execution,JSONObject node,JSONObject input,JSONArray events,Map<String,JSONObject> verifiedOutputs) {
        var values=new LinkedHashMap<String,Object>();
        var data=node.getJSONObject("data");
        var fields=data==null?null:data.getJSONArray("inputs");
        if(fields==null) return values;
        for(Object raw:fields) {
            if(!(raw instanceof JSONObject field) || !(field.get("name") instanceof String name) || name.isBlank() || values.containsKey(name))
                throw DataResourceService.fail("固定节点输入名称无效或重复",409);
            var schema=field.getJSONObject("schema");
            var binding=schema==null?null:schema.getJSONObject("value");
            if(binding==null){if(!ModelNodeSemantics.isModelNode(node)||input==null||!input.containsKey(name))throw DataResourceService.fail("固定节点输入来源缺失",403);values.put(name,input.get(name));continue;}
            Object resolved;
            if("literal".equals(binding.get("type"))) resolved=literal(binding.get("content"),schema.getString("type"));
            else if("ref".equals(binding.get("type"))) {
                var ref=binding.getJSONObject("content");
                if(ref==null || !(ref.get("nodeId") instanceof String source) || !(ref.get("name") instanceof String key) || key.isEmpty())
                    throw DataResourceService.fail("固定节点引用形状无效",409);
                var sourceNode=DataWorkerOperationService.find(execution,source);
                var parts=key.split("\\.",-1);
                JSONObject outputs;
                if(source.startsWith("node-start::")) {
                    outputs=input;
                } else {
                    if(sourceNode==null || !upstream(execution,source,node.getString("id")))
                        throw DataResourceService.fail("输入引用不属于当前节点上游",403);
                    outputs=verifiedOutputs==null?completed(source,events):verifiedOutputs.get(source);
                    if(outputs==null)throw DataResourceService.fail("引用缺少已验证的原输出来源",403);
                }
                var outputSchema=outputSchema(sourceNode,parts[0]);
                boolean implicitError="errorCode".equals(parts[0]) || "errorMessage".equals(parts[0]);
                if(!source.startsWith("node-start::") && outputSchema==null)
                    throw DataResourceService.fail("引用名称不属于固定输出或原错误变量",403);
                Object initial;
                if(implicitError && verifiedOutputs!=null && !source.startsWith("node-start::")) {
                    if(!outputs.containsKey(parts[0]))throw DataResourceService.fail("未执行节点没有原错误变量",403);
                    initial=outputs.get(parts[0]);
                }
                else if(implicitError) initial=zero(outputSchema.getString("type"));
                else if(outputs!=null && outputs.containsKey(parts[0])) initial=outputs.get(parts[0]);
                else if(outputSchema!=null)
                    initial=outputSchema.containsKey("default")?outputSchema.get("default"):zero(outputSchema.getString("type"));
                else throw DataResourceService.fail("节点引用没有已确认输入或完成输出",403);
                if(parts.length>1 && outputSchema==null) throw DataResourceService.fail("嵌套引用缺少固定输出Schema",409);
                resolved=path(initial,outputSchema,parts,1);
            } else throw DataResourceService.fail("节点输入来源类型无效",422);
            values.put(name,resolved);
        }
        RunJsonBoundary.check(values,RunProtocolLimits.INPUT_BYTES);
        return values;
    }

    private static JSONObject completed(String source,JSONArray events) {
        JSONObject outputs=null;
        for(Object raw:events) {
            var event=(JSONObject)raw;
            if(!source.equals(event.get("nodeId")) || event.get("iterationId")!=null || !"node.completed".equals(event.get("eventType"))) continue;
            var payload=event.getJSONObject("payload");
            var frame=payload==null?null:payload.getJSONObject("legacyFrame");
            var step=frame==null?null:frame.getJSONObject("workflow_step");
            var completed=step==null?null:step.getJSONObject("node");
            if(completed!=null && source.equals(completed.get("id"))) outputs=completed.getJSONObject("outputs");
        }
        if(outputs==null) throw DataResourceService.fail("引用缺少中心已接受的上游完成事件",403);
        return outputs;
    }

    private static boolean upstream(JSONObject execution,String source,String target) {
        var edges=execution.getJSONArray("edges");
        if(edges==null || source.equals(target)) return false;
        var visited=new HashSet<String>();var queue=new ArrayDeque<String>();queue.add(source);
        while(!queue.isEmpty()) {
            String current=queue.remove();if(!visited.add(current))continue;
            for(Object raw:edges) {
                var edge=(JSONObject)raw;
                Object from=edge.containsKey("sourceNodeId")?edge.get("sourceNodeId"):edge.get("source"),to=edge.containsKey("targetNodeId")?edge.get("targetNodeId"):edge.get("target");
                if(current.equals(from) && to instanceof String next) {
                    if(target.equals(next))return true;queue.add(next);
                }
            }
        }
        return false;
    }

    private static JSONObject outputSchema(JSONObject node,String name) {
        // 当前准入成功路径无主动重试替代输出；原池在add_variable中写入0和空串。
        if("errorCode".equals(name))return JSONObject.of("type","integer");
        if("errorMessage".equals(name))return JSONObject.of("type","string");
        if(node==null || node.getJSONObject("data")==null)return null;
        var outputs=node.getJSONObject("data").getJSONArray("outputs");
        if(outputs!=null)for(Object raw:outputs) {
            var output=(JSONObject)raw;
            if(name.equals(output.get("name")))return output.getJSONObject("schema");
        }
        return null;
    }

    /** 原数组对象引用是属性投影，不是数组下标；不存在的已声明属性取原类型默认值。 */
    private static Object path(Object value,JSONObject schema,String[] parts,int index) {
        if(index==parts.length)return value;
        String type=schema.getString("type"),key=parts[index];
        if("array".equals(type)) {
            var items=schema.getJSONObject("items");
            if(items==null || !"object".equals(items.get("type")))return value;
            var property=property(items,key);
            if(!(value instanceof List<?> array))throw DataResourceService.fail("原数组引用值不是数组",422);
            var result=new JSONArray();
            for(Object raw:array) {
                if(!(raw instanceof Map<?,?> object))throw DataResourceService.fail("原数组对象引用值无效",422);
                result.add(path(object.containsKey(key)?object.get(key):zero(property.getString("type")),property,parts,index+1));
            }
            return result;
        }
        if("object".equals(type)) {
            var property=property(schema,key);
            if(!(value instanceof Map<?,?> object))throw DataResourceService.fail("原对象引用值不是对象",422);
            return path(object.containsKey(key)?object.get(key):zero(property.getString("type")),property,parts,index+1);
        }
        return value;
    }

    private static JSONObject property(JSONObject schema,String key) {
        var properties=schema.getJSONObject("properties");
        if(properties==null || !(properties.get(key) instanceof JSONObject value))throw DataResourceService.fail("引用属性不属于固定输出Schema",422);
        return value;
    }

    static Object zero(String type) {
        return switch(type==null?"":type) {
            case "string"->"";case "integer"->0;case "number"->0.0;case "boolean"->false;
            case "array"->new JSONArray();case "object"->new JSONObject();default->null;
        };
    }

    static String pythonString(Object value,boolean nested) {
        if(value instanceof PythonLiteralValue.Tuple tuple) {
            String contents=String.join(", ",tuple.stream().map(item->pythonString(item,true)).toList());
            return "("+contents+(tuple.size()==1?",":"")+")";
        }
        if(value instanceof List<?> list)return "["+String.join(", ",list.stream().map(item->pythonString(item,true)).toList())+"]";
        if(value instanceof Map<?,?> map){var entries=new ArrayList<String>();map.forEach((key,item)->entries.add(pythonString(key,true)+": "+pythonString(item,true)));return "{"+String.join(", ",entries)+"}";}
        return ModelMessageSources.pythonString(value,nested);
    }

    private static Object literal(Object content,String type) {
        if(!(content instanceof String || content instanceof Number || content instanceof Boolean))throw DataResourceService.fail("原协议字面量只允许标量content",422);
        // Python bool属于int；number同时接受int和float。原转换不再次强制转换结果类型。
        boolean matched=switch(type==null?"":type) {
            case "string"->content instanceof String;
            case "integer"->content instanceof Byte || content instanceof Short || content instanceof Integer || content instanceof Long || content instanceof java.math.BigInteger || content instanceof Boolean;
            case "number"->content instanceof Number || content instanceof Boolean;
            case "boolean"->content instanceof Boolean;
            case "array"->content instanceof List<?>;case "object"->content instanceof Map<?,?>;default->false;
        };
        if(matched)return content;
        if("boolean".equals(type))return !"false".equals(content) && !"False".equals(content);
        if(!(content instanceof String text))throw DataResourceService.fail("原字面量类型转换失败",422);
        return PythonLiteralValue.parse(text);
    }
}
