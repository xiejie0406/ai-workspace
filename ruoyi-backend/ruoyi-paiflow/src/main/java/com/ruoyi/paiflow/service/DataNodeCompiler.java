package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.util.*;

/** 已规范化执行图的数据节点接线；历史重建只用不可变公开快照，不读当前目录。 */
public final class DataNodeCompiler {
    public interface Resolver { DataRuntimeCatalog.FixedData bind(long dbId); }
    private DataNodeCompiler(){}
    public static void compile(JSONObject execution,JSONObject resources,Resolver resolver){walk(execution,resources,resolver,null,new HashSet<>());}
    public static Set<String> reconstruct(JSONObject execution,JSONObject resources){var used=new HashSet<String>();walk(execution,new JSONObject(),null,resources,used);return used;}
    private static void walk(Object value,JSONObject output,Resolver resolver,JSONObject stored,Set<String> used){
        if(!(value instanceof JSONObject execution) || !(execution.get("nodes") instanceof JSONArray nodes))throw DataResourceService.fail("固定执行图缺少节点数组",422);
        for(Object raw:nodes){
            if(!(raw instanceof JSONObject object))throw DataResourceService.fail("固定节点须为对象",422);
            if(object.get("id") instanceof String id && "database".equals(NodeCatalog.typeOf(id))){
                var data=object.getJSONObject("data");var p=data==null?null:data.getJSONObject("nodeParam");if(p==null)throw DataResourceService.fail("数据节点缺少参数",422);
                long db=DataResourceService.id(p.get("dbId"));String resource=null;JSONObject descriptor=null;
                if(resolver!=null){var fixed=resolver.bind(db);resource=fixed.resourceId();descriptor=fixed.descriptor();}
                else{for(var entry:stored.entrySet()){if(!entry.getKey().startsWith("data:"))continue;var ids=DataRuntimeCatalog.ids(entry.getKey());if(ids[0]==db){if(resource!=null)throw DataResourceService.fail("数据资源对应多个固定版本",409);resource=entry.getKey();descriptor=entry.getValue() instanceof JSONObject o?o:null;}}}
                if(resource==null || descriptor==null || !"database".equals(descriptor.getString("kind")) || !"center-data".equals(descriptor.getString("provider")) || descriptor.getLongValue("legacyDbId")!=db || !(descriptor.get("tables") instanceof JSONObject))throw DataResourceService.fail("数据节点缺少固定中心资源",409);
                if(p.containsKey("dataResourceId")&&!resource.equals(p.get("dataResourceId")))throw DataResourceService.fail("客户端不能更改固定数据资源",409);
                if(p.get("spaceId")!=null && !Objects.equals(String.valueOf(p.get("spaceId")),String.valueOf(descriptor.get("legacySpaceId"))))throw DataResourceService.fail("数据节点空间与中心资源不一致",409);
                long mode=DataResourceService.idOrZero(p.get("mode"));if(mode>4)throw DataResourceService.fail("数据操作模式无效",422);
                if(mode!=0){String table=DataResourceService.identifier(p.get("tableName"));var grant=descriptor.getJSONObject("tables").getJSONObject(table);if(grant==null || !grant.getJSONArray("allowedModes").contains((int)mode))throw DataResourceService.fail("数据节点未获逻辑表操作授权",403);}
                else if(!(p.get("sql") instanceof String sql)||sql.isBlank())throw DataResourceService.fail("CUSTOM节点SQL为空",422);
                // 原凭据只在已明确归属的参数路径移除，保留其他业务扩展和完整图。
                p.remove("apiKey");p.put("dataResourceId",resource);p.put("spaceId",descriptor.get("legacySpaceId"));p.putIfAbsent("appId","");p.put("uid","");output.put(resource,JSON.parseObject(descriptor.toJSONString(JSONWriter.Feature.WriteMapNullValue)));used.add(resource);
            }
        }
    }
}
