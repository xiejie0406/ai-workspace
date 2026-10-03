import type {
  ApsGanttModel,
  ApsPlanAllocation,
  ApsPhaseKind,
  ApsPlanLock,
  ApsPlanSegment,
  ApsPlanVersionDetail,
  ApsTimelineModel,
  ApsTimelineSegment
} from '@/types/aps/planning'
import type { ApsResource as FullApsResource } from '@/types/aps/resource'

export interface ApsWorkbenchModels {
  readonly processGantt: ApsGanttModel
  readonly equipmentTimeline: ApsTimelineModel
  readonly personnelTimeline: ApsTimelineModel
}

export interface ApsWorkbenchFilter {
  readonly searchText?: string
  readonly resourceId?: string
  readonly phase?: ApsPhaseKind
}

/** 纯领域模型筛选；不调用第三方图表的私有过滤 API，三种视图始终使用同一作业集合。 */
type ApsResource=Pick<FullApsResource,'id'|'code'|'name'|'type'|'workshopId'|'workCenterId'|'status'>

export function filterApsWorkbenchModels(
  models: ApsWorkbenchModels,
  filter: ApsWorkbenchFilter
): ApsWorkbenchModels {
  const search = filter.searchText?.trim().toLocaleLowerCase() ?? ''
  const timelines = [models.equipmentTimeline, models.personnelTimeline]
  const resourceTaskIds = filter.resourceId
    ? new Set(timelines.flatMap((model) => model.segments)
      .filter((segment) => segment.resourceId === filter.resourceId)
      .map((segment) => segment.taskId))
    : undefined
  const phaseTaskIds = filter.phase
    ? new Set(timelines.flatMap((model) => model.segments)
      .filter((segment) => segment.phase === filter.phase)
      .map((segment) => segment.taskId))
    : undefined
  const tasks = models.processGantt.tasks.filter((task) =>
    (!search || task.taskId.toLocaleLowerCase().includes(search) || task.label.toLocaleLowerCase().includes(search))
    && (!resourceTaskIds || resourceTaskIds.has(task.taskId))
    && (!phaseTaskIds || phaseTaskIds.has(task.taskId)))
  const taskIds = new Set(tasks.map((task) => task.taskId))
  const timeline = (model: ApsTimelineModel): ApsTimelineModel => {
    const segments = model.segments.filter((segment) => taskIds.has(segment.taskId)
      && (!filter.resourceId || segment.resourceId === filter.resourceId)
      && (!filter.phase || segment.phase === filter.phase))
    const resourceIds = new Set(segments.map((segment) => segment.resourceId))
    return { ...model, resources: model.resources.filter((resource) => resourceIds.has(resource.resourceId)), segments }
  }
  return {
    processGantt: {
      ...models.processGantt,
      tasks,
      dependencies: models.processGantt.dependencies.filter((dependency) =>
        taskIds.has(dependency.predecessorTaskId) && taskIds.has(dependency.successorTaskId))
    },
    equipmentTimeline: timeline(models.equipmentTimeline),
    personnelTimeline: timeline(models.personnelTimeline)
  }
}

/**
 * 把服务端 M19～M24 只读模型投影成三类视图。
 * 这里不生成调整结果；拖动仍只能形成 ApsAdjustmentIntent 并等待服务端确认。
 */
export function buildApsWorkbenchModels(
  detail: ApsPlanVersionDetail,
  resources: readonly ApsResource[]
): ApsWorkbenchModels {
  const resourcesById = uniqueById(resources, '资源')
  const jobsById = uniqueById(detail.jobs, '计划作业', (value) => value.jobId)
  const segmentsById = uniqueById(detail.segments, '计划分段')
  const locks = lockIndex(detail.locks)

  for (const segment of detail.segments) {
    if (!jobsById.has(segment.jobId)) {
      throw new Error(`计划分段 ${segment.id} 引用了不存在的作业 ${segment.jobId}`)
    }
  }

  const processGantt: ApsGanttModel = {
    planVersionId: detail.version.planVersionId,
    revision: detail.version.rowVersion,
    tasks: detail.jobs.map((job) => ({
      taskId: job.jobId,
      label: `${job.jobCode} · ${job.plannedQty} ${job.uomCode}`,
      startAt: job.startAt,
      endAt: job.endAt,
      progressRatio: 0,
      expanded: true
    })),
    // M19～M24 详情不伪造工序依赖；后续由工作台读模型显式提供连线。
    dependencies: []
  }

  const equipmentTimeline = timeline(
    detail,
    resourcesById,
    segmentsById,
    locks,
    (allocation) => allocation.allocationRole !== 'PERSON'
  )
  const personnelTimeline = timeline(
    detail,
    resourcesById,
    segmentsById,
    locks,
    (allocation) => allocation.allocationRole === 'PERSON'
  )

  return { processGantt, equipmentTimeline, personnelTimeline }
}

function timeline(
  detail: ApsPlanVersionDetail,
  resourcesById: Map<string, ApsResource>,
  segmentsById: Map<string, ApsPlanSegment>,
  locks: LockIndex,
  include: (allocation: ApsPlanAllocation) => boolean
): ApsTimelineModel {
  const allocations = detail.allocations.filter(include)
  const resourceIds = [...new Set(allocations.map((value) => value.resourceId))].sort()
  const resourceRows = resourceIds.map((resourceId, index) => {
    const resource = resourcesById.get(resourceId)
    if (!resource) throw new Error(`资源分配引用了不存在的资源 ${resourceId}`)
    return {
      resourceId,
      label: `${resource.code} · ${resource.name}`,
      kind: resource.type,
      sortOrder: index + 1
    }
  })
  const segments = allocations.map<ApsTimelineSegment>((allocation) => {
    const segment = segmentsById.get(allocation.segmentId)
    if (!segment) throw new Error(`资源分配 ${allocation.id} 引用了不存在的分段 ${allocation.segmentId}`)
    const timeLocked = locks.jobTime.has(segment.jobId)
      || locks.segmentTime.has(segment.id)
      || locks.allocationTime.has(allocation.id)
    return {
      // 同一物理分段可能分配多人/多设备，视图项和领域目标分别保留稳定身份。
      timelineItemId: allocation.id,
      segmentId: segment.id,
      allocationId: allocation.id,
      taskId: segment.jobId,
      resourceId: allocation.resourceId,
      label: `${segment.phaseType} · 席位 ${allocation.seatNo}`,
      startAt: segment.startAt,
      endAt: segment.endAt,
      phase: segment.phaseType,
      editable: !timeLocked
    }
  })
  return {
    planVersionId: detail.version.planVersionId,
    revision: detail.version.rowVersion,
    resources: resourceRows,
    segments
  }
}

interface LockIndex {
  readonly jobTime: Set<string>
  readonly segmentTime: Set<string>
  readonly allocationTime: Set<string>
}

function lockIndex(locks: readonly ApsPlanLock[]): LockIndex {
  const result: LockIndex = {
    jobTime: new Set<string>(),
    segmentTime: new Set<string>(),
    allocationTime: new Set<string>()
  }
  for (const lock of locks) {
    if (lock.lockType === 'RESOURCE') continue
    if (lock.targetType === 'JOB') result.jobTime.add(lock.jobId)
    if (lock.targetType === 'SEGMENT' && lock.segmentId) result.segmentTime.add(lock.segmentId)
    if (lock.targetType === 'ALLOCATION' && lock.allocationId) result.allocationTime.add(lock.allocationId)
  }
  return result
}

function uniqueById<T extends { readonly id: string }>(
  values: readonly T[],
  label: string
): Map<string, T>
function uniqueById<T>(
  values: readonly T[],
  label: string,
  id: (value: T) => string
): Map<string, T>
function uniqueById<T>(
  values: readonly T[],
  label: string,
  id: (value: T) => string = (value) => (value as { id: string }).id
): Map<string, T> {
  const result = new Map<string, T>()
  for (const value of values) {
    const valueId = id(value)
    if (result.has(valueId)) throw new Error(`${label} ID 重复: ${valueId}`)
    result.set(valueId, value)
  }
  return result
}
