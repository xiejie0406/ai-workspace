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
    private final ThreadLocal<Boolean> terminal = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Sub2ApiRequestException.TokenUsage> reportedUsage = new ThreadLocal<>();
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

    public Sub2ApiChatClient(String key, String model, String root) {
        this(key,model,root,null);
    }
    public Sub2ApiChatClient(String key, String model, String root,String approvedHttpRoot) {
        if (model == null || !ModelCatalog.validModelId(model)) throw new IllegalArgumentException("Sub2API 缺少有效的精确模型 ID");
        this.key = key; this.model = model; this.url = Sub2ApiEndpoint.endpoint(root, "chat_completions",approvedHttpRoot);
    }
    protected String getApiUrl() { return url; }
    protected String getModel() { return model; }
    protected String getApiKey() { return key; }
    public String getProviderName() { return "sub2api"; }
    public String getModelName() { return model; }
    public boolean supportsImageInput() { return false; }
    protected OkHttpClient httpClient() { return http; }
    @Override LlmRetryPolicy retryPolicy() { return LlmRetryPolicy.singleAttempt(); }
    @Override protected boolean requireCompleteToolCalls() { return true; }
    @Override public void cancelInFlightCalls() { cancelled.set(true); super.cancelInFlightCalls(); }

    @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        if (messages.stream().anyMatch(Message::hasImageContent)) throw new IOException("Sub2API 本期仅支持文本，请移除图片附件后再发送");
        terminal.set(false);
        reportedUsage.remove();
        try (var watch = new Sub2ApiCancellation(this::cancelInFlightCalls)) {
            ChatResponse result = super.chat(messages, tools, listener);
            if (cancelled.get() || com.paicli.runtime.CancellationContext.isCancelled() || Thread.currentThread().isInterrupted())
                throw new IOException("Sub2API 请求已取消");
            if (!terminal.get()) throw new IOException("Sub2API Chat Completions 缺少真实终态");
            if(result.cachedInputTokens()>result.inputTokens())throw new IOException("Sub2API缓存用量不能超过输入用量");
            java.util.Set<String> ids = new java.util.HashSet<>();
            if (result.toolCalls() != null) for (ToolCall call : result.toolCalls()) {
                var arguments = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.function().arguments());
                if (call.id() == null || call.id().isBlank() || !ids.add(call.id()) || call.function().name().isBlank()
                        || arguments == null || !arguments.isObject()) throw new IOException("Sub2API 工具调用非法");
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            // 上游错误体可能含凭据或提示正文，不转交到用户页和任务日志。
            throw new Sub2ApiRequestException(reportedUsage.get(), cancelled.get() || com.paicli.runtime.CancellationContext.isCancelled() || Thread.currentThread().isInterrupted());
        } finally { terminal.remove(); reportedUsage.remove(); }
    }

    @Override protected void finalizeRequestBody(ObjectNode body) {
        body.remove("thinking"); body.remove("reasoning_effort");
        if (body.has("max_tokens")) body.set("max_completion_tokens", body.remove("max_tokens"));
        for (var message : body.path("messages")) if (message instanceof ObjectNode node) node.remove("reasoning_content");
        body.putObject("stream_options").put("include_usage", true);
    }

    @Override protected void validateResponseFrame(com.fasterxml.jackson.databind.JsonNode frame) throws IOException {
        // 本请求未申请多候选；基类只解码第一个，不能借其他候选的终态证明其完成。
        if(frame.path("choices").isArray()&&frame.path("choices").size()>1)
            throw new IOException("Sub2API返回未申请的多候选响应");
        var usage=frame.get("usage");
        if(usage!=null&&!usage.isNull()){
            if(!hasCompleteTokenUsage(usage))throw new IOException("Sub2API返回无效用量");
            for(String field:List.of("cached_tokens","prompt_cache_hit_tokens","input_cache_hit_tokens"))
                if(usage.has(field))validateCachedUsage(usage.get(field),usage.path("prompt_tokens").intValue());
            for(String field:List.of("prompt_tokens_details","input_tokens_details"))
                if(usage.path(field).has("cached_tokens"))validateCachedUsage(usage.path(field).get("cached_tokens"),usage.path("prompt_tokens").intValue());
            if(usage.path("completion_tokens_details").has("reasoning_tokens"))
                validateCachedUsage(usage.path("completion_tokens_details").get("reasoning_tokens"),usage.path("completion_tokens").intValue());
            if(usage.has("total_tokens")){
                var total=usage.get("total_tokens");
                if(!total.isIntegralNumber()||!total.canConvertToInt()||total.intValue()<0
                        ||total.longValue()!=(long)usage.path("prompt_tokens").intValue()+usage.path("completion_tokens").intValue())
                    throw new IOException("Sub2API总用量非法或不一致");
            }
            int cached=0;
            for(String field:List.of("cached_tokens","prompt_cache_hit_tokens","input_cache_hit_tokens"))
                if(usage.has(field))cached=usage.path(field).intValue();
            for(String field:List.of("prompt_tokens_details","input_tokens_details"))
                if(usage.path(field).has("cached_tokens"))cached=usage.path(field).path("cached_tokens").intValue();
            reportedUsage.set(new Sub2ApiRequestException.TokenUsage(usage.path("prompt_tokens").intValue(),usage.path("completion_tokens").intValue(),cached));
        }
        for (var choice : frame.path("choices")) {
            if(choice.has("index")&&(!choice.path("index").isIntegralNumber()||!choice.path("index").canConvertToInt()||choice.path("index").intValue()!=0))
                throw new IOException("Sub2API候选序号无效");
            for(var tool:choice.path("delta").path("tool_calls")){
                var index=tool.get("index");if(index==null||!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||index.intValue()>255)
                    throw new IOException("Sub2API工具调用序号无效");
            }
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty() && !List.of("stop", "tool_calls").contains(finish))
                throw new IOException("Sub2API Chat Completions 拒绝、截断或终态未知");
            if (!finish.isEmpty()) terminal.set(true);
            if (choice.path("delta").hasNonNull("refusal") || choice.path("message").hasNonNull("refusal"))
                throw new IOException("Sub2API Chat Completions 已拒绝");
        }
    }
    private void validateCachedUsage(com.fasterxml.jackson.databind.JsonNode value,int input)throws IOException{
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<0||value.intValue()>input)throw new IOException("Sub2API返回无效缓存用量");
    }
}
