/**
 * APS 前端领域边界。
 *
 * 这里的结构只能包含可序列化的业务字段，不得引入 DHTMLX、vis-timeline
 * 或浏览器 DOM 类型。可视化组件的对象只允许出现在各自适配目录内。
 */

export type ApsIsoDateTime = string

export interface ApsPlanListItem {
  planVersionId: string; versionNo: number; versionName: string; status: string
  requestId: string; baseVersionId: string | null; rowVersion: number; updatedAt: string
}
export interface ApsPlanRequestStatus {
  requestId: string; planVersionId: string; planStatus: string
  solverStatus: string | null; resultKind: string | null; reasonCodes: string[]
}
export interface ApsCreatePlanRequestInput {
  siteCode: string; workshopIds: string[]; orderIds: string[]; capturedAt: string
  definitionRevision: number; executionRevision: number
  horizon: { startAt: string; detailEndAt: string; endAt: string; planningAnchorAt: string; timeUnitSeconds: number; displayTimeZone: string }
  parameters: { maxSolveSeconds: number; randomSeed: number; solverSearchThreads: number; absoluteGapLimit: number; relativeGapLimit: number }
}

export type ApsDependencyKind = 'FINISH' | 'QUANTITY' | 'TRANSFER'

export type ApsResourceKind =
  | 'WORKSHOP'
  | 'WORK_CENTER'
  | 'PERSON'
  | 'MACHINE'
  | 'WORKSTATION'
  | 'TOOL'

export type ApsPhaseKind =
  | 'SETUP'
  | 'RUN'
  | 'UNLOAD'
  | 'WAIT'
  | 'TRANSPORT'
  | 'RESUME_SETUP'

export interface ApsGanttTask {
  readonly taskId: string
  readonly parentTaskId?: string
  readonly label: string
  readonly startAt: ApsIsoDateTime
  readonly endAt: ApsIsoDateTime
  readonly progressRatio: number
  readonly expanded?: boolean
}

export interface ApsGanttDependency {
  readonly dependencyId: string
  readonly predecessorTaskId: string
  readonly successorTaskId: string
  readonly kind: ApsDependencyKind
}

export interface ApsGanttModel {
  readonly planVersionId: string
  readonly revision: number
  readonly tasks: readonly ApsGanttTask[]
  readonly dependencies: readonly ApsGanttDependency[]
}

export interface ApsResourceRow {
  readonly resourceId: string
  readonly parentResourceId?: string
  readonly label: string
  readonly kind: ApsResourceKind
  readonly sortOrder: number
}

export interface ApsTimelineSegment {
  /** 同一物理分段存在多条资源分配时，用于可视化项唯一身份。 */
  readonly timelineItemId?: string
  readonly segmentId: string
  readonly allocationId?: string
  readonly taskId: string
  readonly resourceId: string
  readonly label: string
  readonly startAt: ApsIsoDateTime
  readonly endAt: ApsIsoDateTime
  readonly phase: ApsPhaseKind
  readonly editable: boolean
}

export interface ApsTimelineModel {
  readonly planVersionId: string
  readonly revision: number
  readonly resources: readonly ApsResourceRow[]
  readonly segments: readonly ApsTimelineSegment[]
}

/**
 * 可视化层只发出调整意图。后端复验前，该意图不代表计划已经修改。
 */
export interface ApsAdjustmentIntent {
  readonly source: 'GANTT' | 'TIMELINE'
  readonly basePlanVersionId: string
  readonly baseRevision: number
  readonly targetType: 'JOB' | 'SEGMENT' | 'ALLOCATION'
  readonly targetId: string
  readonly requestedStartAt: ApsIsoDateTime
  readonly requestedEndAt: ApsIsoDateTime
  readonly requestedResourceId?: string
}

export type ApsAdjustmentIntentHandler = (intent: ApsAdjustmentIntent) => void

export interface ApsPlanVersionSummary {
  readonly planVersionId: string
  readonly baseVersionId: string | null
  readonly versionNo: number
  readonly versionName: string
  readonly status: string
  readonly definitionRevision: number
  readonly executionRevision: number
  readonly inputHash: string
  readonly rowVersion: number
  readonly updatedAt: ApsIsoDateTime
}

export interface ApsPlanMember {
  readonly id: string
  readonly taskId: string
  readonly memberNo: number
  readonly plannedQty: string
  readonly uomCode: string
}

export interface ApsPlanJob {
  readonly jobId: string
  readonly operationSpecId: string
  readonly workCenterId: string | null
  readonly jobCode: string
  readonly jobType: 'NORMAL' | 'SPLIT' | 'SHARED_BATCH' | 'CARRY'
  readonly batchCode: string | null
  readonly plannedQty: string
  readonly uomCode: string
  readonly capacityValue: string | null
  readonly capacityUomCode: string | null
  readonly compatibilityKey: string | null
  readonly carryRunId: string | null
  readonly startAt: ApsIsoDateTime
  readonly endAt: ApsIsoDateTime
  readonly members: readonly ApsPlanMember[]
}

export interface ApsPlanSegment {
  readonly id: string
  readonly jobId: string
  readonly phaseId: string
  readonly segmentNo: number
  readonly phaseType: Exclude<ApsPhaseKind, 'RESUME_SETUP'>
  readonly startAt: ApsIsoDateTime
  readonly endAt: ApsIsoDateTime
  readonly plannedQty: string | null
  readonly releaseAt: ApsIsoDateTime | null
  readonly releaseQty: string | null
  readonly uomCode: string
}

export interface ApsPlanAllocation {
  readonly id: string
  readonly segmentId: string
  readonly phaseId: string
  readonly requirementId: string
  readonly resourceId: string
  readonly allocationRole: 'PERSON' | 'MACHINE' | 'WORKSTATION' | 'TOOL'
  readonly seatNo: number
  readonly capacityUsed: string
}

export interface ApsPlanLock {
  readonly id: string
  readonly targetType: 'JOB' | 'SEGMENT' | 'ALLOCATION'
  readonly jobId: string
  readonly segmentId: string | null
  readonly allocationId: string | null
  readonly lockType: 'TIME' | 'RESOURCE' | 'FULL'
  readonly lockedStartAt: ApsIsoDateTime | null
  readonly lockedEndAt: ApsIsoDateTime | null
  readonly lockedResourceId: string | null
  readonly reason: string
  readonly rowVersion: number
}

export interface ApsCreatePlanLockInput {
  readonly expectedPlanRowVersion: number
  readonly targetType: ApsPlanLock['targetType']
  readonly targetId: string
  readonly lockType: ApsPlanLock['lockType']
  readonly requestedResourceId?: string
  readonly reason: string
}

export interface ApsCreateAdjustmentInput {
  readonly expectedBaseRowVersion: number
  readonly capturedAt: ApsIsoDateTime
  readonly targetType: ApsAdjustmentIntent['targetType']
  readonly targetId: string
  readonly requestedStartAt: ApsIsoDateTime
  readonly requestedEndAt: ApsIsoDateTime
  readonly requestedResourceId?: string
  readonly reason: string
}

export interface ApsAdjustmentAccepted {
  readonly schemaVersion: '1.0'
  readonly contractType: 'PLAN_ADJUSTMENT_ACCEPTED'
  readonly requestId: string
  readonly planVersionId: string
  readonly basePlanVersionId: string
  readonly planStatus: ApsPlanLifecycleStatus
  readonly reused: boolean
  readonly baseCandidateHash: string
  readonly impact: Readonly<{
    affectedTaskIds: readonly string[]
    affectedResourceIds: readonly string[]
    reasons: Readonly<Record<string, readonly string[]>>
    globalRevalidationRequired: true
  }>
}

export type ApsPlanLifecycleStatus = 'DRAFT' | 'SOLVING' | 'FEASIBLE' | 'CONFLICT' | 'CANCELLED'
  | 'PUBLISHING' | 'PUBLISHED' | 'FAILED' | 'SUPERSEDED'

export type ApsStructuralAction = 'INSERT_ORDER' | 'SPLIT_LOT' | 'MERGE_BATCH'

interface ApsStructuralAdjustmentCommon {
  readonly expectedBaseRowVersion: number
  readonly capturedAt: ApsIsoDateTime
  readonly reason: string
}

export type ApsCreateStructuralAdjustmentInput =
  | (ApsStructuralAdjustmentCommon & {
      readonly action: 'INSERT_ORDER'
      readonly orderId: string
    })
  | (ApsStructuralAdjustmentCommon & {
      readonly action: 'SPLIT_LOT'
      readonly parentLotId: string
      readonly splitQuantity: number
    })
  | (ApsStructuralAdjustmentCommon & {
      readonly action: 'MERGE_BATCH'
      readonly compatibilityKey: string
      readonly members: readonly Readonly<{ taskId: string; quantity: number }>[]
    })

export interface ApsStructuralAdjustmentAccepted {
  readonly schemaVersion: '1.0'
  readonly contractType: 'PLAN_STRUCTURAL_ADJUSTMENT_ACCEPTED'
  readonly requestId: string
  readonly planVersionId: string
  readonly basePlanVersionId: string
  readonly planStatus: ApsPlanLifecycleStatus
  readonly reused: boolean
  readonly action: ApsStructuralAction
  readonly derivedLotId: string | null
  readonly baseCandidateHash: string
  readonly impact: ApsAdjustmentAccepted['impact']
}

export interface ApsPublishPlanInput {
  readonly expectedPlanRowVersion: number
  readonly reason: string
}

export type ApsPlanProblemReason =
  | 'NO_ROUTE'
  | 'MISSING_DURATION'
  | 'NO_QUALIFIED_RESOURCE'
  | 'NO_COMMON_WINDOW'
  | 'RESOURCE_OVERLAP'
  | 'CAPACITY_EXCEEDED'
  | 'PRECEDENCE_VIOLATION'
  | 'QUANTITY_NOT_RELEASED'
  | 'BATCH_INCOMPATIBLE'
  | 'LOCK_CONFLICT'
  | 'OPEN_OCCUPANCY_UNKNOWN_RELEASE'
  | 'STALE_INPUT'
  | 'UNSUPPORTED_SYNC_RULE'
  | 'TIME_LIMIT_NO_SOLUTION'
  | 'INVALID_INTERVAL'
  | 'INVALID_REVISION'
  | 'HASH_MISMATCH'
  | 'SOLVER_MODEL_INVALID'
  | 'UNAUTHORIZED'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'STALE_VERSION'
  | 'IDEMPOTENCY_CONFLICT'
  | 'CONTRACT_VALIDATION_FAILED'
  | 'CAPABILITY_NOT_IMPLEMENTED'
  | 'SERVICE_UNAVAILABLE'
  | 'INTERNAL_ERROR'

export interface ApsPlanProblem {
  readonly schemaVersion: '1.0'
  readonly contractType: 'APS_PROBLEM'
  readonly problemId: string
  readonly reasonCode: ApsPlanProblemReason
  readonly constraintCode: string | null
  readonly severity: 'INFO' | 'WARNING' | 'ERROR'
  readonly title: string
  readonly detail: string
  readonly retryable: boolean
  readonly objectRefs: readonly Readonly<{
    objectType: string
    objectId: string
    field: string | null
  }>[]
  readonly timeRange: Readonly<{
    startAt: ApsIsoDateTime
    endAt: ApsIsoDateTime
  }> | null
  readonly measurements: Readonly<Record<string, string | number | boolean | null>>
}

export interface ApsPlanPublished {
  readonly schemaVersion: '1.0'
  readonly contractType: 'PLAN_PUBLISHED'
  readonly plan: ApsPlanVersionDetail
  readonly reused: boolean
  readonly supersededVersionId: string | null
  readonly reason: string
  readonly outboundStatus: 'NOT_APPLICABLE'
}

export interface ApsDiscardCandidateInput {
  readonly expectedPlanRowVersion: number
  readonly reason: string
}

export interface ApsPlanCandidateDiscarded {
  readonly schemaVersion: '1.0'
  readonly contractType: 'PLAN_CANDIDATE_DISCARDED'
  readonly plan: ApsPlanVersionDetail
  readonly reason: string
  readonly discardedAt: ApsIsoDateTime
}

export interface ApsPlanVersionDetail {
  readonly schemaVersion: '1.0'
  readonly contractType: 'PLAN_VERSION_DETAIL'
  readonly version: ApsPlanVersionSummary
  readonly candidateHash: string | null
  readonly solverStatus: 'OPTIMAL' | 'FEASIBLE' | 'INFEASIBLE' | 'UNKNOWN' | 'MODEL_INVALID' | null
  readonly resultKind: 'FEASIBLE' | 'TIMEOUT_WITH_SOLUTION' | 'INFEASIBLE_PROVEN' | 'TIMEOUT_NO_SOLUTION' | 'INVALID_INPUT' | null
  readonly unplannedTaskIds: readonly string[]
  readonly inputCapturedAt: ApsIsoDateTime | null
  readonly latestFactUpdatedAt: ApsIsoDateTime | null
  readonly stale: boolean
  readonly jobs: readonly ApsPlanJob[]
  readonly segments: readonly ApsPlanSegment[]
  readonly allocations: readonly ApsPlanAllocation[]
  readonly locks: readonly ApsPlanLock[]
  readonly problems: readonly ApsPlanProblem[]
}

export type ApsPlanChangeDimension = 'TIME' | 'RESOURCE' | 'JOB_TYPE' | 'QUANTITY' | 'WORK_CENTER'

export interface ApsPlanJobChange {
  readonly changeType: 'ADDED' | 'REMOVED' | 'CHANGED' | 'UNCHANGED'
  readonly memberTaskIds: readonly string[]
  readonly dimensions: readonly ApsPlanChangeDimension[]
  readonly baseJobId: string | null
  readonly targetJobId: string | null
  readonly baseStartAt: ApsIsoDateTime | null
  readonly baseEndAt: ApsIsoDateTime | null
  readonly targetStartAt: ApsIsoDateTime | null
  readonly targetEndAt: ApsIsoDateTime | null
  readonly baseResourceIds: readonly string[]
  readonly targetResourceIds: readonly string[]
}

export interface ApsPlanVersionComparison {
  readonly schemaVersion: '1.0'
  readonly contractType: 'PLAN_VERSION_COMPARISON'
  readonly baseVersionId: string
  readonly targetVersionId: string
  readonly summary: Readonly<{ added: number; removed: number; changed: number; unchanged: number }>
  readonly jobs: readonly ApsPlanJobChange[]
}
