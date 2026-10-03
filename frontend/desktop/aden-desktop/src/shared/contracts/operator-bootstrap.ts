import type {
  WorkspaceBootstrapSnapshot,
  WorkspaceListResponse,
  TaskSnapshot,
  RunnerSummary,
  CapabilityProjection
} from '../generated/operator-contracts'
import { parseCanonicalInt64 } from './wire-scalars'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const SHA256 = /^[0-9a-f]{64}$/
const UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const TASK_STATES = new Set(['DRAFT', 'VALIDATING', 'QUEUED', 'RUNNING', 'WAITING_USER', 'WAITING_EXTERNAL', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED'])
const RUNNER_STATES = new Set(['ONLINE', 'OFFLINE', 'LEASED', 'QUARANTINED'])
const CAPABILITY_STATES = new Set(['AVAILABLE', 'GATED', 'DISABLED', 'EXPERIMENTAL'])
const CAPABILITIES = new Set(['CORE', 'WX', 'PUR', 'COL'])

/** Bootstrap 的主进程 runtime Schema 边界；未知字段、枚举和 int64 number 均拒绝。 */
export function parseWorkspaceBootstrap(value: unknown, allowRestricted = false): WorkspaceBootstrapSnapshot {
  const body = exactObject(value, [
    'schemaVersion', 'workspace', 'tasks', 'runners', 'capabilities', 'snapshotQueryHash',
    'streamFilter', 'streamWatermark', 'streamCursor', 'generatedAt'
  ], 'WorkspaceBootstrapSnapshot')
  if (body.schemaVersion !== 1 || body.streamFilter !== 'workspace-all-v1') throw new TypeError('Bootstrap 版本或 streamFilter 非法')
  const workspace = exactObject(body.workspace, ['workspaceId', 'displayName', 'role', 'status', 'version', 'createdAt'], 'workspace')
  uuid(workspace.workspaceId, 'workspaceId')
  nonEmpty(workspace.displayName, 'displayName', 120)
  if (!['OWNER', 'OPERATOR', 'VIEWER'].includes(workspace.role as string)) throw new TypeError('workspace.role 非法')
  if (!['ACTIVE', 'SUSPENDED'].includes(workspace.status as string)) throw new TypeError('workspace.status 非法')
  parseCanonicalInt64(workspace.version)
  utc(workspace.createdAt, 'workspace.createdAt')

  const tasks = exactObject(body.tasks, ['items', 'nextCursor', 'queryHash'], 'tasks')
  if (!Array.isArray(tasks.items) || tasks.items.length > 100) throw new TypeError('tasks.items 非法')
  tasks.items.forEach(parseTaskSnapshot)
  if (tasks.nextCursor !== null) nonEmpty(tasks.nextCursor, 'tasks.nextCursor', 768)
  sha(tasks.queryHash, 'tasks.queryHash')

  if (!Array.isArray(body.runners) || body.runners.length > 100) throw new TypeError('runners 非法')
  body.runners.forEach(parseRunner)
  if (!Array.isArray(body.capabilities)) throw new TypeError('capabilities 非法')
  const restricted = allowRestricted && body.capabilities.length === 0 && tasks.items.length === 0 && body.runners.length === 0
  if (!restricted && body.capabilities.length !== 4) throw new TypeError('capabilities 必须恰好四项')
  const codes = new Set(body.capabilities.map(parseCapability))
  if (!restricted && (codes.size !== 4 || [...CAPABILITIES].some((code) => !codes.has(code)))) throw new TypeError('capabilities 必须各包含 CORE/WX/PUR/COL 一项')
  sha(body.snapshotQueryHash, 'snapshotQueryHash')
  parseCanonicalInt64(body.streamWatermark)
  nonEmpty(body.streamCursor, 'streamCursor', 768)
  utc(body.generatedAt, 'generatedAt')
  return body as unknown as WorkspaceBootstrapSnapshot
}

export function parseWorkspaceList(value: unknown): WorkspaceListResponse {
  const body = exactObject(value, ['items'], 'WorkspaceListResponse')
  if (!Array.isArray(body.items) || body.items.length > 100) throw new TypeError('WorkspaceListResponse.items 非法')
  for (const workspaceValue of body.items) {
    const workspace = exactObject(workspaceValue, ['workspaceId', 'displayName', 'role', 'status', 'version', 'createdAt'], 'WorkspaceSnapshot')
    uuid(workspace.workspaceId, 'workspaceId'); nonEmpty(workspace.displayName, 'displayName', 120)
    if (!['OWNER', 'OPERATOR', 'VIEWER'].includes(workspace.role as string)) throw new TypeError('workspace.role 非法')
    if (!['ACTIVE', 'SUSPENDED'].includes(workspace.status as string)) throw new TypeError('workspace.status 非法')
    parseCanonicalInt64(workspace.version); utc(workspace.createdAt, 'workspace.createdAt')
  }
  return body as unknown as WorkspaceListResponse
}

export function parseTaskSnapshot(value: unknown): TaskSnapshot {
  const task = exactObject(value, [
    'taskId', 'workspaceId', 'taskType', 'capabilityCode', 'title', 'state', 'version',
    'allowedCommands', 'steps', 'reasonCode', 'createdAt', 'updatedAt', 'correlationId'
  ], 'TaskSnapshot', ['reasonCode'])
  uuid(task.taskId, 'taskId'); uuid(task.workspaceId, 'task.workspaceId')
  if (!((task.taskType === 'SYNTHETIC_CORE' && task.capabilityCode === 'CORE') || (['JD_DETAIL_CAPTURE', 'MANUAL_COLLECTION_ENTRY'].includes(String(task.taskType)) && task.capabilityCode === 'COL'))) throw new TypeError('当前 Task 类型或能力非法')
  nonEmpty(task.title, 'task.title', 120)
  if (typeof task.state !== 'string' || !TASK_STATES.has(task.state)) throw new TypeError('task.state 非法')
  parseCanonicalInt64(task.version)
  if (!Array.isArray(task.allowedCommands) || task.allowedCommands.some((item) => !['SUBMIT_FOR_VALIDATION', 'REQUEST_CANCEL'].includes(item))) {
    throw new TypeError('task.allowedCommands 非法')
  }
  if (task.capabilityCode === 'COL' && task.allowedCommands.length !== 0) throw new TypeError('采集任务仅允许只读投影')
  if (!Array.isArray(task.steps)) throw new TypeError('task.steps 非法')
  for (const stepValue of task.steps) {
    const step = record(stepValue, 'TaskStepSnapshot')
    uuid(step.stepId, 'stepId'); parseCanonicalInt64(step.version)
  }
  utc(task.createdAt, 'task.createdAt'); utc(task.updatedAt, 'task.updatedAt'); uuid(task.correlationId, 'task.correlationId')
  return task as unknown as TaskSnapshot
}

function parseRunner(value: unknown): RunnerSummary {
  const runner = exactObject(value, ['runnerId', 'displayName', 'presence', 'capabilities', 'currentSessionEpoch', 'lastSeenAt'], 'RunnerSummary', ['lastSeenAt'])
  uuid(runner.runnerId, 'runnerId'); nonEmpty(runner.displayName, 'runner.displayName', 80)
  if (typeof runner.presence !== 'string' || !RUNNER_STATES.has(runner.presence)) throw new TypeError('runner.presence 非法')
  if (!Array.isArray(runner.capabilities) || runner.capabilities.some((item) => typeof item !== 'string' || !CAPABILITIES.has(item))) throw new TypeError('runner.capabilities 非法')
  parseCanonicalInt64(runner.currentSessionEpoch)
  if (runner.lastSeenAt !== null && runner.lastSeenAt !== undefined) utc(runner.lastSeenAt, 'runner.lastSeenAt')
  return runner as unknown as RunnerSummary
}

function parseCapability(value: unknown): string {
  const capability = exactObject(value, ['capabilityCode', 'status', 'nextGate', 'externalActionsEnabled', 'projectionVersion'], 'CapabilityProjection')
  if (typeof capability.capabilityCode !== 'string' || !CAPABILITIES.has(capability.capabilityCode)) throw new TypeError('capabilityCode 非法')
  if (typeof capability.status !== 'string' || !CAPABILITY_STATES.has(capability.status)) throw new TypeError('capability.status 非法')
  if (capability.externalActionsEnabled !== false) throw new TypeError('externalActionsEnabled 当前只能为 false')
  if (capability.nextGate !== null) nonEmpty(capability.nextGate, 'nextGate', 240)
  parseCanonicalInt64(capability.projectionVersion)
  void (capability as unknown as CapabilityProjection)
  return capability.capabilityCode
}

function exactObject(value: unknown, fields: readonly string[], label: string, optional: readonly string[] = []): Record<string, unknown> {
  const object = record(value, label)
  const allowed = new Set(fields)
  if (Object.keys(object).some((key) => !allowed.has(key))) throw new TypeError(`${label} 包含未知字段`)
  if (fields.some((field) => !optional.includes(field) && !(field in object))) throw new TypeError(`${label} 缺少必填字段`)
  return object
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${label} 必须是 object`)
  return value as Record<string, unknown>
}

function nonEmpty(value: unknown, label: string, max: number): asserts value is string {
  if (typeof value !== 'string' || value.length < 1 || value.length > max) throw new TypeError(`${label} 非法`)
}
function uuid(value: unknown, label: string): asserts value is string {
  if (typeof value !== 'string' || !UUID.test(value)) throw new TypeError(`${label} 必须是 UUID`)
}
function sha(value: unknown, label: string): asserts value is string {
  if (typeof value !== 'string' || !SHA256.test(value)) throw new TypeError(`${label} 必须是 SHA-256`)
}
function utc(value: unknown, label: string): asserts value is string {
  if (typeof value !== 'string' || !UTC.test(value)) throw new TypeError(`${label} 必须是 UTC date-time`)
}
