package com.ruoyi.interview.application.shared;

/** 协议无关的应用失败类别；REST adapter 再映射 HTTP，Worker 再映射 Job failure。 */
public enum ApplicationErrorCode {
    NOT_FOUND,
    AUTH_REQUIRED,
    FORBIDDEN,
    IDEMPOTENCY_IN_PROGRESS,
    IDEMPOTENCY_REPLAY_FAILURE,
    CAPABILITY_UNAVAILABLE,
    PLAN_NOT_READY,
    PROVIDER_BAD_RESPONSE,
    DEADLINE_EXCEEDED,
    INTERNAL_CONSISTENCY_ERROR
}
