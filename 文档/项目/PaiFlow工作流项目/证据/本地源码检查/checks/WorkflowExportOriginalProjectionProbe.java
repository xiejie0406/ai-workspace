import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.console.commons.entity.workflow.Workflow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 原 DTO 和原导出纯算法私有执行，权限和异常包装不执行，不代表原 Service 联机。 */
public class WorkflowExportOriginalProjectionProbe {
    public static void main(String[] args) throws Exception {
        JSONArray fixtures=JSON.parseArray(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8));
        JSONArray results=new JSONArray();
        for(Object value:fixtures) {
            JSONObject fixture=(JSONObject)value;
            Workflow workflow=JSON.parseObject(fixture.getJSONObject("workflow").toJSONString(JSONWriter.Feature.WriteMapNullValue),Workflow.class);
            workflow.setData(fixture.containsKey("canvasRaw")?fixture.getString("canvasRaw"):
                    JSON.toJSONString(fixture.get("canvas"),JSONWriter.Feature.WriteMapNullValue));
            var result=new LinkedHashMap<String,Object>();result.put("id",fixture.getString("id"));
            try {
                byte[] output=OriginalExportProjection.project(workflow);
                result.put("yamlUTF8Bytes",output.length);
                result.put("yamlSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output)));
                result.put("yamlUTF8Base64",Base64.getEncoder().encodeToString(output));
                result.put("yamlText",new String(output,StandardCharsets.UTF_8));
                Object parsed=new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(output,StandardCharsets.UTF_8));
                result.put("parsedYaml",parsed);
                if(!(parsed instanceof Map<?,?> map) || !map.keySet().equals(java.util.Set.of("flowMeta","flowData")))throw new AssertionError("原二根字段");
            } catch(Exception exception) {
                result.put("errorType",exception.getClass().getName());result.put("errorMessage",exception.getMessage());
            }
            results.add(result);
        }
        // 报告投影保null只为记录实际结果；实际被核原算法仍NON_NULL。
        System.out.println(JSON.toJSONString(results,JSONWriter.Feature.WriteMapNullValue));
    }
}
