package com.ruoyi.paiflow.worker;

import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.ArtifactGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Effect;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectCompletion;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectPolicy;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Event;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Heartbeat;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;
import com.ruoyi.paiflow.worker.service.ExecutionScope;
import com.ruoyi.paiflow.worker.service.WorkerException;
import com.ruoyi.paiflow.worker.service.DslSnapshotVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class WorkerBoundaryOfflineTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static int checks;

    public static void main(String[] args) throws Exception {
        reject("LEASE_REQUIRED", ExecutionScope::current);
        FakeCenter center = new FakeCenter();
        try (ExecutionScope expired = scope(center, "expired", NOW)) { expired.bind(); reject("DEPENDENCY_TIMEOUT", ExecutionScope::current); }
        try (ExecutionScope cancelled = scope(center, "cancelled", NOW.plusSeconds(60))) { cancelled.bind(); cancelled.cancel(); reject("RUN_CANCELLED", ExecutionScope::current); }
        try (ExecutionScope lost = scope(center, "lost", NOW.plusSeconds(60))) { lost.bind(); lost.loseLease(); reject("LEASE_LOST", ExecutionScope::current); }
        try (ExecutionScope valid = scope(center, "valid", NOW.plusSeconds(60))) {
            valid.bind();
            reject("TOOL_POLICY_DENIED", () -> valid.authorize("tool.execute", "https://approved.invalid/api/tools"));
            reject("RESOURCE_UNAVAILABLE", () -> valid.authorize("model.infer", "https://attacker.invalid/api/chat"));
            reject("RESOURCE_UNAVAILABLE", () -> valid.authorize("model.infer", "https://approved.invalid/api2/chat"));
            reject("TOOL_POLICY_DENIED", () -> valid.authorize("model.infer", "https://secret@approved.invalid/api/chat"));
            reject("TOOL_POLICY_DENIED", () -> valid.authorize("model.infer", "https://approved.invalid/api/%2e%2e/private"));
            center.allowed = false;
            reject("TOOL_POLICY_DENIED", () -> valid.authorize("model.infer", "https://approved.invalid/api/chat"));
            center.allowed = true;
            String first = valid.authorize("model.infer", "https://approved.invalid/api/chat");
            String second = valid.authorize("model.infer", "https://approved.invalid/api/chat");
            require(valid.hasUnconfirmedEffect(), "外部请求未完成不能报告已确认");
            center.completionAllowed = false;
            reject("EFFECT_UNKNOWN", () -> valid.confirmedEffect(first, Map.of("sha256", "fixture-result")));
            require(valid.hasUnconfirmedEffect(), "中心未确认回执不能清除本地未确认标记");
            center.completionAllowed = true;
            reject("EFFECT_UNKNOWN", () -> valid.confirmedEffect("unbound-grant", Map.of("sha256", "fixture-result")));
            reject("EFFECT_UNKNOWN", () -> valid.confirmedEffect(first, Map.of()));
            valid.confirmedEffect(first, Map.of("sha256", "fixture-result"));
            require(valid.hasUnconfirmedEffect(), "并行分支只完成一个仍有未确认操作");
            valid.confirmedEffect(second, Map.of("sha256", "fixture-result"));
            require(!valid.hasUnconfirmedEffect(), "全部操作完成后应清空临时标记");
        }
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (ExecutionScope first = scope(center, "first", NOW.plusSeconds(60))) {
                first.bind();
                require("first".equals(executor.submit(ExecutionScope.wrap(() -> ExecutionScope.current().lease().runId())).get()), "线程必须继承本次运行上下文");
            }
            try (ExecutionScope second = scope(center, "second", NOW.plusSeconds(60))) {
                second.bind();
                require("second".equals(executor.submit(ExecutionScope.wrap(() -> ExecutionScope.current().lease().runId())).get()), "复用线程不能保留上次运行上下文");
            }
            executor.submit(() -> reject("LEASE_REQUIRED", ExecutionScope::current)).get();
        }
        require(center.authorizations == 3, "本地拒绝的地址和操作不能调用中心授权");
        byte[] canonical = "{\"节点\":0.001,\"quote\":\"你好😀\"}".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        String base64 = Base64.getEncoder().encodeToString(canonical);
        require(Arrays.equals(canonical, DslSnapshotVerifier.verify(base64, hash)), "浮点和Unicode必须直接使用中心原字节核对");
        reject("DSL_INVALID", () -> DslSnapshotVerifier.verify(base64, "0".repeat(64)));
        reject("DSL_INVALID", () -> DslSnapshotVerifier.verify("not_base64", hash));
        reject("DSL_INVALID", () -> DslSnapshotVerifier.verify(null, hash));
        if (args.length != 1) throw new AssertionError("必须提供中心规范化fixture路径");
        String fixture = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        var fixtureHash = Pattern.compile("\"dslHash\":\"([a-f0-9]{64})\"").matcher(fixture);
        var fixtureBytes = Pattern.compile("\"dslCanonicalBase64\":\"([A-Za-z0-9+/=]+)\"").matcher(fixture);
        require(fixtureHash.find() && fixtureBytes.find(), "中心fixture必须包含完整hash和规范化原字节");
        String originalHash = fixtureHash.group(1);
        byte[] originalBytes = DslSnapshotVerifier.verify(fixtureBytes.group(1), originalHash);
        require(new String(originalBytes, StandardCharsets.UTF_8).contains("😀"), "必须消费中心实际Unicode原字节");
        byte[] tampered = originalBytes.clone();
        tampered[20] = (byte) (tampered[20] ^ 1);
        reject("DSL_INVALID", () -> DslSnapshotVerifier.verify(Base64.getEncoder().encodeToString(tampered), originalHash));
        System.out.println("Worker boundary checks passed: " + checks);
    }

    private static ExecutionScope scope(FakeCenter center, String runId, Instant deadline) {
        return new ExecutionScope(center, new Assignment("paiflow.worker.v1", runId, "version", "0".repeat(64), "", "java",
                Map.of(), Map.of("model", Map.of("endpoint", "https://approved.invalid/api")), 1, "lease", deadline,
                new EffectPolicy("read_only", List.of("model.infer"), null), Map.of(), 12), CLOCK);
    }

    private static void reject(String code, Runnable action) {
        try { action.run(); throw new AssertionError("应拒绝：" + code); }
        catch (WorkerException error) { require(code.equals(error.getCode()), "实际错误码：" + error.getCode()); }
    }

    private static void require(boolean assertion, String message) {
        if (!assertion) throw new AssertionError(message);
        checks++;
    }

    private static final class FakeCenter implements CenterClient {
        @Override public Lease claim() { throw new AssertionError("离线边界不领取任务"); }
        boolean allowed = true;
        boolean completionAllowed = true;
        int authorizations;
        @Override public EffectGrant authorizeEffect(Lease lease, Effect effect) { authorizations++; return new EffectGrant(allowed, allowed ? "grant-" + authorizations : null); }
        @Override public void completeEffect(Lease lease, EffectCompletion completion) {
            if (!completionAllowed) throw new WorkerException("EFFECT_UNKNOWN", "中心未确认副作用回执");
        }
        @Override public Assignment verify(Lease lease) { throw new AssertionError("未授权网络或任务访问"); }
        @Override public void beginExecution(Lease lease, Assignment assignment) { throw new AssertionError("离线边界不得获取执行许可"); }
        @Override public Heartbeat heartbeat(Lease lease) { throw new AssertionError("未授权网络或任务访问"); }
        @Override public void event(Event event) { throw new AssertionError("未授权网络或任务访问"); }
        @Override public void result(Lease lease, Result result) { throw new AssertionError("未授权网络或任务访问"); }
        @Override public ArtifactGrant prepareArtifact(Lease lease, String key, String type, long size) { throw new AssertionError("未授权文件写入"); }
        @Override public void commitArtifact(Lease lease, String id, String sha256, long size) { throw new AssertionError("未授权文件写入"); }
    }
}
