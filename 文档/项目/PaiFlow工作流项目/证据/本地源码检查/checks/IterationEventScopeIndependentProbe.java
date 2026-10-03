import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.PureIterationPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

/** 私有有限probe：直接调用冻结的真实Policy，不运行Factory、Mapper或服务。 */
public class IterationEventScopeIndependentProbe {
    public static void main(String[] args) throws Exception {
        JSONArray cases=JSON.parseArray(Files.readString(Path.of(args[0]))), rows=new JSONArray();
        for(Object value:cases) {
            JSONObject test=(JSONObject)value;
            boolean accepted=false; Integer code=null; String errorType=null;
            try {
                PureIterationPolicy.requireEventScope(test.getJSONObject("dsl"),test.getJSONObject("input"),
                    (String)test.get("nodeId"),(String)test.get("iterationId"));
                accepted=true;
            } catch(ServiceException error) {
                code=error.getCode(); errorType=error.getClass().getSimpleName();
            } catch(Throwable error) {
                errorType=error.getClass().getSimpleName();
            }
            JSONObject row=JSONObject.of("name",test.get("name"),"expected",test.get("expected"),
                "accepted",accepted,"code",code,"errorType",errorType);
            row.put("matches",accepted==test.getBooleanValue("expected") && (accepted || "ServiceException".equals(errorType)));
            rows.add(row);
        }
        System.out.println(JSON.toJSONString(rows,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        if(rows.stream().anyMatch(r->!((JSONObject)r).getBooleanValue("matches")))System.exit(1);
    }
}
