package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原单层纯迭代的中心准入检查。作用域来自IterationStartNodeId与连线，不能从parentId授权。
 * 只计算有界指标，不枚举原Chains的路径，不创建任务、变量、许可或另一套执行结果。
 */
public final class PureIterationPolicy {
    public static final int MAX_BATCH=64,MAX_NODES=128,MAX_EXPANDED=4096,MAX_PATHS=128,MAX_PATH_LEN=64;
    private static final Set<String> PURE=Set.of("node-start","node-end","if-else","text-joiner","message",
        "iteration","iteration-node-start","iteration-node-end");
    private static final Set<String> RESOURCE=Set.of("spark-llm","plugin","knowledge-base","knowledge-pro-base",
        "agent","flow","mcp","decision-making","extractor-parameter","ifly-code","database","rpa","question-answer","node-variable");
    public record Budget(int expandedNodes,int pathCount,int maxPathLength,Map<String,Integer> batchSizes) {}
    private record Scope(String owner,String start,String end,Set<String> nodes) {}
    private record Paths(int count,int longest) {}
    private PureIterationPolicy() {}

    public static Budget validate(JSONObject execution,JSONObject input) {
        if(execution==null || input==null)throw invalid("迭代图与输入必须为对象");
        JSONArray rawNodes=array(execution,"nodes"),rawEdges=array(execution,"edges");
        if(rawNodes.isEmpty() || rawNodes.size()>MAX_NODES)throw budget("迭代图节点数超过128");
        Map<String,JSONObject> nodes=new LinkedHashMap<>();Map<String,Set<String>> next=new LinkedHashMap<>();
        List<String> starts=new ArrayList<>(),ends=new ArrayList<>(),iterations=new ArrayList<>();
        for(Object raw:rawNodes) {
            if(!(raw instanceof JSONObject node))throw invalid("迭代节点必须为对象");
            String id=text(node.get("id"));if(nodes.putIfAbsent(id,node)!=null)throw invalid("迭代节点ID重复");
            String type=NodeCatalog.typeOf(id);if(!PURE.contains(type))throw unavailable();
            if(!(node.get("data") instanceof JSONObject data))throw invalid("迭代节点data必须为对象");
            rejectNestedResources(data);checkRetry(data);next.put(id,new LinkedHashSet<>());
            if(type.equals("node-start"))starts.add(id);if(type.equals("node-end"))ends.add(id);if(type.equals("iteration"))iterations.add(id);
        }
        if(starts.size()!=1 || ends.size()!=1 || iterations.isEmpty())throw invalid("单层迭代须有唯一主开始、主结束及迭代容器");
        for(Object raw:rawEdges) {
            if(!(raw instanceof JSONObject edge))throw invalid("迭代连线必须为对象");
            String from=text(edge.get("sourceNodeId")),to=text(edge.get("targetNodeId"));
            if(!nodes.containsKey(from) || !nodes.containsKey(to))throw invalid("迭代连线指向不存在的节点");
            next.get(from).add(to); // 与原Chains._deal_edges相同，重复source/target不增加路径。
        }
        checkPoolKeys(nodes);
        List<String> order=topological(next);
        String mainStart=starts.get(0),mainEnd=ends.get(0);List<Scope> scopes=new ArrayList<>();
        scopes.add(scope("main",mainStart,mainEnd,next,nodes));
        Map<String,String> owner=new HashMap<>();assign(scopes.get(0),owner);
        for(String id:iterations) {
            if(!scopes.get(0).nodes().contains(id))continue; // 内部容器稍后明确503；未归属容器按孤儿拒绝。
            JSONObject params=object(data(nodes,id),"nodeParam");String innerStart=text(params.get("IterationStartNodeId"));
            if(!nodes.containsKey(innerStart) || !NodeCatalog.typeOf(innerStart).equals("iteration-node-start"))throw invalid("迭代内部开始引用无效");
            Set<String> inner=reachable(innerStart,next);List<String> innerEnds=inner.stream().filter(n->NodeCatalog.typeOf(n).equals("iteration-node-end")).toList();
            if(innerEnds.size()!=1)throw invalid("每个迭代容器须有唯一内部结束节点");
            var scoped=scope(id,innerStart,innerEnds.get(0),next,nodes);assign(scoped,owner);scopes.add(scoped);
        }
        if(owner.size()!=nodes.size())throw invalid("迭代图含不属于主图或内部图的孤儿节点");
        for(Scope scope:scopes) {
            for(String id:scope.nodes()) {
                String type=NodeCatalog.typeOf(id);
                if(scope.owner().equals("main")) {
                    if(type.startsWith("iteration-node-"))throw invalid("内部节点不能进入主图");
                } else if(type.equals("iteration") || type.equals("node-start") || type.equals("node-end"))throw unavailable();
                if(type.equals("iteration-node-start") && !id.equals(scope.start()))throw invalid("内部开始节点重复");
            }
        }
        for(var entry:next.entrySet())for(String target:entry.getValue())if(!owner.get(entry.getKey()).equals(owner.get(target)))throw invalid("迭代连线不能跨容器");
        Map<String,Integer> batchSizes=new LinkedHashMap<>();int expanded=scopes.get(0).nodes().size(),paths=0,longest=0;
        for(Scope scope:scopes) {
            var metrics=paths(scope,order,next);paths+=metrics.count();longest=Math.max(longest,metrics.longest());
            if(paths>MAX_PATHS || longest>MAX_PATH_LEN)throw budget("迭代路径数量或长度超过预算");
            if(!scope.owner().equals("main")) {
                int batch=batch(scope,nodes,mainStart,input);batchSizes.put(scope.owner(),batch);
                expanded+=batch*scope.nodes().size();if(expanded>MAX_EXPANDED)throw budget("迭代展开节点数超过4096");
            }
        }
        checkReferences(nodes,next,owner,mainStart,iterations);
        return new Budget(expanded,paths,longest,Map.copyOf(batchSizes));
    }

    private static Scope scope(String owner,String start,String end,Map<String,Set<String>> next,Map<String,JSONObject> nodes) {
        Set<String> members=reachable(start,next);if(!members.contains(end) || !next.get(end).isEmpty())throw invalid("作用域结束节点缺失或仍有出边");
        for(String id:members)if(next.get(id).isEmpty() && !id.equals(end))throw invalid("作用域路径必须到达唯一结束节点");
        String expected=owner.equals("main")?"node-start":"iteration-node-start";
        if(!NodeCatalog.typeOf(start).equals(expected))throw invalid("作用域开始节点类型无效");
        for(var entry:next.entrySet())if(entry.getValue().contains(start))throw invalid("作用域开始节点不能有入边");
        return new Scope(owner,start,end,Set.copyOf(members));
    }
    private static void assign(Scope scope,Map<String,String> owners) {
        for(String id:scope.nodes())if(owners.putIfAbsent(id,scope.owner())!=null)throw invalid("迭代节点被多个容器或主图同时引用");
    }
    private static Set<String> reachable(String start,Map<String,Set<String>> next) {
        Set<String> seen=new LinkedHashSet<>();ArrayDeque<String> pending=new ArrayDeque<>();pending.add(start);
        while(!pending.isEmpty()) {String id=pending.remove();if(seen.add(id))pending.addAll(next.get(id));}return seen;
    }
    private static List<String> topological(Map<String,Set<String>> next) {
        Map<String,Integer> degree=new HashMap<>();next.keySet().forEach(id->degree.put(id,0));
        next.values().forEach(targets->targets.forEach(id->degree.merge(id,1,Integer::sum)));
        var pending=new ArrayDeque<String>();degree.forEach((id,d)->{if(d==0)pending.add(id);});var order=new ArrayList<String>();
        while(!pending.isEmpty()){String id=pending.remove();order.add(id);for(String target:next.get(id))if(degree.merge(target,-1,Integer::sum)==0)pending.add(target);}
        if(order.size()!=next.size())throw invalid("迭代普通连线存在循环");return order;
    }
    private static Paths paths(Scope scope,List<String> order,Map<String,Set<String>> next) {
        Map<String,Integer> count=new HashMap<>(),length=new HashMap<>();count.put(scope.end(),1);length.put(scope.end(),1);
        for(int i=order.size()-1;i>=0;i--) {String id=order.get(i);if(!scope.nodes().contains(id) || id.equals(scope.end()))continue;
            int paths=0,longest=0;for(String target:next.get(id)){paths=Math.min(MAX_PATHS+1,paths+count.getOrDefault(target,0));longest=Math.max(longest,length.getOrDefault(target,0));}
            if(paths==0)throw invalid("迭代节点无法到达作用域结束");count.put(id,paths);length.put(id,longest+1);
        }
        return new Paths(count.get(scope.start()),length.get(scope.start()));
    }
    private static int batch(Scope scope,Map<String,JSONObject> nodes,String mainStart,JSONObject input) {
        JSONArray inputs=array(data(nodes,scope.owner()),"inputs");if(inputs.size()!=1)throw unavailable();
        JSONObject first=entry(inputs.get(0));String inputName=text(first.get("name"));JSONObject schema=object(first,"schema");
        JSONObject value=object(schema,"value"),ref=object(value,"content");
        if(!"array".equals(schema.get("type")) || !"ref".equals(value.get("type")) || !mainStart.equals(ref.get("nodeId")))throw unavailable();
        String sourceName=text(ref.get("name"));if(sourceName.contains(".") || sourceName.contains("["))throw unavailable();
        JSONObject output=output(data(nodes,mainStart),sourceName);JSONObject sourceSchema=object(output,"schema");
        if(!"array".equals(sourceSchema.get("type")))throw invalid("迭代来源须在主开始节点声明为数组");
        output(data(nodes,scope.start()),inputName);
        Object actual;
        if(input.containsKey(sourceName))actual=input.get(sourceName);
        else {if(output.containsKey("required") && ModelNodeOptions.bool(output.get("required"),"required"))throw invalid("迭代必填数组输入缺失");actual=sourceSchema.containsKey("default")?sourceSchema.get("default"):new JSONArray();}
        if(!(actual instanceof JSONArray array))throw invalid("迭代批次输入必须为真实JSON数组");
        if(array.size()>MAX_BATCH)throw budget("迭代批次数超过64");return array.size();
    }
    private static void checkReferences(Map<String,JSONObject> nodes,Map<String,Set<String>> next,Map<String,String> owner,String mainStart,List<String> iterations) {
        Map<String,Set<String>> closure=new HashMap<>();
        for(var node:nodes.entrySet()) {
            JSONArray inputs=optionalArray(node.getValue().getJSONObject("data"),"inputs");int index=0;
            for(Object raw:inputs) {JSONObject schema=object(entry(raw),"schema"),value=object(schema,"value");
                if("ref".equals(value.get("type"))) {
                    JSONObject ref=object(value,"content");String source=text(ref.get("nodeId")),sourceName=text(ref.get("name"));
                    if(!nodes.containsKey(source))throw unavailable();
                    String declaredName=sourceName.split("\\.",2)[0];
                    if(!Set.of("errorCode","errorMessage").contains(declaredName))output(data(nodes,source),declaredName);
                    boolean batch=index==0 && iterations.contains(node.getKey()) && source.equals(mainStart);
                    if(!batch && !owner.get(node.getKey()).equals(owner.get(source)))throw unavailable();
                    if(source.equals(node.getKey()) || !closure.computeIfAbsent(source,id->reachable(id,next)).contains(node.getKey()))throw invalid("变量引用须来自同作用域上游节点");
                } else if(!"literal".equals(value.get("type")))throw invalid("输入值须为原literal或ref");
                index++;
            }
        }
    }
    private static void rejectNestedResources(JSONObject data) {
        ArrayDeque<Object> queue=new ArrayDeque<>();queue.add(data);
        while(!queue.isEmpty()) {
            Object value=queue.remove();if(value instanceof JSONObject object) {
                if(object.get("id") instanceof String id && RESOURCE.contains(NodeCatalog.typeOf(id)))throw unavailable();
                if(object.containsKey("$ref"))throw unavailable(); // 原CNValidator的外部schema解析不在本地纯图范围。
                for(Object child:object.values())if(child instanceof JSONObject || child instanceof JSONArray)queue.add(child);
            } else if(value instanceof JSONArray array)for(Object child:array)if(child instanceof JSONObject || child instanceof JSONArray)queue.add(child);
        }
    }
    private static void checkRetry(JSONObject data) {
        if(!data.containsKey("retryConfig"))return;JSONObject config=object(data,"retryConfig");
        if(!config.containsKey("shouldRetry") || !ModelNodeOptions.bool(config.get("shouldRetry"),"shouldRetry"))return;
        throw unavailable(); // 此批预算不宣称已覆盖原主动重试执行次数。
    }
    private static void checkPoolKeys(Map<String,JSONObject> nodes) {
        // 原VariablePool使用nodeId+'-'+name，两张映射分别检查；同节点input/output同名是合法配置。
        for(String field:List.of("inputs","outputs")) {
            Map<String,String> keys=new HashMap<>();
            for(var node:nodes.entrySet())for(Object raw:optionalArray(node.getValue().getJSONObject("data"),field)) {
                String name=text(entry(raw).get("name"));
                if(keys.putIfAbsent(node.getKey()+"-"+name,node.getKey())!=null)throw invalid("原变量池复合键发生碰撞");
            }
            if(field.equals("outputs"))for(String id:nodes.keySet())for(String name:List.of("errorCode","errorMessage")) {
                String previous=keys.putIfAbsent(id+"-"+name,id);
                if(previous!=null && !previous.equals(id))throw invalid("原变量池隐式错误输出复合键发生碰撞");
            }
        }
    }
    private static JSONObject output(JSONObject data,String name) {
        JSONObject selected=null;for(Object raw:array(data,"outputs")){JSONObject output=entry(raw);if(name.equals(output.get("name"))){if(selected!=null)throw invalid("节点输出名重复");selected=output;}}
        if(selected==null)throw invalid("迭代绑定的声明输出不存在");return selected;
    }
    private static JSONObject data(Map<String,JSONObject> nodes,String id){return nodes.get(id).getJSONObject("data");}
    private static JSONObject entry(Object value){if(!(value instanceof JSONObject object))throw invalid("节点字段必须为对象");return object;}
    private static JSONObject object(JSONObject parent,String key){return entry(parent.get(key));}
    private static JSONArray array(JSONObject object,String key){if(!(object.get(key) instanceof JSONArray array))throw invalid(key+"必须为数组");return array;}
    private static JSONArray optionalArray(JSONObject object,String key){return object.get(key)==null?new JSONArray():array(object,key);}
    private static String text(Object value){if(!(value instanceof String text) || text.isBlank())throw invalid("节点标识或引用名必须为非空字符串");return text;}
    private static ServiceException invalid(String message){return new ServiceException(message,400);}
    private static ServiceException budget(String message){return new ServiceException(message,413);}
    private static ServiceException unavailable(){return new ServiceException("本批仅接单层纯迭代、同域节点引用与主开始数组直接输入；其他资源/变量/重试能力尚未准入",503);}
}
