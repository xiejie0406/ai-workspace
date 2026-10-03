package com.ruoyi.aden.application.workspace;

import com.ruoyi.aden.application.error.AdenAccessDeniedException;
import com.ruoyi.aden.application.error.AdenApplicationException;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.domain.shared.AdenIdGenerator;
import com.ruoyi.aden.domain.workspace.AdenWorkspace;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceAudit;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceId;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceMemberStatus;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceMembership;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceRepository;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceRole;
import com.ruoyi.aden.domain.workspace.AdenWorkspaceStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class AdenWorkspaceService {
    public static final String LIST_PERMISSION = "aden:workspace:list";
    public static final String CREATE_PERMISSION = "aden:workspace:create";

    private final AdenWorkspaceRepository repository;
    private final AdenIdGenerator idGenerator;
    private final Clock clock;
    private final String configuredDefaultWorkspaceId;

    public AdenWorkspaceService(AdenWorkspaceRepository repository, AdenIdGenerator idGenerator, Clock clock) {
        this(repository, idGenerator, clock, "");
    }

    public AdenWorkspaceService(AdenWorkspaceRepository repository, AdenIdGenerator idGenerator, Clock clock,
                                String configuredDefaultWorkspaceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configuredDefaultWorkspaceId = Objects.requireNonNull(configuredDefaultWorkspaceId).trim();
    }

    /** 登录只解析已有有效范围，不创建空间或任意选取第一项。 */
    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public AdenWorkspaceMembership resolveDefault(AdenOperatorPrincipal principal) {
        if (!principal.hasPermission(LIST_PERMISSION) && !principal.hasPermission("aden:collection:read")) {
            throw new AdenAccessDeniedException();
        }
        if (!configuredDefaultWorkspaceId.isBlank()) {
            AdenWorkspaceId workspaceId;
            try { workspaceId = new AdenWorkspaceId(configuredDefaultWorkspaceId); }
            catch (IllegalArgumentException invalid) { throw defaultUnavailable("默认业务范围配置无效，请联系管理员"); }
            return repository.findActiveMembership(workspaceId, principal.userId())
                    .orElseThrow(() -> defaultUnavailable("默认业务范围不可用，请联系管理员核对配置与权限"));
        }
        List<AdenWorkspaceMembership> memberships = repository.findActiveByUserId(principal.userId(), 2);
        if (memberships.size() == 1) return memberships.get(0);
        throw defaultUnavailable(memberships.isEmpty()
                ? "账号没有可用业务范围，请联系管理员"
                : "账号有多个业务范围，请联系管理员配置默认范围");
    }

    private static AdenApplicationException defaultUnavailable(String message) {
        return new AdenApplicationException("ADEN_DEFAULT_WORKSPACE_UNAVAILABLE", message);
    }

    @Transactional(transactionManager = "adenTransactionManager", readOnly = true)
    public List<AdenWorkspaceMembership> list(AdenOperatorPrincipal principal) {
        requirePermission(principal, LIST_PERMISSION);
        return List.copyOf(repository.findActiveByUserId(principal.userId(), 100));
    }

    @Transactional(transactionManager = "adenTransactionManager")
    public AdenWorkspaceMembership create(
            AdenOperatorPrincipal principal,
            String displayName,
            String correlationId) {
        requirePermission(principal, CREATE_PERMISSION);
        Instant now = clock.instant();
        AdenWorkspace workspace = new AdenWorkspace(
                new AdenWorkspaceId(idGenerator.nextId()),
                displayName,
                AdenWorkspaceStatus.ACTIVE,
                0,
                principal.userId(),
                now,
                now);
        AdenWorkspaceMembership owner = new AdenWorkspaceMembership(
                workspace,
                principal.userId(),
                AdenWorkspaceRole.OWNER,
                AdenWorkspaceMemberStatus.ACTIVE,
                now);
        repository.insertWorkspace(workspace);
        repository.insertInitialOwner(owner);
        repository.insertAudit(new AdenWorkspaceAudit(
                idGenerator.nextId(),
                workspace.id(),
                "WORKSPACE_CREATED",
                workspace.id().value(),
                principal.userId(),
                Objects.requireNonNull(correlationId, "correlationId"),
                now));
        return owner;
    }

    private static void requirePermission(AdenOperatorPrincipal principal, String permission) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.hasPermission(permission)) throw new AdenAccessDeniedException();
    }
}
