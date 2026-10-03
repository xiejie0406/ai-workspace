import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.console.toolkit.tool.JsonConverter;
import com.iflytek.astron.console.toolkit.entity.biz.workflow.node.BizInputOutput;
import com.iflytek.astron.console.commons.dto.workflow.WorkflowInputTypeDto;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 原纯转换类与DTO的独立读取探针；不加载MaasUtil或业务Service。 */
public class InputTypesSourceReview {
    public static void main(String[] args) throws Exception {
        var cases=JSON.parseArray(Files.readString(Path.of(args[0])));
        var report=new JSONArray();
        for(Object value:cases) {
            var c=(JSONObject)value;
            var raw=c.getJSONArray("rawStartOutputs");
            String rawJson=raw.toJSONString(JSONWriter.Feature.WriteMapNullValue);
            List<BizInputOutput> business=JSON.parseArray(rawJson,BizInputOutput.class);
            // 精确对应原getInputsInfo中的JSON.toJSONString(outputs)。
            String toolkitJson=JSON.toJSONString(business);
            var toolkitArray=JSON.parseArray(toolkitJson);
            List<WorkflowInputTypeDto> dtos=toolkitArray.toJavaList(WorkflowInputTypeDto.class);
            var fields=new JSONArray();
            for(var dto:dtos) {
                var row=new JSONObject();
                for(Field field:WorkflowInputTypeDto.class.getDeclaredFields()) {
                    if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
                    field.setAccessible(true);
                    row.put(field.getName(),field.get(dto));
                }
                fields.add(row);
            }
            var observation=new JSONObject();
            observation.put("id",c.getString("id"));
            observation.put("actualRawTypeTemplate",JsonConverter.flowInputTypeConvert(rawJson));
            observation.put("actualRawValueTemplate",JsonConverter.flowInputTemplateConvert(rawJson));
            observation.put("actualGetInputsInfoData",toolkitArray);
            observation.put("actualGetInputsInfoDataString",toolkitJson);
            observation.put("actualTypedTypeTemplate",JsonConverter.flowInputTypeConvert(toolkitJson));
            observation.put("actualTypedValueTemplate",JsonConverter.flowInputTemplateConvert(toolkitJson));
            observation.put("actualDtoFieldState",fields);
            report.add(observation);
        }
        var emptyArray=new JSONArray();
        String emptyArrayFailure=null;
        try { emptyArray.getString(0); }
        catch(Exception ex) { emptyArrayFailure=ex.getClass().getName(); }
        System.out.println(JSON.toJSONString(JSONObject.of("cases",report,
            "formatPrimitiveProbes",JSONObject.of("emptyAllowedFileTypeGetStringZero",emptyArrayFailure),
            "fullMaasUtilExecuted",false,"httpSerializerExecuted",false),JSONWriter.Feature.WriteMapNullValue));
    }
}
