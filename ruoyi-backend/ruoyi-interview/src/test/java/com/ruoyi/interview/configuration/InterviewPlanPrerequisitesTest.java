package com.ruoyi.interview.configuration;

import com.ruoyi.interview.application.interview.InterviewTargetLevel;
import com.ruoyi.interview.application.interview.InterviewTargetRole;
import com.ruoyi.interview.application.interview.port.InterviewPlanPolicyPort;
import com.ruoyi.interview.application.interview.port.PlanQuestionSelectionPort;
import com.ruoyi.interview.application.shared.ApplicationErrorCode;
import com.ruoyi.interview.application.shared.ApplicationException;
import com.ruoyi.interview.domain.interview.InterviewMode;
import com.ruoyi.interview.domain.interview.PlannedQuestion;
import com.ruoyi.interview.domain.platform.ImmutableVersionRef;
import com.ruoyi.interview.domain.platform.ResourceId;
import com.ruoyi.interview.domain.platform.TenantId;
import com.ruoyi.interview.domain.platform.TimeBudget;
import com.ruoyi.interview.domain.platform.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewPlanPrerequisitesTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final InterviewUseCaseConfiguration configuration = new InterviewUseCaseConfiguration();
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("interview.catalog.public-tenant-id", "platform-public");

    @Test
    void missingProfileRejectsBeforeCreatingTrialEntitlement() {
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<ImmutableVersionRef>>any()))
                .thenReturn(List.of());
        var request = new InterviewPlanPolicyPort.Request(TenantId.of("ruoyi-user-1"), UserId.of("1"),
                InterviewTargetRole.JAVA_BACKEND, InterviewTargetLevel.MID, Set.of("Spring Boot"),
                30, InterviewMode.TEXT, Instant.parse("2026-10-01T10:00:00Z"));
        var failure = assertThrows(ApplicationException.class,
                () -> configuration.interviewPlanPolicy(jdbc).resolve(request));
        assertEquals(ApplicationErrorCode.PLAN_NOT_READY, failure.code());
        assertEquals("PROFILE_MISSING", failure.details().get("reason"));
        assertFalse(failure.retryable());
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void noPublishedQuestionsReturnsNonRetryableBusinessFailure() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<PlannedQuestion>>any()))
                .thenReturn(List.of());
        var failure = assertThrows(ApplicationException.class,
                () -> configuration.planQuestionSelection(jdbc, environment).select(selectionRequest()));
        assertEquals(ApplicationErrorCode.PLAN_NOT_READY, failure.code());
        assertEquals(Map.of("reason", "PUBLISHED_QUESTIONS_MISSING"), failure.details());
        assertFalse(failure.retryable());
    }

    @Test
    void availablePublishedQuestionStillProducesPlanSelection() {
        var question = new PlannedQuestion(1,
                new ImmutableVersionRef(ResourceId.of("question-version-test"), 1, "question-hash"),
                new ImmutableVersionRef(ResourceId.of("rubric-version-test"), 1, "rubric-hash"),
                "Spring Boot", new TimeBudget(Duration.ofMinutes(5)), 1);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<PlannedQuestion>>any()))
                .thenReturn(List.of(question));
        assertEquals(List.of(question), configuration.planQuestionSelection(jdbc, environment).select(selectionRequest()));
    }

    private PlanQuestionSelectionPort.SelectionRequest selectionRequest() {
        return new PlanQuestionSelectionPort.SelectionRequest(TenantId.of("ruoyi-user-1"),
                Set.of("Spring Boot"), Set.of("JAVA_BACKEND"), Set.of("MID"),
                new TimeBudget(Duration.ofMinutes(30)), 6, 2);
    }
}
