import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.paiflow.controller.WorkerRunController;
import com.ruoyi.paiflow.service.RunLeaseService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 真实Boot4/Jackson3 MVC和原Controller；mock仅捕获参数，不证明授权/持久化。 */
public class WorkerEventHttpNullProbe {
    private static JSONObject presence(JSONObject event) {
        var payload=event.getJSONObject("payload");var frame=payload.getJSONObject("legacyFrame");
        var node=frame.getJSONObject("workflow_step").getJSONObject("node");var outputs=node.getJSONObject("outputs");
        return JSONObject.of("outputNullKeyPresent",outputs.containsKey("value"),"outputNullValue",outputs.get("value"),
                "zero",outputs.get("zero"),"flag",outputs.get("flag"),
                "metadataNullKeyPresent",payload.getJSONObject("metadata").getJSONObject("nested").containsKey("nullable"),
                "nodeFinishNullKeyPresent",node.containsKey("finish_reason"),
                "choiceFinishNullKeyPresent",frame.getJSONArray("choices").getJSONObject(0).containsKey("finish_reason"),
                "deltaContentNullKeyPresent",frame.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").containsKey("content"));
    }
    public static void main(String[] args)throws Exception {
        boolean repaired=args.length==1 && "repaired".equals(args[0]);
        String run="00000000-0000-0000-0000-000000000001";
        String request="""
        {"schemaVersion":"paiflow.events.v1","runId":"00000000-0000-0000-0000-000000000001","leaseId":"lease-fixture","attempt":1,"seq":1,"eventType":"node.started","timestamp":"2026-10-03T00:00:00Z","nodeId":"text-joiner::fixture","payload":{"metadata":{"nested":{"nullable":null}},"legacyFrame":{"id":"00000000-0000-0000-0000-000000000001","code":0,"message":"","workflow_step":{"seq":0,"progress":0,"node":{"id":"text-joiner::fixture","finish_reason":null,"inputs":{},"outputs":{"value":null,"zero":0,"flag":false}}},"choices":[{"index":0,"delta":{"role":"assistant","content":null},"finish_reason":null}]}}}
        """;
        var principal=new WorkerIdentityService.Principal("worker-fixture","python");
        var identity=mock(WorkerIdentityService.class);var leases=mock(RunLeaseService.class);
        when(identity.authenticate("Bearer mock-fixture")).thenReturn(principal);
        when(leases.append(eq(run),eq(principal),any(JSONObject.class))).thenReturn(JSONObject.of("lastEventSeq",1));
        var converter=new JacksonJsonHttpMessageConverter(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        var mvc=MockMvcBuilders.standaloneSetup(new WorkerRunController(identity,leases)).setMessageConverters(converter).build();
        var response=mvc.perform(post("/internal/paiflow/v1/runs/"+run+"/events").header("Authorization","Bearer mock-fixture")
            .contentType("application/json").content(request)).andReturn().getResponse();
        if(response.getStatus()!=200)throw new AssertionError("实际MVC应进入append capture，status="+response.getStatus());
        var capture=ArgumentCaptor.forClass(JSONObject.class);verify(leases).append(eq(run),eq(principal),capture.capture());
        verify(identity).authenticate("Bearer mock-fixture");var event=capture.getValue();
        var original=presence(JSON.parseObject(request));var actual=presence(event);
        for(String key:new String[]{"outputNullKeyPresent","metadataNullKeyPresent","nodeFinishNullKeyPresent","choiceFinishNullKeyPresent","deltaContentNullKeyPresent"}) {
            if(!Boolean.TRUE.equals(original.get(key)))throw new AssertionError("探针原始JSON缺null字段"+key);
            if(!Boolean.valueOf(repaired).equals(actual.get(key)))throw new AssertionError("实际null存在性与本阶段不符 "+key+":"+actual.get(key));
        }
        if(!Integer.valueOf(0).equals(actual.get("zero")) || !Boolean.FALSE.equals(actual.get("flag")))throw new AssertionError("0/false必须原样保留");
        var result=JSONObject.of("status",response.getStatus(),"mode",repaired?"repaired":"first_counterexample",
          "converter",converter.getClass().getName(),"jackson",tools.jackson.databind.json.JsonMapper.class.getPackage().getImplementationVersion(),
          "requestPresence",original,"appendArgumentPresence",actual,
          "requestBase64",Base64.getEncoder().encodeToString(request.getBytes(StandardCharsets.UTF_8)),
          "appendArgumentBase64",Base64.getEncoder().encodeToString(event.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8)),
          "mockIdentity",true,"mockRunLease",true,"realAuthorityOrPersistenceVerified",false);
        System.out.println("WORKER_HTTP_NULL_PROBE="+result.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
}
