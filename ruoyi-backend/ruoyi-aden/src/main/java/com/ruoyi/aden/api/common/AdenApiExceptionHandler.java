package com.ruoyi.aden.api.common;

import com.ruoyi.aden.application.error.AdenApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.ruoyi.aden.api")
@ConditionalOnProperty(prefix = "aden", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdenApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AdenApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AdenErrorEnvelope> invalidBody(MethodArgumentNotValidException exception,
                                                   HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> details.putIfAbsent(error.getField(), "invalid"));
        return response(HttpStatus.BAD_REQUEST, "ADEN_INVALID_ARGUMENT", "请求参数不合法", details, request);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<AdenErrorEnvelope> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "ADEN_INVALID_ARGUMENT", "请求参数不合法", Map.of(), request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<AdenErrorEnvelope> notFound(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "ADEN_TASK_NOT_FOUND", "请求的资源不存在", Map.of(), request);
    }

    @ExceptionHandler(AdenApplicationException.class)
    ResponseEntity<AdenErrorEnvelope> applicationFailure(AdenApplicationException exception,
                                                          HttpServletRequest request) {
        HttpStatus status = switch (exception.errorCode()) {
            case "ADEN_AUTH_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "ADEN_PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "ADEN_TASK_NOT_FOUND", "ADEN_RUNNER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ADEN_STATE_TRANSITION_DENIED", "ADEN_IDEMPOTENCY_KEY_REUSED",
                    "ADEN_DEFAULT_WORKSPACE_UNAVAILABLE",
                    "ADEN_RUNNER_LEASE_LOST", "ADEN_SESSION_EPOCH_STALE",
                    "ADEN_RECEIPT_STALE", "ADEN_OUTCOME_UNKNOWN",
                    "ADEN_RUNNER_SESSION_INVALID", "ADEN_RUNNER_CAPACITY_MISMATCH",
                    "ADEN_HEARTBEAT_OUT_OF_ORDER" -> HttpStatus.CONFLICT;
            case "ADEN_STREAM_CURSOR_EXPIRED" -> HttpStatus.GONE;
            case "ADEN_VERSION_CONFLICT" -> HttpStatus.PRECONDITION_FAILED;
            case "ADEN_TASK_VALIDATION_FAILED" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "ADEN_PRECONDITION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED;
            case "ADEN_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "ADEN_DEPENDENCY_UNAVAILABLE", "ADEN_AUDIT_UNAVAILABLE",
                    "ADEN_STREAM_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, exception.errorCode(), exception.getMessage(), exception.details(), request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<AdenErrorEnvelope> accessDenied(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "ADEN_PERMISSION_DENIED",
                "当前账号无权执行此操作", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<AdenErrorEnvelope> unexpected(Exception exception, HttpServletRequest request) {
        String correlationId = AdenCorrelationIdFilter.correlationId(request);
        LOG.error("Aden 请求失败 correlationId={} method={} path={} exceptionType={}",
                correlationId, request.getMethod(), request.getRequestURI(), exception.getClass().getName());
        if (exception instanceof NoSuchBeanDefinitionException missingBean) {
            LOG.error("Aden 依赖缺失 correlationId={} beanName={} beanType={}", correlationId,
                    missingBean.getBeanName(), missingBean.getResolvableType());
        }
        LOG.debug("Aden 请求失败诊断 correlationId={}", correlationId, exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "ADEN_DEPENDENCY_UNAVAILABLE",
                "服务暂时不可用", Map.of(), request);
    }

    private ResponseEntity<AdenErrorEnvelope> response(HttpStatus status, String errorCode, String message,
                                                        Map<String, Object> details, HttpServletRequest request) {
        String correlationId = AdenCorrelationIdFilter.correlationId(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(AdenCorrelationIdFilter.HEADER, correlationId);
        if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        boolean retryable = status == HttpStatus.TOO_MANY_REQUESTS
                || status == HttpStatus.SERVICE_UNAVAILABLE;
        return builder.body(new AdenErrorEnvelope(status.value(), message, null, errorCode,
                retryable, correlationId, details));
    }
}
