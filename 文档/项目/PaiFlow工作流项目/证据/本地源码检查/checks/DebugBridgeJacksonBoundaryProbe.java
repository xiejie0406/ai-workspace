import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.lang.reflect.Method;
import java.util.Map;

/** 仅真实Jackson DTO内存反序列化；不加载候选Service，不执行HTTP/DB/Provider。 */
public class DebugBridgeJacksonBoundaryProbe {
    public static void main(String[] args)throws Exception {
        String input="{\"debugReceipt\":{\"workflowVersionId\":\"1\",\"comparisonId\":null},\"inputs\":{\"opaque\":null},\"regen\":false}";
        var results=new JSONObject();
        for(String name:args){
            Class<?> mapperType=Class.forName(name);
            Object mapper=mapperType.getConstructor().newInstance();
            Method read=mapperType.getMethod("readValue",String.class,Class.class);
            JSONObject body=(JSONObject)read.invoke(mapper,input,JSONObject.class);
            Object receipt=body.get("debugReceipt"),inputs=body.get("inputs");
            var row=JSONObject.of("receiptType",receipt.getClass().getName(),"inputsType",inputs.getClass().getName(),
                    "currentSnapshotInstanceofAccepted",receipt instanceof JSONObject,"currentChatInstanceofAccepted",inputs instanceof JSONObject,
                    "getJSONObjectAccepted",body.getJSONObject("debugReceipt")!=null && body.getJSONObject("inputs")!=null,
                    "rawInputsNullPreserved",inputs instanceof Map<?,?> m && m.containsKey("opaque") && m.get("opaque")==null,
                    "adaptedInputsNullPreserved",body.getJSONObject("inputs").containsKey("opaque") && body.getJSONObject("inputs").get("opaque")==null);
            if(!Boolean.FALSE.equals(row.get("currentSnapshotInstanceofAccepted")) || !Boolean.FALSE.equals(row.get("currentChatInstanceofAccepted")))throw new AssertionError("预期原Jackson嵌套Map边界变化，请保留实际结果核对");
            results.put(name,row);
        }
        System.out.println(results.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
}
