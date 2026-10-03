package com.ruoyi.paiflow.worker.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.astron.workflow.engine.ParallelWorkflowEngine;
import com.iflytek.astron.workflow.engine.WorkflowEngine;
import com.iflytek.astron.workflow.engine.node.impl.StartNodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.EndNodeExecutor;
import com.ruoyi.paiflow.worker.service.WorkerTaskRunner;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 当前真实中心Controller/事务Mapper与Worker的隔离闭环；仅随机H2内存库，无HTTP服务器或真实库。 */
public final class CenterBudgetHandshakeProbe {
    public static void main(String[] args) throws Exception {
        String centerPath = System.getProperty("paiflow.probe.center.classpath");
        if (centerPath == null) throw new AssertionError("必须显式提供已核对的中心测试classpath");
        var urls = Arrays.stream(centerPath.split(java.io.File.pathSeparator)).map(value -> {
            try { return Path.of(value).toUri().toURL(); } catch (Exception error) { throw new IllegalArgumentException(error); }
        }).toArray(java.net.URL[]::new);
        // 保持中心和独立Worker的原精确依赖隔离，不能把中心Fastjson/Spring版本替换到Worker。
        URLClassLoader centerLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        Class<?> fixtureClass = Class.forName("com.ruoyi.paiflow.service.RunPersistenceContractTest", true, centerLoader);
        var constructor = fixtureClass.getDeclaredConstructor(); constructor.setAccessible(true);
        Object fixture = constructor.newInstance(); invoke(fixture, "setup");
        Object run = invoke(fixture, "queued"); String runId = (String) invoke(run, "getId");
        Object service = field(fixture, "service"); Object runs = field(fixture, "mapper");
        DataSource source = (DataSource) field(fixture, "source");
        // fixture本身只创建jdbc:h2:mem:<UUID>，禁止替换成应用DataSource或实际业务地址。
        try (var connection = source.getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) throw new AssertionError("禁止连接非内存fixture");
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Class<?> coreTest = Class.forName("com.ruoyi.paiflow.worker.CoreEngineTest");
        var dslMethod = coreTest.getDeclaredMethod("dsl"); dslMethod.setAccessible(true);
        JSONObject dsl = JSON.parseObject(mapper.writeValueAsBytes(dslMethod.invoke(null)));
        Class<?> centerJson = centerLoader.loadClass("com.alibaba.fastjson2.JSON");
        Class<?> centerObject = centerLoader.loadClass("com.alibaba.fastjson2.JSONObject");
        Object centerDsl = centerJson.getMethod("parseObject", String.class).invoke(null, dsl.toJSONString());
        String canonical = (String) centerLoader.loadClass("com.ruoyi.paiflow.service.DslCodec")
                .getMethod("canonicalDslBase64", centerObject).invoke(null, centerDsl);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(canonical)));
        Instant instant = (Instant) field(fixture, "instant");
        JSONObject input = JSONObject.of("question", "x".repeat(300 * 1024));
        JSONObject assignment = new JSONObject();
        assignment.fluentPut("schemaVersion", "paiflow.worker.v1").fluentPut("runId", runId).fluentPut("workflowVersionId", "2")
                .fluentPut("dslHash", hash).fluentPut("dslCanonicalBase64", canonical).fluentPut("engineProfile", "java").fluentPut("input", input)
                .fluentPut("resourceSnapshots", new JSONObject()).fluentPut("attempt", 1).fluentPut("leaseId", "pending-center-claim")
                .fluentPut("deadline", instant.plusSeconds(600).toString()).fluentPut("effectPolicy", JSONObject.of("mode", "read_only", "allowedOperations", List.of()))
                .fluentPut("dsl", dsl).fluentPut("lastEventSeq", 0);
        Class<?> limits = centerLoader.loadClass("com.ruoyi.paiflow.service.RunProtocolLimits");
        Class<?> boundary = centerLoader.loadClass("com.ruoyi.paiflow.service.RunJsonBoundary");
        boundary.getMethod("check", Object.class, int.class).invoke(null, input, limits.getField("INPUT_BYTES").getInt(null));
        boundary.getMethod("check", Object.class, int.class).invoke(null, assignment, limits.getField("ASSIGNMENT_BYTES").getInt(null));
        try (var connection = source.getConnection(); var update = connection.prepareStatement("UPDATE paiflow_run SET engine_profile='java',assignment_json=? WHERE id=?")) {
            update.setString(1, assignment.toJSONString()); update.setString(2, runId); if (update.executeUpdate() != 1) throw new AssertionError("fixture更新失败");
        }
        String token = "worker-a." + "x".repeat(40);
        Class<?> propertiesType = centerLoader.loadClass("com.ruoyi.paiflow.config.PaiflowWorkerProperties");
        Object properties = propertiesType.getConstructor().newInstance();
        Object credential = centerLoader.loadClass("com.ruoyi.paiflow.config.PaiflowWorkerProperties$Credential").getConstructor().newInstance();
        call(credential, "setEngineProfile", new Class<?>[]{String.class}, "java");
        call(credential, "setExpiresAt", new Class<?>[]{Instant.class}, Instant.now().plusSeconds(120));
        call(credential, "setTokenSha256", new Class<?>[]{String.class}, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))));
        @SuppressWarnings("unchecked") Map<String,Object> identities = (Map<String,Object>) invoke(properties, "getIdentities");
        identities.put("worker-a", credential);
        Class<?> identityType = centerLoader.loadClass("com.ruoyi.paiflow.service.WorkerIdentityService");
        Object identity = identityType.getConstructor(propertiesType).newInstance(properties);
        Object controller = centerLoader.loadClass("com.ruoyi.paiflow.controller.WorkerRunController")
                .getConstructor(identityType, centerLoader.loadClass("com.ruoyi.paiflow.service.RunLeaseService")).newInstance(identity, service);
        List<String> paths = Collections.synchronizedList(new ArrayList<>()); long[] largest = {0}; int[] begin = {0};
        HttpCenterClient center = new HttpCenterClient(URI.create("https://isolated-center.invalid/context"), token, mapper, request -> {
            try {
            String path = request.uri().getPath(); paths.add(path);
            String authorization = request.headers().firstValue("Authorization").orElseThrow();
            byte[] bytes = body(request);
            @SuppressWarnings("unchecked") Map<String,Object> values = mapper.readValue(bytes, Map.class);
            Object response;
            Class<?>[] arguments = new Class<?>[]{String.class, String.class, Map.class};
            if (path.endsWith("/claim")) response = call(controller, "claim", new Class<?>[]{String.class}, authorization);
            else if (path.endsWith("/lease/verify")) response = call(controller, "verify", arguments, authorization, runId, values);
            else if (path.endsWith("/execution/begin")) {
                begin[0]++; response = call(controller, "beginExecution", arguments, authorization, runId, values);
                if (invoke(call(runs, "find", new Class<?>[]{String.class}, runId), "getExecutionStartedAt") == null) throw new AssertionError("begin返回前必须持久化许可");
            } else if (path.endsWith("/heartbeat")) response = call(controller, "heartbeat", arguments, authorization, runId, values);
            else if (path.endsWith("/events")) {
                if (begin[0] != 1) throw new AssertionError("节点事件不得先于一次许可");
                largest[0] = Math.max(largest[0], bytes.length); response = call(controller, "event", arguments, authorization, runId, values);
            } else if (path.endsWith("/result")) response = call(controller, "result", arguments, authorization, runId, values);
            else throw new AssertionError("此图禁止其他外调：" + path);
            return new HttpCenterClient.WireResponse(200, ((String) centerJson.getMethod("toJSONString", Object.class).invoke(null, response)).getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) { throw new IOException("实际中心隔离调用未完成", failure); }
        });
        var nodes = List.of(new StartNodeExecutor(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(List.copyOf(nodes));
        try (WorkerTaskRunner runner = new WorkerTaskRunner(center, new WorkflowEngine(List.copyOf(nodes)), parallel,
                Clock.fixed(instant, ZoneOffset.UTC), 30, 1, mapper)) {
            var result = runner.claimAndExecute();
            Object stored = call(runs, "find", new Class<?>[]{String.class}, runId);
            if (result == null || !"COMPLETED".equals(result.state()) || !"COMPLETED".equals(invoke(stored, "getState")) || begin[0] != 1)
                throw new AssertionError("实际许可/原引擎/持久终态必须完成");
            int claimAt = paths.indexOf("/context/internal/paiflow/v1/runs/claim");
            int verifyAt = paths.indexOf("/context/internal/paiflow/v1/runs/" + runId + "/lease/verify");
            int beginAt = paths.indexOf("/context/internal/paiflow/v1/runs/" + runId + "/execution/begin");
            if (!(claimAt >= 0 && verifyAt > claimAt && beginAt > verifyAt)) throw new AssertionError("领取/核对/许可顺序错误");
            Object worker = centerLoader.loadClass("com.ruoyi.paiflow.service.WorkerIdentityService$Principal")
                    .getConstructor(String.class, String.class).newInstance("worker-a", "java");
            try {
                call(service, "beginExecution", new Class<?>[]{String.class, worker.getClass(), String.class, int.class},
                        runId, worker, invoke(stored, "getLeaseId"), invoke(stored, "getAttempt"));
                throw new AssertionError("已终态不得重复许可");
            } catch (InvocationTargetException rejected) { if (!Integer.valueOf(410).equals(invoke(rejected.getCause(), "getCode"))) throw rejected; }
            long eventRows = ((List<?>) call(runs, "events", new Class<?>[]{String.class, long.class, int.class}, runId, 0L, 100)).size();
            System.out.println("isolatedDatabase=H2-memory,actualClaimVerifyBeginOrder=true,strictBeginAck=true,beginRequests=" + begin[0]
                    + ",persistedExecutionStarted=true,persistedEvents=" + eventRows + ",largestActualEventBytes=" + largest[0]
                    + ",actualTerminal=" + result.state() + ",persistedTerminal=" + invoke(stored, "getState")
                    + ",dependencyClassloadersIsolated=true");
        } finally { parallel.close(); centerLoader.close(); }
    }
    private static Object invoke(Object value, String name) throws Exception {
        return call(value, name, new Class<?>[]{});
    }
    private static Object call(Object value, String name, Class<?>[] types, Object... arguments) throws Exception {
        java.lang.reflect.Method method;
        try { method = value.getClass().getDeclaredMethod(name, types); }
        catch (NoSuchMethodException absent) { method = value.getClass().getMethod(name, types); }
        method.setAccessible(true);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(value.getClass().getClassLoader());
            return method.invoke(value, arguments);
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static byte[] body(HttpRequest request) {
        var output = new ByteArrayOutputStream(); var result = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) { byte[] value = new byte[buffer.remaining()]; buffer.get(value); output.writeBytes(value); }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(output.toByteArray()); }
        });
        return result.join();
    }
}
