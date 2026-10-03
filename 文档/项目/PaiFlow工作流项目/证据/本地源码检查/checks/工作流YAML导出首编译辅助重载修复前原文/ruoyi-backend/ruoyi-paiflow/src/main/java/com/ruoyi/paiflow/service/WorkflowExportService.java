package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONField;
import com.ruoyi.common.exception.ServiceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/** 当前草稿的原YAML导出；不编译执行图，不建立版本、App或资源授权。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkflowExportService {
    private static final String QUERY="paiflow:workflow:query";
    private static final int DRAFT_BYTES=16*1024*1024;
    private static final int EXPORT_BYTES=32*1024*1024;
    private final WorkflowService workflows;
    private final DefaultPersonalSpaceService defaults;
    private final ResourceAccessService access;
    public WorkflowExportService(WorkflowService workflows,DefaultPersonalSpaceService defaults,ResourceAccessService access) {
        this.workflows=workflows;this.defaults=defaults;this.access=access;
    }
    /** 读到同一草稿后只处理本地副本；响应提交前重新核对现行平台/成员/主体。 */
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Export export(long id,String header) {
        if(id<=0)throw new ServiceException("工作流标识无效",400);
        long actor=access.requirePermission(QUERY);
        var selection=defaults.select(header,null,QUERY);
        Long space=defaults.existing(selection,QUERY);
        if(space==null)throw new ServiceException("工作流不存在或不可访问",404);
        sameActor(actor,space);
        var row=workflows.require(id,space,QUERY,3,false);
        // String是不可变快照；不能让后续MyBatis复读或typed投影改写原画布。
        String rawCanvas=row.getCanvasJson(),rawMetadata=row.getMetadataJson(),rawAdvanced=row.getAdvancedConfigJson();
        String name=row.getName(),description=row.getDescription();
        JSONObject canvas=object(rawCanvas,"flowData"),metadata=object(rawMetadata,"flowMeta");
        WorkflowJsonBoundary.check(canvas,"flowData",DRAFT_BYTES);
        WorkflowJsonBoundary.check(metadata,"metadata",DRAFT_BYTES);
        WorkflowJsonBoundary.check(object(rawAdvanced,"flowMeta/advancedConfig"),"flowMeta/advancedConfig",DRAFT_BYTES);
        var cleared=new ArrayList<String>();clearInitiator(canvas,cleared);
        SourceGraph typed;
        try {typed=JSON.parseObject(canvas.toJSONString(JSONWriter.Feature.WriteMapNullValue),SourceGraph.class);}
        catch(RuntimeException ex){throw new ServiceException("工作流画布不符合原导出字段类型",400);}
        JSONObject flowData=(JSONObject)project(canvas,typed);
        // JSONObject原typed转换可能将字符串形式的nodeParam还原成对象；最终副本也须核同一精确路径。
        clearInitiator(flowData,cleared);
        var metaInput=new JSONObject();metaInput.put("name",name);metaInput.put("description",description);
        for(String key:List.of("avatarIcon","avatarColor","edgeType","category"))if(metadata.containsKey(key))metaInput.put(key,metadata.get(key));
        metaInput.put("advancedConfig",rawAdvanced);
        SourceMeta typedMeta;
        try {typedMeta=JSON.parseObject(metaInput.toJSONString(JSONWriter.Feature.WriteMapNullValue),SourceMeta.class);}
        catch(RuntimeException ex){throw new ServiceException("工作流元信息不符合原导出字段类型",400);}
        Map<String,Object> meta=new LinkedHashMap<>();
        meta.put("name",typedMeta.name);meta.put("description",typedMeta.description);meta.put("avatarIcon",typedMeta.avatarIcon);
        meta.put("avatarColor",typedMeta.avatarColor);meta.put("edgeType",typedMeta.edgeType);meta.put("category",typedMeta.category);meta.put("advancedConfig",typedMeta.advancedConfig);
        meta.values().removeIf(value->value==null);meta.put("dslVersion","v1");
        Map<String,Object> wrapper=new LinkedHashMap<>();wrapper.put("flowMeta",meta);wrapper.put("flowData",flowData);
        WorkflowJsonBoundary.check(wrapper,"export",EXPORT_BYTES);
        byte[] bytes=dump(wrapper,cleared);
        // requireSpace清当前SqlSession后再复读WF，避免缓存行掩盖撤权/删除/域变更。
        sameActor(actor,space);workflows.require(id,space,QUERY,3,false);sameActor(actor,space);
        return new Export(bytes);
    }
    private void sameActor(long actor,long space) {
        access.requireSpace(space,QUERY,3,false);
        if(access.requirePermission(QUERY)!=actor)throw new ServiceException("工作流导出主体已经变化",403);
    }
    private static JSONObject object(String text,String path) {
        try {
            Object parsed=JSON.parse(text);
            if(parsed instanceof JSONObject object)return object;
        }catch(RuntimeException ignored) { /* 不将损坏草稿或远端材料回显。 */ }
        throw new ServiceException("工作流导出字段必须为JSON对象："+path,400);
    }
    private static void clearInitiator(JSONObject canvas,List<String> paths) {
        if(!(canvas.get("nodes") instanceof JSONArray nodes) || !(canvas.get("edges") instanceof JSONArray))
            throw new ServiceException("工作流导出画布缺少节点或连线数组",400);
        for(int index=0;index<nodes.size();index++) {
            if(!(nodes.get(index) instanceof JSONObject node))throw new ServiceException("工作流导出节点必须为对象：flowData/nodes/"+index,400);
            if(!(node.get("data") instanceof JSONObject data) || !(data.get("nodeParam") instanceof JSONObject parameters))continue;
            Object uid=parameters.get("uid");String path="flowData/nodes/"+index+"/data/nodeParam/uid";
            if(uid==null)continue;
            if(!(uid instanceof String text))throw new ServiceException("节点发起者UID必须为字符串："+path,400);
            if(!text.isEmpty()){parameters.put("uid","");paths.add(path);}
        }
    }
    /** 已知字段沿原Biz DTO做typed转换；设计2.2保全未知布局/扩展与显式null。 */
    private static Object project(Object raw,Object typed) {
        if(typed==null)return null;
        if(typed instanceof Map<?,?> || typed instanceof String || typed instanceof Number || typed instanceof Boolean)return typed;
        if(typed instanceof List<?> list) {
            var result=new JSONArray();List<?> original=raw instanceof List<?> values?values:List.of();
            for(int i=0;i<list.size();i++)result.add(project(i<original.size()?original.get(i):null,list.get(i)));
            return result;
        }
        if(!(raw instanceof JSONObject original))throw new ServiceException("工作流原DTO字段必须为对象",400);
        var result=new JSONObject(original);
        try {
            for(Field field:typed.getClass().getFields()) {
                JSONField alias=field.getAnnotation(JSONField.class);String key=alias==null?field.getName():alias.name();
                if(!original.containsKey(key))continue;
                if(original.get(key)==null){result.put(key,null);continue;}
                Object value=field.get(typed);
                if(value==null)result.remove(key);else result.put(key,project(original.get(key),value));
            }
        }catch(IllegalAccessException ex){throw new IllegalStateException("原导出DTO字段不可访问",ex);}
        return result;
    }
    private static byte[] dump(Map<String,Object> wrapper,List<String> cleared) {
        var options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setPrettyFlow(true);
        options.setIndent(2);options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        var loader=new LoaderOptions();var representer=new Representer(options);representer.getPropertyUtils().setSkipMissingProperties(true);
        var yaml=new Yaml(new SafeConstructor(loader),representer,options,loader);
        var output=new BoundedOutput(EXPORT_BYTES);
        try(var writer=new OutputStreamWriter(output,StandardCharsets.UTF_8)) {
            if(!cleared.isEmpty()) {
                writer.write("# 已清空节点发起者UID，导入后由当前中心身份确定\n");
                for(String path:cleared)writer.write("# "+path+"\n");
            }
            yaml.dump(wrapper,writer);writer.flush();return output.bytes();
        }catch(RuntimeException | IOException ex) {
            for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof ServiceException service)throw service;
            throw new ServiceException("工作流YAML导出失败",400);
        }
    }
    /** dump期间按真正UTF8字节停止；不先积累无界YAML字符串。 */
    private static final class BoundedOutput extends OutputStream {
        private final int maximum;private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        BoundedOutput(int maximum){this.maximum=maximum;}
        @Override public void write(int value){reserve(1);buffer.write(value);}
        @Override public void write(byte[] bytes,int offset,int length){reserve(length);buffer.write(bytes,offset,length);}
        private void reserve(int amount){if(amount>maximum-buffer.size())throw new ServiceException("工作流YAML超出允许大小",413);}
        byte[] bytes(){return buffer.toByteArray();}
    }
    public record Export(byte[] bytes) {
        public Export {bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    // 原8个Biz DTO的导出字段；不作为执行schema，不迁入原导入/Provider/环境解析。
    public static final class SourceMeta {public String name,description,avatarIcon,avatarColor,edgeType,advancedConfig;public Integer category;}
    public static final class SourceGraph {public List<SourceNode> nodes;public List<SourceEdge> edges;}
    public static final class SourceNode {
        public String id,flowId,name,icon,type,parentId,extent;public Boolean dragging,selected;public Integer width,height;
        public Object position,positionAbsolute,zIndex,draggable;public SourceData data;
    }
    public static final class SourceEdge {public String source,sourceHandle,target,targetHandle,id;public Object type,markerEnd,zIndex,data;}
    public static final class SourceData {
        public String id,label,status,icon,description,parentId,pluginName;public Boolean allowInputReference,allowOutputReference,labelEdit,updatable,isLatest;
        public Object references,originPosition;public JSONObject nodeMeta,nodeParam,retryConfig,errorOutputs;public List<SourceInput> inputs,outputs;
    }
    public static final class SourceInput {
        public String id,name,nameErrMsg,fileType,description,customParameterType;public SourceSchema schema;public List<String> allowedFileType;
        public Boolean required;public Object refId,deleteDisabled,disabled;
    }
    public static final class SourceSchema {
        public String type,description;public SourceValue value;@JSONField(name="default")public Object dft;public JSONObject item;public List<SourceProperty> properties;
    }
    public static final class SourceProperty {
        public String id,name,type;@JSONField(name="default")public String dft;public Boolean required;public List<SourceProperty> properties;
    }
    public static final class SourceValue {public String type,contentErrMsg;public Object content;}
}
