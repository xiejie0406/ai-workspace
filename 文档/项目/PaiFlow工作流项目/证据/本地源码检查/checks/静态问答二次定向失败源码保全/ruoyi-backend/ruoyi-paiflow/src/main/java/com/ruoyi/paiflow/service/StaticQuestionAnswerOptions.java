package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 原QA静态分支的有效参数；只转换执行副本，不构造Provider、App或凭据关系。 */
public final class StaticQuestionAnswerOptions {
    private StaticQuestionAnswerOptions() {}
    private static final Set<String> OWNED=Set.of("node_id","alias_name","node_type","input_identifier","output_identifier","retry_config","stream_node_first_token","span","extractor_params","default_outputs","start_time","event_id","token_usage","processed_options");
    private static Object field(JSONObject raw,String name,Object fallback){return raw.containsKey(name)?raw.get(name):fallback;}
    private static String string(Object value,String name){if(!(value instanceof String text))throw invalid(name+"必须为字符串");return text;}
    private static int bounded(Object value,String name){BigInteger number=ModelNodeOptions.integer(value,name);if(number.compareTo(BigInteger.ONE)<0 || number.compareTo(BigInteger.valueOf(5))>0)throw invalid(name+"须在1至5之间");return number.intValue();}
    public static JSONObject effective(JSONObject raw) {
        if(raw==null)throw invalid("静态问答参数必须为对象");
        for(String key:OWNED)if(raw.containsKey(key))throw unavailable("客户端不能注入原问答执行状态");
        JSONObject result=ModelNodeOptions.effective(raw);
        if(result.getBooleanValue("enableChatHistory") || result.getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled"))throw unavailable("静态问答历史尚未接通");
        for(String key:List.of("apiKey","apiSecret","url")){String value=string(field(raw,key,""),key);if(!value.isEmpty())throw unavailable("静态问答不能携带Provider材料");}
        if(!string(field(raw,"modelResourceId",""),"modelResourceId").isEmpty())throw unavailable("静态问答不绑定模型资源");
        for(String key:List.of("credentialRef","credentialVersion","endpoint"))if(raw.get(key)!=null && !"".equals(raw.get(key)))throw unavailable("静态问答不绑定模型资源");
        // 原静态分支不消费modelId/llmId等extra metadata，不从它们签发资源权限。
        result.put("remarkVisible",ModelNodeOptions.bool(field(raw,"remarkVisible",false),"remarkVisible"));result.put("remark",string(field(raw,"remark",""),"remark"));
        // 原BaseLLM必填str允许空；补空只是静态节点构造兼容，不是Provider鉴权或App引用。
        result.put("domain",string(field(raw,"domain",""),"domain"));result.put("appId",string(field(raw,"appId",""),"appId"));
        result.put("uid",string(field(raw,"uid",""),"uid"));result.put("source",string(field(raw,"source","xinghuo"),"source"));
        result.put("template",string(field(raw,"template",""),"template"));result.put("systemTemplate",string(field(raw,"systemTemplate",""),"systemTemplate"));
        result.put("re_match_pattern",string(field(raw,"re_match_pattern","```(json)?(.*)```"),"re_match_pattern"));result.put("instruction",string(field(raw,"instruction",""),"instruction"));
        result.put("question",string(raw.get("question"),"question"));String type=string(raw.get("answerType"),"answerType");if(!Set.of("direct","option").contains(type))throw invalid("answerType须为原direct或option");result.put("answerType",type);
        result.put("timeout",bounded(raw.get("timeout"),"timeout"));result.put("needReply",ModelNodeOptions.bool(raw.get("needReply"),"needReply"));
        if(!(raw.get("directAnswer") instanceof Map<?,?> direct))throw invalid("directAnswer必须为对象");
        boolean handle=ModelNodeOptions.bool(direct.get("handleResponse"),"directAnswer.handleResponse");int retries=bounded(direct.get("maxRetryCounts"),"directAnswer.maxRetryCounts");
        if(type.equals("direct") && handle)throw unavailable("直接问答的模型处理尚未接通");result.put("directAnswer",JSONObject.of("handleResponse",handle,"maxRetryCounts",retries));
        Object value=field(raw,"optionAnswer",new JSONArray());if(!(value instanceof List<?> list))throw invalid("optionAnswer必须为数组");JSONArray options=new JSONArray();
        for(Object item:list){if(!(item instanceof Map<?,?> option))throw invalid("问答选项必须为对象");String name=string(option.get("name"),"option.name");if(!name.equals("default") && !name.matches("[A-Z]"))throw invalid("option.name须为default或单个A至Z");
            Object optionType=option.get("type");if(!(optionType instanceof Byte || optionType instanceof Short || optionType instanceof Integer || optionType instanceof Long || optionType instanceof BigInteger))throw invalid("option.type须为原整数1或2");BigInteger n=ModelNodeOptions.integer(optionType,"option.type");if(!n.equals(BigInteger.ONE) && !n.equals(BigInteger.TWO))throw invalid("option.type须为原整数1或2");
            options.add(JSONObject.of("id",string(option.get("id"),"option.id"),"name",name,"type",n.intValue(),"content_type",string(option.get("content_type"),"option.content_type"),"content",string(option.get("content"),"option.content")));
        }
        // 原process_option_answers只由option分支调用，type1 DEFAULT最多一个；direct的extra不生效。
        if(type.equals("option") && options.stream().filter(item->((JSONObject)item).getIntValue("type")==1).count()>1)throw invalid("原问答默认选项分支只能配置一个");
        result.put("optionAnswer",options);return result;
    }
    private static ServiceException invalid(String text){return new ServiceException(text,422);}
    private static ServiceException unavailable(String text){return new ServiceException(text,503);}
}
