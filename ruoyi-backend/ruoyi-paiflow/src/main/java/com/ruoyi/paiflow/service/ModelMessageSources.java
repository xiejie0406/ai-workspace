package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;
import java.util.regex.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;

/** 从中心根输入、固定引用和已接受完成事件复原原LLM提示；不信任Worker自报messages。 */
final class ModelMessageSources {
    private ModelMessageSources() {}
    static LinkedHashMap<String,Object> values(JSONObject node,JSONObject input,JSONArray events){
        var data=node.getJSONObject("data");var p=data.getJSONObject("nodeParam");var values=new LinkedHashMap<String,Object>();
        var fields=data.getJSONArray("inputs");if(fields!=null)for(Object raw:fields){var field=(JSONObject)raw;String name=field.getString("name");
            var schema=field.getJSONObject("schema");var value=schema==null?null:schema.getJSONObject("value");Object resolved;
            if(value==null){if(!input.containsKey(name))throw error("模型输入缺少真实中心来源",403);resolved=input.get(name);}
            else if("literal".equals(value.getString("type")))resolved=value.get("content");
            else if("ref".equals(value.getString("type"))){var ref=value.getJSONObject("content");if(ref==null)throw error("模型引用形状无效",403);
                String source=ref.getString("nodeId"),key=ref.getString("name");if(source==null || key==null)throw error("模型引用形状无效",403);
                if(source.startsWith("node-start::")){if(!input.containsKey(key))throw error("模型根引用没有真实输入",403);resolved=input.get(key);}
                else{JSONObject outputs=null;for(Object e:events){var event=(JSONObject)e;if(Objects.equals(source,event.getString("nodeId")) && "node.completed".equals(event.getString("eventType")) && event.get("iterationId")==null){
                        var payload=event.getJSONObject("payload");var legacy=payload==null?null:payload.getJSONObject("legacyFrame");var step=legacy==null?null:legacy.getJSONObject("workflow_step");var completed=step==null?null:step.getJSONObject("node");
                        if(completed!=null && Objects.equals(source,completed.getString("id")))outputs=completed.getJSONObject("outputs");}}
                    if(outputs==null || !outputs.containsKey(key))throw error("模型上游引用缺少已确认完成输出",403);resolved=outputs.get(key);}
            }else throw error("模型输入来源类型尚未接通",503);
            if(values.containsKey(name))throw error("模型输入名称重复",409);values.put(name,resolved);
        }
        return values;
    }
    static JSONArray llm(JSONObject node,JSONObject input,JSONArray events){
        return llm(node,values(node,input,events));
    }
    private static JSONArray llm(JSONObject node,Map<String,Object> values){
        var p=node.getJSONObject("data").getJSONObject("nodeParam");
        var messages=new JSONArray();String system=render(p.getString("systemTemplate"),values),user=render(p.getString("template"),values);
        if(!system.isEmpty())messages.add(JSONObject.of("role","system","content",system));messages.add(JSONObject.of("role","user","content",user));return messages;
    }
    static JSONArray expected(JSONObject node,JSONObject input,JSONArray events,JSONArray qaSources){
        return expected(node,values(node,input,events),qaSources);
    }
    static JSONArray expected(JSONObject node,Map<String,Object> values,JSONArray qaSources){
        values=orderedValues(node,values);String type=ModelNodeSemantics.type(node);if("spark-llm".equals(type))return llm(node,values);
        var p=node.getJSONObject("data").getJSONObject("nodeParam");String text;
        if("extractor-parameter".equals(type)){if(values.isEmpty())throw error("参数提取缺少原首输入",422);String user=pythonString(values.values().iterator().next(),false);
            text="function_call".equals(ModelNodeSemantics.mode(node))?user:prompt("pe").replace("{{histories}}","").replace("{{instruction}}",p.getString("instruction")).replace("{{json_structure}}",pythonJson(schema(node,false))).replace("{{user_text}}",user);
        }else if("decision-making".equals(type)){
            var chains=p.getJSONArray("intentChains");String prefix=p.getString("promptPrefix");String all=String.join("",values.values().stream().map(v->pythonString(v,false)).toList());
            if("function_call".equals(ModelNodeSemantics.mode(node)))text=render(prefix,values);
            else if(p.getIntValue("reasonMode")==1){var categories=new JSONArray();for(Object raw:chains){var c=(JSONObject)raw;boolean fallback=c.getIntValue("intentType")==1;categories.add(JSONObject.of("category_id",c.get("id"),"category_name",fallback?"DEFAULT":c.get("name"),"category_desc",fallback?"Default intent when no other intent matches":c.get("description")));}
                var destinations=JSONObject.of("input_text",all,"categories",categories,"classification_instructions",List.of(prefix.isEmpty()?"Classify text based on user input and provided classification information":prefix));text=prompt("decision-prompt").replace("{{destinations}}",pythonJson(destinations)).replace("{{histories}}","");
            }else{var destinations=new ArrayList<String>();for(Object raw:chains){var c=(JSONObject)raw;if(c.getIntValue("intentType")!=1)destinations.add(c.getString("name")+": "+c.getString("description"));}text=prefix+"\n"+prompt("decision-normal").replace("{destinations}",String.join("\n",destinations)).replace("{{__input__}}",all);}
        }else if("question-answer".equals(type)){
            if(qaSources==null || qaSources.isEmpty())throw error("动态问答缺少中心消费回复与先前模型事实",403);var history=new JSONArray();String question=renderQuestion(p.getString("question"),values),user=null;
            for(int i=0;i<qaSources.size();i++){var source=qaSources.getJSONObject(i);if(source.getIntValue("ordinal")!=i+1 || !Objects.equals(source.get("question"),question) || !(source.get("reply") instanceof String reply))throw error("动态问答轮次题面与真实回复不一致",403);
                history.add(JSONObject.of("role","assistant","content",question));history.add(JSONObject.of("role","user","content",reply));user=reply;
                if(i+1<qaSources.size()){var prior=slotOutput(source.getString("priorModelOutput"));if(prior.getJSONObject("incomplete").isEmpty())throw error("已完整填槽不能签发另一问答轮次",403);question=prior.getString("content");}
            }
            text=prompt("qa").replace("{{histories}}",pythonJson(history)).replace("{{instruction}}",p.getString("instruction")).replace("{{json_structure}}",pythonJson(schema(node,true))).replace("{{user_text}}",user);
        }else throw error("模型节点消息生产尚未接通",503);
        return new JSONArray(List.of(JSONObject.of("role","user","content",text)));
    }
    /** 私有字符串作为候选指纹事实，保留原值内对象顺序和负零；公共请求不增加字段。 */
    static String verifiedInputsJson(Map<String,Object> values){RunJsonBoundary.check(values,REQUEST_BYTES);String json=JSON.toJSONString(values,JSONWriter.Feature.WriteMapNullValue);if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>REQUEST_BYTES)throw error("可信模型输入超出预算",413);return json;}
    static LinkedHashMap<String,Object> verifiedValues(JSONObject node,String json){
        var result=orderedValues(node,sourceObject(json));var fields=node.getJSONObject("data").getJSONArray("inputs");if(fields!=null)for(Object raw:fields){var field=(JSONObject)raw;var schema=field.getJSONObject("schema");var binding=schema==null?null:schema.getJSONObject("value");if(binding!=null&&"literal".equals(binding.get("type"))){String name=field.getString("name");var literalNode=JSONObject.of("id",node.get("id"),"data",JSONObject.of("inputs",new JSONArray(List.of(field))));Object actual=WorkflowInputSources.values(new JSONObject(),literalNode,new JSONObject(),new JSONArray()).get(name);if(!canonical(actual,REQUEST_BYTES).equals(canonical(result.get(name),REQUEST_BYTES)))throw error("可信模型字面量偏离固定节点",403);result.put(name,actual);}}return result;
    }
    private static LinkedHashMap<String,Object> orderedValues(JSONObject node,Map<String,Object> values){
        if(values==null)throw error("可信模型输入缺失",403);var result=new LinkedHashMap<String,Object>();var fields=node.getJSONObject("data").getJSONArray("inputs");if(fields!=null)for(Object raw:fields){var field=(JSONObject)raw;String name=field.getString("name");if(name==null||name.isBlank()||result.containsKey(name))throw error("固定模型输入名称无效或重复",409);if(!values.containsKey(name))throw error("可信模型输入字段缺失",403);result.put(name,values.get(name));}if(!result.keySet().equals(values.keySet()))throw error("可信模型输入含非固定字段",403);return result;
    }
    static JSONObject slotOutput(String value){if(value==null)throw error("重问缺少先前已完成模型原输出",403);var match=Pattern.compile("```json\\s*\\n?(.*?)\\n?```",Pattern.DOTALL).matcher(value);String json=match.find()?match.group(1).strip():value.strip();
        JSONObject result;try{result=JSON.parseObject(json);}catch(RuntimeException e){throw error("先前模型填槽输出不是合法JSON",403);}if(result==null)throw error("先前模型填槽输出必须为对象",403);
        if(!result.containsKey("content"))result.put("content","");if(!result.containsKey("role"))result.put("role","assistant");if(!result.containsKey("completed"))result.put("completed",new JSONObject());if(!result.containsKey("incomplete"))result.put("incomplete",new JSONObject());
        if(!(result.get("role") instanceof String) || !(result.get("content") instanceof String) || !(result.get("completed") instanceof JSONObject) || !(result.get("incomplete") instanceof JSONObject))throw error("先前模型填槽结构无效",403);return result;}
    static JSONObject schema(JSONObject node,boolean qa){var p=node.getJSONObject("data").getJSONObject("nodeParam");var schema=qa || !p.containsKey("fc_schema_params")?JSONObject.of("type","object","properties",new JSONObject(),"required",new JSONArray()):ModelOperationJson.copySchema(p.getJSONObject("fc_schema_params"));
        if(!(schema.get("properties") instanceof JSONObject properties) || !(schema.get("required") instanceof JSONArray required))throw error("原提取base schema缺少properties/required",422);
        var outputs=node.getJSONObject("data").getJSONArray("outputs");if(outputs!=null)for(Object raw:outputs){var out=(JSONObject)raw;String name=out.getString("name");if(qa && Set.of("query","content").contains(name))continue;var s=out.getJSONObject("schema");properties.put(name,JSONObject.of("type",s.get("type"),"description",s.get("description")));if(ModelNodeOptions.bool(out.getOrDefault("required",false),"output.required"))required.add(name);}return schema;
    }
    static JSONArray functions(JSONObject node){String t=ModelNodeSemantics.type(node);var p=node.getJSONObject("data").getJSONObject("nodeParam");var functions=new JSONArray();
        if("extractor-parameter".equals(t))functions.add(JSONObject.of("name","extractor_parameter","description","Extract corresponding parameters based on user's question","parameters",schema(node,false)));
        else if("decision-making".equals(t)){Object params=p.getOrDefault("fs_params",JSONObject.of("type","object","properties",JSONObject.of("next_inputs",JSONObject.of("type","string","description","User input content")),"required",List.of("next_inputs")));for(Object raw:p.getJSONArray("intentChains")){var c=(JSONObject)raw;functions.add(JSONObject.of("name",c.get("name"),"description",c.get("description"),"parameters",params));}}
        else throw error("该节点不属于原FunctionCall",503);return functions;
    }
    private static String prompt(String name){try(var stream=ModelMessageSources.class.getResourceAsStream("/paiflow/model-prompts/"+name+".txt")){if(stream==null)throw error("原模型Prompt资源缺失",503);return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException e){throw error("原模型Prompt资源不可读",503);}}
    static String pythonJson(Object value){if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(JSON.toJSONString(k)+": "+pythonJson(v)));return "{"+String.join(", ",parts)+"}";}if(value instanceof List<?> list)return "["+String.join(", ",list.stream().map(ModelMessageSources::pythonJson).toList())+"]";if(value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal)return pythonFloat((Number)value);return JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue);}
    /** 默认JSON十进制解析保留精度但丢负零；仅复原原字节中的负零，不改变其他值/键顺序。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper SOURCE_NUMBERS=new com.fasterxml.jackson.databind.ObjectMapper();
    private static Object sourceNumbers(String json){try{return SOURCE_NUMBERS.readValue(json,Object.class);}catch(java.io.IOException e){throw error("模型中心JSON来源无效",400);}}
    static JSONObject sourceObject(String json){var normal=JSON.parseObject(json);restoreNegativeZero(normal,sourceNumbers(json));return normal;}
    static JSONArray sourceArray(String json){var normal=JSON.parseArray(json);restoreNegativeZero(normal,sourceNumbers(json));return normal;}
    private static void restoreNegativeZero(Object normal,Object floating){
        if(normal instanceof Map map && floating instanceof Map other)for(Object key:new ArrayList<>(map.keySet())){Object n=map.get(key),f=other.get(key);if(f instanceof Double d && Double.doubleToRawLongBits(d)==Long.MIN_VALUE)map.put(key,d);else restoreNegativeZero(n,f);}
        else if(normal instanceof List list && floating instanceof List other)for(int i=0;i<list.size();i++){Object f=other.get(i);if(f instanceof Double d && Double.doubleToRawLongBits(d)==Long.MIN_VALUE)list.set(i,d);else restoreNegativeZero(list.get(i),f);}
    }
    private static String pythonFloat(Number value){
        double d=value instanceof Float?Double.parseDouble(value.toString()):value.doubleValue();if(!Double.isFinite(d))throw error("模型浮点来源超出原JSON范围",422);
        if(d==0)return Double.doubleToRawLongBits(d)<0?"-0.0":"0.0";
        var exact=new java.math.BigDecimal(Math.abs(d));java.math.BigDecimal shortest=null;
        // 选最短可回读到同一IEEE754值的最近十进制；Java17默认Double文本不是Python最短表示。
        for(int digits=1;digits<=17;digits++){var candidate=exact.round(new java.math.MathContext(digits,java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros();if(Double.doubleToLongBits(candidate.doubleValue())==Double.doubleToLongBits(Math.abs(d))){shortest=candidate;break;}}
        if(shortest==null)throw error("模型浮点来源不可准确复原",422);int exponent=shortest.precision()-shortest.scale()-1;String sign=d<0?"-":"";
        if(exponent>=-4 && exponent<16){String plain=shortest.toPlainString();return sign+plain+(plain.contains(".")?"":".0");}
        String digits=shortest.unscaledValue().abs().toString();return sign+digits.charAt(0)+(digits.length()>1?"."+digits.substring(1):"")+"e"+(exponent<0?"-":"+")+String.format(java.util.Locale.ROOT,"%02d",Math.abs(exponent));
    }
    private static String pythonRepr(String s){String quote=s.contains("'")&&!s.contains("\"")?"\"":"'";var out=new StringBuilder(quote);s.codePoints().forEach(c->{
        if(c=='\\' || c==quote.charAt(0))out.append('\\').appendCodePoint(c);else if(c=='\n')out.append("\\n");else if(c=='\r')out.append("\\r");else if(c=='\t')out.append("\\t");else {
            int type=Character.getType(c);boolean hidden=Character.isISOControl(c) || type==Character.FORMAT || type==Character.SURROGATE || type==Character.PRIVATE_USE || type==Character.UNASSIGNED || type==Character.LINE_SEPARATOR || type==Character.PARAGRAPH_SEPARATOR || type==Character.SPACE_SEPARATOR && c!=' ';
            if(hidden)out.append(String.format(java.util.Locale.ROOT,c<=255?"\\x%02x":c<=65535?"\\u%04x":"\\U%08x",c));else out.appendCodePoint(c);
        }});return out.append(quote).toString();}
    static String render(String template,Map<String,Object> values){return render(template,values,true);}
    static String renderQuestion(String template,Map<String,Object> values){return render(template,values,false);}
    private static String render(String template,Map<String,Object> values,boolean emptySpace){
        if(template==null)return "";var matcher=Pattern.compile("\\{\\{(.*?)}}").matcher(template);String result=template;
        while(matcher.find()){String key=matcher.group(1).replaceAll("^[{}]+|[{}]+$","");if(!key.matches("[A-Za-z0-9_-]+(?:\\[-?\\d+\\])*(?:\\.[A-Za-z0-9_-]+(?:\\[-?\\d+\\])*)*"))continue;
            String first=key.split("[.\\[]",2)[0];if(!values.containsKey(first))continue;Object value=values.get(first);
            String tail=key.substring(first.length());var part=Pattern.compile("\\.([A-Za-z0-9_-]+)|\\[(-?\\d+)\\]").matcher(tail);
            while(part.find()){if(part.group(1)!=null){if(!(value instanceof Map<?,?> map))throw error("模型对象提示路径无效",422);value=map.get(part.group(1));}
                else{if(!(value instanceof List<?> list))throw error("模型数组提示路径无效",422);int index=Integer.parseInt(part.group(2));if(index<0)index+=list.size();if(index<0 || index>=list.size())throw error("模型数组提示下标无效",422);value=list.get(index);}}
            String text=pythonString(value,false);result=result.replace("{{"+key+"}}",emptySpace && text.isEmpty()?" ":text);
        }return result;
    }
    static String pythonString(Object value,boolean nested){
        if(value instanceof PythonLiteralValue.Tuple)return WorkflowInputSources.pythonString(value,nested);if(value==null)return "None";if(value instanceof Boolean b)return b?"True":"False";
        if(value instanceof String s)return nested?pythonRepr(s):s;
        if(value instanceof List<?> list)return "["+String.join(", ",list.stream().map(v->pythonString(v,true)).toList())+"]";
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(pythonString(k,true)+": "+pythonString(v,true)));return "{"+String.join(", ",parts)+"}";}
        if(value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal)return pythonFloat((Number)value);if(value instanceof Number)return value.toString();throw error("模型模板值类型未批准",422);
    }
}
