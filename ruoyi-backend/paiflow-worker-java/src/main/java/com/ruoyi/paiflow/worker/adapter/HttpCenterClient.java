package com.ruoyi.paiflow.worker.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.ArtifactGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Effect;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectCompletion;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Event;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Heartbeat;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;
import com.ruoyi.paiflow.worker.service.WorkerException;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class HttpCenterClient implements CenterClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final URI endpoint;
    private final String serviceToken;
    private final ObjectMapper mapper;
    private final Exchange exchange;

    public HttpCenterClient(URI endpoint, String serviceToken, ObjectMapper mapper) {
        this(endpoint, serviceToken, mapper, defaultExchange());
    }

    HttpCenterClient(URI endpoint, String serviceToken, ObjectMapper mapper, Exchange exchange) {
        if (endpoint == null || !ListSupport.httpScheme(endpoint) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("中心地址必须是无凭证、查询和片段的HTTP地址");
        }
        if (serviceToken == null || serviceToken.isBlank() || serviceToken.indexOf('\r') >= 0 || serviceToken.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("必须配置Worker独立服务身份");
        }
        this.endpoint = URI.create(endpoint.toString().replaceAll("/+$", "") + "/");
        this.serviceToken = serviceToken;
        this.mapper = mapper;
        this.exchange = exchange;
    }

    @Override public Lease claim() {
        JsonNode data = postPath("internal/paiflow/v1/runs/claim", Map.of(), JsonNode.class);
        if (data == null || data.isNull()) return null;
        try {
            requireAssignmentFields(data);
            Assignment assignment = mapper.treeToValue(data, Assignment.class);
            if (!"paiflow.worker.v1".equals(assignment.schemaVersion()) || !"java".equals(assignment.engineProfile())
                    || !assignment.runId().matches("[a-zA-Z0-9_-]{1,128}") || !assignment.leaseId().matches("[a-zA-Z0-9_-]{1,128}")) {
                throw new WorkerException("LEASE_LOST", "中心领取响应的租约或引擎无效");
            }
            // 领取响应不直接作为执行快照；Runner仍须通过verify取得并校验中心当下授权。
            return new Lease(assignment.runId(), assignment.leaseId(), assignment.attempt());
        } catch (WorkerException error) { throw error; }
        catch (Exception error) { throw new WorkerException("LEASE_LOST", "中心领取响应不满足Worker协议", error); }
    }

    @Override public Assignment verify(Lease lease) {
        JsonNode data = post(lease, "lease/verify", leaseBody(lease), JsonNode.class);
        if (data == null || !data.path("valid").isBoolean() || !data.path("valid").asBoolean()
                || !data.path("cancelRequested").isBoolean() || !data.path("assignment").isObject()) {
            throw new WorkerException("LEASE_LOST", "中心未确认执行租约");
        }
        if (data.path("cancelRequested").asBoolean()) throw new WorkerException("RUN_CANCELLED", "中心已经请求取消运行");
        try {
            requireAssignmentFields(data.get("assignment"));
            Assignment assignment = mapper.treeToValue(data.get("assignment"), Assignment.class);
            if (!data.path("dslHash").isTextual() || !data.path("dslHash").asText().equals(assignment.dslHash())
                    || !data.path("workflowVersionId").isTextual() || !data.path("workflowVersionId").asText().equals(assignment.workflowVersionId())
                    || !data.path("lastEventSeq").isIntegralNumber() || data.path("lastEventSeq").asLong(-1) != assignment.lastEventSeq()) {
                throw new WorkerException("LEASE_LOST", "中心租约元数据与任务快照不一致");
            }
            return assignment;
        } catch (WorkerException error) { throw error; }
        catch (Exception error) { throw new WorkerException("LEASE_LOST", "中心任务快照不满足Worker协议", error); }
    }

    @Override public Heartbeat heartbeat(Lease lease) {
        JsonNode data = post(lease, "heartbeat", leaseBody(lease), JsonNode.class);
        if (data == null || !data.path("valid").isBoolean() || !data.path("cancelRequested").isBoolean()) {
            throw new WorkerException("LEASE_LOST", "中心未明确确认心跳状态");
        }
        return new Heartbeat(data.path("valid").asBoolean(), data.path("cancelRequested").asBoolean());
    }

    @Override public void beginExecution(Lease lease, Assignment assignment) {
        JsonNode data = post(lease, "execution/begin", leaseBody(lease), JsonNode.class);
        if (data == null || !data.isObject() || data.size() != 6
                || !data.path("begun").isBoolean() || !data.path("begun").asBoolean()
                || !sameText(data, "runId", lease.runId()) || !sameText(data, "leaseId", lease.leaseId())
                || !data.path("attempt").isIntegralNumber() || !data.path("attempt").canConvertToInt()
                || data.path("attempt").asInt() != lease.attempt()
                || !sameText(data, "workflowVersionId", assignment.workflowVersionId())
                || !sameText(data, "dslHash", assignment.dslHash())) {
            throw new WorkerException("CENTER_REJECTED", "中心未确认与快照绑定的一次性执行许可");
        }
    }

    private static boolean sameText(JsonNode value, String name, String expected) {
        return value.path(name).isTextual() && value.path(name).asText().equals(expected);
    }

    @Override public void event(Event event) {
        if (event == null || !mapper.valueToTree(event.payload()).isObject()) {
            throw new WorkerException("EVENT_PROTOCOL_INVALID", "执行事件payload必须为JSON对象");
        }
        JsonNode data = post(new Lease(event.runId(), event.leaseId(), event.attempt()), "events", event, JsonNode.class);
        if (data == null || !data.path("accepted").isBoolean() || !data.path("accepted").asBoolean()
                || !data.path("seq").isIntegralNumber() || data.path("seq").asLong(-1) != event.seq()) {
            throw new WorkerException("CENTER_REJECTED", "中心未确认相同序号的执行事件");
        }
    }

    @Override public void result(Lease lease, Result result) {
        JsonNode data = post(lease, "result", result, JsonNode.class);
        if (data == null || !data.path("accepted").isBoolean() || !data.path("accepted").asBoolean()
                || !data.path("state").isTextual() || !data.path("state").asText().equals(result.state())) {
            throw new WorkerException("CENTER_REJECTED", "中心未确认相同终态的执行结果");
        }
    }
    @Override public EffectGrant authorizeEffect(Lease lease, Effect effect) { return post(lease, "effects/authorize", effect, EffectGrant.class); }
    @Override public void completeEffect(Lease lease, EffectCompletion completion) {
        JsonNode data = post(lease, "effects/complete", completion, JsonNode.class);
        if (data == null || !data.path("accepted").isBoolean() || !data.path("accepted").asBoolean()
                || !data.path("grantId").isTextual() || !data.path("grantId").asText().equals(completion.grantId())
                || !data.path("state").isTextual() || !data.path("state").asText().equals(completion.state())) {
            throw new WorkerException("EFFECT_UNKNOWN", "中心未确认相同grant的实际副作用回执");
        }
    }
    @Override public ArtifactGrant prepareArtifact(Lease lease, String objectKey, String contentType, long size) {
        return post(lease, "artifacts/prepare", Map.of("leaseId", lease.leaseId(), "attempt", lease.attempt(), "objectKey", objectKey,
                "contentType", contentType, "size", size), ArtifactGrant.class);
    }
    @Override public void commitArtifact(Lease lease, String artifactId, String sha256, long size) {
        JsonNode data = post(lease, "artifacts/commit", Map.of("leaseId", lease.leaseId(), "attempt", lease.attempt(), "artifactId", artifactId,
                "sha256", sha256, "size", size), JsonNode.class);
        if (data == null || !data.path("accepted").isBoolean() || !data.path("accepted").asBoolean()
                || !data.path("artifactId").isTextual() || !artifactId.equals(data.path("artifactId").asText())) {
            throw new WorkerException("EFFECT_UNKNOWN", "中心未确认相同成果的实际上传证据");
        }
    }

    private static void requireAssignmentFields(JsonNode assignment) {
        for (String field : java.util.List.of("schemaVersion", "runId", "workflowVersionId", "dslHash", "dslCanonicalBase64",
                "engineProfile", "input", "resourceSnapshots", "attempt", "leaseId", "deadline", "effectPolicy", "dsl", "lastEventSeq")) {
            if (!assignment.hasNonNull(field)) throw new WorkerException("LEASE_LOST", "中心任务缺少必填字段：" + field);
        }
        for (String field : java.util.List.of("schemaVersion", "runId", "workflowVersionId", "dslHash", "dslCanonicalBase64", "engineProfile", "leaseId", "deadline")) {
            if (!assignment.get(field).isTextual()) throw new WorkerException("LEASE_LOST", "中心任务字段类型无效：" + field);
        }
        for (String field : java.util.List.of("input", "resourceSnapshots", "effectPolicy", "dsl")) {
            if (!assignment.get(field).isObject()) throw new WorkerException("LEASE_LOST", "中心任务对象字段无效：" + field);
        }
        if (!assignment.get("attempt").isIntegralNumber() || !assignment.get("attempt").canConvertToInt()
                || assignment.get("attempt").asInt() < 1 || !assignment.get("lastEventSeq").isIntegralNumber()
                || !assignment.get("lastEventSeq").canConvertToLong() || assignment.get("lastEventSeq").asLong() < 0) {
            throw new WorkerException("LEASE_LOST", "中心任务attempt或事件序号无效");
        }
    }

    private <T> T post(Lease lease, String suffix, Object body, Class<T> type) {
        String runId = URLEncoder.encode(lease.runId(), StandardCharsets.UTF_8).replace("+", "%20");
        return postPath("internal/paiflow/v1/runs/" + runId + "/" + suffix, body, type);
    }

    private <T> T postPath(String path, Object body, Class<T> type) {
        try {
            URI uri = endpoint.resolve(path);
            ObjectNode message = mapper.valueToTree(body);
            // 机器事件时间固定ISO-8601字符串，不能依赖调用者Jackson的日期序列化开关。
            if (body instanceof Event event && event.timestamp() != null) message.put("timestamp", event.timestamp().toString());
            message.properties().stream().filter(entry -> entry.getValue().isNull()).map(Map.Entry::getKey).toList()
                    .forEach(message::remove);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + serviceToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(message))).build();
            WireResponse response = exchange.send(request);
            {
                byte[] bytes = response.body();
                if (bytes.length > MAX_RESPONSE_BYTES) throw new WorkerException("CENTER_RESPONSE_TOO_LARGE", "中心响应超出预算");
                if (response.statusCode() != 200) throw new WorkerException("CENTER_REJECTED", "中心拒绝请求，HTTP " + response.statusCode());
                JsonNode envelope = mapper.readTree(bytes);
                if (envelope == null || envelope.path("code").asInt(-1) != 200 || !envelope.has("data")) {
                    throw new WorkerException("CENTER_REJECTED", "中心未确认Worker请求");
                }
                return mapper.treeToValue(envelope.get("data"), type);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException("RUN_INTERRUPTED", "Worker请求被中断", e);
        } catch (WorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkerException("CENTER_UNAVAILABLE", "无法核对中心租约或写入执行证据", e);
        }
    }

    private static Map<String, Object> leaseBody(Lease lease) {
        return Map.of("leaseId", lease.leaseId(), "attempt", lease.attempt());
    }

    private static Exchange defaultExchange() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                return new WireResponse(response.statusCode(), stream.readNBytes(MAX_RESPONSE_BYTES + 1));
            }
        };
    }

    interface Exchange { WireResponse send(HttpRequest request) throws IOException, InterruptedException; }
    record WireResponse(int statusCode, byte[] body) { }

    private static class ListSupport {
        static boolean httpScheme(URI uri) { return "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()); }
    }
}
