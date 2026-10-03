import com.paicli.runner.*;
import com.paicli.runtime.center.CenterCommandGateway;
import java.util.*;

/** 分发包实际类加载/SPI检查；不执行模型、MCP、微信或中心请求。 */
public class NativeDistributionProbe {
    public static void main(String[] args)throws Exception {
        if(args[0].equals("cli")) {
            var providers=ServiceLoader.load(NativeControlExecutor.Extension.class).stream().map(ServiceLoader.Provider::get).toList();
            var types=providers.stream().map(value->value.getClass().getName()).sorted().toList();
            if(!types.equals(List.of("com.paicli.cli.CenterProjectMemoryControl","com.paicli.harness.CenterHarnessControl","com.paicli.wechat.CenterWechatControl")))throw new AssertionError(types);
            for(String operation:List.of("INIT_PROJECT_MEMORY","HARNESS_QUICK","HARNESS_NORMAL","HARNESS_INLINE","WECHAT_SETUP","WECHAT_START"))if(providers.stream().noneMatch(value->value.supports(operation)))throw new AssertionError(operation);
            var gatewayTypes=ServiceLoader.load(CenterCommandGateway.class).stream().map(value->value.type().getName()).toList();
            if(!gatewayTypes.equals(List.of("com.paicli.runner.HttpCenterCommandGateway")))throw new AssertionError(gatewayTypes);
            try(var transport=new CenterHttpClient("http://127.0.0.1:9","fixture",false);var gateway=new HttpCenterCommandGateway(transport)) { /* 构造实际适配器，不发请求。 */ }
            providers.forEach(NativeControlExecutor.Extension::close);
            System.out.println("providers="+types);System.out.println("gateway="+gatewayTypes);System.out.println("providerConstructed=true; externalRequests=0");
        } else {
            for(String type:List.of("com.paicli.eval.benchmark.BenchmarkRelayWorkerMain","com.paicli.eval.benchmark.BenchmarkToolRegistry","com.paicli.eval.benchmark.BenchmarkToolProfile","com.paicli.eval.LlmJudge","com.paicli.eval.PositionBalancedPairwiseJudge"))System.out.println("loaded="+Class.forName(type).getName());
            System.out.println("formalEvaluationExecuted=false");
        }
    }
}
