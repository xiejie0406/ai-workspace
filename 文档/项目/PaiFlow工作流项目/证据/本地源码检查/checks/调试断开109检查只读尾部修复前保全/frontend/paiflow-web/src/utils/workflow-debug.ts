/** 原调试调用的单次传输上下文；版本、运行和检查点真相均来自中心回执。 */
type ObjectValue = Record<string, unknown>;
export interface DebugReceipt {
  workflowVersionId: string;
  workflowId: string;
  flowId: string;
  mode: 'ordinary' | 'comparison';
  comparisonId: string | null;
  buildRequestKey: string;
  snapshotRevision: string;
  dslHash: string;
  engineProfile: string;
  executionCodec: string;
}
export interface DebugBuildExpectation {
  workflowId: unknown;
  flowId: unknown;
  spaceId: unknown;
  mode: DebugReceipt['mode'];
  comparisonId: string | null;
  buildRequestKey: string;
}
export interface DebugCheckpoint {
  eventId: string;
  nodeId: string;
  revision: string;
  state: 'WAITING';
  replyKey: string;
  replyBody?: string;
}
const object = (value: unknown): ObjectValue | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as ObjectValue : undefined;
const integer = (value: unknown, positive = false): string | undefined => {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return undefined;
  if (typeof value !== 'string' && typeof value !== 'number') return undefined;
  const text = String(value);
  if (!/^(0|[1-9]\d{0,18})$/.test(text)) return undefined;
  const number = BigInt(text);
  return number > 9223372036854775807n || number < (positive ? 1n : 0n) ? undefined : text;
};
const identifier = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const key = (value: unknown): value is string => identifier(value) && value.length >= 8;
// 原执行节点ID包含类型前缀和::，不能套Run/检查点UUID的格式规则。
const nodeId = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 255;
const space = (value: unknown): string | undefined => value === null || value === undefined || value === '' ? '' : integer(value, true);
export class WorkflowDebugProtocolError extends Error {}
function fail(): never { throw new WorkflowDebugProtocolError('调试回执或运行上下文不匹配，请重新打开并构建当前工作流'); }

export function readDebugReceipt(value: unknown, expected: DebugBuildExpectation): Readonly<DebugReceipt> {
  const data = object(value), receipt = object(data?.debugReceipt);
  if (!data || !receipt || !Object.prototype.hasOwnProperty.call(data, 'spaceId') || typeof data.id !== 'string' || data.id !== integer(expected.workflowId, true)
    || data.flowId !== expected.flowId || space(data.spaceId) === undefined || space(data.spaceId) !== space(expected.spaceId)
    || receipt.workflowId !== data.id || receipt.flowId !== expected.flowId || !identifier(receipt.flowId)
    || typeof receipt.workflowVersionId !== 'string' || !integer(receipt.workflowVersionId, true)
    || typeof receipt.snapshotRevision !== 'string' || !integer(receipt.snapshotRevision)
    || data.revision !== receipt.snapshotRevision || receipt.mode !== expected.mode
    || receipt.comparisonId !== expected.comparisonId || (receipt.comparisonId !== null && !identifier(receipt.comparisonId))
    || !key(receipt.buildRequestKey) || receipt.buildRequestKey !== expected.buildRequestKey
    || typeof receipt.dslHash !== 'string' || !/^[a-f0-9]{64}$/.test(receipt.dslHash)
    || !identifier(receipt.engineProfile) || typeof receipt.executionCodec !== 'string'
    || !['paiflow.execution.v1', 'paiflow.execution.v2', 'paiflow.execution.v3'].includes(receipt.executionCodec)) fail();
  // 复制已核对的字段，避免外部响应对象后续变化改变请求绑定。
  return Object.freeze({ workflowVersionId: receipt.workflowVersionId, workflowId: receipt.workflowId,
    flowId: receipt.flowId, mode: receipt.mode, comparisonId: receipt.comparisonId,
    buildRequestKey: receipt.buildRequestKey, snapshotRevision: receipt.snapshotRevision,
    dslHash: receipt.dslHash, engineProfile: receipt.engineProfile, executionCodec: receipt.executionCodec } as DebugReceipt);
}

export class WorkflowDebugContext {
  readonly controller = new AbortController();
  readonly chatBody: string;
  runId?: string;
  lastEventId?: string;
  checkpoint?: DebugCheckpoint;
  waiting = false;
  terminal = false;
  private sequence = 0n;
  private readonly cleanups: Array<() => void> = [];
  private streamController?: AbortController;
  constructor(readonly receipt: Readonly<DebugReceipt>, readonly scopeStamp: string,
    private readonly currentScope: () => string, readonly requestKey: string,
    originalChat: ObjectValue, private readonly makeKey: () => string) {
    if (!key(requestKey) || originalChat.flow_id !== receipt.flowId
      || (receipt.mode === 'comparison' ? originalChat.version !== receipt.comparisonId || originalChat.promptDebugger !== true
        : originalChat.version !== undefined || originalChat.promptDebugger === true)) fail();
    this.chatBody = JSON.stringify({ ...originalChat, debugReceipt: receipt, requestKey });
    this.controller.signal.addEventListener('abort', () => {
      this.streamController?.abort();
      this.cleanups.splice(0).forEach(cleanup => cleanup());
    }, { once: true });
  }
  alive(): boolean {
    if (this.currentScope() !== this.scopeStamp) this.detach();
    return !this.controller.signal.aborted && !this.terminal;
  }
  addCleanup(cleanup: () => void): () => void {
    if (this.controller.signal.aborted) cleanup(); else this.cleanups.push(cleanup);
    return () => { const index = this.cleanups.indexOf(cleanup); if (index >= 0) this.cleanups.splice(index, 1); };
  }
  detach(): void { this.controller.abort(); }
  nextStream(): AbortSignal {
    if (!this.alive()) fail();
    this.streamController?.abort();
    this.streamController = new AbortController();
    return this.streamController.signal;
  }
  open(response: Pick<Response, 'status' | 'headers'>): void {
    if (!this.alive() || response.status !== 200 || response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'text/event-stream') fail();
    const runId = response.headers.get('X-PaiFlow-Run-Id');
    if (!key(runId) || response.headers.get('X-PaiFlow-Workflow-Version-Id') !== this.receipt.workflowVersionId
      || (this.runId !== undefined && this.runId !== runId)) fail();
    this.runId = runId;
  }
  /** 持久事件序号去重后才交原消费者；EOF本身不产生终态。 */
  frame(id: string, data: string): boolean {
    if (!this.alive() || !this.runId) fail();
    const prefix = `${this.runId}:`;
    const number = id.startsWith(prefix) ? integer(id.slice(prefix.length), true) : undefined;
    if (!number) fail();
    const sequence = BigInt(number);
    // 投影器可能跳过不供原页面消费的中心事件，允许序号有间隔。
    let parsed: unknown;
    try { parsed = JSON.parse(data); } catch { fail(); }
    const frame = object(parsed), choices = frame?.choices;
    const choice = Array.isArray(choices) && choices.length === 1 ? object(choices[0]) : undefined;
    if (!frame || !choice || ![null, 'interrupt', 'stop'].includes(choice.finish_reason as null | string)) fail();
    if (choice.finish_reason === 'interrupt') {
      const event = object(frame.event_data), step = object(frame.workflow_step), node = object(step?.node);
      if (!event || !identifier(event.event_id) || typeof event.checkpoint_revision !== 'string' || !integer(event.checkpoint_revision)
        || typeof event.checkpoint_state !== 'string' || !['WAITING', 'RESUMED', 'CONSUMED', 'EXPIRED', 'CANCELLED'].includes(event.checkpoint_state)
        || typeof event.checkpoint_waiting !== 'boolean' || !nodeId(node?.id)) fail();
      if (event.checkpoint_waiting && event.checkpoint_state !== 'WAITING') fail();
      if (!event.checkpoint_waiting) {
        if (this.checkpoint?.eventId === event.event_id) { this.checkpoint = undefined; this.waiting = false; }
        else if (!this.checkpoint) this.waiting = false;
        if (sequence > this.sequence) { this.sequence = sequence; this.lastEventId = id; }
        return false;
      }
      if (sequence <= this.sequence) return false;
      const prior = this.checkpoint;
      if (prior?.eventId === event.event_id && (prior.revision !== event.checkpoint_revision || prior.nodeId !== node.id)) fail();
      if (!prior || prior.eventId !== event.event_id) {
        const replyKey = this.makeKey(); if (!key(replyKey)) fail();
        this.checkpoint = { eventId: event.event_id, nodeId: node.id, revision: event.checkpoint_revision, state: 'WAITING', replyKey };
      }
      this.waiting = true;
    } else if (sequence <= this.sequence) return false;
    else if (choice.finish_reason === 'stop') {
      if (![0, 500, 499].includes(frame.code as number)) fail();
      this.terminal = true; this.waiting = false;
      this.streamController?.abort();
      this.cleanups.splice(0).forEach(cleanup => cleanup());
    }
    this.sequence = sequence; this.lastEventId = id;
    return true;
  }
  reply(eventId: string, eventType: 'resume' | 'ignore' | 'abort', content: unknown): string {
    if (!this.alive() || !this.runId || !this.checkpoint || this.checkpoint.eventId !== eventId || typeof content !== 'string') fail();
    const cp = this.checkpoint;
    const body = JSON.stringify({ flow_id: this.receipt.flowId, eventId, eventType, content,
      ...(this.receipt.mode === 'comparison' ? { version: this.receipt.comparisonId, promptDebugger: true } : {}),
      runId: this.runId, debugReceipt: this.receipt, expectedRevision: cp.revision, replyKey: cp.replyKey });
    if (cp.replyBody !== undefined && cp.replyBody !== body) throw new Error('该检查点已有固定回复，请等待原回复结果');
    cp.replyBody = body; this.waiting = false;
    return body;
  }
  cancelBody(): string {
    if (!this.alive() || !this.runId) fail();
    return JSON.stringify({ runId: this.runId, flow_id: this.receipt.flowId, debugReceipt: this.receipt,
      ...(this.receipt.mode === 'comparison' ? { version: this.receipt.comparisonId, promptDebugger: true } : {}) });
  }
  acknowledge(value: unknown, abortReply: boolean): void {
    const envelope = object(value), data = object(envelope?.data);
    if (!this.alive() || envelope?.code !== 0 || !data || data.runId !== this.runId || typeof data.state !== 'string'
      || (abortReply ? data.checkpointId !== this.checkpoint?.eventId || !['RESUMED', 'CONSUMED', 'EXPIRED', 'CANCELLED'].includes(data.state)
        : !['CANCEL_REQUESTED', 'COMPLETED', 'FAILED', 'EFFECT_UNKNOWN', 'CANCELLED'].includes(data.state))) fail();
  }
}
