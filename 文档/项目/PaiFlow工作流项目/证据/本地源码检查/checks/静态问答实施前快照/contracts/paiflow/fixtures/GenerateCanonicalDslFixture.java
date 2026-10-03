import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.service.DslCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 调用实际中心Codec生成公共字节fixture，不另手写JSON规范化算法。 */
class GenerateCanonicalDslFixture {
    public static void main(String[] args) throws Exception {
        JSONObject data = JSONObject.parseObject("{\"tiny\":1e-7,\"large\":1e20,\"negativeZero\":-0.0,\"nested\":{\"中文\":\"换行\\n引号\\\"\"}}");
        data.put(new String(Character.toChars(0x1f600)), "supplementary");
        data.put("\uE000", "bmp");
        JSONArray nodes = JSONArray.of(
            new JSONObject().fluentPut("id", "node-start::fixture-start").fluentPut("data", data),
            new JSONObject().fluentPut("id", "node-end::fixture-end").fluentPut("data", new JSONObject()));
        JSONArray edges = JSONArray.of(new JSONObject().fluentPut("sourceNodeId","node-start::fixture-start").fluentPut("targetNodeId","node-end::fixture-end"));
        var normalized = new DslCodec().normalize(new JSONObject().fluentPut("nodes",nodes).fluentPut("edges",edges));
        JSONObject fixture = new JSONObject().fluentPut("schemaVersion", "paiflow.canonical-fixture.v1")
            .fluentPut("generator", "center DslCodec 2026-10-03")
            .fluentPut("scope", "特殊Unicode键、指数浮点、负零与嵌套字符串；只证明字节传输/hash边界，不证明节点业务参数或执行")
            .fluentPut("dsl", normalized.execution()).fluentPut("dslHash", normalized.executionHash())
            .fluentPut("dslCanonicalBase64", DslCodec.canonicalDslBase64(normalized.execution()));
        Path output = Path.of(args.length == 0 ? "contracts/paiflow/fixtures/dsl-canonical.json" : args[0]);
        Files.createDirectories(output.getParent());Files.writeString(output, fixture.toJSONString(), StandardCharsets.UTF_8);
        System.out.println(output + " hash=" + normalized.executionHash());
    }
}
