package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.PcException;
import java.util.*;

/** 保留Runner真实检索证据；原索引未提供的行号或文件哈希不得推算。 */
public final class PcKnowledgeProjection {
    private PcKnowledgeProjection() {}
    public static List<Map<String,Object>> hits(Map<String,Object> payload,long indexVersion) {
        Object raw=payload.getOrDefault("hits",payload.getOrDefault("results",List.of()));
        if(!(raw instanceof List<?> list)||list.size()>100)throw PcException.invalid("检索结果应为最多100条数组");
        List<Map<String,Object>> hits=new ArrayList<>();
        for(Object value:list){if(!(value instanceof Map<?,?> item))throw PcException.invalid("检索命中不是对象");Map<String,Object> hit=new LinkedHashMap<>();
            hit.put("file",Objects.toString(item.get("file"),Objects.toString(item.get("filePath"),"")));
            hit.put("snippet",Objects.toString(item.get("snippet"),Objects.toString(item.get("content"),"")));
            hit.put("startLine",positive(item.get("startLine")));hit.put("endLine",positive(item.get("endLine")));
            Object hash=item.get("fileHash");hit.put("fileHash",hash!=null&&hash.toString().matches("[a-f0-9]{64}")?hash:null);
            hit.put("indexVersion",indexVersion);hit.put("stale",Boolean.TRUE.equals(payload.get("stale")));
            hit.put("source","SEMANTIC".equals(payload.get("mode"))?"SEMANTIC":"EXACT");
            Object score=item.containsKey("score")?item.get("score"):item.get("similarity");if(score instanceof Number)hit.put("score",score);
            for(String field:List.of("name","chunkType"))if(item.containsKey(field))hit.put(field,item.get(field));hits.add(hit);
        }return hits;
    }
    public static Map<String,Object> graph(Map<String,Object> payload,long indexVersion) {
        if(payload.get("graph") instanceof Map<?,?> graph&&graph.get("nodes") instanceof List<?> &&graph.get("edges") instanceof List<?>){Map<String,Object> result=new LinkedHashMap<>();graph.forEach((k,v)->result.put(String.valueOf(k),v));result.put("indexVersion",indexVersion);result.put("stale",Boolean.TRUE.equals(payload.get("stale")));return result;}
        Object raw=payload.getOrDefault("results",List.of());if(!(raw instanceof List<?> list)||list.size()>10000)throw PcException.invalid("关系图结果数组非法");
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();List<Map<String,Object>> edges=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object value:list){if(!(value instanceof Map<?,?> relation))throw PcException.invalid("关系不是对象");String from=node(nodes,relation.get("fromFile"),relation.get("fromName")),to=node(nodes,relation.get("toFile"),relation.get("toName"));String type=Objects.toString(relation.get("relationType"),"UNKNOWN");if(seen.add(from+":"+to+":"+type))edges.add(Map.of("source",from,"target",to,"type",type));}
        return Map.of("nodes",new ArrayList<>(nodes.values()),"edges",edges,"indexVersion",indexVersion,"stale",Boolean.TRUE.equals(payload.get("stale")));
    }
    private static String node(Map<String,Map<String,Object>> nodes,Object file,Object name){String label=Objects.toString(name,""),path=Objects.toString(file,"");String id=PcJson.digest(path+"\u0000"+label);if(!nodes.containsKey(id)){Map<String,Object> n=new LinkedHashMap<>();n.put("id",id);n.put("label",label);n.put("type","SYMBOL");n.put("file",path.isBlank()?null:path);n.put("line",null);nodes.put(id,n);}return id;}
    private static Object positive(Object value){return value instanceof Number n&&n.longValue()>0?n.longValue():null;}
}
