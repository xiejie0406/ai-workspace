import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.paiflow.service.DslCodec;
import com.ruoyi.paiflow.service.RunJsonBoundary;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 调用既有classes的私有独立探针，不启动平台、不写target或DB。 */
public class RunCanonicalNullReview {
    private static String canonical(JSONObject value,String codec) {
        return new String(Base64.getDecoder().decode(DslCodec.canonicalDslBase64(value,codec)),StandardCharsets.UTF_8);
    }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static JSONObject check(String name,JSONObject value) throws Exception {
        String old=canonical(value,DslCodec.V1),fresh=canonical(value,DslCodec.V2),actual=RunJsonBoundary.canonical(value);
        return JSONObject.of("case",name,"oldV1Bytes",old,"currentV2Bytes",fresh,"actualBoundaryBytes",actual,
            "oldV1Hash",sha(old),"currentV2Hash",sha(fresh),"actualBoundaryHash",RunJsonBoundary.hash(value),
            "bytesChanged",!old.equals(fresh),"currentBoundaryUsesV2",actual.equals(fresh),
            "sameRequestAgainstOldHashWouldCompareDifferent",!sha(old).equals(RunJsonBoundary.hash(value)));
    }
    public static void main(String[] args) throws Exception {
        JSONObject input=JSONObject.of("workflowVersionId",7,"engineProfile","python","timeoutSeconds",900,"input",JSONObject.of("opaqueNull",null,"text","fixture"));
        JSONObject event=JSONObject.of("schemaVersion","paiflow.events.v1","runId","fixture-run","leaseId","fixture-lease","attempt",1,"seq",1,"eventType","node.completed","timestamp","2026-10-03T03:00:00Z","payload",JSONObject.of("legacyFrame",JSONObject.of("opaqueNull",null,"code",0)));
        JSONObject result=JSONObject.of("leaseId","fixture-lease","attempt",1,"state","COMPLETED","output",JSONObject.of("opaqueNull",null,"answer","fixture"));
        System.out.println(JSON.toJSONString(JSONObject.of("request",check("run-inputHash",input),"event",check("event-duplicate-bytes",event),"result",check("resultHash",result)),JSONWriter.Feature.WriteMapNullValue));
    }
}
