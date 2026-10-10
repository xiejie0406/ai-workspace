package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 原flowMeta/flowData包的预览与确认；每次重读完整文件，不信任客户端差异或资源授权。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkflowImportService {
    private static final int MAX=20*1024*1024;
    private static final String CREATE="paiflow:workflow:create",POLICY="paiflow-import-v1-unbound-resources";
    private static final Set<String> META_CONTROLLED=Set.of("id","flowId","spaceId","uid","userId","ownerUserId","appId","appKey","appSecret","botId","agentRevision","agentBindingStatus","ext","revision","latestVersionId","status","isPublic","canPublish","createBy","updateBy","createTime","updateTime","deleted","publishResult","publishChannel","publicationStatus","bindingStatus");
    private final DefaultPersonalSpaceService defaults;private final ResourceAccessService access;private final WorkflowService workflows;private final AgentService agents;
    public WorkflowImportService(DefaultPersonalSpaceService defaults,ResourceAccessService access,WorkflowService workflows,AgentService agents){this.defaults=defaults;this.access=access;this.workflows=workflows;this.agents=agents;}
    public record Difference(String field,String code,String message){}
    public record Preview(String previewHash,String fileHash,String policy,String name,int nodes,int edges,List<Difference> differences,DslValidationService.Report compatibility,boolean resourcesBound){}
    private record Prepared(JSONObject body,List<Difference> differences,DslValidationService.Report compatibility,String hash,String fileHash,DefaultPersonalSpaceService.Selection selection){}
    public Preview preview(byte[] bytes,String filename,String header){var prepared=prepare(bytes,filename,header);JSONObject graph=prepared.body.getJSONObject("data");return new Preview(prepared.hash,prepared.fileHash,POLICY,prepared.body.getString("name"),graph.getJSONArray("nodes").size(),graph.getJSONArray("edges").size(),prepared.differences,prepared.compatibility,false);}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject confirm(byte[] bytes,String filename,String header,String expected){
        var prepared=prepare(bytes,filename,header);
        if(expected==null||!expected.matches("[a-f0-9]{64}")||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),prepared.hash.getBytes(StandardCharsets.US_ASCII)))throw new ServiceException("导入文件、主体、空间或映射规则已变化，请重新预览",409);
        // 原导入同时产生工作流型Bot。沿中心Agent事务创建真实关系，不导入原App或凭据。
        long space=defaults.materialize(prepared.selection,CREATE);access.requireSpace(space,"paiflow:workflow:edit",3,true);access.requireSpace(space,"paiflow:agent:create",3,false);
        var body=prepared.body;String base=body.getString("name");if(base==null||base.isBlank())base="workflow";
        String name=(base.length()>6?base.substring(0,6):base)+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        var agentBody=JSONObject.of("name",name,"description",shortDescription(body.getString("description")),"data",body.get("data"),"engineProfile","python","avatar",body.get("avatarIcon"));
        JSONObject agent=agents.create(space,3,agentBody);long id=agent.getLongValue("workflowId");
        var draft=workflows.detail(id,space);body.put("name",name);JSONObject saved=workflows.save(id,space,draft.getLong("revision"),body);
        saved.put("importFileHash",prepared.fileHash);saved.put("importDifferences",prepared.differences);saved.put("importCompatibility",prepared.compatibility);saved.put("resourceBindingStatus","requires_binding");return saved;
    }
    private Prepared prepare(byte[] bytes,String filename,String header){
        if(bytes==null||bytes.length==0||bytes.length>MAX)throw new ServiceException("导入文件须为非空且不超过20MB的YAML",413);
        if(filename==null||!filename.toLowerCase(java.util.Locale.ROOT).matches(".*\\.ya?ml"))throw new ServiceException("只接受yml/yaml工作流文件",400);
        var selection=defaults.select(header,null,CREATE);defaults.existing(selection,CREATE);access.requirePermission("paiflow:agent:create");access.requirePermission("paiflow:workflow:edit");
        var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setAllowRecursiveKeys(false);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(48);options.setCodePointLimit(MAX);
        Object loaded;try{loaded=new Yaml(new SafeConstructor(options)).load(new ByteArrayInputStream(bytes));}catch(RuntimeException ex){throw new ServiceException("工作流YAML格式无效、重复字段或超出解析限制",400);}
        if(!(loaded instanceof Map<?,?> raw))throw new ServiceException("工作流包必须为对象",400);JSONObject root;
        try{root=JSON.parseObject(JSON.toJSONString(raw,JSONWriter.Feature.WriteMapNullValue));}catch(RuntimeException ex){throw new ServiceException("工作流包只能包含JSON兼容内容",400);}
        var meta=object(root.get("flowMeta"),"flowMeta");var graph=object(root.get("flowData"),"flowData");
        if(!(graph.get("nodes") instanceof JSONArray nodes)||!(graph.get("edges") instanceof JSONArray))throw new ServiceException("flowData缺少节点/连线数组",400);
        var differences=new ArrayList<Difference>();var body=new JSONObject(meta);
        for(String key:META_CONTROLLED)if(body.containsKey(key)){body.remove(key);differences.add(new Difference("flowMeta/"+key,"SERVER_OWNED","原账号、App、绑定或状态不带入中心"));}
        body.remove("data");body.remove("engineProfile");body.remove("flowConfig");body.put("engineProfile","python");
        if(body.get("advancedConfig")!=null)body.put("advancedConfig",object(body.get("advancedConfig"),"flowMeta/advancedConfig"));
        clearSecrets(body,"flowMeta",differences,0);
        for(int index=0;index<nodes.size();index++){
            if(!(nodes.get(index) instanceof JSONObject node))throw new ServiceException("导入节点须为对象：flowData/nodes/"+index,400);
            String path="flowData/nodes/"+index;String type=NodeCatalog.typeOf(node.getString("id"));
            if(NodeCatalog.find(type)==null)differences.add(new Difference(path+"/id","UNKNOWN_NODE","未知节点布局保全，现中心验证拒绝运行"));
            if(node.containsKey("flowId")){node.remove("flowId");differences.add(new Difference(path+"/flowId","SERVER_OWNED","节点所属旧流程不带入"));}
            if(!(node.get("data") instanceof JSONObject data))continue;
            if(data.get("nodeParam") instanceof String text)data.put("nodeParam",object(text,path+"/data/nodeParam"));
            if(!(data.get("nodeParam") instanceof JSONObject params))continue;
            remove(params,List.of("uid","userId","ownerUserId","appId","appKey","appSecret"),path+"/data/nodeParam",differences,"SERVER_OWNED");
            List<String> resourceKeys=switch(type){
                case "spark-llm","decision-making","extractor-parameter","question-answer"->List.of("llmId","modelId","serviceId","domain","url","source","patchId");
                case "plugin"->List.of("pluginId","toolId");case "flow"->List.of("flowId","workflowId","workflowVersionId");
                case "knowledge-base","knowledge-pro-base"->List.of("repoList","repoId","llmId","modelId","serviceId","domain","url");
                case "database"->List.of("dbId","tableId");case "rpa"->List.of("projectId","rpaId","runnerId");
                case "agent"->List.of("agentId","botId","llmId","modelId","serviceId","plugin","modelConfig");default->List.of();};
            remove(params,resourceKeys,path+"/data/nodeParam",differences,"RESOURCE_REBIND");
        }
        clearSecrets(graph,"flowData",differences,0);body.put("data",graph);WorkflowJsonBoundary.check(body,"import",16*1024*1024);
        var report=new DslValidationService().validate(graph,"python");String fileHash=hash(bytes);
        // 将全部字节、当前主体、空间选择及受控映射版本纳入确认收据；确认重跑同一解析/权限逻辑。
        String receipt=hash((fileHash+"\n"+selection.ownerUserId()+"\n"+selection.spaceId()+"\n"+selection.defaultPersonal()+"\n"+POLICY+"\n"+hash(body.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8));
        return new Prepared(body,List.copyOf(differences),report,receipt,fileHash,selection);
    }
    private static void remove(JSONObject object,List<String> keys,String path,List<Difference> changes,String code){for(String key:keys)if(object.containsKey(key)){object.remove(key);changes.add(new Difference(path+"/"+key,code,"导入后须用当前空间的中心资源重新绑定"));}}
    private static void clearSecrets(Object value,String path,List<Difference> differences,int depth){
        if(depth>48)throw new ServiceException("导入内容嵌套过深",400);
        if(value instanceof Map<?,?> map){
            @SuppressWarnings("unchecked") var object=(Map<String,Object>)map;
            for(String key:new ArrayList<>(object.keySet())){
                Object item=object.get(key);String location=path+"/"+key;boolean secret=key.equals("secretRef")||key.equals("secretRefs");
                if(!secret)try{RunJsonBoundary.check(Collections.singletonMap(key,item==null?null:"".equals(item)?"":"present"),Integer.MAX_VALUE);}catch(ServiceException ex){secret=true;}
                if(secret){object.remove(key);differences.add(new Difference(location,"SECRET_REBIND","凭据/密钥引用不从包中授权，导入后须由当前主体绑定"));continue;}
                if(item instanceof String text&&(text.trim().startsWith("{")||text.trim().startsWith("["))){Object nested;try{nested=JSON.parse(text);}catch(RuntimeException ex){continue;}if(nested instanceof Map<?,?>||nested instanceof List<?>){clearSecrets(nested,location,differences,depth+1);object.put(key,JSON.toJSONString(nested,JSONWriter.Feature.WriteMapNullValue));}}else clearSecrets(item,location,differences,depth+1);
            }
        }else if(value instanceof List<?> list){for(int i=0;i<list.size();i++)clearSecrets(list.get(i),path+"/"+i,differences,depth+1);}
    }
    private static JSONObject object(Object value,String field){try{if(value instanceof String text)value=JSON.parse(text);if(value instanceof JSONObject object)return object;}catch(RuntimeException ignored){}throw new ServiceException("导入字段必须为对象："+field,400);}
    private static String shortDescription(String value){return value==null?null:value.length()>200?value.substring(0,200):value;}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
