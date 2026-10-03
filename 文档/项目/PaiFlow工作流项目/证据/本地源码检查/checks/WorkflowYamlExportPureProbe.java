package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 实际冻结Export的纯静态方法，不构建Service/Auth/Mapper。 */
public final class WorkflowYamlExportPureProbe {
    private static final JSONArray observations=new JSONArray();
    private static Object call(String method,Class<?>[] types,Object... args)throws Exception {
        Method target=WorkflowExportService.class.getDeclaredMethod(method,types);target.setAccessible(true);
        try{return target.invoke(null,args);}catch(InvocationTargetException error){
            if(error.getCause() instanceof Exception failure)throw failure;throw error;
        }
    }
    private static void check(String name,boolean passed){if(!passed)throw new AssertionError(name);observations.add(JSONObject.of("id",name,"passed",true));}
    private static JSONObject node(Object parameters){return JSONObject.of("id","spark-llm::fixture","data",JSONObject.of("nodeParam",parameters,"outputs",new JSONArray()));}
    private static JSONObject graph(Object parameters){return JSONObject.of("nodes",new JSONArray(List.of(node(parameters))),"edges",new JSONArray());}
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        JSONObject original=graph(JSONObject.of("uid","SYNTHETIC_ACTOR","appId","fixture:app","modelId",17,"opaqueNull",null));
        original.put("unknownLayout",JSONObject.of("uid","business-value","null",null));
        JSONObject data=original.getJSONArray("nodes").getJSONObject(0).getJSONObject("data");
        data.put("inputs",new JSONArray(List.of(JSONObject.of("name","request","schema",JSONObject.of("type","array-string","default",JSONObject.of("uid","business-value","null",null),"schemaExtension",null)))));
        String raw=original.toJSONString(JSONWriter.Feature.WriteMapNullValue);
        JSONObject copy=(JSONObject)call("object",new Class<?>[]{String.class,String.class},raw,"flowData");
        List<String> paths=new ArrayList<>();call("clearInitiator",new Class<?>[]{JSONObject.class,List.class},copy,paths);
        WorkflowExportService.SourceGraph typed=JSON.parseObject(copy.toJSONString(JSONWriter.Feature.WriteMapNullValue),WorkflowExportService.SourceGraph.class);
        JSONObject projected=(JSONObject)call("project",new Class<?>[]{Object.class,Object.class},copy,typed);
        call("clearInitiator",new Class<?>[]{JSONObject.class,List.class},projected,paths);
        JSONObject params=projected.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONObject("nodeParam");
        check("uid-cleared-only-in-copy", "".equals(params.get("uid")) && raw.equals(original.toJSONString(JSONWriter.Feature.WriteMapNullValue)) && paths.equals(List.of("flowData/nodes/0/data/nodeParam/uid")));
        JSONObject schema=projected.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONArray("inputs").getJSONObject(0).getJSONObject("schema");
        check("unknown-layout-open-default-null-resource-and-canvas-type-preserved",projected.getJSONObject("unknownLayout").containsKey("null") && "business-value".equals(projected.getJSONObject("unknownLayout").get("uid")) && params.containsKey("opaqueNull") && "fixture:app".equals(params.get("appId")) && params.getInteger("modelId")==17 && "array-string".equals(schema.get("type")) && schema.containsKey("schemaExtension") && "business-value".equals(schema.getJSONObject("default").get("uid")));
        Map<String,Object> wrapper=new LinkedHashMap<>();wrapper.put("flowMeta",Map.of("name","fixture","dslVersion","v1"));wrapper.put("flowData",projected);
        byte[] bytes=(byte[])call("dump",new Class<?>[]{Map.class,List.class},wrapper,paths);
        String yamlText=new String(bytes,StandardCharsets.UTF_8);
        Map<String,Object> loaded=new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        check("yaml-comments-no-uid-value-and-safe-loader-ignores-them",yamlText.startsWith("# 已清空节点发起者UID") && !yamlText.contains("SYNTHETIC_ACTOR") && loaded.get("flowData") instanceof Map && JSON.toJSONString(loaded.get("flowData"),JSONWriter.Feature.WriteMapNullValue).contains("\"uid\":\"\""));
        for(Object uid:List.of(1,JSONObject.of("value","fixture"))) {
            int code=0;try{call("clearInitiator",new Class<?>[]{JSONObject.class,List.class},graph(JSONObject.of("uid",uid)),new ArrayList<>());}catch(ServiceException failure){code=failure.getCode();}
            check("non-string-uid-rejected-"+(uid instanceof Number?"number":"object"),code==400);
        }
        for(Object uid:java.util.Arrays.asList(null,""," ")) {
            JSONObject candidate=graph(JSONObject.of("uid",uid));List<String> p=new ArrayList<>();call("clearInitiator",new Class<?>[]{JSONObject.class,List.class},candidate,p);
            Object effective=candidate.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONObject("nodeParam").get("uid");
            check("uid-null-empty-whitespace-"+(uid==null?"null":"".equals(uid)?"empty":"whitespace"),uid==null?effective==null && p.isEmpty():"".equals(effective) && p.size()==("".equals(uid)?0:1));
        }
        JSONObject encoded=graph("{\"uid\":\"SYNTHETIC_ACTOR\",\"opaqueNull\":null}");List<String> encodedPaths=new ArrayList<>();call("clearInitiator",new Class<?>[]{JSONObject.class,List.class},encoded,encodedPaths);
        typed=JSON.parseObject(encoded.toJSONString(JSONWriter.Feature.WriteMapNullValue),WorkflowExportService.SourceGraph.class);
        projected=(JSONObject)call("project",new Class<?>[]{Object.class,Object.class},encoded,typed);call("clearInitiator",new Class<?>[]{JSONObject.class,List.class},projected,encodedPaths);
        check("encoded-nodeparam-typed-conversion-clears-final-path",encodedPaths.size()==1 && "".equals(projected.getJSONArray("nodes").getJSONObject(0).getJSONObject("data").getJSONObject("nodeParam").get("uid")));
        String before=copy.toJSONString(JSONWriter.Feature.WriteMapNullValue);
        JSONObject secret=graph(JSONObject.of("apiKey","SYNTHETIC_PLACEHOLDER"));int secretCode=0;
        try{WorkflowJsonBoundary.check(secret,"flowData",16*1024*1024);}catch(ServiceException failure){secretCode=failure.getCode();}
        check("same-secret-boundary-before-dump-no-removal",secretCode==400 && before.equals(copy.toJSONString(JSONWriter.Feature.WriteMapNullValue)));
        Object nested="x\n".repeat(400000);for(int i=0;i<44;i++)nested=JSONObject.of("layout",nested);
        Map<String,Object> amplified=new LinkedHashMap<>();amplified.put("flowData",nested);
        WorkflowJsonBoundary.check(amplified,"export",32*1024*1024);
        int sizeCode=0;try{call("dump",new Class<?>[]{Map.class,List.class},amplified,List.of());}catch(ServiceException failure){sizeCode=failure.getCode();}
        check("actual-utf8-dump-stops-indentation-amplification-at-32m",sizeCode==413);
        System.out.println(observations.toJSONString());
    }
}
