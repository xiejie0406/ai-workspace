package com.ruoyi.paismart.api;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 复用若依错误信封；不把 SQL、凭证及外部响应原文返回浏览器。 */
@RestControllerAdvice(basePackages = "com.ruoyi.paismart.api")
@PaismartEnabled
public class PaismartExceptionHandler {
    @ExceptionHandler(com.ruoyi.paismart.application.billing.RateLimitExceededException.class)
    public ResponseEntity<AjaxResult> rateLimited(com.ruoyi.paismart.application.billing.RateLimitExceededException ex) {
        AjaxResult result=AjaxResult.error(429,ex.getMessage());result.put("errorCode","RATE_LIMITED");result.put("retryAfter",ex.retryAfterSeconds());
        return ResponseEntity.status(429).header("Retry-After",Long.toString(ex.retryAfterSeconds())).body(result);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AjaxResult> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(AjaxResult.error(400, ex.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AjaxResult> conflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(AjaxResult.error(409, ex.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AjaxResult> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(AjaxResult.error(403, "无权访问该知识库资源"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AjaxResult> unavailable(DataAccessException ex) {
        return ResponseEntity.status(503).body(AjaxResult.error(503, "知识库数据结构或连接未就绪，请联系管理员"));
    }
}
