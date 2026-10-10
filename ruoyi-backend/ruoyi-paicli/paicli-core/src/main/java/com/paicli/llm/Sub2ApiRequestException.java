package com.paicli.llm;

import java.io.IOException;

/** 失败或取消前已收到的合法计量是证据，不表示上游最终结算或退款。 */
public final class Sub2ApiRequestException extends IOException {
    private static final long serialVersionUID = 1L;

    public record TokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        public TokenUsage {
            if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || cachedInputTokens > inputTokens)
                throw new IllegalArgumentException("Sub2API 已报告用量非法");
        }
        public long totalTokens() { return (long) inputTokens + outputTokens; }
    }

    private final TokenUsage reportedUsage;
    private final boolean cancelled;

    Sub2ApiRequestException(TokenUsage reportedUsage, boolean cancelled) {
        super(cancelled ? "Sub2API 请求已取消，最终结果与收费尚未确认" : "Sub2API 请求失败或结果未知，最终收费尚未确认");
        this.reportedUsage = reportedUsage;
        this.cancelled = cancelled;
    }
    public TokenUsage reportedUsage() { return reportedUsage; }
    public boolean cancelled() { return cancelled; }
}
