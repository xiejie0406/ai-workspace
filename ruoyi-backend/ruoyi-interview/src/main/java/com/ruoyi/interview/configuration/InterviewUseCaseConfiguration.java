package com.ruoyi.interview.configuration;

import com.ruoyi.interview.infrastructure.persistence.shared.JdbcPersistenceSupport;
import com.ruoyi.interview.application.billing.CheckEntitlement;
import com.ruoyi.interview.application.billing.ReleaseUsage;
import com.ruoyi.interview.application.billing.ReserveUsage;
import com.ruoyi.interview.application.billing.internal.DefaultCheckEntitlement;
import com.ruoyi.interview.application.billing.internal.DefaultEntitlementPort;
import com.ruoyi.interview.application.billing.internal.DefaultReleaseUsage;
import com.ruoyi.interview.application.billing.internal.DefaultReserveUsage;
import com.ruoyi.interview.application.billing.port.BillingRepository;
import com.ruoyi.interview.application.billing.port.EntitlementPort;
import com.ruoyi.interview.application.security.ActivePrincipalGuard;
import com.ruoyi.interview.application.interview.ConfirmInterviewPlan;
import com.ruoyi.interview.application.interview.CancelInterviewPlan;
import com.ruoyi.interview.application.interview.CreateInterviewPlan;
import com.ruoyi.interview.application.interview.CreateInterviewSession;
import com.ruoyi.interview.application.interview.RecoverInterview;
import com.ruoyi.interview.application.interview.GetInterviewPlan;
import com.ruoyi.interview.application.interview.ProgressInterview;
import com.ruoyi.interview.application.interview.StartInterview;
import com.ruoyi.interview.application.interview.SubmitInterviewAnswer;
import com.ruoyi.interview.application.interview.ApplySessionCommand;
import com.ruoyi.interview.application.interview.internal.DefaultConfirmInterviewPlan;
import com.ruoyi.interview.application.interview.internal.DefaultCancelInterviewPlan;
import com.ruoyi.interview.application.interview.internal.DefaultCreateInterviewPlan;
import com.ruoyi.interview.application.interview.internal.DefaultCreateInterviewSession;
import com.ruoyi.interview.application.interview.internal.DefaultRecoverInterview;
import com.ruoyi.interview.application.interview.internal.DefaultGetInterviewPlan;
import com.ruoyi.interview.application.interview.internal.DefaultProgressInterview;
import com.ruoyi.interview.application.interview.internal.DefaultStartInterview;
import com.ruoyi.interview.application.interview.internal.DefaultSubmitInterviewAnswer;
import com.ruoyi.interview.application.interview.internal.DefaultApplySessionCommand;
import com.ruoyi.interview.application.interview.internal.PlanUsageEstimator;
import com.ruoyi.interview.application.interview.internal.ProfileAccessPort;
import com.ruoyi.interview.application.interview.port.InterviewPlanPolicyPort;
import com.ruoyi.interview.application.interview.port.InterviewRecoveryProjectionPort;
import com.ruoyi.interview.application.interview.port.InterviewRepository;
import com.ruoyi.interview.application.interview.port.PlanQuestionSelectionPort;
import com.ruoyi.interview.application.platform.IdempotencyGuard;
import com.ruoyi.interview.application.shared.ApplicationErrorCode;
import com.ruoyi.interview.application.shared.ApplicationException;
import com.ruoyi.interview.application.platform.port.ClockPort;
import com.ruoyi.interview.application.platform.port.DomainEventPort;
import com.ruoyi.interview.application.platform.port.IdGeneratorPort;
import com.ruoyi.interview.application.platform.port.JobPort;
import com.ruoyi.interview.application.platform.port.TransactionPort;
import com.ruoyi.interview.domain.interview.PlannedQuestion;
import com.ruoyi.interview.domain.interview.UsageEstimate;
import com.ruoyi.interview.domain.platform.ImmutableVersionRef;
import com.ruoyi.interview.domain.platform.ResourceId;
import com.ruoyi.interview.domain.platform.TenantId;
import com.ruoyi.interview.application.catalog.port.PublishedQuestionPort;
import com.ruoyi.interview.application.interview.InterviewAgentCandidate;
import com.ruoyi.interview.application.interview.internal.DefaultInterviewAgentCandidate;
import com.ruoyi.interview.application.agent.port.ChatModelPort;
import com.ruoyi.interview.application.voice.port.ContentDigestPort;
import com.ruoyi.interview.configuration.properties.ProviderProperties;
import com.ruoyi.interview.domain.platform.TimeBudget;
import com.ruoyi.interview.domain.platform.UsageQuantity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 迁移阶段的 Interview/Billing 组合根；所有写入仍通过既有 application use case。 */
@InterviewEnabled
@Configuration
public class InterviewUseCaseConfiguration {

    @Bean
    CheckEntitlement checkEntitlement(BillingRepository repository, ActivePrincipalGuard principal) {
        return new DefaultCheckEntitlement(repository, principal);
    }

    @Bean
    ReserveUsage reserveUsage(BillingRepository repository, ActivePrincipalGuard principal,
                              IdGeneratorPort ids, IdempotencyGuard idempotency,
                              DomainEventPort events, TransactionPort transaction) {
        return new DefaultReserveUsage(repository, principal, ids, idempotency, events, transaction);
    }

    @Bean
    ReleaseUsage releaseUsage(BillingRepository repository, ActivePrincipalGuard principal,
                              IdempotencyGuard idempotency, DomainEventPort events,
                              TransactionPort transaction) {
        return new DefaultReleaseUsage(repository, principal, idempotency, events, transaction);
    }

    @Bean
    EntitlementPort entitlementPort(CheckEntitlement check, ReserveUsage reserve, ReleaseUsage release,
                                    BillingRepository repository, ActivePrincipalGuard principal, ClockPort clock) {
        return new DefaultEntitlementPort(check, reserve, release, repository, principal, clock);
    }

    @Bean
    InterviewPlanPolicyPort interviewPlanPolicy(NamedParameterJdbcTemplate jdbc) {
        return request -> {
            ImmutableVersionRef profile = jdbc.query("""
                    select profile_version_id, version_no, content_hash
                      from platform.profile_version
                     where business_tenant_id = :tenantId
                       and ruoyi_user_id = cast(:userId as bigint)
                     order by version_no desc limit 1
                    """, Map.of("tenantId", request.tenantId().value(), "userId", request.userId().value()),
                    (row, index) -> new ImmutableVersionRef(ResourceId.of(row.getString("profile_version_id")),
                            row.getInt("version_no"), row.getString("content_hash")))
                    .stream().findFirst().orElseThrow(() -> new ApplicationException(
                            ApplicationErrorCode.PLAN_NOT_READY,
                            "当前账号缺少有效面试档案", false, Map.of("reason", "PROFILE_MISSING")));

            // 本地迁移验收的试用权益。生产套餐接入后由 Billing onboarding 取代此幂等初始化。
            jdbc.update("""
                    insert into billing.entitlement (
                        tenant_id, entitlement_id, user_id, product_plan_id, source,
                        valid_from, valid_to, usage_unit, limit_value, consumed_value,
                        reserved_value, state, aggregate_version)
                    select :tenantId, :entitlementId, :userId, :productPlanId, 'TRIAL',
                           :validFrom, :validTo, 'INTERVIEW_CREDIT', 10000, 0, 0, 'ACTIVE', 0
                     where not exists (
                        select 1 from billing.entitlement
                         where tenant_id = :tenantId and user_id = :userId
                           and usage_unit = 'INTERVIEW_CREDIT' and state = 'ACTIVE')
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", request.tenantId().value())
                    .addValue("userId", request.userId().value())
                    .addValue("entitlementId", UUID.randomUUID().toString())
                    .addValue("productPlanId", "local-migration-trial")
                    .addValue("validFrom", JdbcPersistenceSupport.writeInstant(
                            request.requestedAt().minus(Duration.ofDays(1))))
                    .addValue("validTo", JdbcPersistenceSupport.writeInstant(
                            request.requestedAt().plus(Duration.ofDays(365)))));

            int questionCount = Math.max(1, Math.min(6, request.durationMinutes() / 5));
            return new InterviewPlanPolicyPort.Resolved(
                    "migration-policy-v1", profile, request.topics(),
                    java.util.Set.of(request.targetRole().name()),
                    java.util.Set.of(request.targetLevel().name()),
                    new TimeBudget(Duration.ofMinutes(request.durationMinutes())),
                    questionCount, Math.min(2, questionCount), request.requestedAt().plus(Duration.ofHours(2)));
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "interview.foundation-safety", name = "business-rest-endpoints-enabled",
            havingValue = "true")
    PlanQuestionSelectionPort planQuestionSelection(NamedParameterJdbcTemplate jdbc, Environment environment) {
        String publicTenantId = environment.getRequiredProperty("interview.catalog.public-tenant-id");
        return request -> {
            long millis = Math.max(60_000L,
                    request.totalTimeBudget().duration().toMillis() / request.desiredQuestionCount());
            String topic = request.topicCodes().stream().sorted().findFirst().orElse("GENERAL");
            List<PlannedQuestion> selected = jdbc.query("""
                    select p.question_version_id, p.question_version_no, p.question_content_hash,
                           p.rubric_version_id, p.rubric_version_no, p.rubric_content_hash
                      from catalog.question_publication p
                      join catalog.question_version qv
                        on qv.tenant_id = p.tenant_id and qv.question_version_id = p.question_version_id
                     where p.tenant_id = :catalogTenantId
                       and exists (select 1 from jsonb_array_elements_text(qv.target_roles) r(value)
                                    where r.value in (:roles))
                     order by p.question_id
                     limit :limit
                    """, new MapSqlParameterSource()
                    .addValue("catalogTenantId", publicTenantId)
                    .addValue("roles", request.targetRoles())
                    .addValue("limit", request.desiredQuestionCount()),
                    (row, index) -> new PlannedQuestion(index + 1,
                            new ImmutableVersionRef(ResourceId.of(row.getString("question_version_id")),
                                    row.getInt("question_version_no"), row.getString("question_content_hash")),
                            new ImmutableVersionRef(ResourceId.of(row.getString("rubric_version_id")),
                                    row.getInt("rubric_version_no"), row.getString("rubric_content_hash")),
                            topic, new TimeBudget(Duration.ofMillis(millis)),
                            index < request.totalFollowUpBudget() ? 1 : 0));
            if (selected.isEmpty()) {
                throw new ApplicationException(
                        ApplicationErrorCode.PLAN_NOT_READY,
                        "公开题库中没有匹配当前岗位的已发布题目", false,
                        Map.of("reason", "PUBLISHED_QUESTIONS_MISSING"));
            }
            return selected;
        };
    }

    @Bean
    PlanUsageEstimator planUsageEstimator() {
        return (questions, followUps) -> new UsageEstimate(
                new UsageQuantity("INTERVIEW_CREDIT", BigDecimal.valueOf(questions.size())),
                "migration-credit-v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "interview.foundation-safety", name = "business-rest-endpoints-enabled",
            havingValue = "true")
    CreateInterviewPlan createInterviewPlan(InterviewRepository repository, PlanQuestionSelectionPort selection,
                                            InterviewPlanPolicyPort policy, ProfileAccessPort profiles,
                                            PlanUsageEstimator usage, ActivePrincipalGuard principal,
                                            IdGeneratorPort ids, IdempotencyGuard idempotency,
                                            DomainEventPort events, TransactionPort transaction) {
        return new DefaultCreateInterviewPlan(repository, selection, policy, profiles, usage,
                principal, ids, idempotency, events, transaction);
    }

    @Bean
    ConfirmInterviewPlan confirmInterviewPlan(InterviewRepository repository, EntitlementPort billing,
                                              ActivePrincipalGuard principal, IdempotencyGuard idempotency,
                                              DomainEventPort events, TransactionPort transaction) {
        return new DefaultConfirmInterviewPlan(repository, billing, principal, idempotency, events, transaction);
    }

    @Bean
    CancelInterviewPlan cancelInterviewPlan(InterviewRepository repository, EntitlementPort billing,
                                            ActivePrincipalGuard principal, IdempotencyGuard idempotency,
                                            DomainEventPort events, TransactionPort transaction) {
        return new DefaultCancelInterviewPlan(repository, billing, principal, idempotency, events, transaction);
    }

    @Bean
    CreateInterviewSession createInterviewSession(InterviewRepository repository, EntitlementPort billing,
                                                  ActivePrincipalGuard principal, IdGeneratorPort ids,
                                                  IdempotencyGuard idempotency, DomainEventPort events,
                                                  TransactionPort transaction,
                                                  InterviewRecoveryProjectionPort projections) {
        return new DefaultCreateInterviewSession(repository, billing, principal, ids, idempotency,
                events, transaction, projections);
    }

    @Bean
    RecoverInterview recoverInterview(InterviewRepository repository, ActivePrincipalGuard principal,
                                      InterviewRecoveryProjectionPort projections, TransactionPort transaction) {
        return new DefaultRecoverInterview(repository, principal, projections, transaction);
    }

    @Bean
    GetInterviewPlan getInterviewPlan(InterviewRepository repository, ActivePrincipalGuard principal) {
        return new DefaultGetInterviewPlan(repository, principal);
    }

    @Bean
    StartInterview startInterview(InterviewRepository repository, EntitlementPort billing, JobPort jobs,
                                  ActivePrincipalGuard principal, IdGeneratorPort ids,
                                  IdempotencyGuard idempotency, DomainEventPort events,
                                  TransactionPort transaction, InterviewRecoveryProjectionPort projections) {
        return new DefaultStartInterview(repository, billing, jobs, principal, ids, idempotency,
                events, transaction, projections);
    }

    @Bean
    SubmitInterviewAnswer submitInterviewAnswer(InterviewRepository repository, JobPort jobs,
                                                ActivePrincipalGuard principal, IdGeneratorPort ids,
                                                IdempotencyGuard idempotency, DomainEventPort events,
                                                TransactionPort transaction,
                                                InterviewRecoveryProjectionPort projections) {
        return new DefaultSubmitInterviewAnswer(repository, jobs, principal, ids, idempotency,
                events, transaction, projections);
    }

    @Bean
    ApplySessionCommand applySessionCommand(InterviewRepository repository, ActivePrincipalGuard principal,
                                            IdempotencyGuard idempotency, DomainEventPort events,
                                            TransactionPort transaction,
                                            InterviewRecoveryProjectionPort projections) {
        return new DefaultApplySessionCommand(repository, principal, idempotency, events,
                transaction, projections);
    }

    @Bean
    InterviewAgentCandidate interviewAgentCandidate(ChatModelPort model, IdGeneratorPort ids,
                                                     ProviderProperties providers) {
        return new DefaultInterviewAgentCandidate(model, ids,
                providers.getDeepseek().getInterviewModel());
    }

    @Bean
    @ConditionalOnProperty(prefix = "interview.foundation-safety", name = "business-rest-endpoints-enabled",
            havingValue = "true")
    ProgressInterview progressInterview(InterviewRepository repository, PublishedQuestionPort questions,
                                        ActivePrincipalGuard principal, IdGeneratorPort ids,
                                        DomainEventPort events, TransactionPort transaction,
                                        InterviewRecoveryProjectionPort projections, Environment environment,
                                        InterviewAgentCandidate agent, ContentDigestPort digest) {
        return new DefaultProgressInterview(repository, questions,
                TenantId.of(environment.getRequiredProperty("interview.catalog.public-tenant-id")),
                principal, ids, events, transaction, projections, agent, digest);
    }
}

