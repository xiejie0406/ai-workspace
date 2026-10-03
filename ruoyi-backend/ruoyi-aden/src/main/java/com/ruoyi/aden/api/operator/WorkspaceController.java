package com.ruoyi.aden.api.operator;

import com.ruoyi.aden.api.common.AdenCorrelationIdFilter;
import com.ruoyi.aden.application.security.AdenOperatorPrincipal;
import com.ruoyi.aden.application.security.AdenOperatorPrincipalProvider;
import com.ruoyi.aden.application.workspace.AdenWorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Validated
@RestController
@RequestMapping("/api/v1/aden")
@ConditionalOnProperty(prefix = "aden", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkspaceController {
    private final AdenOperatorPrincipalProvider principalProvider;
    private final AdenWorkspaceService service;

    public WorkspaceController(AdenOperatorPrincipalProvider principalProvider, AdenWorkspaceService service) {
        this.principalProvider = principalProvider;
        this.service = service;
    }

    @GetMapping("/workspaces")
    @PreAuthorize("@ss.hasPermi('aden:workspace:list')")
    public WorkspaceListResponse list() {
        AdenOperatorPrincipal principal = principalProvider.current();
        return new WorkspaceListResponse(service.list(principal).stream().map(WorkspaceSnapshot::from).toList());
    }

    @GetMapping("/default-workspace")
    @PreAuthorize("@ss.hasPermi('aden:workspace:list') or @ss.hasPermi('aden:collection:read')")
    public ResponseEntity<WorkspaceSnapshot> defaultWorkspace() {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(WorkspaceSnapshot.from(service.resolveDefault(principalProvider.current())));
    }

    @PostMapping("/admin/workspaces")
    @PreAuthorize("@ss.hasPermi('aden:workspace:create')")
    public ResponseEntity<WorkspaceSnapshot> create(@Valid @RequestBody CreateWorkspaceRequest request,
                                                     HttpServletRequest servletRequest) {
        AdenOperatorPrincipal principal = principalProvider.current();
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.from(service.create(
                principal, request.displayName(), AdenCorrelationIdFilter.correlationId(servletRequest)));
        URI location = URI.create("/api/v1/aden/workspaces/" + snapshot.workspaceId());
        String eTag = "\"workspace-" + snapshot.workspaceId() + "-v" + snapshot.version() + "\"";
        return ResponseEntity.created(location).eTag(eTag).body(snapshot);
    }
}
