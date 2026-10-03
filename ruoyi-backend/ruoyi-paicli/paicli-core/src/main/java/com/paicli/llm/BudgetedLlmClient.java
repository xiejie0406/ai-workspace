package com.paicli.llm;

import com.paicli.memory.TokenBudget;
import com.paicli.runtime.CancellationContext;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** 三条路径、压缩、事实提取共享中心剩余额度；并行请求先预留再记账。 */
public final class BudgetedLlmClient implements LlmClient {
    public record Usage(int inputTokens, int outputTokens, int cachedInputTokens, long totalTokens,
                        int reserved, boolean usagePresent, String status) {}
    private final LlmClient delegate;
    private final int maxOutput;
    private final Consumer<Usage> observer;
    private long remaining;
    private long spent;
    public BudgetedLlmClient(LlmClient delegate, long availableTokens, int maxOutput, Consumer<Usage> observer) {
        if (availableTokens <= 0 || maxOutput <= 0) throw new IllegalArgumentException("中心预算必须为正");
        this.delegate = java.util.Objects.requireNonNull(delegate); this.remaining = availableTokens;
        this.maxOutput = maxOutput; this.observer = java.util.Objects.requireNonNull(observer);
    }
    @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
    @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        if (CancellationContext.isCancelled()) throw new IOException("中心任务已取消");
        int input = Math.max(1, TokenBudget.estimateMessagesTokens(messages));
        if (tools != null) for (Tool tool : tools) input += Math.max(1, (tool.name().length() + tool.description().length() + tool.parameters().toString().length()) / 2);
        int output; int reserved;
        synchronized (this) {
            if (remaining <= input) throw new IOException("中心Token剩余额度不足，停止模型和后续工具调用");
            output = (int) Math.min(maxOutput, remaining - input); reserved = input + output; remaining -= reserved;
        }
        ChatResponse response;
        try (var limit = LlmRequestLimit.bind(output)) { response = delegate.chat(messages, tools, listener); }
        catch (IOException | RuntimeException failure) {
            synchronized (this) { spent += reserved; observer.accept(new Usage(input, output, 0, reserved, reserved, false, "UNCERTAIN")); }
            throw failure;
        }
        if (response == null) {
            synchronized (this) { spent += reserved; observer.accept(new Usage(input, output, 0, reserved, reserved, false, "UNCERTAIN")); }
            throw new IOException("供应商返回空响应");
        }
        int actualInput = response.usagePresent() ? Math.max(0, response.inputTokens()) : Math.max(input, response.inputTokens());
        int actualOutput = response.usagePresent() ? Math.max(0, response.outputTokens()) : Math.max(output, Math.max(response.outputTokens(),
                ((response.content() == null ? 0 : response.content().length()) + (response.reasoningContent() == null ? 0 : response.reasoningContent().length()))));
        synchronized (this) {
            long charged = (long) actualInput + actualOutput; spent += charged; remaining += reserved - charged;
            observer.accept(new Usage(actualInput, actualOutput, Math.max(0, response.cachedInputTokens()), charged, reserved, response.usagePresent(), "COMPLETED"));
            if (remaining < 0) throw new IOException("供应商用量超过中心预留额度，后续执行已停止");
        }
        return response;
    }
    public synchronized long remainingTokens() { return remaining; }
    @FunctionalInterface public interface ExternalCall<T> { ExternalResponse<T> run() throws IOException; }
    public record ExternalResponse<T>(T result, int inputTokens, int outputTokens, int cachedTokens, boolean usagePresent) {}
    /** 向量请求只产生输入Token；与编码/搜索共享余额，缺usage时保守计预留输入。 */
    public <T> T externalInputOnly(int estimatedInput, ExternalCall<T> call) throws IOException {
        if (CancellationContext.isCancelled()) throw new IOException("中心任务已取消");
        int reserved = Math.max(1, estimatedInput);
        synchronized (this) { if (remaining < reserved) throw new IOException("中心Token剩余额度不足"); remaining -= reserved; }
        ExternalResponse<T> response;
        try { response = call.run(); }
        catch (IOException | RuntimeException failure) { synchronized (this) { spent += reserved; observer.accept(new Usage(reserved, 0, 0, reserved, reserved, false, "UNCERTAIN")); } throw failure; }
        int charged = response.usagePresent() ? Math.max(0, response.inputTokens()) : Math.max(reserved, response.inputTokens());
        synchronized (this) { spent += charged; remaining += reserved - charged; observer.accept(new Usage(charged, 0, response.cachedTokens(), charged, reserved, response.usagePresent(), "COMPLETED")); if (remaining < 0) throw new IOException("向量用量超过中心预算"); }
        return response.result();
    }
    /** 独立计费的模型搜索与主模型共享同一预留额度和实际用量。 */
    public <T> T external(int estimatedInput, ExternalCall<T> call) throws IOException {
        if (CancellationContext.isCancelled()) throw new IOException("中心任务已取消");
        int input = Math.max(1, estimatedInput), output, reserved;
        synchronized (this) { if (remaining <= input) throw new IOException("中心Token剩余额度不足"); output = (int) Math.min(maxOutput, remaining - input); reserved = input + output; remaining -= reserved; }
        ExternalResponse<T> response;
        try (var cap = LlmRequestLimit.bind(output)) { response = call.run(); }
        catch (IOException | RuntimeException failure) { synchronized (this) { spent += reserved; observer.accept(new Usage(input, output, 0, reserved, reserved, false, "UNCERTAIN")); } throw failure; }
        int actualInput = response.usagePresent() ? Math.max(0, response.inputTokens()) : Math.max(input, response.inputTokens());
        int actualOutput = response.usagePresent() ? Math.max(0, response.outputTokens()) : Math.max(output, response.outputTokens());
        synchronized (this) { long charged = (long) actualInput + actualOutput; spent += charged; remaining += reserved - charged; observer.accept(new Usage(actualInput, actualOutput, response.cachedTokens(), charged, reserved, response.usagePresent(), "COMPLETED")); if (remaining < 0) throw new IOException("外部模型搜索用量超过中心预算"); }
        return response.result();
    }
    public String getModelName() { return delegate.getModelName(); }
    public String getProviderName() { return delegate.getProviderName(); }
    public int maxContextWindow() { return delegate.maxContextWindow(); }
    public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
    public boolean supportsTools() { return delegate.supportsTools(); }
    public boolean supportsImageInput() { return delegate.supportsImageInput(); }
    public boolean looksLikeUnexecutedToolCall(String content) { return delegate.looksLikeUnexecutedToolCall(content); }
    public String promptCacheMode() { return delegate.promptCacheMode(); }
    public void cancelInFlightCalls() { delegate.cancelInFlightCalls(); }
}
