package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.util.*;
import java.util.regex.Pattern;

/** 中心重建原PGSqlNode实际请求；Worker不能决定写入值、条件值或SQL占位内容。 */
final class DataInputPayload {
    private DataInputPayload() {}
    static JSONObject expected(JSONObject execution,JSONObject node,JSONObject input,JSONArray events,int environment) {
        return expected(node,WorkflowInputSources.values(execution,node,input,events),environment);
    }
    /** 正式入口使用公共来源校核后的值，未选分支默认不伪造完成事件。 */
    static JSONObject expected(JSONObject node,Map<String,Object> values,int environment) {
        var p=node.getJSONObject("data").getJSONObject("nodeParam");
        int mode=Math.toIntExact(DataResourceService.idOrZero(p.get("mode")));
        if(mode==0) {
            String template=p.getString("sql");if(template==null)throw DataResourceService.fail("固定CUSTOM SQL缺失",409);
            var matches=Pattern.compile("\\{\\{([\\p{L}\\p{N}_]+)}}").matcher(template);var result=new StringBuilder();
            while(matches.find()) {
                String key=matches.group(1),text=values.containsKey(key)?WorkflowInputSources.pythonString(values.get(key),false):matches.group();
                matches.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(text));
            }
            matches.appendTail(result);return JSONObject.of("mode",0,"sql",result.toString(),"execDev",environment);
        }
        if(mode<1 || mode>4)throw DataResourceService.fail("固定数据节点模式无效",422);
        if(mode==3 && p.containsKey("limit") && p.get("limit")==null)throw DataResourceService.fail("原数据查询limit不能为null",422);
        var assignments=p.getJSONArray("assignmentList");if(assignments==null)assignments=new JSONArray();
        var updates=new JSONObject();
        if(mode==1)updates.putAll(values);
        if(mode==2)for(Object raw:assignments) {
            if(!(raw instanceof String name) || !values.containsKey(name))throw DataResourceService.fail("固定更新列缺少真实输入",422);
            updates.put(name,values.get(name));
        }
        var cases=p.getJSONArray("cases");var first=cases==null || cases.isEmpty()?null:cases.getJSONObject(0);var conditions=new JSONArray();
        if(first!=null)for(Object raw:first.getJSONArray("conditions")) {
            var fixed=(JSONObject)raw;String op=fixed.getString("selectCondition");var condition=JSONObject.of("fieldName",fixed.get("fieldName"),"operator",op);
            if("null".equals(op) || "not null".equals(op)) {
                String type=fixed.getString("fieldType");type=type==null?"":type.toLowerCase(Locale.ROOT);
                if(Set.of("string","integer","number","boolean","array","object").contains(type))condition.put("zeroEquivalent",WorkflowInputSources.zero(type));
            } else {
                String key=fixed.getString("varIndex");Object value=values.containsKey(key)?values.get(key):key;
                if(value instanceof String text && ("like".equals(op) || "not like".equals(op)))value="%"+text+"%";
                condition.put("value",value);
            }
            conditions.add(condition);
        }
        var order=p.getJSONArray("orderData");if(order==null)order=new JSONArray();
        return JSONObject.of("mode",mode,"table",p.get("tableName"),"values",updates,
            "where",JSONObject.of("logicalOperator",first==null?"and":first.get("logicalOperator"),"conditions",conditions),
            "columns",mode==3?assignments:new JSONArray(),"order",mode==3?order:new JSONArray(),
            "limit",mode==3?(p.containsKey("limit")?p.get("limit"):0):null,
            "returning",mode==1?"last_inserted_row":mode==2?"matched_rows":"exec_success","execDev",environment);
    }
}
