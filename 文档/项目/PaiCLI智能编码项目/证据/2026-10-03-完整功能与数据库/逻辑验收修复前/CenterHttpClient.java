package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** 不跟随重定向、不自动重发写请求、不在 URL 或日志中携带凭证。 */
public final class CenterHttpClient implements AutoCloseable {
    // 64MB受控edit参数加AjaxResult封装；正文实际5MB约束由中心和原工具双重核验。
    public static final int MAX_RESPONSE_BYTES = 65 * 1024 * 1024;
    public static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;
    private final String baseUrl;
    private final String header;
    private volatile String token;
    private final OkHttpClient client;

    public CenterHttpClient(String baseUrl, String token, boolean runner) {
        this(baseUrl, token, runner, new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(25))
            .callTimeout(Duration.ofSeconds(30)).build());
    }
    CenterHttpClient(String baseUrl, String token, boolean runner, OkHttpClient client) {
        URI uri = URI.create(Objects.requireNonNull(baseUrl));
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme()))))
            throw new IllegalArgumentException("中心地址必须是 HTTPS 或明确的本机回环地址");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.header = runner ? "X-PaiCLI-Runner-Token" : "Authorization";
        this.client = client;
        replaceToken(token);
    }
    public void replaceToken(String value) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("缺少有效的中心凭证");
        token = value;
    }
    /** 微信等独立短期通道使用自己的连接池和凭证，关闭时不会取消执行租约的请求。 */
    public CenterHttpClient withRunnerToken(String scopedToken) {
        return new CenterHttpClient(baseUrl, scopedToken, true);
    }
    public JsonNode get(String path) throws IOException { return exchange("GET", path, null, null, null); }
    public JsonNode post(String path, JsonNode value, String idempotencyKey) throws IOException {
        return exchange("POST", path, value, idempotencyKey, null);
    }
    public JsonNode mutate(String method, String path, JsonNode value, String key, String version) throws IOException {
        return exchange(method, path, value, key, version);
    }
    public JsonNode uploadImage(String workspaceId, String name, String mimeType, byte[] image, String key) throws IOException {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("写请求必须有幂等键");
        if (image == null || image.length == 0 || image.length > 5 * 1024 * 1024) throw new IOException("API图片附件上限为5MB");
        if (!workspaceId.matches("[1-9][0-9]{0,18}") || name == null || name.contains("\r") || name.contains("\n") || name.length() > 255) throw new IOException("附件标识非法");
        MediaType media = MediaType.parse(mimeType == null ? "image/png" : mimeType);
        if (media == null || !"image".equals(media.type())) throw new IOException("附件必须声明图片MIME");
        MultipartBody form = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("workspaceId", workspaceId).addFormDataPart("purpose", "TURN_IMAGE")
            .addFormDataPart("file", name, RequestBody.create(image, media)).build();
        Request request = new Request.Builder().url(baseUrl + "/paicli/v1/artifacts")
            .header(header, "Authorization".equals(header) ? "Bearer " + token : token)
            .header("Idempotency-Key", key).post(form).build();
        return execute(request);
    }
    private JsonNode exchange(String method, String path, JsonNode value, String key, String version) throws IOException {
        if (!path.startsWith("/paicli/") || path.startsWith("//") || path.contains("#") || path.contains(".."))
            throw new IllegalArgumentException("非法中心接口路径");
        Request.Builder builder = new Request.Builder().url(baseUrl + path)
            .header(header, "Authorization".equals(header) ? "Bearer " + token : token)
            .header("Accept", "application/json");
        if (!"GET".equals(method)) {
            if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("写请求必须有幂等键");
            builder.header("Idempotency-Key", key);
            if (version != null) builder.header("If-Match", version);
            byte[] data=(value==null?"{}":value.toString()).getBytes(StandardCharsets.UTF_8);
            if(data.length>MAX_REQUEST_BYTES)throw new IOException("中心请求超过64MB协议上限");
            builder.method(method, RequestBody.create(data, MediaType.get("application/json; charset=utf-8")));
        }
        return execute(builder.build());
    }
    private JsonNode execute(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("中心请求失败，HTTP " + response.code());
            if (response.body() == null) throw new IOException("中心返回空响应");
            byte[] bytes;
            try (InputStream input = response.body().byteStream()) { bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1); }
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("中心响应超过上限");
            JsonNode envelope = CanonicalJson.MAPPER.readTree(bytes);
            if (envelope == null || !envelope.isObject() || !envelope.has("code") || envelope.path("code").asInt(-1) != 200)
                throw new IOException("中心拒绝该操作（业务状态 " + (envelope == null ? "空" : envelope.path("code").asText()) + "）");
            return envelope.has("data") ? envelope.get("data") : envelope;
        }
    }
    public void cancelCalls() { client.dispatcher().cancelAll(); }
    @Override public void close() { cancelCalls(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
}
