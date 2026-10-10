package com.ruoyi.paiflow.service;
import com.alibaba.fastjson2.*;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;
/** 工具编译只改执行副本；历史重构仅消费已存快照，不取最新版本。 */
final class ToolNodeCompiler {
 interface Resolver {ToolRuntimeCatalog.Fixed bind(String plugin,String version,String operation,String app);}
 private ToolNodeCompiler(){}
 static Set<String> transform(JSONObject execution,JSONObject resources,Resolver resolver,JSONObject stored){return transform(execution,resources,resolver,stored,false);}
 static Set<String> transform(JSONObject execution,JSONObject resources,Resolver resolver,JSONObject stored,boolean javaEngine){var used=new HashSet<String>();for(Object raw:execution.getJSONArray("nodes")){var node=(JSONObject)raw;if(!"plugin".equals(com.ruoyi.paiflow.domain.NodeCatalog.typeOf(node.getString("id"))))continue;var p=node.getJSONObject("data").getJSONObject("nodeParam");String plugin=ToolSchema.text(p.get("pluginId"),128,"pluginId"),version=javaEngine&&(p.get("version")==null||p.get("version") instanceof String s&&s.isEmpty())?"V1.0":p.containsKey("version")?ToolSchema.text(p.get("version"),32,"version"):"V1.0",op=ToolSchema.text(p.get("operationId"),128,"operationId"),app=ToolSchema.text(p.get("appId"),128,"appId");String resource;JSONObject d;
  if(stored==null){if(resolver==null)throw error("工具固定目录尚未装配",503);var fixed=resolver.bind(plugin,version,op,app);resource=fixed.resourceId();d=fixed.descriptor();}
  else{resource=null;d=null;for(var field:stored.entrySet())if(field.getValue() instanceof JSONObject desc&&"tool".equals(desc.get("kind"))&&Objects.equals(plugin,desc.get("pluginId"))&&Objects.equals(version,desc.get("legacyVersion"))&&Objects.equals(op,desc.get("operationId"))&&Objects.equals(app,desc.get("legacyAppId"))){if(resource!=null)throw error("工具节点有多个匹配固定版本",409);resource=field.getKey();d=desc;}if(resource==null)throw error("工具节点固定版本缺失",409);}
  descriptor(resource,d);if(p.containsKey("toolResourceId")&&!Objects.equals(resource,p.get("toolResourceId")))throw error("Worker/画布不能更改工具资源绑定",409);p.put("toolResourceId",resource);if(javaEngine)p.put("version",version);else p.putIfAbsent("version","V1.0");p.putIfAbsent("businessInput",new JSONArray());resources.put(resource,JSON.parseObject(d.toJSONString(JSONWriter.Feature.WriteMapNullValue)));used.add(resource);
 }return used;}
 static void descriptor(String resource,JSONObject d){long[] ids=ToolRuntimeCatalog.ids(resource);if(d==null||!d.keySet().equals(Set.of("kind","provider","version","pluginId","operationId","legacyVersion","legacyAppId","effectClass","openApiSchema","enabled"))||!"tool".equals(d.get("kind"))||!"spark-link".equals(d.get("provider"))||!Long.toString(ids[1]).equals(d.get("version"))||!ToolRuntimeCatalog.operationId(resource).equals(d.get("operationId"))||!Boolean.TRUE.equals(d.get("enabled"))||!Set.of("read","write").contains(d.getString("effectClass")))throw error("工具公开描述绑定无效",409);ToolSchema.operations(d.getJSONObject("openApiSchema"));ToolSchema.guardApi(d.getJSONObject("openApiSchema"));}
}
