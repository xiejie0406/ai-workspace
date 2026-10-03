import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONField;
import com.ruoyi.paiflow.service.WorkflowExportService;
import com.iflytek.astron.console.toolkit.entity.biz.workflow.*;
import com.iflytek.astron.console.toolkit.entity.biz.workflow.node.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;

/** 只执行实际候选private投影与DTO字段检查，不构造/调用export业务服务或权限/Mapper。 */
public class WorkflowExportCurrentProjectionProbe {
    private static String type(String name) {
        return name.replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.node.BizInputOutput","Input")
                .replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.node.BizSchema","Schema")
                .replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.node.BizProperty","Property")
                .replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.node.BizValue","Value")
                .replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.node.BizNodeData","Data")
                .replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.BizWorkflowNode","Node")
                .replace("com.iflytek.astron.console.toolkit.entity.biz.workflow.BizWorkflowEdge","Edge")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceInput","Input")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceSchema","Schema")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceProperty","Property")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceValue","Value")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceData","Data")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceNode","Node")
                .replace("com.ruoyi.paiflow.service.WorkflowExportService$SourceEdge","Edge");
    }
    private static Map<String,Object> fields(Class<?> cls)throws Exception {
        var result=new LinkedHashMap<String,Object>();Object value=cls.getDeclaredConstructor().newInstance();
        for(Field field:cls.getDeclaredFields()) {
            if(field.isSynthetic() || Modifier.isStatic(field.getModifiers()))continue;
            field.setAccessible(true);var alias=field.getAnnotation(JSONField.class);
            String key=alias==null?field.getName():alias.name();
            result.put(key,JSONObject.of("type",type(field.getGenericType().getTypeName()),"initialValue",field.get(value)));
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        Class<?>[][] pairs={{BizWorkflowData.class,WorkflowExportService.SourceGraph.class},{BizWorkflowNode.class,WorkflowExportService.SourceNode.class},
                {BizWorkflowEdge.class,WorkflowExportService.SourceEdge.class},{BizNodeData.class,WorkflowExportService.SourceData.class},
                {BizInputOutput.class,WorkflowExportService.SourceInput.class},{BizSchema.class,WorkflowExportService.SourceSchema.class},
                {BizProperty.class,WorkflowExportService.SourceProperty.class},{BizValue.class,WorkflowExportService.SourceValue.class}};
        var dtoRows=new JSONArray();
        for(Class<?>[] pair:pairs) {
            var original=fields(pair[0]);var current=fields(pair[1]);
            if(!original.equals(current))throw new AssertionError("原known DTO字段/类型/default不一致："+pair[0].getSimpleName());
            dtoRows.add(JSONObject.of("original",pair[0].getName(),"current",pair[1].getName(),"fields",current,"exactFieldsAndInitialValuesMatch",true));
        }
        var golden=JSON.parseObject(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8));
        Method project=WorkflowExportService.class.getDeclaredMethod("project",Object.class,Object.class);project.setAccessible(true);
        var cases=new JSONArray();
        for(Object raw:golden.getJSONArray("fixtures")) {
            JSONObject fixture=(JSONObject)raw;JSONObject row=JSONObject.of("id",fixture.get("id"));
            try {
                Object canvas=fixture.containsKey("canvasRaw")?JSON.parse(fixture.getString("canvasRaw")):fixture.get("canvas");
                var typed=JSON.parseObject(JSON.toJSONString(canvas,JSONWriter.Feature.WriteMapNullValue),WorkflowExportService.SourceGraph.class);
                row.put("currentProject",project.invoke(null,canvas,typed));
            } catch(Exception exception) {row.put("errorType",exception.getClass().getName());row.put("errorMessage",exception.getMessage());}
            cases.add(row);
        }
        System.out.println("EXPORT_CURRENT_PROJECTION="+JSON.toJSONString(JSONObject.of("dtoPairs",dtoRows,"currentCases",cases),JSONWriter.Feature.WriteMapNullValue));
    }
}
