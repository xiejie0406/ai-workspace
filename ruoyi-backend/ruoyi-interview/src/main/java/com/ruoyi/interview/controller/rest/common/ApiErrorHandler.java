package com.ruoyi.interview.controller.rest.common;

import com.ruoyi.interview.configuration.InterviewEnabled;

import com.ruoyi.interview.infrastructure.AdapterUnavailableException;
import com.ruoyi.interview.application.shared.ApplicationErrorCode;
import com.ruoyi.interview.application.shared.ApplicationException;
import com.ruoyi.interview.domain.platform.DomainErrorCode;
import com.ruoyi.interview.domain.platform.DomainException;
import com.ruoyi.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps known failures to the stable ErrorEnvelope contract without leaking internals. */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@InterviewEnabled
@RestControllerAdvice(basePackages = "com.ruoyi.interview.controller.rest")
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AjaxResult> statusFailure(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        LOG.warn("Request rejected correlationId={} path={} status={}",
                correlationId(request), request.getRequestURI(), status.value());
        // 当前受控朗读接口的中文原因不包含供应商响应或秘密；其他接口使用通用提示。
        String message = request.getRequestURI().endsWith("/question-speech") && exception.getReason() != null
                ? exception.getReason() : "请求暂时无法完成";
        return response(status, status == HttpStatus.SERVICE_UNAVAILABLE ? "VOICE_SYNTHESIS_UNAVAILABLE" : "REQUEST_REJECTED",
                message, status.is5xxServerError(), Map.of(), request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<AjaxResult> accessDenied(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有访问权限", false, Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AjaxResult> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), "invalid"));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", false, fields, request);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<AjaxResult> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", false, Map.of(), request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<AjaxResult> notFound(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "请求的资源不存在", false, Map.of(), request);
    }

    @ExceptionHandler(AdapterUnavailableException.class)
    ResponseEntity<AjaxResult> capabilityUnavailable(
            AdapterUnavailableException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.NOT_IMPLEMENTED,
                "CAPABILITY_UNAVAILABLE",
                "该能力暂不可用",
                false,
                Map.of("capability", exception.capability()),
                request);
    }

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<AjaxResult> applicationFailure(
            ApplicationException exception,
            HttpServletRequest request) {
        HttpStatus status = applicationStatus(exception.code());
        Map<String, Object> details = new LinkedHashMap<>();
        exception.details().forEach(details::put);
        return response(
                status,
                exception.code().name(),
                applicationMessage(exception),
                exception.retryable(),
                details,
                request);
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<AjaxResult> domainRejection(DomainException exception, HttpServletRequest request) {
        DomainErrorCode code = exception.code();
        return response(
                domainStatus(code),
                publicDomainCode(code),
                domainMessage(code),
                false,
                Map.of(),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<AjaxResult> unexpected(Exception exception, HttpServletRequest request) {
        String correlationId = correlationId(request);
        LOG.error("Unhandled request failure correlationId={} method={} path={} exceptionType={}",
                correlationId,
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用", true, Map.of(), request);
    }

    private ResponseEntity<AjaxResult> response(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details,
            HttpServletRequest request) {
        AjaxResult result = AjaxResult.error(status.value(), message);
        result.put("errorCode", code);
        result.put("retryable", retryable);
        result.put("correlationId", correlationId(request));
        if (!details.isEmpty()) result.put("data", details);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }

    private String correlationId(HttpServletRequest request) {
        return CorrelationIdFilter.correlationId(request);
    }

    private HttpStatus applicationStatus(ApplicationErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AUTH_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case IDEMPOTENCY_IN_PROGRESS, IDEMPOTENCY_REPLAY_FAILURE -> HttpStatus.CONFLICT;
            case CAPABILITY_UNAVAILABLE -> HttpStatus.NOT_IMPLEMENTED;
            case PLAN_NOT_READY -> HttpStatus.CONFLICT;
            case PROVIDER_BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERNAL_CONSISTENCY_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String applicationMessage(ApplicationException exception) {
        return switch (exception.code()) {
            case NOT_FOUND -> "请求的资源不存在";
            case AUTH_REQUIRED -> "需要登录后继续";
            case FORBIDDEN -> "没有执行该操作的权限";
            case IDEMPOTENCY_IN_PROGRESS -> "相同操作仍在处理中";
            case IDEMPOTENCY_REPLAY_FAILURE -> "该幂等操作已记录失败结果";
            case CAPABILITY_UNAVAILABLE -> "该能力暂不可用";
            case PLAN_NOT_READY -> switch (exception.details().getOrDefault("reason", "")) {
                case "PROFILE_MISSING" -> "当前账号缺少有效面试档案，请先准备档案";
                case "PUBLISHED_QUESTIONS_MISSING" -> "没有匹配岗位的已发布题目，请联系题库维护人员";
                default -> "面试前提资料尚未就绪，请检查档案和已发布题目";
            };
            case PROVIDER_BAD_RESPONSE -> "外部能力返回了不可接受的结果";
            case DEADLINE_EXCEEDED -> "操作未能在时限内完成";
            case INTERNAL_CONSISTENCY_ERROR -> "服务暂时不可用";
        };
    }

    private HttpStatus domainStatus(DomainErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case TENANT_MISMATCH, OWNERSHIP_DENIED -> HttpStatus.NOT_FOUND;
            case ACCOUNT_NOT_ACTIVE, MEMBERSHIP_NOT_ACTIVE, CONSENT_REQUIRED,
                    ENTITLEMENT_NOT_ACTIVE, ENTITLEMENT_EXPIRED, POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case VERSION_CONFLICT -> HttpStatus.PRECONDITION_FAILED;
            case INVALID_STATE, RESOURCE_NOT_ACTIVE, QUESTION_NOT_PUBLISHABLE,
                    CONTENT_SOURCE_REQUIRED, RUBRIC_REQUIRED, ANSWER_REQUIRED,
                    ANSWER_ALREADY_SUBMITTED, ANSWER_ALREADY_CONFIRMED, USAGE_EXCEEDED,
                    RESERVATION_EXPIRED, RESERVATION_ALREADY_FINALIZED, PLAN_NOT_CONFIRMED,
                    PLAN_EXPIRED, SESSION_ALREADY_ACTIVE, STALE_TURN, JOB_LEASE_NOT_HELD,
                    JOB_NOT_CANCELLABLE, IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS,
                    RETRY_NOT_ALLOWED -> HttpStatus.CONFLICT;
        };
    }

    private String publicDomainCode(DomainErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "VALIDATION_FAILED";
            case INVALID_STATE -> "INVALID_TRANSITION";
            case TENANT_MISMATCH, OWNERSHIP_DENIED -> "NOT_FOUND";
            default -> code.name();
        };
    }

    private String domainMessage(DomainErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "请求参数不合法";
            case TENANT_MISMATCH, OWNERSHIP_DENIED -> "请求的资源不存在";
            case ACCOUNT_NOT_ACTIVE, MEMBERSHIP_NOT_ACTIVE -> "当前账号或成员身份不可用";
            case CONSENT_REQUIRED -> "需要先完成相应授权";
            case ENTITLEMENT_NOT_ACTIVE, ENTITLEMENT_EXPIRED -> "当前权益不可用";
            case USAGE_EXCEEDED -> "可用额度不足";
            case VERSION_CONFLICT -> "资源已发生变化，请刷新后重试";
            case PLAN_EXPIRED -> "面试计划已过期";
            case STALE_TURN -> "当前轮次已发生变化，请恢复最新状态";
            case IDEMPOTENCY_IN_PROGRESS -> "相同操作仍在处理中";
            case POLICY_DENIED -> "当前策略不允许该操作";
            default -> "当前状态不允许该操作";
        };
    }
}


