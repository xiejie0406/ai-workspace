package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;
import java.math.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 按原纯节点计算完成输出；模板复用原Python值表示，不能信任中间Worker输出。 */
final class CompletedPureOutputs {
    private CompletedPureOutputs() {}
    static JSONObject project(JSONObject node,LinkedHashMap<String,Object> inputs) {
        String type=ModelNodeSemantics.type(node);var p=node.getJSONObject("data").getJSONObject("nodeParam");
        if("message".equals(type))return new JSONObject();
        if("node-end".equals(type))return new JSONObject(inputs);
        if("if-else".equals(type))return branch(node,inputs);
        if(!"text-joiner".equals(type))throw error("上游纯节点输出重建尚未接通",503);
        Object output;
        if(p==null||p.getIntValue("mode")==0)output=ModelMessageSources.renderQuestion(p==null?"":p.getString("prompt"),inputs);
        else if(p.getIntValue("mode")==1){Object last="";for(Object value:inputs.values())last=value;String separator=p.getString("separator");if(!(last instanceof String text)||separator==null||separator.isEmpty())throw error("原文本拆分不能成功完成",403);output=new JSONArray(Arrays.asList(text.split(java.util.regex.Pattern.quote(separator),-1)));}
        else throw error("原文本节点模式无效",403);
        var fields=node.getJSONObject("data").getJSONArray("outputs");if(fields==null||fields.isEmpty())throw error("原文本节点没有首输出",403);return JSONObject.of(fields.getJSONObject(0).getString("name"),output);
    }
    private record Branch(JSONObject output,String handle) {}
    static String branchHandle(JSONObject node,Map<String,Object> inputs){return branchResult(node,inputs).handle();}
    private static JSONObject branch(JSONObject node,Map<String,Object> inputs){return branchResult(node,inputs).output();}
    private static Branch branchResult(JSONObject node,Map<String,Object> inputs){var ids=new HashMap<String,String>();for(Object value:node.getJSONObject("data").getJSONArray("inputs")){var field=(JSONObject)value;ids.put(field.getString("id"),field.getString("name"));}var errors=new JSONObject();boolean branchFailure=false;var cases=node.getJSONObject("data").getJSONObject("nodeParam").getJSONArray("cases");JSONObject last=null;String handle=null;int index=0;
        for(Object value:cases){var c=(JSONObject)value;if(c.getIntValue("level")==999){if(branchFailure)throw error("原分支DEFAULT含异常对象不能生成当前合法JSON完成帧",403);return new Branch(errors,c.getString("id"));}handle=c.getString("id");var results=new ArrayList<Boolean>();boolean failed=false;for(Object raw:c.getJSONArray("conditions")){if(raw==null)continue;var condition=(JSONObject)raw;String left=ids.get(condition.getString("leftVarIndex")),right=ids.get(condition.getString("rightVarIndex"));if(left==null)throw error("原分支条件变量不存在",403);try{results.add(compare(inputs.get(left),right==null?null:inputs.get(right),condition.getString("compareOperator")));}catch(RuntimeException e){failed=true;break;}}
            index++;if(failed){branchFailure=true;continue;}boolean matched="and".equals(c.get("logicalOperator"))?!results.contains(false):results.contains(true);last=JSONObject.of("res",matched);errors.put("Branch "+index+" errors: ",matched?"no error, execute it.":"no error, but not execute it.");if(matched)return new Branch(last,handle);
        }return new Branch(last==null?JSONObject.of("[warning]: ","If-else node encountered unknown error, please check!"):last,handle);
    }
    private static boolean compare(Object actual,Object expected,String op){return switch(op){
        case "null"->actual==null;case "not_null"->actual!=null;
        case "empty"->empty(actual);case "not_empty"->!empty(actual);
        case "contains"->contains(actual,expected);case "not_contains"->notContains(actual,expected);
        case "start_with"->truth(actual)&&actual instanceof String s&&s.startsWith((String)expected);
        case "end_with"->truth(actual)&&actual instanceof String s&&s.endsWith((String)expected);
        case "is","eq"->equal(actual,expected);case "is_not","ne"->actual!=null&&(actual instanceof String||numeric(actual))&&!equal(actual,expected);
        case "gt","ge","lt","le"->{if(actual==null||!numeric(actual))yield false;int sign=decimal(actual).compareTo(decimal(coerce(actual,expected)));yield switch(op){case "gt"->sign>0;case "ge"->sign>=0;case "lt"->sign<0;default->sign<=0;};}
        default->throw error("原条件比较操作无效",403);
    };}
    private static boolean numeric(Object value){return value instanceof Number||value instanceof Boolean;}
    private static BigDecimal decimal(Object value){return value instanceof Boolean b?BigDecimal.valueOf(b?1:0):new BigDecimal(value.toString());}
    private static Object coerce(Object actual,Object expected){if(actual instanceof Double||actual instanceof Float||actual instanceof BigDecimal){if(expected instanceof String s)return Double.parseDouble(s.strip().replace("_",""));if(numeric(expected))return expected;throw new IllegalArgumentException();}if(expected instanceof String s)return new BigInteger(s.strip().replace("_",""));if(expected instanceof Float||expected instanceof Double||expected instanceof BigDecimal)return new BigDecimal(((Number)expected).doubleValue()).toBigInteger();if(numeric(expected))return expected;throw new IllegalArgumentException();}
    private static boolean equal(Object actual,Object expected){if(actual==null)return false;if(actual instanceof String s)return expected instanceof String&&s.equals(expected);if(numeric(actual))return decimal(actual).compareTo(decimal(coerce(actual,expected)))==0;return false;}
    private static boolean truth(Object value){return value!=null&&!(value instanceof Boolean b&&!b)&&!(value instanceof Number n&&n.doubleValue()==0)&&!(value instanceof String s&&s.isEmpty())&&!(value instanceof Collection<?> c&&c.isEmpty())&&!(value instanceof Map<?,?> m&&m.isEmpty());}
    private static boolean empty(Object value){if(value instanceof String s)return s.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85);if(value instanceof List<?> l)return l.isEmpty();if(value instanceof Map<?,?> m)return m.isEmpty();if(value instanceof Boolean b)return !b;if(value instanceof Number n)return n.doubleValue()==0;return false;}
    private static boolean contains(Object actual,Object expected){if("".equals(actual)&&"".equals(expected))return true;if(!truth(actual))return false;if(actual instanceof String s)return s.contains((String)expected);if(actual instanceof List<?> values)return values.stream().anyMatch(v->pythonEqual(v,expected));return false;}
    private static boolean notContains(Object actual,Object expected){if("".equals(actual)&&"".equals(expected))return false;if(!truth(actual))return true;if(actual instanceof String s)return !s.contains((String)expected);if(actual instanceof List<?> values)return values.stream().noneMatch(v->pythonEqual(v,expected));return false;}
    private static boolean pythonEqual(Object a,Object b){if(numeric(a)&&numeric(b))return decimal(a).compareTo(decimal(b))==0;if(a instanceof List<?> l&&b instanceof List<?> r){if(l.size()!=r.size())return false;for(int i=0;i<l.size();i++)if(!pythonEqual(l.get(i),r.get(i)))return false;return true;}if(a instanceof Map<?,?> l&&b instanceof Map<?,?> r){if(!l.keySet().equals(r.keySet()))return false;for(Object k:l.keySet())if(!pythonEqual(l.get(k),r.get(k)))return false;return true;}return Objects.equals(a,b);}
}
