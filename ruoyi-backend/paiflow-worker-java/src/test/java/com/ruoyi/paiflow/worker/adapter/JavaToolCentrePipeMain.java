package com.ruoyi.paiflow.worker.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.integration.model.ModelServiceClient;
import com.iflytek.astron.workflow.engine.integration.plugins.PluginServiceClient;
import com.iflytek.astron.workflow.engine.integration.plugins.aitools.AiToolsIntegration;
import com.iflytek.astron.workflow.engine.node.NodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.EndNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.StartNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.llm.LLMNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.plugin.PluginNodeExecutor;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;
import com.ruoyi.paiflow.worker.service.WorkerTaskRunner;
import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 隔离父进程的真实中心 Service 与原 Java Worker 的 stdio Exchange 桥。
 * 不启动 HTTP，不生成业务 ACK/binding；父进程必须逐请求返回真实中心值。
 */
public final class JavaToolCentrePipeMain {
    private JavaToolCentrePipeMain() {}

    /** args[0] 为父中心实际 Clock instant，args[1] 为私有输出路径；可选args[2]明确预期终态。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) throw new IllegalArgumentException("需要当前中心UTC Instant、私有输出JSON路径及可选预期终态");
        String expectedTerminal=args.length==3?args[2]:"COMPLETED";
        if(!Set.of("COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN").contains(expectedTerminal))
            throw new IllegalArgumentException("预期终态必须为明确的Worker终态");
        // Windows 的 stdout.encoding 可为 GBK；管道 JSON 必须显式 UTF-8 保全业务中文。
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8);
        // 必须先重定向，再加载原引擎/日志设施；原业务日志不能污染 stdout NDJSON。
        System.setOut(System.err);
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Clock clock = Clock.fixed(Instant.parse(args[0]),ZoneOffset.UTC);
        var pipe = new Pipe(mapper,protocol,new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)));
        var client = new HttpCenterClient(URI.create("https://centre-fixture.invalid"),"isolated-stdio-worker",mapper,pipe::exchange);
        var service = new PluginServiceClient();
        ReflectionTestUtils.setField(service,"aiToolsIntegration",new AiToolsIntegration());
        ReflectionTestUtils.setField(service,"smartTTSIntegration",List.of());
        var plugin = new PluginNodeExecutor();
        ReflectionTestUtils.setField(plugin,"pluginServiceClient",service);
        List<NodeExecutor> executors = List.of(new StartNodeExecutor(),new LLMNodeExecutor(new ModelServiceClient()),plugin,new EndNodeExecutor());
        var parallel = new ParallelWorkflowEngine(executors);
        Result result;
        try (var runner = new WorkerTaskRunner(client,new WorkflowEngine(executors),parallel,clock,60,60,mapper)) {
            result = runner.claimAndExecute();
            if (result == null) throw new IllegalStateException("父中心未返回真实待执行任务");
        } finally { parallel.close(); }
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        var capture = mapper.createObjectNode();
        capture.put("scope","原Java Worker/HttpCenterClient与父进程真实隔离中心Service逐请求闭环；无HTTP服务/真实Provider");
        capture.put("clockInstant",clock.instant().toString());
        capture.set("requests",mapper.valueToTree(pipe.requests));
        capture.set("responses",mapper.valueToTree(pipe.responses));
        capture.set("events",mapper.valueToTree(pipe.events));
        capture.set("result",mapper.valueToTree(result));
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),capture);
        var done = mapper.createObjectNode().put("type","result");
        done.set("result",mapper.valueToTree(result));
        protocol.println(mapper.writeValueAsString(done)); protocol.flush();
        if (!expectedTerminal.equals(result.state())) throw new IllegalStateException("原Worker闭环终态为 "+result.state()+"，预期 "+expectedTerminal);
    }

    private static final class Pipe {
        final ObjectMapper mapper; final PrintStream stdout; final BufferedReader stdin;
        final List<JsonNode> requests = new ArrayList<>(), responses = new ArrayList<>(), events = new ArrayList<>();
        long nextId;
        Pipe(ObjectMapper mapper,PrintStream stdout,BufferedReader stdin) { this.mapper=mapper;this.stdout=stdout;this.stdin=stdin; }

        /** 心跳/节点回调可并发，整次发送及对应读取必须在同一锁内。 */
        synchronized HttpCenterClient.WireResponse exchange(HttpRequest request) throws IOException,InterruptedException {
            if (!"POST".equals(request.method())) throw new IOException("原Worker出现非POST控制面请求");
            JsonNode body;
            try { body=mapper.readTree(requestBody(request)); }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception failure) { throw new IOException("无法读取实际HttpRequest",failure); }
            if (body == null || !body.isObject()) throw new IOException("原Worker请求体须为对象");
            long id=++nextId;
            var outgoing=mapper.createObjectNode().put("type","request").put("id",id).put("method",request.method()).put("path",request.uri().getRawPath());
            outgoing.set("body",body); requests.add(outgoing);
            if (request.uri().getRawPath().endsWith("/events")) events.add(body);
            stdout.println(mapper.writeValueAsString(outgoing)); stdout.flush();
            String line=stdin.readLine();
            if (line == null) throw new IOException("父中心管道已关闭，不生成替代ACK");
            JsonNode reply=mapper.readTree(line);
            if (reply == null || !reply.isObject() || !reply.path("id").isIntegralNumber() || reply.path("id").asLong()!=id
                    || !reply.path("status").isIntegralNumber() || !reply.path("status").canConvertToInt() || !reply.has("body")) throw new IOException("父中心管道响应不对应实际请求");
            responses.add(reply);
            JsonNode response=reply.get("body");
            byte[] bytes=response.isTextual()?response.asText().getBytes(StandardCharsets.UTF_8):mapper.writeValueAsBytes(response);
            return new HttpCenterClient.WireResponse(reply.path("status").asInt(),bytes);
        }
    }

    private static byte[] requestBody(HttpRequest request) throws Exception {
        var bytes=new ByteArrayOutputStream();var result=new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer value) { byte[] chunk=new byte[value.remaining()];value.get(chunk);bytes.writeBytes(chunk); }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.get(5,TimeUnit.SECONDS);
    }
}
