package com.ruoyi.aden.application.projection;

import tools.jackson.databind.ObjectMapper;
import com.ruoyi.aden.application.error.AdenApplicationException;
import com.ruoyi.aden.application.idempotency.AdenRequestFingerprint;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.application.workspace.AdenWorkspaceAccessGuard;
import com.ruoyi.aden.contract.OperatorTaskCommand;
import com.ruoyi.aden.domain.workspace.AdenWorkspace;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceId;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceMemberStatus;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceMembership;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceRole;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceRepository;
import com.ruoyi.aden.application.workspace.AdenWorkspaceSecurityAuditPort;
import com.ruoyi.aden.application.error.AdenAccessDeniedException;
import com.ruoyi.aden.application.error.AdenNotFoundException;

class AdenOperatorQueryServiceTest {
    private static final AdenWorkspaceId WORKSPACE =
            new AdenWorkspaceId("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-13T05:00:00Z");
    private static final String TASK = "22222222-2222-4222-8222-222222222222";

    private AdenOperatorProjectionRepository repository;
    private AdenOpaqueCursorCodec cursors;
    private AdenOperatorQueryService service;

    @BeforeEach
    void createService() {
        repository = mock(AdenOperatorProjectionRepository.class);
        AdenWorkspaceAccessGuard guard = mock(AdenWorkspaceAccessGuard.class);
        when(guard.requireWorkspace(any(), any(), any(), any(), any())).thenReturn(membership());
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.oldestEventSequence(any()))
                .thenReturn(AdenOperatorProjectionRepository.OptionalLongValue.empty());
        cursors = new AdenOpaqueCursorCodec(new byte[32]);
        ObjectMapper mapper = new ObjectMapper();
        service = new AdenOperatorQueryService(repository, guard, cursors,
                new AdenRequestFingerprint(mapper), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    @Test
    void allowedCommandsUseIndependentCommandAndCancelPermissions() {
        List<PermissionCase> cases = List.of(
                new PermissionCase(Set.of("aden:task:command"),
                        List.of(OperatorTaskCommand.SUBMIT_FOR_VALIDATION), List.of()),
                new PermissionCase(Set.of("aden:task:cancel"),
                        List.of(), List.of(OperatorTaskCommand.REQUEST_CANCEL)),
                new PermissionCase(Set.of("aden:task:command", "aden:task:cancel"),
                        List.of(OperatorTaskCommand.SUBMIT_FOR_VALIDATION),
                        List.of(OperatorTaskCommand.REQUEST_CANCEL)),
                new PermissionCase(Set.of(), List.of(), List.of()));

        for (PermissionCase current : cases) {
            AdenOperatorPrincipal principal = new AdenOperatorPrincipal(42, "operator", current.permissions());
            when(repository.findTask(WORKSPACE, TASK)).thenReturn(Optional.of(task("DRAFT")));
            assertEquals(current.draft(), service.getTask(principal, WORKSPACE, TASK, "correlation")
                    .allowedCommands());
            when(repository.findTask(WORKSPACE, TASK)).thenReturn(Optional.of(task("VALIDATING")));
            assertEquals(current.validating(), service.getTask(principal, WORKSPACE, TASK, "correlation")
                    .allowedCommands());
        }
    }

    @Test
    void viewerNeverReceivesWriteCommandsEvenWithBothRuoYiPermissions() {
        AdenWorkspaceAccessGuard guard = mock(AdenWorkspaceAccessGuard.class);
        when(guard.requireWorkspace(any(), any(), any(), any(), any())).thenReturn(membership(AdenWorkspaceRole.VIEWER));
        ObjectMapper mapper = new ObjectMapper();
        var viewerService = new AdenOperatorQueryService(repository, guard, cursors,
                new AdenRequestFingerprint(mapper), mapper, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        when(repository.findTask(WORKSPACE, TASK)).thenReturn(Optional.of(task("DRAFT")));
        AdenOperatorPrincipal principal = new AdenOperatorPrincipal(42, "viewer",
                Set.of("aden:task:command", "aden:task:cancel"));

        assertEquals(List.of(), viewerService.getTask(principal, WORKSPACE, TASK, "correlation")
                .allowedCommands());
    }

    @Test
    void streamCursorExpiresByIssuedAtEvenWhenLedgerStillExists() {
        String streamHash = new AdenRequestFingerprint(new ObjectMapper()).hashValue(
                java.util.Map.of("filter", AdenOperatorQueryService.STREAM_FILTER, "schemaVersion", 1));
        String expired = cursors.encodeStream(WORKSPACE, streamHash, 7, NOW.minus(Duration.ofMinutes(6)));
        AdenOperatorPrincipal principal = new AdenOperatorPrincipal(42, "operator",
                Set.of("aden:event:subscribe"));

        AdenApplicationException failure = assertThrows(AdenApplicationException.class,
                () -> service.events(principal, WORKSPACE, expired, 10, "correlation"));

        assertEquals("ADEN_STREAM_CURSOR_EXPIRED", failure.errorCode());
        assertEquals("/api/v1/aden/workspaces/" + WORKSPACE.value() + "/bootstrap",
                failure.details().get("snapshotPath"));
    }

    @Test
    void unexplainedLedgerGapFailsClosedInsteadOfAdvancingCursor() {
        when(repository.oldestEventSequence(WORKSPACE))
                .thenReturn(AdenOperatorProjectionRepository.OptionalLongValue.of(1));
        when(repository.workspaceWatermark(WORKSPACE)).thenReturn(3L);
        when(repository.listEvents(WORKSPACE, 0, 10)).thenReturn(List.of(
                new AdenOperatorProjectionRepository.EventView(
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", WORKSPACE.value(), 1,
                        "TASK", TASK, 1, "aden.task.created.v1", "{\"to\":\"DRAFT\"}",
                        NOW, "77777777-7777-4777-8777-777777777777"),
                new AdenOperatorProjectionRepository.EventView(
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", WORKSPACE.value(), 3,
                        "TASK", TASK, 2, "aden.task.state-changed.v1", "{\"to\":\"VALIDATING\"}",
                        NOW, "77777777-7777-4777-8777-777777777777")));
        AdenOperatorPrincipal principal = new AdenOperatorPrincipal(42, "operator",
                Set.of("aden:event:subscribe"));

        AdenApplicationException failure = assertThrows(AdenApplicationException.class,
                () -> service.events(principal, WORKSPACE, null, 10, "correlation"));

        assertEquals("ADEN_STREAM_UNAVAILABLE", failure.errorCode());
    }

    @Test
    void collectionViewerBootstrapReadsNoCoreProjectionAndDoesNotGrantEventAccess() {
        AdenWorkspaceRepository members=mock(AdenWorkspaceRepository.class);
        when(members.findActiveMembership(WORKSPACE,42)).thenReturn(Optional.of(membership(AdenWorkspaceRole.VIEWER)));
        var guard=new AdenWorkspaceAccessGuard(members,mock(AdenWorkspaceSecurityAuditPort.class));
        ObjectMapper mapper=new ObjectMapper();
        var restricted=new AdenOperatorQueryService(repository,guard,cursors,new AdenRequestFingerprint(mapper),mapper,
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5));
        var viewer=new AdenOperatorPrincipal(42,"viewer",Set.of("aden:collection:read"));
        var snapshot=restricted.bootstrap(viewer,WORKSPACE,null,null,50,"correlation");
        assertEquals(List.of(),snapshot.tasks().items());assertEquals(List.of(),snapshot.runners());assertEquals(List.of(),snapshot.capabilities());
        assertEquals("0",snapshot.streamWatermark());assertEquals(WORKSPACE.value(),snapshot.workspace().workspaceId());
        assertThrows(AdenAccessDeniedException.class,()->restricted.events(viewer,WORKSPACE,snapshot.streamCursor(),10,"correlation"));
        verifyNoInteractions(repository);
    }

    @Test
    void collectionBootstrapCannotReadAnotherWorkspaceOrAcceptCorePagination() {
        AdenWorkspaceRepository members=mock(AdenWorkspaceRepository.class);
        when(members.findActiveMembership(WORKSPACE,42)).thenReturn(Optional.empty());
        ObjectMapper mapper=new ObjectMapper();
        var restricted=new AdenOperatorQueryService(repository,new AdenWorkspaceAccessGuard(members,mock(AdenWorkspaceSecurityAuditPort.class)),cursors,
                new AdenRequestFingerprint(mapper),mapper,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5));
        var viewer=new AdenOperatorPrincipal(42,"viewer",Set.of("aden:collection:read"));
        assertThrows(AdenNotFoundException.class,()->restricted.bootstrap(viewer,WORKSPACE,null,null,50,"correlation"));
        when(members.findActiveMembership(WORKSPACE,42)).thenReturn(Optional.of(membership(AdenWorkspaceRole.VIEWER)));
        assertThrows(IllegalArgumentException.class,()->restricted.bootstrap(viewer,WORKSPACE,null,"old-core-cursor",50,"correlation"));
        verifyNoInteractions(repository);
    }

    private static AdenOperatorProjectionRepository.TaskView task(String state) {
        return new AdenOperatorProjectionRepository.TaskView(TASK, WORKSPACE.value(),
                "SYNTHETIC_CORE", "CORE", "合成任务", state, 1, null,
                NOW.minusSeconds(10), NOW, "77777777-7777-4777-8777-777777777777");
    }

    private static AdenWorkspaceMembership membership() {
        return membership(AdenWorkspaceRole.OPERATOR);
    }

    private static AdenWorkspaceMembership membership(AdenWorkspaceRole role) {
        AdenWorkspace workspace = new AdenWorkspace(WORKSPACE, "本地工作区", AdenWorkspaceStatus.ACTIVE,
                1, 42, NOW.minusSeconds(60), NOW.minusSeconds(60));
        return new AdenWorkspaceMembership(workspace, 42, role, AdenWorkspaceMemberStatus.ACTIVE,
                NOW.minusSeconds(60));
    }

    private record PermissionCase(Set<String> permissions, List<OperatorTaskCommand> draft,
                                  List<OperatorTaskCommand> validating) { }
}
