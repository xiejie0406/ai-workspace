import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
public class WorkflowJsonMergeReview {
 static Object invoke(String name,Class<?>[] types,Object... args) throws Exception {
  Method m=com.ruoyi.paiflow.service.WorkflowService.class.getDeclaredMethod(name,types);m.setAccessible(true);
  try{return m.invoke(null,args);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
 }
 public static void main(String[] args) throws Exception {
  ObjectMapper mapper=new ObjectMapper();JSONObject report=new JSONObject();
  Object patch=mapper.readValue("{\"part\":{\"a\":null},\"arr\":[2],\"scalar\":false}",JSONObject.class);
  String merged=(String)invoke("mergeAdvanced",new Class<?>[]{String.class,Object.class},"{\"part\":{\"a\":1,\"b\":2},\"arr\":[1,3],\"scalar\":{\"old\":true}}",patch);
  report.put("advancedResult",merged);
  try {invoke("mergeAdvanced",new Class<?>[]{String.class,Object.class},"{\"provider\":{\"apiSecret\":\"isolated-fixture-never-disclose\"}}",mapper.readValue("{\"newKey\":true}",JSONObject.class));report.put("historicSecret","accepted");}
  catch(com.ruoyi.common.exception.ServiceException e){report.put("historicSecret",JSONObject.of("code",e.getCode(),"containsPath",e.getMessage().contains("advancedConfig/provider/apiSecret"),"leaksValue",e.getMessage().contains("isolated-fixture-never-disclose")));}
  try {String c=(String)invoke("canvas",new Class<?>[]{Object.class},mapper.readValue("{\"nodes\":\"[]\",\"edges\":\"[]\"}",JSONObject.class));report.put("stringArrayFields",JSONObject.of("accepted",true,"result",c));}
  catch(com.ruoyi.common.exception.ServiceException e){report.put("stringArrayFields",JSONObject.of("accepted",false,"code",e.getCode()));}
  String old=JSONObject.of("existing","x".repeat(9*1024*1024)).toJSONString();
  JSONObject largeNull=new JSONObject();largeNull.put("n".repeat(9*1024*1024),null);
  try {String result=(String)invoke("mergeAdvanced",new Class<?>[]{String.class,Object.class},old,largeNull);report.put("nullKeyCombinedBudget",JSONObject.of("accepted",true,"resultBytes",result.getBytes(StandardCharsets.UTF_8).length,"limit",16*1024*1024));}
  catch(com.ruoyi.common.exception.ServiceException e){report.put("nullKeyCombinedBudget",JSONObject.of("accepted",false,"code",e.getCode()));}
  System.out.println(report.toJSONString());
 }
}
