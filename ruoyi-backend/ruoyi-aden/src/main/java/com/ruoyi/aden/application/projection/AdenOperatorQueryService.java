package com.ruoyi.aden.application.projection;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.aden.application.error.AdenApplicationException;
import com.ruoyi.aden.application.error.AdenNotFoundException;
import com.ruoyi.aden.application.idempotency.AdenRequestFingerprint;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.application.workspace.AdenWorkspaceAccessGuard;
import com.ruoyi.aden.contract.OperatorTaskCommand;
import com.ruoyi.aden.domain.task.AdenTaskActor;
import com.ruoyi.aden.domain.task.AdenTaskCommand;
import com.ruoyi.aden.domain.task.AdenTaskState;
import com.ruoyi.aden.domain.task.AdenTaskTransitions;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceId;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceMembership;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceRole;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 MySQL 权威账本投影为批准的 Operator v1 只读 DTO。 */
public class AdenOperatorQueryService {
    public static final String TASK_LIST = "aden:task:list";
    public static final String TASK_QUERY = "aden:task:query";
    public static final String RUNNER_LIST = "aden:runner:list";
    public static final String CAPABILITY_LIST = "aden:capability:list";
    public static final String AUDIT_LIST = "aden:audit:list";
    public static final String EVENT_SUBSCRIBE = "aden:event:subscribe";
    public static final String STREAM_FILTER = "workspace-all-v1";

    private static final Set<String> PUBLIC_EVENT_KEYS = Set.of(
            "from", "to", "reasonCode", "progressPercent", "runnerId",
            "capabilityCode", "externalActionsEnabled");

    private final AdenOperatorProjectionRepository repository;
    private final AdenWorkspaceAccessGuard accessGuard;
    private final AdenOpaqueCursorCodec cursors;
    private final AdenRequestFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration eventRetention;

    public AdenOperatorQueryService(AdenOperatorProjectionRepository repository,
                                    AdenWorkspaceAccessGuard accessGuard,
                                    AdenOpaqueCursorCodec cursors,
                                    AdenRequestFingerprint fingerprint,
                                    ObjectMapper objectMapper,
                                    Clock clock,
                                    Duration eventRetention) {
        this.repository = repository;
        this.accessGuard = accessGuard;
        this.cursors = cursors;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.eventRetention = eventRetention;
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public TaskPage listTasks(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                              String capability, String cursor, int limit, String correlationId) {
        AdenWorkspaceMembership membership = accessGuard.requireWorkspace(principal, TASK_LIST, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        return taskPage(principal, membership, capability, cursor, limit);
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public TaskSnapshot getTask(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                                String taskId, String correlationId) {
        AdenWorkspaceMembership membership = accessGuard.requireWorkspace(principal, TASK_QUERY, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        return task(principal, membership, repository.findTask(workspaceId, taskId)
                .orElseThrow(AdenNotFoundException::new));
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public TaskSnapshot getTaskAfterWrite(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                                          String taskId, String permission, String correlationId) {
        AdenWorkspaceMembership membership = accessGuard.requireWorkspace(principal, permission, workspaceId,
                AdenWorkspaceAccessGuard.WRITE_ROLES, correlationId);
        return task(principal, membership, repository.findTask(workspaceId, taskId)
                .orElseThrow(AdenNotFoundException::new));
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public RunnerList listRunners(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                                  String correlationId) {
        accessGuard.requireWorkspace(principal, RUNNER_LIST, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        return new RunnerList(workspaceId.value(), runners(workspaceId));
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public CapabilityList listCapabilities(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                                           String correlationId) {
        accessGuard.requireWorkspace(principal, CAPABILITY_LIST, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        return new CapabilityList(workspaceId.value(), capabilities(repository.workspaceWatermark(workspaceId)));
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public AuditPage listAudit(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                               String cursor, int limit, String correlationId) {
        accessGuard.requireWorkspace(principal, AUDIT_LIST, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        String queryHash = fingerprint.hashValue(Map.of("projection", "audit-v1"));
        AdenOpaqueCursorCodec.PageCursor position = cursor == null ? null
                : cursors.decodePage(cursor, "AUDIT", workspaceId, queryHash);
        List<AdenOperatorProjectionRepository.AuditView> rows = repository.listAudit(workspaceId,
                position == null ? null : position.positionAt(),
                position == null ? null : position.positionId(), limit + 1);
        boolean more = rows.size() > limit;
        List<AdenOperatorProjectionRepository.AuditView> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? cursors.encodePage("AUDIT", workspaceId, queryHash,
                page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).auditEventId()) : null;
        return new AuditPage(page.stream().map(this::audit).toList(), next);
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Bootstrap bootstrap(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                               String capability, String taskCursor, int taskLimit,
                               String correlationId) {
        boolean fullProjection = principal.hasPermission(TASK_LIST) && principal.hasPermission(RUNNER_LIST)
                && principal.hasPermission(CAPABILITY_LIST) && principal.hasPermission(EVENT_SUBSCRIBE);
        if (!fullProjection && principal.hasPermission("aden:collection:read")) {
            AdenWorkspaceMembership member = accessGuard.requireWorkspace(principal, "aden:collection:read",
                    workspaceId, AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
            if (taskLimit < 1 || taskLimit > 100 || taskCursor != null
                    || capability != null && !Set.of("CORE", "WX", "PUR", "COL").contains(capability)) {
                throw new IllegalArgumentException("受限快照请求参数无效");
            }
            // 空投影不读取或暴露 CORE 水位、任务、Runner 与能力。事件端点仍有独立权限门禁。
            String queryHash = fingerprint.hashValue(Map.of("projection", "collection-only-v1"));
            Instant generatedAt = clock.instant();
            String snapshotHash = fingerprint.hashValue(Map.of("schemaVersion", 1, "taskQueryHash", queryHash,
                    "streamFilter", STREAM_FILTER, "projection", "collection-only-v1"));
            return new Bootstrap(1, workspace(member), new TaskPage(List.of(), null, queryHash),
                    List.of(), List.of(), snapshotHash, STREAM_FILTER, "0",
                    cursors.encodeStream(workspaceId, streamFilterHash(), 0, generatedAt), generatedAt);
        }
        AdenWorkspaceMembership membership = accessGuard.requireWorkspace(principal, TASK_LIST, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        accessGuard.requirePermission(principal, RUNNER_LIST);
        accessGuard.requirePermission(principal, CAPABILITY_LIST);
        accessGuard.requirePermission(principal, EVENT_SUBSCRIBE);
        long watermark = repository.workspaceWatermark(workspaceId);
        TaskPage tasks = taskPage(principal, membership, capability, taskCursor, taskLimit);
        List<RunnerSummary> runners = runners(workspaceId);
        List<CapabilityProjection> capabilities = capabilities(watermark);
        String snapshotHash = fingerprint.hashValue(Map.of(
                "schemaVersion", 1, "capability", capability == null ? "" : capability,
                "taskQueryHash", tasks.queryHash(), "streamFilter", STREAM_FILTER));
        String streamHash = streamFilterHash();
        Instant generatedAt = clock.instant();
        return new Bootstrap(1, workspace(membership), tasks, runners, capabilities,
                snapshotHash, STREAM_FILTER, Long.toString(watermark),
                cursors.encodeStream(workspaceId, streamHash, watermark, generatedAt), generatedAt);
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public EventBatch events(AdenOperatorPrincipal principal, AdenWorkspaceId workspaceId,
                             String cursor, int limit, String correlationId) {
        accessGuard.requireWorkspace(principal, EVENT_SUBSCRIBE, workspaceId,
                AdenWorkspaceAccessGuard.READ_ROLES, correlationId);
        long after = 0;
        AdenOperatorProjectionRepository.OptionalLongValue oldest = repository.oldestEventSequence(workspaceId);
        if (cursor != null) {
            AdenOpaqueCursorCodec.StreamCursor decoded = cursors.decodeStream(cursor, workspaceId, streamFilterHash());
            if (decoded.issuedAt().plus(eventRetention).isBefore(clock.instant())) throw expired(workspaceId);
            after = decoded.sequence();
            if (oldest.present() && after < oldest.value() - 1) throw expired(workspaceId);
        } else if (oldest.present() && oldest.value() > 1) {
            throw expired(workspaceId);
        }
        long watermark = repository.workspaceWatermark(workspaceId);
        if (after > watermark) {
            throw new AdenApplicationException("ADEN_STREAM_CURSOR_INVALID", "事件游标超过当前工作空间水位");
        }
        List<AdenOperatorProjectionRepository.EventView> rows = repository.listEvents(workspaceId, after, limit);
        long expected = after + 1;
        for (AdenOperatorProjectionRepository.EventView row : rows) {
            if (row.sequence() != expected || row.sequence() > watermark) throw streamGap();
            expected++;
        }
        long lastRow = rows.isEmpty() ? after : rows.get(rows.size() - 1).sequence();
        if (lastRow < watermark && rows.size() < limit) throw streamGap();
        List<EventEnvelope> events = rows.stream().map(this::event).toList();
        long last = events.isEmpty() ? after : Long.parseLong(events.get(events.size() - 1).sequence());
        return new EventBatch(events, cursors.encodeStream(
                workspaceId, streamFilterHash(), last, clock.instant()), last);
    }

    public String streamCursor(AdenWorkspaceId workspaceId, long sequence) {
        return cursors.encodeStream(workspaceId, streamFilterHash(), sequence, clock.instant());
    }

    private TaskPage taskPage(AdenOperatorPrincipal principal, AdenWorkspaceMembership membership,
                              String capability, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必须在 1..100");
        if (capability != null && !Set.of("CORE", "WX", "PUR", "COL").contains(capability)) {
            throw new IllegalArgumentException("capability 不合法");
        }
        String queryHash = fingerprint.hashValue(Map.of(
                "projection", "task-page-v1", "capability", capability == null ? "" : capability));
        AdenOpaqueCursorCodec.PageCursor position = cursor == null ? null
                : cursors.decodePage(cursor, "TASK", membership.workspace().id(), queryHash);
        List<AdenOperatorProjectionRepository.TaskView> rows = repository.listTasks(
                membership.workspace().id(), capability,
                position == null ? null : position.positionAt(),
                position == null ? null : position.positionId(), limit + 1);
        boolean more = rows.size() > limit;
        List<AdenOperatorProjectionRepository.TaskView> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? cursors.encodePage("TASK", membership.workspace().id(), queryHash,
                page.get(page.size() - 1).updatedAt(), page.get(page.size() - 1).taskId()) : null;
        return new TaskPage(page.stream().map(row -> task(principal, membership, row)).toList(), next, queryHash);
    }

    private TaskSnapshot task(AdenOperatorPrincipal principal, AdenWorkspaceMembership membership,
                              AdenOperatorProjectionRepository.TaskView row) {
        AdenTaskState state = AdenTaskState.valueOf(row.state());
        List<OperatorTaskCommand> allowed = new ArrayList<>();
        boolean canWrite = membership.role() != AdenWorkspaceRole.VIEWER && "SYNTHETIC_CORE".equals(row.taskType());
        if (canWrite && principal.hasPermission("aden:task:command")
                && AdenTaskTransitions.allows(state, AdenTaskActor.OPERATOR,
                AdenTaskCommand.SUBMIT_FOR_VALIDATION)) {
            allowed.add(OperatorTaskCommand.SUBMIT_FOR_VALIDATION);
        }
        if (canWrite && principal.hasPermission("aden:task:cancel")
                && AdenTaskTransitions.allows(state, AdenTaskActor.OPERATOR, AdenTaskCommand.REQUEST_CANCEL)) {
            allowed.add(OperatorTaskCommand.REQUEST_CANCEL);
        }
        List<TaskStepSnapshot> steps = repository.listSteps(membership.workspace().id(), row.taskId()).stream()
                .map(step -> new TaskStepSnapshot(step.stepId(), step.ordinal(), step.state(), step.attemptNo(),
                        Long.toString(step.version()), step.progressPercent())).toList();
        return new TaskSnapshot(row.taskId(), row.workspaceId(), row.taskType(), row.capabilityCode(),
                row.title(), row.state(), Long.toString(row.version()), List.copyOf(allowed), steps,
                row.reasonCode(), row.createdAt(), row.updatedAt(), row.correlationId());
    }

    private WorkspaceSnapshot workspace(AdenWorkspaceMembership membership) {
        return new WorkspaceSnapshot(membership.workspace().id().value(), membership.workspace().displayName(),
                membership.role().name(), membership.workspace().status().name(),
                Long.toString(membership.workspace().version()), membership.workspace().createdAt());
    }

    private List<RunnerSummary> runners(AdenWorkspaceId workspaceId) {
        return repository.listRunners(workspaceId, 100).stream().map(row -> new RunnerSummary(
                row.runnerId(), row.displayName(), row.presence(), capabilitiesJson(row.capabilitiesJson()),
                Long.toString(row.currentSessionEpoch()), row.lastSeenAt())).toList();
    }

    private List<String> capabilitiesJson(String value) {
        try {
            List<String> result = objectMapper.readValue(value, new TypeReference<>() { });
            return result.stream().sorted().toList();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Runner capability 投影损坏", exception);
        }
    }

    private static List<CapabilityProjection> capabilities(long version) {
        String wireVersion = Long.toString(version);
        return List.of(
                new CapabilityProjection("CORE", "AVAILABLE", null, false, wireVersion),
                new CapabilityProjection("WX", "GATED", "需要独立 L3 方案与真实账号授权", false, wireVersion),
                new CapabilityProjection("PUR", "GATED", "需要采购业务规格与审批边界", false, wireVersion),
                new CapabilityProjection("COL", "EXPERIMENTAL", "仅允许后续合成采集验证", false, wireVersion));
    }

    private AuditEventSummary audit(AdenOperatorProjectionRepository.AuditView row) {
        String action = "aden." + row.actionCode().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        String outcome = "REJECTED".equals(row.outcome()) ? "DENIED" : row.outcome();
        return new AuditEventSummary(row.auditEventId(), row.workspaceId(), action, outcome,
                row.actorType(), row.actorId(), row.occurredAt(), row.correlationId());
    }

    private EventEnvelope event(AdenOperatorProjectionRepository.EventView row) {
        try {
            Map<String, Object> raw = objectMapper.readValue(row.payloadJson(), new TypeReference<>() { });
            Map<String, Object> publicData = new LinkedHashMap<>();
            raw.forEach((key, value) -> { if (PUBLIC_EVENT_KEYS.contains(key)) publicData.put(key, value); });
            if (publicData.isEmpty()) publicData.put("reasonCode", "PROJECTION_CHANGED");
            return new EventEnvelope(1, row.eventId(), row.workspaceId(), row.eventType(), row.aggregateType(),
                    row.aggregateId(), Long.toString(row.aggregateVersion()), Long.toString(row.sequence()),
                    row.occurredAt(), row.correlationId(), Map.copyOf(publicData));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Event payload 投影损坏", exception);
        }
    }

    private String streamFilterHash() {
        return fingerprint.hashValue(Map.of("filter", STREAM_FILTER, "schemaVersion", 1));
    }

    private static AdenApplicationException expired(AdenWorkspaceId workspaceId) {
        return new AdenApplicationException("ADEN_STREAM_CURSOR_EXPIRED",
                "事件游标已超出保留窗口，请重新获取工作空间快照",
                Map.of("snapshotPath", "/api/v1/aden/workspaces/" + workspaceId.value() + "/bootstrap"));
    }

    private static AdenApplicationException streamGap() {
        return new AdenApplicationException("ADEN_STREAM_UNAVAILABLE",
                "事件账本序号不连续，请重新获取工作空间快照");
    }

    public record WorkspaceSnapshot(String workspaceId, String displayName, String role,
                                    String status, String version, Instant createdAt) { }
    public record TaskStepSnapshot(String stepId, int ordinal, String state, int attemptNo,
                                   String version, int progressPercent) { }
    public record TaskSnapshot(String taskId, String workspaceId, String taskType,
                               String capabilityCode, String title, String state, String version,
                               List<OperatorTaskCommand> allowedCommands, List<TaskStepSnapshot> steps,
                               String reasonCode, Instant createdAt, Instant updatedAt,
                               String correlationId) { }
    public record TaskPage(List<TaskSnapshot> items, String nextCursor, String queryHash) { }
    public record RunnerSummary(String runnerId, String displayName, String presence,
                                List<String> capabilities, String currentSessionEpoch,
                                Instant lastSeenAt) { }
    public record RunnerList(String workspaceId, List<RunnerSummary> items) { }
    public record CapabilityProjection(String capabilityCode, String status, String nextGate,
                                       boolean externalActionsEnabled, String projectionVersion) { }
    public record CapabilityList(String workspaceId, List<CapabilityProjection> items) { }
    public record AuditEventSummary(String auditEventId, String workspaceId, String action,
                                    String outcome, String subjectType, String subjectId,
                                    Instant occurredAt, String correlationId) { }
    public record AuditPage(List<AuditEventSummary> items, String nextCursor) { }
    public record EventEnvelope(int schemaVersion, String eventId, String workspaceId,
                                String eventType, String aggregateType, String aggregateId,
                                String aggregateVersion, String sequence, Instant occurredAt,
                                String correlationId, Map<String, Object> data) { }
    public record EventBatch(List<EventEnvelope> items, String nextCursor, long lastSequence) { }
    public record Bootstrap(int schemaVersion, WorkspaceSnapshot workspace, TaskPage tasks,
                            List<RunnerSummary> runners, List<CapabilityProjection> capabilities,
                            String snapshotQueryHash, String streamFilter, String streamWatermark,
                            String streamCursor, Instant generatedAt) { }
}
