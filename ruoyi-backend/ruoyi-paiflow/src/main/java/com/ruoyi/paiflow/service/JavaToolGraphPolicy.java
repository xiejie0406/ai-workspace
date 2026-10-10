package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.util.*;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 原Java四执行器顶层DAG准入；只校核固定图，不建立另一份运行状态或输出声明投影。 */
final class JavaToolGraphPolicy {
 private JavaToolGraphPolicy() {}
 static void validate(JSONObject graph) {
  var report=new DslValidationService().validate(graph,"java");
  if(!report.valid())throw error("Java工具图结构无效："+report.issues().getFirst().message(),422);
  var nodes=new LinkedHashMap<String,JSONObject>();var next=new LinkedHashMap<String,Set<String>>();
  if(graph.getJSONArray("nodes").size()>128)throw error("Java工具图节点超过预算",413);
  String start=null;var ends=new HashSet<String>();
  for(Object raw:graph.getJSONArray("nodes")) {
   var node=(JSONObject)raw;String id=node.getString("id"),type=NodeCatalog.typeOf(id);
   if(!Set.of("node-start","node-end","spark-llm","plugin").contains(type))throw error("Java该类资源执行桥尚未接通",503);
   var data=node.getJSONObject("data");var retry=data.getJSONObject("retryConfig");
   if("plugin".equals(type)){if(!(data.get("nodeParam") instanceof JSONObject p))throw error("Java工具参数必须为对象",422);if("tool@8b2262bef821000".equals(p.get("pluginId")))throw error("Java语音工具生产端尚未接通",503);}
   if("spark-llm".equals(type)&&!(data.get("nodeParam") instanceof JSONObject))throw error("Java模型参数必须为对象",422);
   if(retry!=null&&(retry.getBooleanValue("shouldRetry")||retry.getIntValue("errorStrategy")!=0))throw error("Java工具重试或失败分支尚未接通",503);
   if(data.get("nodes")!=null||data.get("edges")!=null)throw error("Java嵌套执行图尚未接通",503);
   nodes.put(id,node);next.put(id,new LinkedHashSet<>());
   if("node-start".equals(type))start=id;if("node-end".equals(type))ends.add(id);
  }
  for(Object raw:graph.getJSONArray("edges")) {
   var edge=(JSONObject)raw;String from=edge.getString("sourceNodeId"),to=edge.getString("targetNodeId");
   if(edge.get("sourceHandle") instanceof String h&&!h.isBlank()&&!h.startsWith("normal_one_of"))throw error("Java工具条件或失败分支尚未接通",503);
   next.get(from).add(to);
  }
  for(var targets:next.values())if(targets.contains(start))throw error("Java主开始不能有入边",422);
  var reached=reachable(start,next);if(reached.size()!=nodes.size())throw error("Java工具图存在孤立节点",422);
  for(var entry:next.entrySet())if(ends.contains(entry.getKey())?!entry.getValue().isEmpty():entry.getValue().isEmpty())throw error("Java路径须终止于固定结束节点",422);
  // 原Java允许读取Plugin整Map的未声明键；来源仍必须在同图且确为前驱。
  for(var entry:nodes.entrySet()) {
   var fields=entry.getValue().getJSONObject("data").getJSONArray("inputs");if(fields==null)continue;
   for(Object raw:fields) {
    if(!(raw instanceof JSONObject field)||!(field.get("name") instanceof String name)||name.isBlank())throw error("Java节点输入声明无效",422);
    var schema=field.getJSONObject("schema");var value=schema==null?null:schema.getJSONObject("value");
    if(value==null||!"ref".equals(value.get("type")))continue;
    var content=value.getJSONObject("content");String source=content==null?null:content.getString("nodeId");
    if(source==null||!nodes.containsKey(source)||source.equals(entry.getKey())||!reachable(source,next).contains(entry.getKey()))throw error("Java输入来源不是固定图前驱",403);
    if(!(content.get("name") instanceof String path)||path.isBlank())throw error("Java输入引用名无效",422);
   }
  }
  var degrees=new HashMap<String,Integer>();nodes.keySet().forEach(id->degrees.put(id,0));next.values().forEach(v->v.forEach(id->degrees.merge(id,1,Integer::sum)));
  var queue=new ArrayDeque<String>();degrees.forEach((id,d)->{if(d==0)queue.add(id);});var order=new ArrayList<String>();
  while(!queue.isEmpty()){String id=queue.remove();order.add(id);for(String to:next.get(id))if(degrees.merge(to,-1,Integer::sum)==0)queue.add(to);}
  var paths=new HashMap<String,Integer>();var lengths=new HashMap<String,Integer>();
  for(String end:ends){paths.put(end,1);lengths.put(end,1);}
  for(int i=order.size()-1;i>=0;i--){String id=order.get(i);if(ends.contains(id))continue;int count=0,length=0;for(String to:next.get(id)){count=Math.min(129,count+paths.getOrDefault(to,0));length=Math.max(length,lengths.getOrDefault(to,0));}paths.put(id,count);lengths.put(id,length+1);}
  if(paths.get(start)>128||lengths.get(start)>64)throw error("Java工具图路径超过预算",413);
 }
 private static Set<String> reachable(String id,Map<String,Set<String>> next){var seen=new HashSet<String>();var queue=new ArrayDeque<String>();queue.add(id);while(!queue.isEmpty()){String at=queue.remove();if(seen.add(at))queue.addAll(next.get(at));}return seen;}
}
