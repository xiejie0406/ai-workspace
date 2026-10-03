package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.annotation.JSONField;
import com.ruoyi.common.exception.ServiceException;
import java.util.List;
import java.util.Locale;

/** 原Toolkit Biz DTO读取投影与Maas本地元数据算法；不编译DSL、不创建App或上传文件。 */
public final class WorkflowInputTypesPolicy {
    private WorkflowInputTypesPolicy() {}
    private static final List<String> NO_SUPPORT=List.of("string","integer","boolean","number","object","array-string","array-integer","array-boolean","array-number","array-object");
    public record Projection(JSONArray responseFields,String extraInputsConfig,String extraInputs,int supportUpload) {}
    public static Projection project(JSONObject canvas) {
        if(canvas==null || !(canvas.get("nodes") instanceof JSONArray nodes))throw invalid();
        // 原getInputsInfo依画布次序选择首个node-start；不以执行图、parentId或资源元数据替代。
        for(Object value:nodes) {
            if(!(value instanceof JSONObject node) || !(node.get("id") instanceof String id))throw invalid();
            if(id.startsWith("node-start")) {
                if(!(node.get("data") instanceof JSONObject data) || !(data.get("outputs") instanceof JSONArray outputs))throw invalid();
                return projectOutputs(outputs);
            }
        }
        throw invalid();
    }
    public static Projection projectOutputs(JSONArray outputs) {
        if(outputs==null)throw invalid();WorkflowJsonBoundary.check(outputs,"input-types",1024*1024);
        JSONArray typed;
        try {
            // 精确原JSON.parseArray(BizInputOutput)→默认JSON.toJSONString；开放Object内部也省null。
            List<SourceInput> values=JSON.parseArray(outputs.toJSONString(JSONWriter.Feature.WriteMapNullValue),SourceInput.class);
            typed=JSON.parseArray(JSON.toJSONString(values));
        }catch(RuntimeException ex){throw invalid();}
        var extras=new JSONArray();
        for(Object value:typed) {
            if(!(value instanceof JSONObject row) || !(row.get("schema") instanceof JSONObject schema))throw invalid();
            String name=row.getString("name");if(name==null)throw invalid();
            JSONArray allowed=row.getJSONArray("allowedFileType");
            // 原Maas空列表/首项null会异常；有file但无schema亦会破坏原页面，明确拒绝且不写。
            if(allowed!=null && (allowed.isEmpty() || allowed.get(0)==null))throw invalid();
            if("AGENT_USER_INPUT".equals(name))continue;
            var extra=new JSONObject();String type;
            if(allowed!=null) {
                type=allowed.getString(0).toLowerCase(Locale.ROOT);extra.put(name,type);extra.put("required",row.getBoolean("required"));
                extra.put("schema",schema);extra.put("name",name);extra.put("type",type);extra.put("fullParam",row);
            }else {
                type=schema.getString("type");extra.put(name,type);extra.put(name+"_required",row.getBoolean("required"));extra.put("name",name);extra.put("type",type);extra.put("schema",schema);
            }
            extras.add(extra);
        }
        JSONObject selected=new JSONObject();
        for(Object value:extras) {
            var extra=(JSONObject)value;String type=extra.getString("type");
            if(!"array-string".equalsIgnoreCase(extra.getJSONObject("schema").getString("type")) && (type==null || !NO_SUPPORT.contains(type))) {selected=extra;break;}
        }
        int support=fileType(selected.getString("type"));
        WorkflowJsonBoundary.check(typed,"input-types/response",1024*1024);WorkflowJsonBoundary.check(extras,"input-types/metadata",1024*1024);
        return new Projection(typed,extras.toJSONString(),selected.toJSONString(),support);
    }
    private static int fileType(String type) {
        if(type==null)return 0;
        return switch(type.toLowerCase(Locale.ROOT)){case "pdf"->1;case "image"->2;case "doc"->3;case "ppt"->4;case "excel"->5;case "txt"->6;case "audio"->7;default->0;};
    }
    private static ServiceException invalid(){return new ServiceException("原工作流开始输入参数结构无效",400);}
    // 仅legacy读取边界复刻原四个typed DTO字段；不作为画布存储或执行schema。
    public static final class SourceInput {
        public String id,name,nameErrMsg;public SourceSchema schema;public List<String> allowedFileType;
        public String fileType,description;public Boolean required;public Object refId,deleteDisabled,disabled;public String customParameterType;
    }
    public static final class SourceSchema {
        public String type;public SourceValue value;@JSONField(name="default") public Object dft;
        public JSONObject item;public String description;public List<SourceProperty> properties;
    }
    public static final class SourceProperty {
        public String id,name;@JSONField(name="default") public String dft;public Boolean required;public String type;public List<SourceProperty> properties;
    }
    public static final class SourceValue {public String type;public Object content;public String contentErrMsg;}
}
