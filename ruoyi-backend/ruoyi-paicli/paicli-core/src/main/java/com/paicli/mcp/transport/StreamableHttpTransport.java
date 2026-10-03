package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.protocol.McpInitializeRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StreamableHttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");
    public static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final String INCOMPLETE = "MCP_RESULT_INCOMPLETE: MCP响应超过限额或协议不完整，原结果不完整，须暂停对账";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private final String url;
    @FunctionalInterface public interface HeaderProvider { Map<String, String> current() throws IOException; }
    private final HeaderProvider headers;
    private volatile int responseLimit;
    private volatile boolean closed;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile String sessionId;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this(url, headers, MAX_RESPONSE_BYTES);
    }

    StreamableHttpTransport(String url, Map<String, String> headers, int responseLimit) {
        this.url = url;
        this.responseLimit = checkedLimit(responseLimit);
        Map<String, String> frozen = headers == null ? Map.of() : Map.copyOf(headers);
        this.headers = () -> frozen;
    }

    public StreamableHttpTransport(String url, HeaderProvider headers) {
        this.url = url;
        this.responseLimit = MAX_RESPONSE_BYTES;
        this.headers = java.util.Objects.requireNonNull(headers);
    }

    /** 每个请求开始捕获限额，后续配置变化不能放宽已开始的读取。 */
    public void setResponseLimit(int bytes) { responseLimit=checkedLimit(bytes); }

    @Override
    public void send(JsonNode message) throws IOException {
        if (closed) throw new IOException("MCP HTTP transport already closed; " + INCOMPLETE);
        int requestLimit=responseLimit;
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .post(body);
        headers.current().forEach(builder::header);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String newSession = response.header("Mcp-Session-Id");
            if (newSession != null && !newSession.isBlank()) {
                sessionId = newSession;
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + "; " + INCOMPLETE);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return;
            }
            String contentType = response.header("Content-Type", "");
            String raw = readUtf8(responseBody.byteStream(), responseBody.contentLength(), requestLimit);
            // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body。
            // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException。
            if (raw == null || raw.isBlank()) {
                return;
            }
            List<JsonNode> messages = contentType.contains("text/event-stream")
                    ? parseSse(raw)
                    : List.of(MAPPER.readTree(raw));
            for (JsonNode node : messages) {
                if (node == null || !node.isObject()) throw new IOException(INCOMPLETE);
                for (Consumer<JsonNode> listener : listeners) {
                    listener.accept(node);
                }
            }
        } catch (IOException failure) {
            closeLocal();
            if (failure.getMessage() != null && failure.getMessage().startsWith("HTTP ")) throw failure;
            // 不保留Jackson/服务端响应正文及异常cause，避免凭证混入诊断信息。
            throw new IOException(INCOMPLETE);
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (closed) return;
        if (sessionId == null || sessionId.isBlank()) {
            closeLocal();
            return;
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .delete();
        // close 是 best-effort：server 已经关停 / 网络不通时不应该让 PaiCLI 退出卡住。
        // 主 client 的 callTimeout 是 60s，这里用 5s 短超时单独发请求。
        OkHttpClient closeClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();
        try {
            headers.current().forEach(builder::header);
            try (Response ignored = closeClient.newCall(builder.build()).execute()) {
            // best effort
            }
        } catch (IOException ignored) {
        } finally {
            closeLocal();
        }
    }

    /** 授权已撤销时只清理本机资源，不带旧凭证再发送远端请求。 */
    public void closeLocal() {
        closed = true;
        client.dispatcher().cancelAll(); client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow(); listeners.clear(); sessionId = null;
    }

    private static int checkedLimit(int value) {
        if (value < 1 || value > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("MCP响应限额非法");
        return value;
    }

    /** Content-Length只是提前拒绝提示，chunked/解压正文仍逐块按实际字节计数。 */
    static String readUtf8(InputStream input, long announcedLength, int limit) throws IOException {
        if (announcedLength > limit) throw new IOException(INCOMPLETE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] chunk = new byte[8192];
        int size;
        while ((size = input.read(chunk, 0, Math.min(chunk.length, limit - bytes.size() + 1))) != -1) {
            if (size > limit - bytes.size()) throw new IOException(INCOMPLETE);
            bytes.write(chunk, 0, size);
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    messages.add(MAPPER.readTree(data.toString()));
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            messages.add(MAPPER.readTree(data.toString()));
        }
        return messages;
    }
}
