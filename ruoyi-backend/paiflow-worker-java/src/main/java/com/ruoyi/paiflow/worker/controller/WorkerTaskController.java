package com.ruoyi.paiflow.worker.controller;

import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;
import com.ruoyi.paiflow.worker.service.WorkerException;
import com.ruoyi.paiflow.worker.service.WorkerTaskRunner;
import com.ruoyi.paiflow.worker.service.WorkerServiceIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/paiflow/v1/worker")
@ConditionalOnProperty(prefix = "paiflow.worker", name = "enabled", havingValue = "true")
public final class WorkerTaskController {
    private final WorkerTaskRunner runner;
    private final WorkerServiceIdentity identity;

    public WorkerTaskController(WorkerTaskRunner runner, WorkerServiceIdentity identity) {
        this.runner = runner;
        this.identity = identity;
    }

    @PostMapping("/execute")
    public Result execute(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Lease lease) {
        identity.require(authorization);
        return runner.execute(lease);
    }

    @ExceptionHandler(WorkerException.class)
    public ResponseEntity<Map<String, String>> rejected(WorkerException exception) {
        HttpStatus status = switch (exception.getCode()) {
            case "SERVICE_UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "TOOL_POLICY_DENIED" -> HttpStatus.FORBIDDEN;
            case "QUOTA_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "CENTER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "DSL_INVALID", "ENGINE_UNSUPPORTED", "LEASE_REQUIRED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
    }
}
