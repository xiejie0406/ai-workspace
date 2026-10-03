// GENERATED FROM contracts/aden — DO NOT EDIT
import type { CanonicalInt64String as BrandedCanonicalInt64String } from '../contracts/wire-scalars'

/** 此文件由 current JSON Schema 确定性生成；运行时仍必须经过边界 validator。 */
export type CanonicalInt64String = BrandedCanonicalInt64String

export type Uuid = string

export type UtcDateTime = string

export type Sha256 = string

export type CorrelationId = Uuid

export type IdempotencyKey = string

export type StreamCursor = string

export type PageCursor = string

export type CapabilityCode = "CORE" | "WX" | "PUR" | "COL"

export type WorkspaceRole = "OWNER" | "OPERATOR" | "VIEWER"

export type WorkspaceStatus = "ACTIVE" | "SUSPENDED"

export type TaskState = "DRAFT" | "VALIDATING" | "QUEUED" | "RUNNING" | "WAITING_USER" | "WAITING_EXTERNAL" | "CANCEL_REQUESTED" | "SUCCEEDED" | "FAILED" | "CANCELED"

export type TaskStepState = "PENDING" | "READY" | "RUNNING" | "WAITING_RETRY" | "SUCCEEDED" | "FAILED" | "CANCELED" | "OUTCOME_UNKNOWN"

export type OperatorTaskCommand = "SUBMIT_FOR_VALIDATION" | "REQUEST_CANCEL"

export type RunnerPresence = "ONLINE" | "OFFLINE" | "LEASED" | "QUARANTINED"

export type RunnerSessionState = "ACTIVE" | "EXPIRED" | "REVOKED" | "SUPERSEDED" | "CLOSED"

export type DeliveryState = "READY" | "LEASED" | "RUNNING" | "COMPLETED" | "FAILED_RETRYABLE" | "FAILED_FINAL" | "CANCELED" | "OUTCOME_UNKNOWN"

export type ErrorCode = "ADEN_INVALID_ARGUMENT" | "ADEN_STREAM_CURSOR_INVALID" | "ADEN_AUTH_REQUIRED" | "ADEN_RUNNER_AUTH_INVALID" | "ADEN_PERMISSION_DENIED" | "ADEN_EXTERNAL_ACTION_DISABLED" | "ADEN_TASK_NOT_FOUND" | "ADEN_RUNNER_NOT_FOUND" | "ADEN_STATE_TRANSITION_DENIED" | "ADEN_DEFAULT_WORKSPACE_UNAVAILABLE" | "ADEN_IDEMPOTENCY_KEY_REUSED" | "ADEN_RUNNER_LEASE_LOST" | "ADEN_SESSION_EPOCH_STALE" | "ADEN_RECEIPT_STALE" | "ADEN_OUTCOME_UNKNOWN" | "ADEN_STREAM_CURSOR_EXPIRED" | "ADEN_VERSION_CONFLICT" | "ADEN_TASK_VALIDATION_FAILED" | "ADEN_PRECONDITION_REQUIRED" | "ADEN_RATE_LIMITED" | "ADEN_DEPENDENCY_UNAVAILABLE" | "ADEN_AUDIT_UNAVAILABLE"

export type ErrorEnvelope = { readonly code: 400 | 401 | 403 | 404 | 409 | 410 | 412 | 422 | 428 | 429 | 503; readonly msg: string; readonly data: null; readonly errorCode: ErrorCode; readonly retryable: boolean; readonly correlationId: CorrelationId; readonly details?: { readonly [key: string]: string | number | boolean | null } }

export type CreateWorkspaceRequest = { readonly displayName: string }

export type WorkspaceSnapshot = { readonly workspaceId: Uuid; readonly displayName: string; readonly role: WorkspaceRole; readonly status: WorkspaceStatus; readonly version: CanonicalInt64String; readonly createdAt: UtcDateTime }

export type WorkspaceListResponse = { readonly items: readonly (WorkspaceSnapshot)[] }

export type CapabilityProjection = { readonly capabilityCode: CapabilityCode; readonly status: "AVAILABLE" | "GATED" | "DISABLED" | "EXPERIMENTAL"; readonly nextGate: string | null; readonly externalActionsEnabled: false; readonly projectionVersion: CanonicalInt64String }

export type CapabilityProjectionList = readonly (CapabilityProjection)[]

export type CapabilityListResponse = { readonly workspaceId: Uuid; readonly items: CapabilityProjectionList }

export type SyntheticTaskInput = { readonly fixtureId: string; readonly instruction: string; readonly expectedOutcome?: "SUCCEED" | "FAIL_VALIDATION" | "CANCEL_AT_SAFE_POINT" }

export type CreateTaskRequest = { readonly taskType: "SYNTHETIC_CORE"; readonly capabilityCode: "CORE"; readonly title: string; readonly input: SyntheticTaskInput }

export type TaskStepSnapshot = { readonly stepId: Uuid; readonly ordinal: number; readonly state: TaskStepState; readonly attemptNo: number; readonly version: CanonicalInt64String; readonly progressPercent?: number }

export type TaskSnapshot = { readonly taskId: Uuid; readonly workspaceId: Uuid; readonly taskType: "SYNTHETIC_CORE" | "JD_DETAIL_CAPTURE" | "MANUAL_COLLECTION_ENTRY"; readonly capabilityCode: "CORE" | "COL"; readonly title: string; readonly state: TaskState; readonly version: CanonicalInt64String; readonly allowedCommands: readonly (OperatorTaskCommand)[]; readonly steps: readonly (TaskStepSnapshot)[]; readonly reasonCode?: string | null; readonly createdAt: UtcDateTime; readonly updatedAt: UtcDateTime; readonly correlationId: CorrelationId }

export type OperatorTaskCommandRequest = { readonly command: "SUBMIT_FOR_VALIDATION" } | { readonly command: "REQUEST_CANCEL"; readonly reasonCode: string }

export type TaskPage = { readonly items: readonly (TaskSnapshot)[]; readonly nextCursor: PageCursor | null; readonly queryHash: Sha256 }

export type RunnerSummary = { readonly runnerId: Uuid; readonly displayName: string; readonly presence: RunnerPresence; readonly capabilities: readonly (CapabilityCode)[]; readonly currentSessionEpoch: CanonicalInt64String; readonly lastSeenAt?: UtcDateTime | null }

export type RunnerListResponse = { readonly workspaceId: Uuid; readonly items: readonly (RunnerSummary)[] }

export type EnrollRunnerRequest = { readonly displayName: string; readonly capabilities: readonly (CapabilityCode)[] }

export type RunnerEnrollmentResponse = { readonly runnerId: Uuid; readonly credentialId: Uuid; readonly credentialToken: string; readonly credentialEpoch: CanonicalInt64String; readonly expiresAt: UtcDateTime; readonly issuedAt: UtcDateTime }

export type WorkspaceBootstrapSnapshot = { readonly schemaVersion: 1; readonly workspace: WorkspaceSnapshot; readonly tasks: TaskPage; readonly runners: readonly (RunnerSummary)[]; readonly capabilities: CapabilityProjectionList | readonly (CapabilityProjection)[]; readonly snapshotQueryHash: Sha256; readonly streamFilter: "workspace-all-v1"; readonly streamWatermark: CanonicalInt64String; readonly streamCursor: StreamCursor; readonly generatedAt: UtcDateTime }

export type PublicProjectionState = "DRAFT" | "VALIDATING" | "QUEUED" | "RUNNING" | "WAITING_USER" | "WAITING_EXTERNAL" | "CANCEL_REQUESTED" | "SUCCEEDED" | "FAILED" | "CANCELED" | "ONLINE" | "OFFLINE" | "LEASED" | "QUARANTINED" | "AVAILABLE" | "GATED" | "DISABLED" | "EXPERIMENTAL"

export type PublicEventData = { readonly from?: PublicProjectionState; readonly to?: PublicProjectionState; readonly reasonCode?: string; readonly progressPercent?: number; readonly runnerId?: Uuid; readonly capabilityCode?: CapabilityCode; readonly externalActionsEnabled?: false }

export type EventEnvelope = { readonly schemaVersion: 1; readonly eventId: Uuid; readonly workspaceId: Uuid; readonly eventType: "aden.task.created.v1" | "aden.task.state-changed.v1" | "aden.task.progressed.v1" | "aden.runner.status-changed.v1" | "aden.capability.changed.v1" | "aden.audit.recorded.v1"; readonly aggregateType: "TASK" | "RUNNER" | "CAPABILITY" | "AUDIT"; readonly aggregateId: Uuid; readonly aggregateVersion: CanonicalInt64String; readonly sequence: CanonicalInt64String; readonly occurredAt: UtcDateTime; readonly correlationId: CorrelationId; readonly data: PublicEventData }

export type AuditEventSummary = { readonly auditEventId: Uuid; readonly workspaceId: Uuid; readonly action: string; readonly outcome: "SUCCEEDED" | "DENIED" | "FAILED"; readonly subjectType?: "OPERATOR" | "RUNNER" | "SYSTEM"; readonly subjectId?: string; readonly occurredAt: UtcDateTime; readonly correlationId: CorrelationId }

export type AuditEventPage = { readonly items: readonly (AuditEventSummary)[]; readonly nextCursor: PageCursor | null }
