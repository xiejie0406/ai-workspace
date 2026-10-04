package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import java.io.IOException;
import java.util.List;

/** 复用 Chat Completions 编解码，禁用其他厂商扩展和不确定请求重放。 */
public final class Sub2ApiChatClient extends AbstractOpenAiCompatibleClient {
    private final String key, model, url;
    private final OkHttpClient http = SHARED_HTTP_CLIENT.newBuilder().dispatcher(new Dispatcher()).build();

    public Sub2ApiChatClient(String key, String model, String root) {
        if (model == null || !ModelCatalog.validModelId(model)) throw new IllegalArgumentException("Sub2API 缺少有效的精确模型 ID");
        this.key = key; this.model = model; this.url = Sub2ApiEndpoint.endpoint(root, "chat_completions");
    }
    protected String getApiUrl() { return url; }
    protected String getModel() { return model; }
    protected String getApiKey() { return key; }
    public String getProviderName() { return "sub2api"; }
    public String getModelName() { return model; }
    public boolean supportsImageInput() { return false; }
    protected OkHttpClient httpClient() { return http; }
    @Override LlmRetryPolicy retryPolicy() { return LlmRetryPolicy.singleAttempt(); }

    @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        if (messages.stream().anyMatch(Message::hasImageContent)) throw new IOException("Sub2API 本期仅支持文本，请移除图片附件后再发送");
        try {
            ChatResponse result = super.chat(messages, tools, listener);
            java.util.Set<String> ids = new java.util.HashSet<>();
            if (result.toolCalls() != null) for (ToolCall call : result.toolCalls()) {
                var arguments = mapper.readTree(call.function().arguments());
                if (call.id() == null || call.id().isBlank() || !ids.add(call.id()) || call.function().name().isBlank()
                        || arguments == null || !arguments.isObject()) throw new IOException("Sub2API 工具调用非法");
            }
            return result;
        }
        catch (IOException failure) {
            if (failure instanceof LlmHttpException httpFailure) throw new IOException("Sub2API 请求失败，HTTP " + httpFailure.statusCode());
            // 上游错误体可能含凭据或提示正文，不转交到用户页和任务日志。
            throw new IOException("Sub2API Chat Completions 响应失败、已取消或结果未知");
        }
    }

    @Override protected void finalizeRequestBody(ObjectNode body) {
        body.remove("thinking"); body.remove("reasoning_effort");
        if (body.has("max_tokens")) body.set("max_completion_tokens", body.remove("max_tokens"));
        for (var message : body.path("messages")) if (message instanceof ObjectNode node) node.remove("reasoning_content");
        body.putObject("stream_options").put("include_usage", true);
    }

    @Override protected void validateResponseFrame(com.fasterxml.jackson.databind.JsonNode frame) throws IOException {
        for (var choice : frame.path("choices")) {
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty() && !List.of("stop", "tool_calls").contains(finish))
                throw new IOException("Sub2API Chat Completions 拒绝、截断或终态未知");
            if (choice.path("delta").hasNonNull("refusal") || choice.path("message").hasNonNull("refusal"))
                throw new IOException("Sub2API Chat Completions 已拒绝");
        }
    }
}
