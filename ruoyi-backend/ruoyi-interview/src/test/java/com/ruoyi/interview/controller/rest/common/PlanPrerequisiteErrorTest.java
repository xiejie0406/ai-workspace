package com.ruoyi.interview.controller.rest.common;

import com.ruoyi.interview.application.shared.ApplicationErrorCode;
import com.ruoyi.interview.application.shared.ApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanPrerequisiteErrorTest {
    private final ApiErrorHandler handler = new ApiErrorHandler();

    @Test
    void emptyCatalogIsConflictWithSafeActionableMessage() {
        var response = handler.applicationFailure(new ApplicationException(ApplicationErrorCode.PLAN_NOT_READY,
                "internal diagnostic must not escape", false, Map.of("reason", "PUBLISHED_QUESTIONS_MISSING")),
                new MockHttpServletRequest("POST", "/api/v1/interview-plans"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("PLAN_NOT_READY", response.getBody().get("errorCode"));
        assertEquals("没有匹配岗位的已发布题目，请联系题库维护人员", response.getBody().get("msg"));
        assertEquals(false, response.getBody().get("retryable"));
        assertEquals(Map.of("reason", "PUBLISHED_QUESTIONS_MISSING"), response.getBody().get("data"));
    }

    @Test
    void missingProfileHasItsOwnRecoveryMessage() {
        var response = handler.applicationFailure(new ApplicationException(ApplicationErrorCode.PLAN_NOT_READY,
                "internal diagnostic", false, Map.of("reason", "PROFILE_MISSING")), new MockHttpServletRequest());
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("当前账号缺少有效面试档案，请先准备档案", response.getBody().get("msg"));
    }

    @Test
    void unknownPrerequisiteNeverExposesExceptionMessage() {
        var response = handler.applicationFailure(new ApplicationException(ApplicationErrorCode.PLAN_NOT_READY,
                "private data", false, Map.of("reason", "UNKNOWN")), new MockHttpServletRequest());
        assertEquals("面试前提资料尚未就绪，请检查档案和已发布题目", response.getBody().get("msg"));
    }
}
