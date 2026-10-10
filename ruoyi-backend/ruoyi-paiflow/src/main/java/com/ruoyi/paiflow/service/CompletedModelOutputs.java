package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 只从已验证的中心原帧重建原节点输出；不签发模型或副作用许可。 */
final class CompletedModelOutputs {
    record Content(String text,String reasoning,String functionName,String arguments,boolean failed) {}
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_READER=new com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build());
    private CompletedModelOutputs() {}

    static Content content(JSONObject node,JSONArray frames) {
        String provider=node.getJSONObject("data").getJSONObject("nodeParam").getString("source");
        var text=new StringBuilder();var reasoning=new StringBuilder();String name=null,args=null;boolean failed=false;
        for(Object value:frames) {
            var raw=(JSONObject)value;JSONObject message=null;
            if("openai".equals(provider)) {var choices=raw.getJSONArray("choices");if(choices!=null&&!choices.isEmpty()){var choice=choices.getJSONObject(0);message=choice.getJSONObject("delta");Object finish=choice.get("finish_reason");if(finish!=null&&!"stop".equals(finish))failed=true;}}
            else {var header=raw.getJSONObject("header");if(header==null)throw error("模型来源帧缺少真实header",403);if(header.getIntValue("code")!=0)failed=true;var payload=raw.getJSONObject("payload");var choices=payload==null?null:payload.getJSONObject("choices");var messages=choices==null?null:choices.getJSONArray("text");if(messages!=null&&!messages.isEmpty())message=messages.getJSONObject(0);
                if("function_call".equals(ModelNodeSemantics.mode(node))){String sid=header.getString("sid");if(sid==null||sid.isEmpty()||sid.endsWith("1"))failed=true;if(header.getIntValue("status")!=2)continue;var call=message==null?null:message.getJSONObject("function_call");if(call==null||!(call.get("name") instanceof String)||!(call.get("arguments") instanceof String))failed=true;else{name=call.getString("name");args=call.getString("arguments");}}}
            if(message!=null){append(text,message.get("content"));append(reasoning,message.get("reasoning_content"));}
        }
        return new Content(text.toString(),reasoning.toString(),name,args,failed);
    }
    private static void append(StringBuilder target,Object value){if(value==null)return;if(!(value instanceof String s))throw error("原模型文本帧类型无效",403);target.append(s);if(target.length()>ModelOperationJson.STREAM_BYTES)throw error("模型输出来源超过预算",413);}

    static JSONObject project(JSONObject node,JSONArray frames) {
        Content source=content(node,frames);String type=ModelNodeSemantics.type(node);var p=node.getJSONObject("data").getJSONObject("nodeParam");
        if("decision-making".equals(type))return decision(node,source);
        if(source.failed())throw error("失败模型不能提供成功节点输出",403);
        if("spark-llm".equals(type)) {
            int format=p.getIntValue("respFormat",0);Object parsed=format==2?parsedFenced(source.text(),p.getString("re_match_pattern","```(json)?(.*)```"),true,false):null;
            JSONObject values;if(format==2&&parsed instanceof JSONObject object)values=object;else {
                values=text(node,source,format==1);
                // 原JSON非对象回退的or链：空文本投影会继续调用原非dict的update并失败。
                if(format==2&&values.isEmpty())throw error("原LLM非对象JSON空投影无法完成格式化",403);
            }
            var result=new JSONObject();for(Object value:outputs(node)){var field=(JSONObject)value;String name=field.getString("name");Object actual=values.get(name);result.put(name,truth(actual)?actual:defaultValue(field));}return result;
        }
        if("extractor-parameter".equals(type)) {
            Object parsed="function_call".equals(ModelNodeSemantics.mode(node))?parse(source.arguments(),true):parsedFenced(source.text(),"```(json)?(.*)```",false,true);
            if(!(parsed instanceof JSONObject values))throw error("原参数提取没有合法对象输出",403);
            var result=new JSONObject();for(Object value:outputs(node)){var field=(JSONObject)value;String name=field.getString("name");result.put(name,values.containsKey(name)?values.get(name):defaultValue(field));}return requiredTypes(node,result);
        }
        throw error("该模型完成输出尚未由中心恢复",503);
    }
    private static JSONObject text(JSONObject node,Content source,boolean markdown){var result=new JSONObject();var fields=outputs(node);if(!markdown&&!source.reasoning().isEmpty()){for(Object value:fields){String name=((JSONObject)value).getString("name");result.put(name,"REASONING_CONTENT".equals(name)?source.reasoning():source.text());}}else {if(fields.isEmpty())throw error("原LLM没有输出声明",403);result.put(fields.getJSONObject(0).getString("name"),source.text());}return result;}
    private static JSONObject decision(JSONObject node,Content source){var p=node.getJSONObject("data").getJSONObject("nodeParam");String selected="DEFAULT";if(!source.failed())try{if("function_call".equals(ModelNodeSemantics.mode(node))){if(source.functionName()==null)throw new IllegalArgumentException();selected=source.functionName();}
        else {var raw=parsedFenced(source.text(),"```(json)?(.*)```",true,true);if(!(raw instanceof JSONObject result))throw new IllegalArgumentException();String key=p.getIntValue("reasonMode",0)==1?"category_name":"destination";Object name=result.get(key);if(!(name instanceof String))throw new IllegalArgumentException();boolean known=false;for(Object value:p.getJSONArray("intentChains")){var chain=(JSONObject)value;if(Objects.equals(chain.get("name"),name)){known=chain.getIntValue("intentType")!=1;if("category_name".equals(key)&&chain.getIntValue("intentType")==1)name="DEFAULT";break;}}selected="DEFAULT".equals(name)||known?name.toString():"DEFAULT";}}
        catch(RuntimeException e){selected="DEFAULT";}var fields=outputs(node);if(fields.isEmpty())throw error("原Decision没有输出声明",403);return JSONObject.of(fields.getJSONObject(0).getString("name"),selected);}

    static JSONArray outputs(JSONObject node){var fields=node.getJSONObject("data").getJSONArray("outputs");if(fields==null)return new JSONArray();var seen=new HashSet<String>();for(Object value:fields)if(!(value instanceof JSONObject f)||!(f.get("name") instanceof String name)||!seen.add(name)||f.getJSONObject("schema")==null)throw error("固定模型输出声明无效",403);return fields;}
    static Object defaultValue(JSONObject field){var schema=field.getJSONObject("schema");return schema.containsKey("default")?schema.get("default"):zero(schema.getString("type"));}
    static Object zero(String type){return switch(type==null?"":type){case "string"->"";case "number"->0.0;case "integer"->0;case "boolean"->false;case "array"->new JSONArray();case "object"->new JSONObject();default->null;};}
    static boolean truth(Object value){if(value==null)return false;if(value instanceof Boolean b)return b;if(value instanceof Number n)return n.doubleValue()!=0;if(value instanceof String s)return !s.isEmpty();if(value instanceof Map<?,?> m)return !m.isEmpty();if(value instanceof Collection<?> c)return !c.isEmpty();return true;}

    static JSONObject requiredTypes(JSONObject node,JSONObject values){for(Object value:outputs(node)){var field=(JSONObject)value;if(Boolean.TRUE.equals(field.get("required")))values.put(field.getString("name"),fix(values.get(field.getString("name")),field.getJSONObject("schema")));}return values;}
    private static Object fix(Object value,JSONObject schema){String type=schema.getString("type");try{return switch(type==null?"":type){case "integer"->{double n=number(value);if(!Double.isFinite(n))throw error("原integer输出不能从非有限数恢复",403);yield new java.math.BigDecimal(n).toBigInteger();}case "number"->{double n=number(value);if(!Double.isFinite(n))throw error("原number输出不是有限数",403);yield n;}case "string"->value instanceof String?value:ModelMessageSources.pythonString(value,false);case "boolean"->value instanceof Boolean?value:false;case "array"->{var items=schema.getJSONObject("items");if(items==null)items=new JSONObject();var result=new JSONArray();for(Object item:value instanceof JSONArray a?a:new JSONArray(Arrays.asList(value)))result.add(fix(item,items));yield result;}case "object"->{if(!(value instanceof JSONObject object))yield new JSONObject();var result=new JSONObject();var properties=schema.getJSONObject("properties");for(var e:object.entrySet()){var property=properties==null?null:properties.getJSONObject(e.getKey());if(property==null)throw error("原required对象输出没有字段Schema",403);result.put(e.getKey(),fix(e.getValue(),property));}yield result;}default->value;};}catch(NumberFormatException|NullPointerException e){return zero(type);}}
    private static double number(Object value){if(value instanceof Boolean b)return b?1:0;if(value instanceof Number n)return n.doubleValue();if(value instanceof String s){int from=0,to=s.length();while(from<to&&space(s.codePointAt(from)))from+=Character.charCount(s.codePointAt(from));while(to>from&&space(s.codePointBefore(to)))to-=Character.charCount(s.codePointBefore(to));var text=new StringBuilder();s.substring(from,to).codePoints().forEach(cp->{int digit=Character.getType(cp)==Character.DECIMAL_DIGIT_NUMBER?Character.digit(cp,10):-1;if(digit>=0)text.append((char)('0'+digit));else text.appendCodePoint(cp);});String normalized=text.toString();if(!normalized.matches("(?i)[+-]?(?:(?:(?:[0-9](?:_?[0-9])*)(?:\\.(?:[0-9](?:_?[0-9])*)?)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?|inf(?:inity)?|nan)"))throw new NumberFormatException();if(normalized.toLowerCase(Locale.ROOT).contains("inf"))return normalized.startsWith("-")?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;if(normalized.toLowerCase(Locale.ROOT).contains("nan"))return Double.NaN;return Double.parseDouble(normalized.replace("_",""));}throw new NumberFormatException();}
    private static boolean space(int cp){return cp==9||cp==10||cp==11||cp==12||cp==13||cp==32||cp==0x85||Character.isSpaceChar(cp);}
    static Object parsedFenced(String text,String pattern,boolean custom,boolean strict){try{var match=Pattern.compile(pattern,Pattern.DOTALL).matcher(text);String json=match.find()?match.group(2):text;json=json.strip();if(custom)json=customParser(json);return parse(json,strict);}catch(RuntimeException e){if(strict)throw error("模型原输出不是可解析JSON",403);return new JSONObject();}}
    static Object parse(String text,boolean strict){try{try(var reader=JSON_READER.createParser(text)){Object value=JSON_READER.readValue(reader,Object.class);if(reader.nextToken()!=null)throw new IllegalArgumentException();return jsonValue(value);}}catch(Exception e){if(strict)throw error("模型原输出不是合法JSON",403);return new JSONObject();}}
    private static Object jsonValue(Object value){if(value instanceof Map<?,?> map){var result=new JSONObject();map.forEach((k,v)->result.put(k.toString(),jsonValue(v)));return result;}if(value instanceof List<?> list){var result=new JSONArray();for(Object v:list)result.add(jsonValue(v));return result;}return value;}
    private static String customParser(String value){var match=Pattern.compile("(\"action_input\"\\:\\s*\")(.*)(\")",Pattern.DOTALL).matcher(value);if(!match.find())return value;String content=match.group(2).replace("\n","\\n").replace("\r","\\r").replace("\t","\\t").replaceAll("(?<!\\\\)\"","\\\\\"");return value.substring(0,match.start())+match.group(1)+content+match.group(3)+value.substring(match.end());}
}
