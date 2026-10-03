import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.service.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public class StaticQaNumberProbe {
 static void compare(Object a,Object b,String path){
  if(a instanceof Map<?,?> am && b instanceof Map<?,?> bm){for(var k:am.keySet())compare(am.get(k),bm.get(k),path+"."+k);return;}
  if(a instanceof List<?> al && b instanceof List<?> bl){for(int i=0;i<al.size();i++)compare(al.get(i),bl.get(i),path+"["+i+"]");return;}
  if(!Objects.equals(a,b))System.out.println(path+" expected="+a+" ("+(a==null?"null":a.getClass().getName())+") actual="+b+" ("+(b==null?"null":b.getClass().getName())+")");
 }
 public static void main(String[] args)throws Exception{
  var method=StaticQuestionAnswerTestFixture.class.getDeclaredMethod("canvas");method.setAccessible(true);var raw=(JSONObject)method.invoke(null);
  var compiled=WorkflowModelCompiler.compile(raw,(id,provider,domain)->{throw new AssertionError("no provider");});
  var normalized=new DslCodec().normalize(compiled.execution(),DslCodec.V3);
  String stored=normalized.execution().toJSONString(JSONWriter.Feature.WriteMapNullValue);var fixed=JSON.parseObject(stored);
  var reconstructed=WorkflowModelCompiler.reconstruct(raw,new JSONObject(),DslCodec.V3);
  System.out.println("boxedJSONObjectEquals="+fixed.equals(reconstructed));compare(fixed,reconstructed,"dsl");
  String canonical=DslCodec.canonicalDslBase64(fixed,DslCodec.V3), reconstructedCanonical=DslCodec.canonicalDslBase64(reconstructed,DslCodec.V3);
  System.out.println("canonicalBytesEqual="+canonical.equals(reconstructedCanonical));
  System.out.println("fixedHash="+DslCodec.fixedExecutionHash(fixed,DslCodec.V3));
  System.out.println("reconstructHash="+DslCodec.fixedExecutionHash(reconstructed,DslCodec.V3));
  var decoded=JSON.parseObject(new String(Base64.getDecoder().decode(canonical),StandardCharsets.UTF_8));
  System.out.println("productionStoredVsCanonicalEquals="+fixed.equals(decoded));
  System.out.println("opaqueNullPresent="+fixed.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONObject("nodeParam").containsKey("opaqueNull"));
  if(fixed.equals(reconstructed) || !canonical.equals(reconstructedCanonical) || !fixed.equals(decoded))throw new AssertionError("unexpected probe outcome");
 }
}
