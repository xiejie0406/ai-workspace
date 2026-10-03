package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 原画布→固定公开资源引用的纯转换；历史校验只消费已存描述，不读取当前模型或Provider。 */
public final class WorkflowModelCompiler {
    private static final Set<String> RESOURCE_TYPES=Set.of("spark-llm","plugin","knowledge-base","knowledge-pro-base","agent","flow","mcp","decision-making","extractor-parameter");
    private static final Set<String> DESCRIPTOR_KEYS=Set.of("kind","provider","version","modelName","enabled","capabilities");
    private static final Set<String> OVERRIDES=Set.of("extra_body","extra_headers","extra_query","timeout","model","messages","stream","base_url","api_key");
    public interface Resolver { ModelRuntimeCatalog.FixedModel bind(long modelId,String source,String domain); }
    public record Compiled(JSONObject execution,JSONObject resourceSnapshots) {}
    private WorkflowModelCompiler() {}
    public static Compiled compile(JSONObject canvas,Resolver resolver){return transform(canvas,resolver,null);}
    public static JSONObject reconstruct(JSONObject canvas,JSONObject stored){return transform(canvas,null,stored).execution();}
    public static boolean hasResources(JSONObject canvas){return containsResource(new DslCodec().normalize(canvas).execution());}
    private static boolean containsResource(Object value){
        if(value instanceof JSONObject object){if(object.get("id") instanceof String id && RESOURCE_TYPES.contains(NodeCatalog.typeOf(id)))return true;for(Object child:object.values())if(containsResource(child))return true;}
        else if(value instanceof JSONArray array)for(Object child:array)if(containsResource(child))return true;return false;
    }
    private static Compiled transform(JSONObject canvas,Resolver resolver,JSONObject stored){
        JSONObject execution=new DslCodec().normalize(canvas).execution(),resources=new JSONObject();Set<String> used=new HashSet<>();
        for(Object item:execution.getJSONArray("nodes")){
            var node=(JSONObject)item;String type=NodeCatalog.typeOf(node.getString("id"));var data=node.getJSONObject("data");
            if(!"spark-llm".equals(type)){
                if(RESOURCE_TYPES.contains(type) || containsResource(data))throw unavailable();
                continue;
            }
            if(data==null || !(data.get("nodeParam") instanceof JSONObject p))throw new ServiceException("模型节点参数必须为对象",422);
            if(containsResource(p))throw unavailable();preflight(p,data);
            long modelId=positiveId(p.get("modelId"));String source=string(p,"source","xinghuo"),domain=string(p,"domain",null);
            JSONObject descriptor;String resourceId;
            if(resolver!=null){var fixed=resolver.bind(modelId,source,domain);resourceId=fixed.resourceId();descriptor=fixed.publicDescriptor();}
            else{
                if(stored==null)throw new ServiceException("版本缺少中心资源编译事实",409);
                resourceId=null;descriptor=null;
                for(var entry:stored.entrySet()){
                    long[] ids=ModelRuntimeCatalog.parseResourceId(entry.getKey());
                    if(ids[0]==modelId){if(resourceId!=null)throw new ServiceException("一个画布模型对应多个固定版本",409);resourceId=entry.getKey();descriptor=entry.getValue() instanceof JSONObject o?o:null;}
                }
                if(resourceId==null)throw new ServiceException("版本模型绑定缺失",409);
            }
            validateDescriptor(resourceId,descriptor);
            if(!Objects.equals(source,descriptor.get("provider")) || !Objects.equals(domain,descriptor.get("modelName")))throw new ServiceException("画布模型声明与固定版本冲突",409);
            if(p.containsKey("modelResourceId") && !Objects.equals(p.get("modelResourceId"),resourceId))throw new ServiceException("客户端不能更改中心模型资源绑定",409);
            p.remove("apiKey");p.remove("apiSecret");p.remove("url");p.remove("credentialRef");p.remove("credentialVersion");p.remove("endpoint");
            p.put("source",descriptor.get("provider"));p.put("domain",descriptor.get("modelName"));p.put("modelResourceId",resourceId);
            typedDefaults(p);resources.put(resourceId,JSON.parseObject(descriptor.toJSONString()));used.add(resourceId);
        }
        if(stored!=null && !used.equals(stored.keySet()))throw new ServiceException("版本含未使用或缺失的模型资源绑定",409);
        WorkflowJsonBoundary.check(execution,"execution",16*1024*1024);WorkflowJsonBoundary.check(resources,"resourceSnapshots",1024*1024);
        return new Compiled(execution,resources);
    }
    public static void validateDescriptor(String resourceId,JSONObject d){
        long[] ids=ModelRuntimeCatalog.parseResourceId(resourceId);
        if(d==null || !d.keySet().equals(DESCRIPTOR_KEYS) || !"model".equals(d.get("kind")) || !"openai".equals(d.get("provider")) || !String.valueOf(ids[1]).equals(d.get("version")) || !(d.get("modelName") instanceof String name) || name.isBlank() || !Boolean.TRUE.equals(d.get("enabled")) || !(d.get("capabilities") instanceof JSONArray capabilities) || !capabilities.equals(new JSONArray(java.util.List.of("chat"))))throw new ServiceException("模型公开快照形状或绑定无效",409);
    }
    private static void preflight(JSONObject p,JSONObject data){
        var effective=ModelNodeOptions.effective(p);if(effective.getBooleanValue("enableChatHistory") || effective.getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled") || Boolean.TRUE.equals(p.get("multiMode")) || Set.of("image","imagev3").contains(p.getString("domain")))throw unavailable();
        if(data.get("inputs") instanceof JSONArray inputs)for(Object item:inputs)if(item instanceof JSONObject input && "SYSTEM_IMAGE".equals(input.get("name")))throw unavailable();
        for(String privateKey:Set.of("credentialRef","credentialVersion","endpoint"))if(p.get(privateKey)!=null)throw new ServiceException("客户端不能提交模型私有绑定",409);
        if(p.get("extraParams")!=null && !(p.get("extraParams") instanceof JSONObject))throw new ServiceException("extraParams必须为对象",422);
        if(p.get("extraParams") instanceof JSONObject extra){for(String k:extra.keySet())if(OVERRIDES.contains(k))throw new ServiceException("模型额外参数不能覆盖受控调用边界",422);if(extra.containsKey("n") && !Objects.equals(extra.get("n"),1))throw new ServiceException("本批模型只支持单回复",503);}
    }
    private static void typedDefaults(JSONObject p){
        p.putAll(ModelNodeOptions.effective(p));
        if(p.get("patchId")!=null && !Objects.equals(p.get("patchId"),"") && !(p.get("patchId") instanceof JSONArray a && a.isEmpty()))throw new ServiceException("旧patchId转换尚未批准",503);
        p.remove("patchId");p.putIfAbsent("patch_id",new JSONArray());p.putIfAbsent("extraParams",new JSONObject());p.putIfAbsent("template","");p.putIfAbsent("systemTemplate","");
        // 原Node必填字段兼容；OpenAI受控桥不用appId鉴权，此值不宣告App关系。
        p.putIfAbsent("appId","");if(!(p.get("appId") instanceof String))throw new ServiceException("appId兼容字段必须为字符串",422);
        if(!(p.get("template") instanceof String) || !(p.get("systemTemplate") instanceof String))throw new ServiceException("模型提示模板必须为字符串",422);
    }
    private static long positiveId(Object value){try{if(value instanceof Boolean || value==null || !value.toString().matches("[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(value.toString());}catch(IllegalArgumentException ex){throw new ServiceException("模型节点必须选择真实中心modelId",503);}}
    private static String string(JSONObject p,String key,String fallback){Object value=p.containsKey(key)?p.get(key):fallback;if(!(value instanceof String s) || s.isBlank())throw new ServiceException("模型"+key+"必须为非空字符串",422);return s;}
    private static ServiceException unavailable(){return new ServiceException("当前版本编译仅支持顶层固定OpenAI chat，其他资源/历史/图像能力尚未接通",503);}
}
