package com.ruoyi.aden.configuration;

import tools.jackson.databind.ObjectMapper;
import com.ruoyi.aden.api.common.AdenAccessDeniedHandler;
import com.ruoyi.aden.api.common.AdenApiErrorWriter;
import com.ruoyi.aden.api.common.AdenAuthenticationEntryPoint;
import com.ruoyi.aden.api.common.AdenCorrelationIdFilter;
import com.ruoyi.aden.api.common.AdenOriginGuardFilter;
import com.ruoyi.aden.application.security.AdenOperatorPrincipalProvider;
import com.ruoyi.aden.application.idempotency.AdenRequestFingerprint;
import com.ruoyi.aden.application.event.AdenOutboxRecoveryPort;
import com.ruoyi.aden.application.event.AdenOutboxRecoveryService;
import com.ruoyi.aden.application.task.AdenTaskCasRepository;
import com.ruoyi.aden.application.task.AdenTaskLedgerRepository;
import com.ruoyi.aden.application.task.AdenTaskTransactionService;
import com.ruoyi.aden.application.task.AdenTaskCommandService;
import com.ruoyi.aden.application.reliability.AdenIdempotentTransactionRunner;
import com.ruoyi.aden.application.projection.AdenOpaqueCursorCodec;
import com.ruoyi.aden.application.projection.AdenOperatorProjectionRepository;
import com.ruoyi.aden.application.projection.AdenOperatorQueryService;
import com.ruoyi.aden.application.runner.AdenRunnerAuthenticationPort;
import com.ruoyi.aden.application.runner.AdenRunnerPepperProvider;
import com.ruoyi.aden.application.runner.AdenRunnerLedgerRepository;
import com.ruoyi.aden.application.runner.AdenRunnerAdministrationService;
import com.ruoyi.aden.application.runner.AdenRunnerSessionService;
import com.ruoyi.aden.application.runner.AdenRunnerClaimService;
import com.ruoyi.aden.application.runner.AdenRunnerDeliveryRepository;
import com.ruoyi.aden.application.runner.AdenRunnerHeartbeatService;
import com.ruoyi.aden.application.runner.AdenRunnerReceiptService;
import com.ruoyi.aden.application.runner.AdenRunnerLeaseRecoveryService;
import com.ruoyi.aden.application.task.AdenSyntheticTaskValidator;
import com.ruoyi.aden.application.workspace.AdenWorkspaceService;
import com.ruoyi.aden.application.workspace.AdenWorkspaceAccessGuard;
import com.ruoyi.aden.application.workspace.AdenWorkspaceSecurityAuditPort;
import com.ruoyi.aden.application.workspace.AdenWorkspaceSecurityAuditService;
import com.ruoyi.aden.domain.shared.AdenIdGenerator;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenWorkspaceRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenOutboxRecoveryRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenTaskCasRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenTaskLedgerRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenRunnerLedgerRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenRunnerAuthenticationRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenRunnerDeliveryRepository;
import com.ruoyi.aden.infrastructure.persistence.MyBatisAdenOperatorProjectionRepository;
import com.ruoyi.aden.infrastructure.persistence.mapper.AdenTaskMapper;
import com.ruoyi.aden.infrastructure.persistence.mapper.AdenOutboxMapper;
import com.ruoyi.aden.infrastructure.persistence.mapper.AdenWorkspaceMapper;
import com.ruoyi.aden.infrastructure.persistence.mapper.AdenRunnerMapper;
import com.ruoyi.aden.infrastructure.persistence.mapper.AdenRunnerDeliveryMapper;
import com.ruoyi.aden.infrastructure.persistence.mapper.AdenProjectionMapper;
import com.ruoyi.aden.infrastructure.security.RuoYiAdenOperatorPrincipalProvider;
import com.ruoyi.aden.infrastructure.security.AdenRunnerAuthenticationEntryPoint;
import com.ruoyi.aden.infrastructure.security.AdenRunnerAuthenticationFilter;
import com.ruoyi.aden.infrastructure.security.AdenRunnerCredentialCodec;
import com.ruoyi.aden.infrastructure.security.AdenRunnerSessionCodec;
import com.ruoyi.aden.infrastructure.security.ManagedAdenRunnerPepperProvider;
import com.ruoyi.aden.infrastructure.transaction.SpringAdenIdempotentTransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Clock;
import java.time.Duration;
import java.security.SecureRandom;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aden", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdenRuntimeConfiguration {
    @Bean
    public AdenRunnerPepperProvider adenRunnerPepperProvider(
            com.ruoyi.system.secret.ManagedSecretService secrets,
            ObjectMapper json) {
        return new ManagedAdenRunnerPepperProvider(secrets, json);
    }

    @Bean
    public AdenRunnerCredentialCodec adenRunnerCredentialCodec() {
        return new AdenRunnerCredentialCodec(new SecureRandom());
    }

    @Bean
    public AdenRunnerSessionCodec adenRunnerSessionCodec() {
        return new AdenRunnerSessionCodec(new SecureRandom());
    }

    @Bean
    public AdenRunnerLedgerRepository adenRunnerLedgerRepository(
            AdenRunnerMapper mapper, ObjectMapper objectMapper) {
        return new MyBatisAdenRunnerLedgerRepository(mapper, objectMapper);
    }

    @Bean
    public AdenRunnerAuthenticationPort adenRunnerAuthenticationPort(
            AdenRunnerMapper mapper, AdenRunnerCredentialCodec credentialCodec,
            AdenRunnerSessionCodec sessionCodec, AdenRunnerPepperProvider peppers) {
        return new MyBatisAdenRunnerAuthenticationRepository(mapper, credentialCodec, sessionCodec, peppers);
    }

    @Bean
    public AdenRunnerAdministrationService adenRunnerAdministrationService(
            AdenRunnerLedgerRepository repository, AdenWorkspaceAccessGuard accessGuard,
            AdenRunnerCredentialCodec codec, AdenRunnerPepperProvider peppers,
            AdenRequestFingerprint json, AdenIdGenerator ids,
            @Qualifier("adenClock") Clock clock) {
        return new AdenRunnerAdministrationService(repository, accessGuard, codec, peppers,
                json, ids, clock, Duration.ofDays(30));
    }

    @Bean
    public AdenRunnerSessionService adenRunnerSessionService(
            AdenRunnerLedgerRepository repository, AdenRunnerSessionCodec codec,
            AdenRunnerPepperProvider peppers, AdenRequestFingerprint json,
            AdenIdGenerator ids, @Qualifier("adenClock") Clock clock,
            AdenProperties properties) {
        return new AdenRunnerSessionService(repository, codec, peppers, json, ids, clock,
                Duration.ofSeconds(properties.getRunner().getSession().getTtlSeconds()));
    }

    @Bean
    public AdenRunnerDeliveryRepository adenRunnerDeliveryRepository(AdenRunnerDeliveryMapper mapper) {
        return new MyBatisAdenRunnerDeliveryRepository(mapper);
    }

    @Bean
    public AdenRunnerClaimService adenRunnerClaimService(
            AdenRunnerDeliveryRepository repository,
            AdenRequestFingerprint fingerprint,
            ObjectMapper objectMapper,
            AdenIdGenerator ids,
            @Qualifier("adenClock") Clock clock,
            AdenProperties properties) {
        return new AdenRunnerClaimService(repository, fingerprint, objectMapper, ids, clock,
                properties.getRunner().getDelivery().getLeaseTtlSeconds(),
                properties.getRunner().getClaim().getMaxBatchSize(),
                properties.getIdempotency().getInProgressTtlSeconds(),
                properties.getIdempotency().getRetentionSeconds());
    }

    @Bean
    public AdenRunnerHeartbeatService adenRunnerHeartbeatService(
            AdenRunnerDeliveryRepository repository, AdenProperties properties) {
        return new AdenRunnerHeartbeatService(repository,
                properties.getRunner().getSession().getTtlSeconds(),
                properties.getRunner().getDelivery().getLeaseTtlSeconds());
    }

    @Bean
    public AdenRunnerReceiptService adenRunnerReceiptService(
            AdenRunnerDeliveryRepository deliveries,
            AdenTaskLedgerRepository tasks,
            AdenTaskCasRepository taskCas,
            AdenRequestFingerprint fingerprint,
            ObjectMapper objectMapper,
            AdenIdGenerator ids,
            @Qualifier("adenClock") Clock clock,
            AdenProperties properties) {
        return new AdenRunnerReceiptService(deliveries, tasks, taskCas, fingerprint,
                objectMapper, ids, clock,
                properties.getIdempotency().getInProgressTtlSeconds(),
                properties.getIdempotency().getRetentionSeconds());
    }

    @Bean
    public AdenRunnerLeaseRecoveryService adenRunnerLeaseRecoveryService(
            AdenRunnerDeliveryRepository deliveries,
            AdenTaskLedgerRepository tasks,
            AdenTaskCasRepository taskCas,
            AdenRequestFingerprint fingerprint,
            ObjectMapper objectMapper,
            AdenIdGenerator ids,
            @Qualifier("adenClock") Clock clock) {
        return new AdenRunnerLeaseRecoveryService(deliveries, tasks, taskCas, fingerprint,
                objectMapper, ids, clock);
    }

    @Bean
    public AdenOperatorProjectionRepository adenOperatorProjectionRepository(AdenProjectionMapper mapper) {
        return new MyBatisAdenOperatorProjectionRepository(mapper);
    }

    @Bean
    public AdenOpaqueCursorCodec adenOpaqueCursorCodec(AdenRunnerPepperProvider peppers) {
        return new AdenOpaqueCursorCodec(peppers.pepper(peppers.currentKeyId()));
    }

    @Bean
    public AdenOperatorQueryService adenOperatorQueryService(
            AdenOperatorProjectionRepository repository,
            AdenWorkspaceAccessGuard accessGuard,
            AdenOpaqueCursorCodec cursors,
            AdenRequestFingerprint fingerprint,
            ObjectMapper objectMapper,
            @Qualifier("adenClock") Clock clock,
            AdenProperties properties) {
        return new AdenOperatorQueryService(repository, accessGuard, cursors, fingerprint,
                objectMapper, clock, Duration.ofSeconds(properties.getEvent().getRetentionSeconds()));
    }
    @Bean
    public AdenWorkspaceRepository adenWorkspaceRepository(AdenWorkspaceMapper mapper) {
        return new MyBatisAdenWorkspaceRepository(mapper);
    }

    @Bean
    public AdenTaskCasRepository adenTaskCasRepository(AdenTaskMapper mapper) {
        return new MyBatisAdenTaskCasRepository(mapper);
    }

    @Bean
    public AdenTaskLedgerRepository adenTaskLedgerRepository(AdenTaskMapper mapper) {
        return new MyBatisAdenTaskLedgerRepository(mapper);
    }

    @Bean
    public AdenOutboxRecoveryPort adenOutboxRecoveryPort(AdenOutboxMapper mapper) {
        return new MyBatisAdenOutboxRecoveryRepository(mapper);
    }

    @Bean
    public AdenOutboxRecoveryService adenOutboxRecoveryService(
            AdenOutboxRecoveryPort port,
            AdenIdGenerator idGenerator,
            @Qualifier("adenClock") Clock clock,
            AdenProperties properties) {
        return new AdenOutboxRecoveryService(
                port, idGenerator, clock,
                properties.getOutbox().getClaimTtlSeconds(),
                properties.getOutbox().getRetry().getInitialDelayMilliseconds(),
                properties.getOutbox().getRetry().getMaxDelayMilliseconds(),
                properties.getOutbox().getRetry().getMaxAttempts());
    }

    @Bean
    public AdenRequestFingerprint adenRequestFingerprint(ObjectMapper objectMapper) {
        return new AdenRequestFingerprint(objectMapper);
    }

    @Bean
    public AdenSyntheticTaskValidator adenSyntheticTaskValidator() {
        return new AdenSyntheticTaskValidator();
    }

    @Bean
    public AdenTaskTransactionService adenTaskTransactionService(
            AdenTaskLedgerRepository ledger,
            AdenTaskCasRepository casRepository,
            AdenWorkspaceAccessGuard accessGuard,
            AdenRequestFingerprint fingerprint,
            AdenSyntheticTaskValidator validator,
            AdenIdGenerator idGenerator,
            ObjectMapper objectMapper,
            @Qualifier("adenClock") Clock clock,
            AdenProperties properties) {
        return new AdenTaskTransactionService(
                ledger, casRepository, accessGuard, fingerprint, validator, idGenerator,
                objectMapper, clock,
                properties.getIdempotency().getInProgressTtlSeconds(),
                properties.getIdempotency().getRetentionSeconds());
    }

    @Bean
    public AdenIdempotentTransactionRunner adenIdempotentTransactionRunner(
            @Qualifier("adenTransactionManager") PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new SpringAdenIdempotentTransactionRunner(
                template, 3, Duration.ofMillis(10), Duration.ofMillis(100),
                SpringAdenIdempotentTransactionRunner.threadSleepWaiter());
    }

    @Bean
    public AdenTaskCommandService adenTaskCommandService(
            AdenTaskTransactionService transactions,
            AdenIdempotentTransactionRunner transactionRunner) {
        return new AdenTaskCommandService(transactions, transactionRunner);
    }

    @Bean
    public AdenOperatorPrincipalProvider adenOperatorPrincipalProvider() {
        return new RuoYiAdenOperatorPrincipalProvider();
    }

    @Bean
    public AdenWorkspaceService adenWorkspaceService(AdenWorkspaceRepository repository,
                                                      AdenIdGenerator idGenerator,
                                                      @Qualifier("adenClock") Clock clock,
                                                      AdenProperties properties) {
        return new AdenWorkspaceService(repository, idGenerator, clock,
                properties.getWorkspace().getDefaultWorkspaceId());
    }

    @Bean
    public AdenWorkspaceSecurityAuditPort adenWorkspaceSecurityAudit(
            AdenWorkspaceRepository repository,
            AdenIdGenerator idGenerator,
            @Qualifier("adenClock") Clock clock) {
        return new AdenWorkspaceSecurityAuditService(repository, idGenerator, clock);
    }

    @Bean
    public AdenWorkspaceAccessGuard adenWorkspaceAccessGuard(
            AdenWorkspaceRepository repository,
            AdenWorkspaceSecurityAuditPort securityAudit) {
        return new AdenWorkspaceAccessGuard(repository, securityAudit);
    }

    @Bean
    public AdenApiErrorWriter adenApiErrorWriter(ObjectMapper objectMapper) {
        return new AdenApiErrorWriter(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<AdenCorrelationIdFilter> adenCorrelationIdFilter() {
        FilterRegistrationBean<AdenCorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new AdenCorrelationIdFilter());
        registration.setName("adenCorrelationIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/v1/aden/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AdenOriginGuardFilter> adenOriginGuardFilter(AdenApiErrorWriter writer) {
        FilterRegistrationBean<AdenOriginGuardFilter> registration =
                new FilterRegistrationBean<>(new AdenOriginGuardFilter(writer));
        registration.setName("adenOriginGuardFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        registration.addUrlPatterns("/api/v1/aden/*");
        return registration;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adenRunnerSecurityFilterChain(
            HttpSecurity http,
            AdenRunnerAuthenticationPort authentication,
            AdenApiErrorWriter writer,
            @Qualifier("adenClock") Clock clock) throws Exception {
        AdenRunnerAuthenticationFilter runnerFilter =
                new AdenRunnerAuthenticationFilter(authentication, writer, clock);
        return http
                .securityMatcher("/api/v1/aden/runner/**")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new AdenRunnerAuthenticationEntryPoint(writer))
                        .accessDeniedHandler(new AdenAccessDeniedHandler(writer)))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .addFilterBefore(runnerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adenOperatorSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("jwtAuthenticationTokenFilter") OncePerRequestFilter jwtAuthenticationTokenFilter,
            AdenApiErrorWriter writer) throws Exception {
        return http
                .securityMatcher("/api/v1/aden/**")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new AdenAuthenticationEntryPoint(writer))
                        .accessDeniedHandler(new AdenAccessDeniedHandler(writer)))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
