package com.ruoyi.paismart.application.billing;
/** 可重试窗口限流，与版本冲突及余额不足区分。 */
public final class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;
    public RateLimitExceededException(String message,long seconds){super(message);retryAfterSeconds=Math.max(1,seconds);}
    public long retryAfterSeconds(){return retryAfterSeconds;}
}
