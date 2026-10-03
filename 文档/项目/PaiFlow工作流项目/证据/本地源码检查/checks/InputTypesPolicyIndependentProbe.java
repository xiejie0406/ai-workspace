import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowInputTypesPolicy;
import java.nio.file.*;

/** 实际当前纯策略的独立小探针；无Service/Mapper/HTTP/数据库。 */
public class InputTypesPolicyIndependentProbe {
    public static void main(String[] args) throws Exception {
        var cases=JSON.parseArray(Files.readString(Path.of(args[0])));var observations=new JSONArray();
        for(Object value:cases){
            var c=(JSONObject)value;var outputs=c.getJSONArray("rawStartOutputs");String before=outputs.toJSONString(JSONWriter.Feature.WriteMapNullValue);
            var result=JSONObject.of("id",c.getString("id"));
            try{var p=WorkflowInputTypesPolicy.projectOutputs(outputs);result.put("status","accepted");result.put("fields",p.responseFields());result.put("extraInputsConfig",JSON.parseArray(p.extraInputsConfig()));result.put("extraInputs",JSON.parseObject(p.extraInputs()));result.put("supportUpload",p.supportUpload());}
            catch(ServiceException error){result.put("status","rejected");result.put("code",error.getCode());result.put("message",error.getMessage());}
            result.put("rawUnchanged",before.equals(outputs.toJSONString(JSONWriter.Feature.WriteMapNullValue)));observations.add(result);
        }
        System.out.println(observations.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
}
