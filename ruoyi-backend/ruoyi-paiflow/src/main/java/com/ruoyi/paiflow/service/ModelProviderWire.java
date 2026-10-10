package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;

/** 原SparkChatAi/FunctionCallAi和OpenAI的生产wire；不执行节点推理或改写供应商原帧。 */
final class ModelProviderWire {
    private ModelProviderWire() {}
    static JSONObject request(JSONObject parameters,PrivateMaterial material,String uid,boolean stream){
        return request(parameters,material,uid,stream,"python");
    }
    /** 引擎来自中心私有许可，不能由公共 options 的形状切换供应商协议。 */
    static JSONObject request(JSONObject parameters,PrivateMaterial material,String uid,boolean stream,String engine){
        if(!Set.of("python","java").contains(engine))throw error("模型执行引擎无效",409);
        var options=parameters.getJSONObject("options");String provider=parameters.getString("provider");
        if("java".equals(engine)){
            if(!"openai".equals(provider) || !"chat".equals(parameters.getString("mode")))throw error("原Java仅使用OpenAI风格chat协议",503);
            RunJsonBoundary.fields(options,Set.of("maxTokens","temperature","topP","presencePenalty","frequencyPenalty","n","streamUsage","enableThinking"),Set.of());
            if(!Boolean.TRUE.equals(options.get("streamUsage")) || !Boolean.TRUE.equals(options.get("enableThinking")))throw error("原Java流选项与固定SDK不一致",409);
            var request=JSONObject.of("model",parameters.getString("modelName"),"messages",parameters.get("messages"),"stream",stream,
                "stream_options",JSONObject.of("include_usage",true),"enable_thinking",true);
            var names=Map.of("maxTokens","max_tokens","temperature","temperature","topP","top_p","presencePenalty","presence_penalty","frequencyPenalty","frequency_penalty","n","n");
            for(var entry:names.entrySet())if(options.get(entry.getKey())!=null)request.put(entry.getValue(),options.get(entry.getKey()));
            return request;
        }
        if("openai".equals(provider)){
            if(!"chat".equals(parameters.getString("mode")))throw error("OpenAI function_call不属于原迁移生产协议",503);
            var request=JSONObject.of("model",parameters.getString("modelName"),"messages",parameters.get("messages"),"stream",stream);
            var extra=options.getJSONObject("extraParams");
            if(extra==null)throw error("Python模型缺少固定额外参数",409);
            if(extra.containsKey("max_tokens") && extra.containsKey("max_completion_tokens"))throw error("OpenAI输出上限字段不能同时使用",422);
            request.putAll(extra);
            // 保留原SDK的显式选项；原默认没有供应商上限时使用固定节点上限，不能同时发两种上限。
            if(!extra.containsKey("max_tokens") && !extra.containsKey("max_completion_tokens"))request.put("max_tokens",options.get("maxTokens"));
            return request;
        }
        if(!"xinghuo".equals(provider) || material.appId()==null || material.apiSecret()==null)throw error("Spark私有生产材料缺失",503);
        var header=JSONObject.of("app_id",material.appId(),"uid",uid);
        if(options.getJSONArray("patchId")!=null && !options.getJSONArray("patchId").isEmpty())header.put("patch_id",options.get("patchId"));
        var chat=JSONObject.of("domain",parameters.getString("modelName"),"temperature",options.get("temperature"),"max_tokens",options.get("maxTokens"),"top_k",options.get("topK"),"auditing","default");
        var payload=JSONObject.of("message",JSONObject.of("text",parameters.get("messages")));
        if("function_call".equals(parameters.getString("mode"))){
            payload.put("functions",JSONObject.of("text",parameters.get("functions")));
            if(!options.getString("questionType").isEmpty())chat.put("question_type",options.get("questionType"));
            if(!options.getString("functionChoice").isEmpty())chat.put("function_choice",options.get("functionChoice"));
        }else if(!options.getBooleanValue("searchDisable")){
            chat.put("tools",List.of(JSONObject.of("type","web_search","web_search",JSONObject.of("enable",true,"show_ref_label",false,"search_mode","normal"))));
        }else chat.put("question_type","not_knowledge");
        return JSONObject.of("header",header,"parameter",JSONObject.of("chat",chat),"payload",payload);
    }
    static URI signedSpark(PrivateMaterial material,Instant now){
        var uri=ManagedModelRuntime.sparkEndpoint(material.baseUrl());String host=uri.getRawAuthority();
        String date=DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atZone(ZoneOffset.UTC));
        String origin="host: "+host+"\ndate: "+date+"\nGET "+uri.getRawPath()+" HTTP/1.1";
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(material.apiSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=Base64.getEncoder().encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
            String auth="api_key=\""+material.apiKey()+"\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""+signature+"\"";
            return URI.create(uri+"?authorization="+encode(Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)))+"&date="+encode(date)+"&host="+encode(host));
        }catch(java.security.GeneralSecurityException e){throw error("Spark签名生成失败",503);}
    }
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    record FrameFacts(String finish,JSONObject usage) {}
    static FrameFacts facts(String provider,JSONObject raw){
        return facts(provider,raw,"python",null);
    }
    static FrameFacts facts(String provider,JSONObject raw,String engine,JSONObject options){
        if(!Set.of("python","java").contains(engine))throw error("模型执行引擎无效",409);
        if("java".equals(engine) && !"openai".equals(provider))throw error("原Java模型供应商协议不一致",409);
        if("xinghuo".equals(provider)){
            var h=raw.getJSONObject("header");if(h==null)throw error("Spark缺少真实header",502);
            long code=integer(h.get("code"),0,Integer.MAX_VALUE,"Spark code"),status=integer(h.get("status"),0,2,"Spark status");
            var payload=raw.getJSONObject("payload");var usage=payload==null?null:payload.getJSONObject("usage");
            JSONObject tokens=usage==null?null:usage.getJSONObject("text");
            return new FrameFacts(code!=0?"provider_error":status==2?"stop":null,tokens);
        }
        long count="java".equals(engine) && options!=null && options.get("n")!=null?integer(options.get("n"),1,MAX_FRAMES,"n"):1;
        Object choicesValue=raw.get("choices");if(!(choicesValue instanceof JSONArray choices) || choices.size()>count)throw error("模型原始帧choice无效",400);
        String finish=null;
        if(choices.isEmpty()){if(!(raw.get("usage") instanceof JSONObject))throw error("空choice缺少实际usage",400);}
        else{var choice=choices.getJSONObject(0);Object reason=choice.get("finish_reason");
            if(reason!=null){if(!(reason instanceof String s) || !Set.of("stop","length","content_filter","tool_calls","function_call").contains(s))throw error("模型实际结束原因无效",400);finish=s;}}
        return new FrameFacts(finish,raw.getJSONObject("usage"));
    }
}
