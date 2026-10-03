import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
public class WorkflowJsonNullReview {
  static Object invoke(String name,Class<?>[] types,Object... args) throws Exception {
    Method method=com.ruoyi.paiflow.service.WorkflowService.class.getDeclaredMethod(name,types);
    method.setAccessible(true);return method.invoke(null,args);
  }
  public static void main(String[] args) throws Exception {
    ObjectMapper mapper=new ObjectMapper();
    JSONObject wire=mapper.readValue("{\"data\":{\"nodes\":[],\"edges\":[],\"futureRoot\":null,\"futureNested\":{\"keepNull\":null}}}",JSONObject.class);
    Object patch=wire.get("data");
    String created=(String)invoke("canvas",new Class<?>[]{Object.class},patch);
    String updated=(String)invoke("updateCanvas",new Class<?>[]{String.class,Object.class},"{\"nodes\":[],\"edges\":[],\"futureRoot\":\"old\",\"retainOld\":true}",patch);
    JSONObject c=JSON.parseObject(created),u=JSON.parseObject(updated);
    JSONObject result=JSONObject.of("patchType",patch.getClass().getName(),"createResult",created,"updateResult",updated,"createRootNullPreserved",c.containsKey("futureRoot") && c.get("futureRoot")==null,"createNestedNullPreserved",c.getJSONObject("futureNested").containsKey("keepNull"),"explicitPatchNullClearedOld",u.containsKey("futureRoot") && u.get("futureRoot")==null,"unmentionedOldPreserved",u.getBooleanValue("retainOld"));
    System.out.println(result.toJSONString());
  }
}
