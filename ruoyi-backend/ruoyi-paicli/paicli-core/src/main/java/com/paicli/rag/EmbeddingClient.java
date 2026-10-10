package com.paicli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端，支持 Ollama 本地模型和 OpenAI 兼容的远程 API
 */
public class EmbeddingClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final int expectedDimensions;
    public static final String CHUNKER_VERSION = "code-chunker-v1";
    public record Usage(int inputTokens, boolean present) { }
    private final ThreadLocal<Usage> lastUsage = new ThreadLocal<>();
    public Usage consumeUsage() { Usage value = lastUsage.get(); lastUsage.remove(); return value == null ? new Usage(0, false) : value; }

    public EmbeddingClient() {
        this(getEnv("EMBEDDING_PROVIDER", "ollama"), getEnv("EMBEDDING_MODEL", "nomic-embed-text:latest"),
                getEnv("EMBEDDING_BASE_URL", inferDefaultUrl(getEnv("EMBEDDING_PROVIDER", "ollama"))),
                getEnv("EMBEDDING_API_KEY", ""));
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this(provider, model, baseUrl, apiKey, 0);
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey, int expectedDimensions) {
        this.provider = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ollama", "openai", "zhipu", "glm").contains(this.provider))
            throw new IllegalArgumentException("Embedding供应商不受支持");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Embedding型号不能为空");
        if (expectedDimensions < 0 || expectedDimensions > 65536) throw new IllegalArgumentException("向量维度非法");
        this.model = model.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.expectedDimensions = expectedDimensions;
    }

    // 安全截断长度（中文密集文本 2000 字符 ≈ 4000~6000 token，适配 8192 上下文模型）
    private static final int MAX_INPUT_CHARS = 2000;

    /**
     * 获取文本的向量表示
     */
    public float[] embed(String text) throws IOException {
        lastUsage.remove();
        if (text == null || text.isEmpty()) {
            return new float[0];
        }

        // 截断过长文本，防止 API 报错
        String input = text.length() > MAX_INPUT_CHARS
                ? text.substring(0, MAX_INPUT_CHARS)
                : text;

        return switch (provider.toLowerCase()) {
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu", "glm" -> embedOpenAICompatible(input);
            default -> throw new IOException("Embedding供应商不受支持");
        };
    }

    private float[] embedOllama(String text) throws IOException {
        String url = baseUrl + "/api/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", text);

        String responseBody = postJson(url, requestBody.toString(), false);
        JsonNode root = mapper.readTree(responseBody);
        lastUsage.set(inputUsage(root.path("prompt_eval_count")));
        JsonNode embeddingNode = root.path("embedding");

        return parseVector(embeddingNode);
    }

    private float[] embedOpenAICompatible(String text) throws IOException {
        String url = baseUrl + "/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("input", text);

        String responseBody = postJson(url, requestBody.toString(), true);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode usage = root.path("usage");
        Usage prompt=inputUsage(usage.path("prompt_tokens")),total=inputUsage(usage.path("total_tokens"));
        if(usage.has("prompt_tokens")&&usage.has("total_tokens"))
            lastUsage.set(new Usage(Math.max(prompt.inputTokens(),total.inputTokens()),prompt.present()&&total.present()&&prompt.inputTokens()==total.inputTokens()));
        else lastUsage.set(usage.has("prompt_tokens")?prompt:total);
        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("API 返回的 embedding 格式不正确");
        }

        if (root.hasNonNull("model") && !model.equals(root.path("model").asText()))
            throw new IOException("Embedding响应型号与固定配置不符");
        return parseVector(data.get(0).path("embedding"));
    }

    private float[] parseVector(JsonNode vector) throws IOException {
        if (!vector.isArray() || vector.isEmpty()) throw new IOException("Embedding响应必须包含非空向量数组");
        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            if (!vector.get(i).isNumber()) throw new IOException("Embedding向量元素必须是数值");
            values[i] = (float) vector.get(i).asDouble();
        }
        validateVector(values, expectedDimensions);
        return values;
    }
    private static Usage inputUsage(JsonNode value) {
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<0||value.asInt()>100_000_000)
            return new Usage(0,false);
        return new Usage(value.asInt(),true);
    }

    public static void validateVector(float[] vector, int expectedDimensions) throws IOException {
        if (vector == null || vector.length == 0) throw new IOException("Embedding向量为空");
        if (expectedDimensions > 0 && vector.length != expectedDimensions)
            throw new IOException("Embedding维度不符：预期" + expectedDimensions + "，实际" + vector.length);
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IOException("Embedding向量必须为有限数值");
            nonzero |= value != 0f;
        }
        if (!nonzero) throw new IOException("Embedding向量不能全零");
    }

    public static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Embedding服务地址不能为空");
        String root = value.trim().replaceAll("/+$", "");
        java.net.URI uri;
        try { uri = java.net.URI.create(root); } catch (RuntimeException failure) { throw new IllegalArgumentException("Embedding服务地址非法"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("http", "https").contains(scheme) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Embedding服务地址非法");
        uri = uri.normalize();
        String path = uri.getRawPath().replaceAll("/+$", "");
        if (path.endsWith("/embeddings")) throw new IllegalArgumentException("请登记Embedding根地址，不能包含请求路径");
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
    }

    public int getExpectedDimensions() { return expectedDimensions; }
    public String getBaseUrl() { return baseUrl; }
    public EmbeddingIdentity getIdentity() { return new EmbeddingIdentity(provider, baseUrl, model, expectedDimensions, CHUNKER_VERSION); }

    private String postJson(String url, String jsonBody, boolean useAuth) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body);

        if (useAuth && apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                throw new IOException("Embedding API 请求失败 [" + response.code() + "]");
            }
            if (responseBody == null) {
                throw new IOException("Embedding API 返回空响应体");
            }
            return responseBody.string();
        }
    }

    private static String inferDefaultUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "http://localhost:11434";
            case "zhipu", "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            default -> "http://localhost:11434";
        };
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        value = System.getProperty(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }
}
