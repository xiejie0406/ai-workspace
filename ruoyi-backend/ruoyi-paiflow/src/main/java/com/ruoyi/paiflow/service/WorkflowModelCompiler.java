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
    private static final Set<String> RESOURCE_TYPES=Set.of("spark-llm","plugin","knowledge-base","knowledge-pro-base","agent","flow","mcp","decision-making","extractor-parameter","database");
    private static final Set<String> DESCRIPTOR_KEYS=Set.of("kind","provider","version","modelName","enabled","capabilities");
    private static final Set<String> OVERRIDES=Set.of("extra_body","extra_headers","extra_query","timeout","model","messages","stream","base_url","api_key");
    public interface Resolver { ModelRuntimeCatalog.FixedModel bind(long modelId,String source,String domain); }
    public record Compiled(JSONObject execution,JSONObject resourceSnapshots) {}
    private WorkflowModelCompiler() {}
    public static String executionCodecFor(JSONObject canvas){var graph=new DslCodec().normalizeV1(canvas).execution();boolean qa=containsStaticQuestionAnswer(graph);for(Object raw:graph.getJSONArray("nodes")){var n=(JSONObject)raw;String t=ModelNodeSemantics.type(n);if(Set.of("plugin","extractor-parameter","decision-making").contains(t) || "question-answer".equals(t) && ModelNodeSemantics.isModelNode(n))return DslCodec.V4;}return qa && containsResource(graph)?DslCodec.V4:qa?DslCodec.V3:DslCodec.V2;}
    public static String executionCodecFor(JSONObject canvas,String engine){if("java".equals(engine)){var execution=new DslCodec().normalize(canvas,DslCodec.V2).execution();JavaToolGraphPolicy.validate(execution);return DslCodec.V2;}if(!"python".equals(engine))throw new ServiceException("固定执行引擎无效",409);return executionCodecFor(canvas);}
    public static boolean isModelNode(JSONObject node){return ModelNodeSemantics.isModelNode(node);}
    /** 原页流重核已固定工具身份；权限与运行准入仍由既有目录及Run负责。 */
    public static Set<String> reconstructToolBindings(JSONObject execution,JSONObject stored,String engine){
        if(!Set.of("java","python").contains(engine==null?"":engine))throw new ServiceException("固定执行引擎无效",409);
        return ToolNodeCompiler.transform(execution,new JSONObject(),null,stored,"java".equals(engine));
    }
    public static boolean containsStaticQuestionAnswer(JSONObject execution){return execution.getJSONArray("nodes").stream().anyMatch(raw->raw instanceof JSONObject node && node.get("id") instanceof String id && "question-answer".equals(NodeCatalog.typeOf(id)));}
    public static Compiled compile(JSONObject canvas,Resolver resolver){return compile(canvas,resolver,null);}
    public static Compiled compile(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver){return transform(canvas,resolver,dataResolver,null,executionCodecFor(canvas));}
    public static Compiled compile(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver,String codec){return transform(canvas,resolver,dataResolver,null,codec);}
    public static JSONObject reconstruct(JSONObject canvas,JSONObject stored){return reconstruct(canvas,stored,DslCodec.V2);}
    public static JSONObject reconstruct(JSONObject canvas,JSONObject stored,String codec){return transform(canvas,null,null,stored,codec).execution();}
    public static JSONObject reconstruct(JSONObject canvas,JSONObject stored,String codec,String engine){if("java".equals(engine)&&stored!=null&&stored.isEmpty()&&!containsResource(new DslCodec().normalizeV1(canvas).execution())&&(codec==null||DslCodec.V1.equals(codec)||DslCodec.V2.equals(codec)))return reconstruct(canvas,stored,codec);return transform(canvas,null,null,null,stored,codec,engine).execution();}
    public static boolean containsModel(Object value){
        if(value instanceof JSONObject object){if(object.get("id") instanceof String id){String type=NodeCatalog.typeOf(id);if(Set.of("spark-llm","decision-making","extractor-parameter").contains(type))return true;
                if("question-answer".equals(type)){var data=object.getJSONObject("data");var p=data==null?null:data.getJSONObject("nodeParam");var direct=p==null?null:p.getJSONObject("directAnswer");if(p!=null && "direct".equals(p.getString("answerType")) && direct!=null && direct.getBooleanValue("handleResponse"))return true;}}
            for(Object child:object.values())if(containsModel(child))return true;
        }else if(value instanceof JSONArray array)for(Object child:array)if(containsModel(child))return true;return false;
    }
    public static boolean hasResources(JSONObject canvas){return containsResource(new DslCodec().normalizeV1(canvas).execution());}
    private static boolean containsResource(Object value){
        if(value instanceof JSONObject object){if(object.get("id") instanceof String id && RESOURCE_TYPES.contains(NodeCatalog.typeOf(id)))return true;for(Object child:object.values())if(containsResource(child))return true;}
        else if(value instanceof JSONArray array)for(Object child:array)if(containsResource(child))return true;return false;
    }
    public static Compiled compile(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver,ToolNodeCompiler.Resolver toolResolver){return transform(canvas,resolver,dataResolver,toolResolver,null,executionCodecFor(canvas));}
    public static Compiled compile(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver,ToolNodeCompiler.Resolver toolResolver,String engine){return transform(canvas,resolver,dataResolver,toolResolver,null,executionCodecFor(canvas,engine),engine);}
    private static Compiled transform(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver,JSONObject stored,String codec){return transform(canvas,resolver,dataResolver,null,stored,codec);}
    private static Compiled transform(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver,ToolNodeCompiler.Resolver toolResolver,JSONObject stored,String codec){
        return transform(canvas,resolver,dataResolver,toolResolver,stored,codec,"python");
    }
    private static Compiled transform(JSONObject canvas,Resolver resolver,DataNodeCompiler.Resolver dataResolver,ToolNodeCompiler.Resolver toolResolver,JSONObject stored,String codec,String engine){
        JSONObject execution=new DslCodec().normalize(canvas,codec).execution(),resources=new JSONObject();Set<String> used=new HashSet<>();
        boolean javaEngine="java".equals(engine);if(!javaEngine&&!"python".equals(engine))throw new ServiceException("固定执行引擎无效",409);
        if(javaEngine){if(!DslCodec.V2.equals(codec))throw new ServiceException("Java工具资源须使用原V2固定版本",503);JavaToolGraphPolicy.validate(execution);}
        // V3静态问答整图先检查，混合资源不得先进入模型Resolver。
        if(DslCodec.V3.equals(codec))StaticQuestionAnswerPolicy.validate(execution);
        for(Object item:execution.getJSONArray("nodes")){
            var node=(JSONObject)item;String type=NodeCatalog.typeOf(node.getString("id"));var data=node.getJSONObject("data");
            if("database".equals(type)){if(stored==null && dataResolver==null)throw new ServiceException("固定中心数据资源编译端口尚未接通",503);continue;}
            if("plugin".equals(type)){if(!javaEngine&&!DslCodec.V4.equals(codec))throw new ServiceException("Python工具节点须建立V4固定版本",503);continue;}
            if("question-answer".equals(type) && (DslCodec.V3.equals(codec) || DslCodec.V4.equals(codec) && !isModelNode(node))) {
                if(data==null || !(data.get("nodeParam") instanceof JSONObject params))throw new ServiceException("静态问答参数必须为对象",422);
                params.putAll(StaticQuestionAnswerOptions.effective(params));continue;
            }
            if(!"spark-llm".equals(type) && !(DslCodec.V4.equals(codec) && isModelNode(node))){
                if(RESOURCE_TYPES.contains(type) || containsResource(data))throw unavailable();
                continue;
            }
            if(data==null || !(data.get("nodeParam") instanceof JSONObject p))throw new ServiceException("模型节点参数必须为对象",422);
            if(containsResource(p))throw unavailable();
            if(javaEngine)javaPreflight(node,p,data);else{preflight(node,p,data);ModelNodeSemantics.providerUid(node);}
            // 原Java SDK忽略source，一律OpenAI HTTP；只改变执行副本，不覆盖原画布。
            long modelId=positiveId(p.get("modelId"));String source=javaEngine?"openai":string(p,"source","xinghuo"),domain=string(p,"domain",null);
            JSONObject descriptor;String resourceId;
            if(resolver!=null){var fixed=resolver.bind(modelId,source,domain);resourceId=fixed.resourceId();descriptor=fixed.publicDescriptor();}
            else{
                if(stored==null)throw new ServiceException("版本缺少中心资源编译事实",409);
                resourceId=null;descriptor=null;
                for(var entry:stored.entrySet()){
                    if(!entry.getKey().startsWith("model:"))continue;
                    long[] ids=ModelRuntimeCatalog.parseResourceId(entry.getKey());
                    if(ids[0]==modelId){if(resourceId!=null)throw new ServiceException("一个画布模型对应多个固定版本",409);resourceId=entry.getKey();descriptor=entry.getValue() instanceof JSONObject o?o:null;}
                }
                if(resourceId==null)throw new ServiceException("版本模型绑定缺失",409);
            }
            validateDescriptor(resourceId,descriptor);
            if(javaEngine&&!"openai".equals(descriptor.get("provider")))throw new ServiceException("原Java模型须绑定已批准的OpenAI HTTP chat配置",409);
            if(!Objects.equals(source,descriptor.get("provider")) || !Objects.equals(domain,descriptor.get("modelName")))throw new ServiceException("画布模型声明与固定版本冲突",409);
            if(p.containsKey("modelResourceId") && !Objects.equals(p.get("modelResourceId"),resourceId))throw new ServiceException("客户端不能更改中心模型资源绑定",409);
            p.remove("apiKey");p.remove("apiSecret");p.remove("url");p.remove("credentialRef");p.remove("credentialVersion");p.remove("endpoint");
            p.put("source",descriptor.get("provider"));p.put("domain",descriptor.get("modelName"));p.put("modelResourceId",resourceId);
            if(!javaEngine){typedDefaults(p);if(DslCodec.V4.equals(codec))ModelNodeSemantics.normalize(node);}
            if("function_call".equals(ModelNodeSemantics.mode(node)) && !descriptor.getJSONArray("capabilities").contains("function_call"))throw new ServiceException("固定Spark模型版本未批准FunctionCall能力",503);
            resources.put(resourceId,JSON.parseObject(descriptor.toJSONString()));used.add(resourceId);
        }
        used.addAll(ToolNodeCompiler.transform(execution,resources,toolResolver,stored,javaEngine));
        if(stored!=null){used.addAll(DataNodeCompiler.reconstruct(execution,stored));if(!used.equals(stored.keySet()))throw new ServiceException("版本含未使用或缺失的固定资源绑定",409);}
        else if(dataResolver!=null)DataNodeCompiler.compile(execution,resources,dataResolver);
        WorkflowJsonBoundary.check(execution,"execution",16*1024*1024);WorkflowJsonBoundary.check(resources,"resourceSnapshots",1024*1024);
        return new Compiled(execution,resources);
    }
    public static void validateDescriptor(String resourceId,JSONObject d){
        long[] ids=ModelRuntimeCatalog.parseResourceId(resourceId);
        if(d==null || !d.keySet().equals(DESCRIPTOR_KEYS) || !"model".equals(d.get("kind")) || !Set.of("openai","xinghuo").contains(d.get("provider")) || !String.valueOf(ids[1]).equals(d.get("version")) || !(d.get("modelName") instanceof String name) || name.isBlank() || !Boolean.TRUE.equals(d.get("enabled")) || !(d.get("capabilities") instanceof JSONArray capabilities) || !(capabilities.equals(new JSONArray(java.util.List.of("chat"))) || "xinghuo".equals(d.get("provider")) && capabilities.equals(new JSONArray(java.util.List.of("chat","function_call")))))throw new ServiceException("模型公开快照形状或绑定无效",409);
    }
    private static void preflight(JSONObject node,JSONObject p,JSONObject data){
        var effective=ModelNodeOptions.effective(p);if(PythonModelHistorySemantics.legacy(node))PythonModelHistorySemantics.requireLegacy(node);String declaredDomain=p.getString("domain");if(effective.getJSONObject("enableChatHistoryV2").getBooleanValue("isEnabled") || Boolean.TRUE.equals(p.get("multiMode")) || declaredDomain!=null && Set.of("image","imagev3").contains(declaredDomain))throw unavailable();
        if(data.get("inputs") instanceof JSONArray inputs)for(Object item:inputs)if(item instanceof JSONObject input && "SYSTEM_IMAGE".equals(input.get("name")))throw unavailable();
        for(String privateKey:Set.of("credentialRef","credentialVersion","endpoint"))if(p.get(privateKey)!=null)throw new ServiceException("客户端不能提交模型私有绑定",409);
        if(p.get("extraParams")!=null && !(p.get("extraParams") instanceof JSONObject))throw new ServiceException("extraParams必须为对象",422);
        if(p.get("extraParams") instanceof JSONObject extra){for(String k:extra.keySet())if(OVERRIDES.contains(k))throw new ServiceException("模型额外参数不能覆盖受控调用边界",422);if(extra.containsKey("n") && !Objects.equals(extra.get("n"),1))throw new ServiceException("本批模型只支持单回复",503);}
    }
    /** 原Java选项由独立语义解释；不注入Python默认，不从旧source或选项签发资源许可。 */
    private static void javaPreflight(JSONObject node,JSONObject p,JSONObject data){
        for(String key:Set.of("credentialRef","credentialVersion","endpoint"))if(p.get(key)!=null)throw new ServiceException("客户端不能提交模型私有绑定",409);
        if(p.get("extraParams")!=null&&!(p.get("extraParams") instanceof JSONObject))throw new ServiceException("extraParams必须为对象",422);
        if(p.get("template")==null)throw new ServiceException("Java模型缺少原用户模板",422);
        Integer history=JavaModelSemantics.historyLimit(node);
        // 原启用历史且rounds缺失/null会在getChatHistory解箱失败；不能漂移为无历史许可。
        if(history==null)throw new ServiceException("Java启用历史时必须提供原有效历史轮数",422);
        if(Boolean.TRUE.equals(p.get("multiMode")))throw new ServiceException("Java多模态中心来源尚未接通",503);
        if(data.get("inputs") instanceof JSONArray inputs)for(Object raw:inputs)if(raw instanceof JSONObject input&&"SYSTEM_IMAGE".equals(input.get("name")))throw new ServiceException("Java多模态中心来源尚未接通",503);
        // 原Double.parseDouble允许NaN/Infinity；不能经canonical序列化为null而掩盖非法预算值。
        for(Object value:JavaModelSemantics.options(node).values())if(value instanceof Number number&&!Double.isFinite(number.doubleValue()))throw new ServiceException("Java模型有效选项必须为有限数字",422);
    }
    private static void typedDefaults(JSONObject p){
        boolean originalHistoryFlag=p.containsKey("enableChatHistory");Object historyFlag=p.get("enableChatHistory");
        p.putAll(ModelNodeOptions.effective(p));
        if(originalHistoryFlag)p.put("enableChatHistory",historyFlag);
        if(p.get("patchId")!=null && !Objects.equals(p.get("patchId"),"") && !(p.get("patchId") instanceof JSONArray a && a.isEmpty()))throw new ServiceException("旧patchId转换尚未批准",503);
        p.remove("patchId");p.putIfAbsent("patch_id",new JSONArray());p.putIfAbsent("extraParams",new JSONObject());p.putIfAbsent("template","");p.putIfAbsent("systemTemplate","");
        // 原Node必填字段兼容；OpenAI受控桥不用appId鉴权，此值不宣告App关系。
        p.putIfAbsent("appId","");if(!(p.get("appId") instanceof String))throw new ServiceException("appId兼容字段必须为字符串",422);
        if(!(p.get("template") instanceof String) || !(p.get("systemTemplate") instanceof String))throw new ServiceException("模型提示模板必须为字符串",422);
    }
    private static long positiveId(Object value){try{if(value instanceof Boolean || value==null || !value.toString().matches("[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(value.toString());}catch(IllegalArgumentException ex){throw new ServiceException("模型节点必须选择真实中心modelId",503);}}
    private static String string(JSONObject p,String key,String fallback){Object value=p.containsKey(key)?p.get(key):fallback;if(!(value instanceof String s) || s.isBlank())throw new ServiceException("模型"+key+"必须为非空字符串",422);return s;}
    private static ServiceException unavailable(){return new ServiceException("当前模型编译支持顶层固定OpenAI/Spark chat及V4参数提取、意图决策、动态问答；当前配置涉及未接通的模型模式、外部历史、多模态或嵌套资源",503);}
}
