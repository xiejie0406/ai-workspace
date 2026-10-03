/** 只缓存实际读过的条件版本；不持有页面数据，不回读最新版本替旧编辑解锁。 */
export interface WorkflowTransportRequest {
  url?: string;
  method?: string;
  baseURL?: string;
  params?: unknown;
  data?: unknown;
  headers?: unknown;
}
type JsonRecord = Record<string, unknown>;
type Kind = 'read' | 'list' | 'create' | 'write' | 'toggle' | 'version' | 'restore' | 'delete';
interface Entry { id: string; flowId: string; revision: string; stamp: number }
interface AgentEntry { id: string; revision: string; stamp: number; workflowId?: string; workflowRevision?: string; flowId?: string }
interface ModelEntry { id: string; revision: string; stamp: number; detailRevision?: string }
export interface WorkflowRevisionTicket {
  readonly epoch: number;
  readonly sequence: number;
  readonly kind: Kind;
  readonly id?: string;
  readonly flowId?: string;
  readonly expectedRevision?: string;
  readonly stamp?: number;
  readonly resource?: 'agent' | 'model';
  readonly generation?: number;
  readonly modelReceipt?: 'validation' | 'boolean';
  readonly workflowId?: string;
  readonly expectedWorkflowRevision?: string;
}
const record = (value: unknown): JsonRecord | undefined => {
  if (typeof value === 'string') {
    try { return record(JSON.parse(value)); } catch { return undefined; }
  }
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonRecord : undefined;
};
const integer = (value: unknown, positive = false): string | undefined => {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return undefined;
  if (typeof value !== 'number' && typeof value !== 'string') return undefined;
  const text = String(value);
  if (!/^\d{1,19}$/.test(text)) return undefined;
  const parsed = BigInt(text);
  return parsed < (positive ? 1n : 0n) || parsed > 9223372036854775807n
    ? undefined : parsed.toString();
};
const flow = (value: unknown): string | undefined =>
  typeof value === 'string' && value.length > 0 && value.length <= 256 && value.trim() === value ? value : undefined;
// 原默认个人区的spaceId为null/缺省，原store使用空字符串；正ID仍必须精确匹配。
const spaceText = (value: unknown): string | undefined => {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'string' && !/^[1-9]\d{0,18}$/.test(value)) return undefined;
  return integer(value, true);
};
const header = (headers: unknown, name: string): unknown => {
  const values = record(headers);
  if (!values) return undefined;
  if (typeof values.get === 'function') return values.get.call(headers, name);
  const key = Object.keys(values).find(key => key.toLowerCase() === name.toLowerCase());
  return key === undefined ? undefined : values[key];
};
const etag = (value: unknown): string | undefined =>
  typeof value === 'string' && /^"\d{1,19}"$/.test(value) ? integer(value.slice(1, -1)) : undefined;
const modelEtag = (value: unknown): { id: string; revision: string } | undefined => {
  const match = typeof value === 'string' && /^"paiflow-model-(\d{1,19})-(\d{1,19})"$/.exec(value);
  if (!match) return undefined;
  const id = integer(match[1], true), revision = integer(match[2]);
  return id && revision !== undefined ? { id, revision } : undefined;
};
const parameter = (request: WorkflowTransportRequest, url: URL, key: string): unknown => {
  const params = record(request.params);
  const fromConfig = params && typeof params.get === 'function' ? params.get.call(request.params, key) : params?.[key];
  const fromUrl = url.searchParams.get(key);
  if (fromConfig !== undefined && fromConfig !== null && fromUrl !== null && String(fromConfig) !== fromUrl) return undefined;
  return fromConfig ?? fromUrl ?? undefined;
};
const setHeader = (request: WorkflowTransportRequest, name: string, revision: string): void => {
  const headers = record(request.headers) || {};
  if (typeof headers.set === 'function') headers.set.call(request.headers, name, `"${revision}"`);
  else headers[name] = `"${revision}"`;
  request.headers = headers;
};
const condition = (request: WorkflowTransportRequest, name: string, supplied: unknown, provided: boolean, cached?: string): string | undefined => {
  const explicit = header(request.headers, name);
  if (explicit !== undefined && explicit !== null) {
    const revision = etag(explicit);
    return provided && revision !== integer(supplied) ? undefined : revision;
  }
  if (provided) return integer(supplied);
  if (cached !== undefined) setHeader(request, name, cached);
  return cached;
};
const responseRevision = (data: JsonRecord, field: string, headers: unknown, name: string): string | undefined => {
  const body = integer(data[field]);
  const raw = header(headers, name);
  const revision = raw === undefined || raw === null ? body : etag(raw);
  return data[field] !== undefined && revision !== body ? undefined : revision;
};
const formBody = (request: WorkflowTransportRequest): JsonRecord | undefined => {
  if (typeof request.data !== 'string') return record(request.data);
  const contentType = header(request.headers, 'Content-Type');
  if (typeof contentType !== 'string' || !contentType.toLowerCase().startsWith('application/x-www-form-urlencoded')) return record(request.data);
  const parsed = new URLSearchParams(request.data), result: JsonRecord = {};
  for (const key of ['botId', 'revision', 'workflowRevision']) {
    const values = parsed.getAll(key);
    if (values.length > 1) return undefined;
    if (values.length === 1) result[key] = values[0];
  }
  return result;
};

export class WorkflowRevisionTransport {
  private epoch = 0;
  private sequence = 0;
  private session = '';
  private space = '';
  private readonly entries = new Map<string, Entry>();
  private readonly flows = new Map<string, string>();
  private readonly reads = new Map<string, number>();
  private readonly agents = new Map<string, AgentEntry>();
  private readonly agentReads = new Map<string, number>();
  private readonly models = new Map<string, ModelEntry>();
  private readonly modelReads = new Map<string, number>();
  private modelListRead = 0;
  private modelGeneration = 0;
  constructor(private readonly limit = 128) {
    if (!Number.isInteger(limit) || limit < 1) throw new Error('版本缓存上限无效');
  }
  setScope(session: string | null, space: string): void {
    if (this.session === (session || '') && this.space === space) return;
    this.clear(); this.session = session || ''; this.space = space;
  }
  clear(): void {
    this.epoch++; this.entries.clear(); this.flows.clear(); this.reads.clear(); this.agents.clear(); this.agentReads.clear();
    this.models.clear(); this.modelReads.clear(); this.modelListRead = 0; this.modelGeneration++;
  }
  get size(): number { return this.entries.size + this.agents.size + this.models.size; }
  prepare(request: WorkflowTransportRequest, centre: string, origin: string): WorkflowRevisionTicket | undefined {
    const source = request.url;
    // 仅识别原相对API，绝不向外部URL或另一个Axios baseURL附加条件头。
    if (!this.session || !source?.startsWith('/') || source.startsWith('//') || /[\\\u0000-\u0020]/.test(source)) return undefined;
    let url: URL;
    try {
      const expected = new URL(centre, origin).href.replace(/\/$/, '');
      if (new URL(request.baseURL || centre, origin).href.replace(/\/$/, '') !== expected) return undefined;
      url = new URL(source, origin);
      // Axios把相对API拼在baseURL后，浏览器URL却会折叠dot段；只认原始精确路径。
      if (source.split('?')[0] !== url.pathname || url.hash) return undefined;
    } catch { return undefined; }
    const method = (request.method || 'get').toLowerCase();
    const body = record(request.data);
    if ((method === 'get' && ['/api/model/detail', '/api/model/on', '/api/model/off', '/api/model/delete'].includes(url.pathname))
      || (method === 'post' && ['/api/model', '/api/model/local-model', '/api/model/list'].includes(url.pathname))) {
      return this.prepareModel(request, url, method, body);
    }
    if (method === 'post' && ['/my-bot/bot-detail', '/bot/create', '/bot/update', '/workflow/base-save', '/my-bot/delete'].includes(url.pathname)) {
      return this.prepareAgent(request, url);
    }
    let kind: Kind;
    if (url.pathname === '/workflow' && method === 'get') kind = 'read';
    else if (url.pathname === '/workflow' && method === 'post') kind = 'create';
    else if (url.pathname === '/workflow' && method === 'put') kind = 'write';
    else if (url.pathname === '/workflow' && method === 'delete') kind = 'delete';
    else if (url.pathname === '/workflow/version' && method === 'post') kind = 'version';
    else if (url.pathname === '/workflow/version/restore' && method === 'post') kind = 'restore';
    else if (url.pathname === '/workflow/build' && method === 'post') kind = 'write';
    else return undefined;
    let id = kind === 'read' || kind === 'delete' ? integer(parameter(request, url, 'id'), true)
      : kind === 'write' ? integer(body?.id, true) : undefined;
    const flowId = kind === 'create' || kind === 'read' || kind === 'delete' ? undefined : flow(body?.flowId);
    const mappedId = flowId ? this.flows.get(flowId) : undefined;
    if (id && mappedId && id !== mappedId) return undefined;
    id ||= mappedId;
    if (kind !== 'create' && !id && !flowId) return undefined;
    if ((kind === 'read' || kind === 'delete' || kind === 'write') && !id) return undefined;
    const entry = id ? this.entries.get(id) : undefined;
    const sequence = ++this.sequence;
    if (kind === 'read' && id) {
      this.reads.delete(id); this.reads.set(id, sequence);
      while (this.reads.size > this.limit) this.reads.delete(this.reads.keys().next().value!);
    }
    let expectedRevision: string | undefined;
    if (kind !== 'read' && kind !== 'create') {
      const supplied = kind === 'delete' ? parameter(request, url, 'revision') : body?.revision;
      const hasRevision = kind === 'delete' ? supplied !== undefined : !!body && Object.prototype.hasOwnProperty.call(body, 'revision');
      expectedRevision = condition(request, 'If-Match', supplied, hasRevision, entry?.revision);
    }
    return Object.freeze({ epoch: this.epoch, sequence, kind, id, flowId, expectedRevision, stamp: entry?.stamp });
  }
  /** false表示该跟踪响应不得再交付原页面，防止旧画布配上较新的缓存条件自动保存。 */
  accept(ticket: WorkflowRevisionTicket | undefined, result: unknown, headers: unknown): boolean {
    if (!ticket) return true;
    if (ticket.epoch !== this.epoch || !this.session) return false;
    const envelope = record(result);
    if (envelope?.code !== 0) return true;
    if (ticket.resource === 'agent') return this.acceptAgent(ticket, envelope, headers);
    if (ticket.resource === 'model') return this.acceptModel(ticket, envelope, headers);
    const existing = ticket.id ? this.entries.get(ticket.id) : undefined;
    if (ticket.kind === 'delete') {
      if (ticket.expectedRevision === undefined || existing?.stamp !== ticket.stamp || (existing && existing.revision !== ticket.expectedRevision)) return false;
      if (existing) this.remove(existing.id);
      return true;
    }
    const data = record(envelope.data);
    if (!data || spaceText(data.spaceId) !== this.space) return false;
    const id = integer(ticket.kind === 'version' ? data.workflowId : data.id, true);
    const flowId = flow(data.flowId);
    if (!id || !flowId || (ticket.id && id !== ticket.id) || (ticket.flowId && flowId !== ticket.flowId)) return false;
    const alias = this.flows.get(flowId);
    if (alias && alias !== id) return false;
    const bodyRevision = integer(data.revision);
    const responseHeader = header(headers, 'ETag');
    const revision = responseHeader === undefined || responseHeader === null ? bodyRevision : etag(responseHeader);
    if (revision === undefined || (data.revision !== undefined && revision !== bodyRevision)) return false;
    const current = this.entries.get(id);
    if (ticket.kind === 'read') {
      if (this.reads.get(id) !== ticket.sequence || current?.stamp !== ticket.stamp) return false;
      if (current && BigInt(revision) < BigInt(current.revision)) return false;
    } else if (ticket.kind === 'create') {
      if (current) return false;
    } else {
      if (ticket.expectedRevision === undefined || BigInt(revision) <= BigInt(ticket.expectedRevision)) return false;
      if (current?.stamp !== ticket.stamp || (current && current.revision !== ticket.expectedRevision)) return false;
    }
    if (current && current.flowId !== flowId) return false;
    this.entries.delete(id); this.entries.set(id, { id, flowId, revision, stamp: ticket.sequence }); this.flows.set(flowId, id);
    this.trim();
    return true;
  }
  private prepareAgent(request: WorkflowTransportRequest, url: URL): WorkflowRevisionTicket | undefined {
    const params = record(request.params);
    for (const key of ['botId', 'revision', 'workflowRevision']) {
      const values = url.searchParams.getAll(key);
      const configured = params && typeof params.get === 'function' ? params.get.call(request.params, key) : params?.[key];
      if (values.length > 1 || (configured !== undefined && configured !== null && values.length === 1 && String(configured) !== values[0])) return undefined;
    }
    const body = url.pathname === '/my-bot/delete' ? formBody(request) : record(request.data);
    if (url.pathname === '/my-bot/delete' && request.data !== undefined && body === undefined) return undefined;
    const kind: Kind = url.pathname === '/my-bot/bot-detail' ? 'read'
      : url.pathname === '/my-bot/delete' ? 'delete'
      : url.pathname === '/bot/create' || (url.pathname === '/workflow/base-save' && (body?.botId === undefined || body.botId === null)) ? 'create' : 'write';
    const fromQuery = parameter(request, url, 'botId');
    const fromBody = body?.botId;
    if (fromQuery !== undefined && fromBody !== undefined && String(fromQuery) !== String(fromBody)) return undefined;
    const id = kind === 'create' ? undefined : integer(fromQuery ?? fromBody, true);
    if (kind !== 'create' && !id) return undefined;
    const entry = id ? this.agents.get(id) : undefined;
    const sequence = ++this.sequence;
    if (kind === 'read' && id) {
      this.agentReads.delete(id); this.agentReads.set(id, sequence);
      while (this.agentReads.size > this.limit) this.agentReads.delete(this.agentReads.keys().next().value!);
    }
    const readCondition = (field: string, name: string, cached?: string): string | undefined => {
      const query = parameter(request, url, field), value = body?.[field];
      if (query !== undefined && value !== undefined && String(query) !== String(value)) return undefined;
      const provided = query !== undefined || (!!body && Object.prototype.hasOwnProperty.call(body, field));
      return condition(request, name, query ?? value, provided, cached);
    };
    const write = kind === 'write' || kind === 'delete';
    return Object.freeze({ epoch: this.epoch, sequence, kind, resource: 'agent', id, stamp: entry?.stamp,
      expectedRevision: write ? readCondition('revision', 'If-Match', entry?.revision) : undefined,
      workflowId: entry?.workflowId,
      expectedWorkflowRevision: write ? readCondition('workflowRevision', 'X-Workflow-Revision', entry?.workflowRevision) : undefined });
  }
  private acceptAgent(ticket: WorkflowRevisionTicket, envelope: JsonRecord, headers: unknown): boolean {
    const existing = ticket.id ? this.agents.get(ticket.id) : undefined;
    if (ticket.kind === 'delete') {
      if (ticket.expectedRevision === undefined || existing?.stamp !== ticket.stamp || (existing && (existing.revision !== ticket.expectedRevision
        || existing.workflowRevision !== ticket.expectedWorkflowRevision))) return false;
      if (existing) this.removeAgent(existing.id);
      return true;
    }
    const data = record(envelope.data);
    if (!data || spaceText(data.spaceId) !== this.space) return false;
    const id = integer(data.botId ?? data.id, true);
    if (!id || (data.id !== undefined && integer(data.id, true) !== id) || (data.botId !== undefined && integer(data.botId, true) !== id) || (ticket.id && id !== ticket.id)) return false;
    const revision = responseRevision(data, 'revision', headers, 'ETag');
    if (revision === undefined) return false;
    const workflowId = integer(data.workflowId ?? data.maasId, true);
    if (data.workflowId !== undefined && data.maasId !== undefined && integer(data.workflowId, true) !== integer(data.maasId, true)) return false;
    if (data.workflowId !== undefined && data.workflowId !== null && integer(data.workflowId, true) !== workflowId) return false;
    if (data.maasId !== undefined && data.maasId !== null && integer(data.maasId, true) !== workflowId) return false;
    const workflowRevision = workflowId ? responseRevision(data, 'workflowRevision', headers, 'X-Workflow-Revision') : undefined;
    const flowId = workflowId ? flow(data.flowId) : undefined;
    if (workflowId && (workflowRevision === undefined || !flowId)) return false;
    if (!workflowId && ((data.workflowId !== undefined && data.workflowId !== null) || (data.maasId !== undefined && data.maasId !== null)
      || (data.workflowRevision !== undefined && data.workflowRevision !== null) || header(headers, 'X-Workflow-Revision') !== undefined)) return false;
    const current = this.agents.get(id);
    if (ticket.kind === 'read') {
      if (this.agentReads.get(id) !== ticket.sequence || current?.stamp !== ticket.stamp) return false;
      if (current && (BigInt(revision) < BigInt(current.revision) || (current.workflowRevision !== undefined && workflowRevision !== undefined && BigInt(workflowRevision) < BigInt(current.workflowRevision)))) return false;
    } else if (ticket.kind === 'create') {
      if (current) return false;
    } else {
      if (ticket.expectedRevision === undefined || BigInt(revision) <= BigInt(ticket.expectedRevision) || current?.stamp !== ticket.stamp || (current && current.revision !== ticket.expectedRevision)) return false;
      if (workflowId && (ticket.workflowId !== workflowId || ticket.expectedWorkflowRevision === undefined || current?.workflowRevision !== ticket.expectedWorkflowRevision || BigInt(workflowRevision!) <= BigInt(ticket.expectedWorkflowRevision))) return false;
    }
    if (current && (current.workflowId !== workflowId || current.flowId !== flowId)) return false;
    this.agents.delete(id); this.agents.set(id, { id, revision, workflowId, workflowRevision, flowId, stamp: ticket.sequence }); this.trim();
    return true;
  }
  private trim(): void {
    while (this.size > this.limit) {
      const workflow = this.entries.values().next().value as Entry | undefined;
      const agent = this.agents.values().next().value as AgentEntry | undefined;
      const model = this.models.values().next().value as ModelEntry | undefined;
      if (workflow && (!agent || workflow.stamp < agent.stamp) && (!model || workflow.stamp <= model.stamp)) this.remove(workflow.id);
      else if (agent && (!model || agent.stamp <= model.stamp)) this.removeAgent(agent.id);
      else if (model) this.removeModel(model.id);
    }
  }
  private prepareModel(request: WorkflowTransportRequest, url: URL, method: string, body?: JsonRecord): WorkflowRevisionTicket | undefined {
    const params = record(request.params);
    for (const key of ['modelId', 'id', 'revision', 'llmSource']) {
      const values = url.searchParams.getAll(key);
      const configured = params && typeof params.get === 'function' ? params.get.call(request.params, key) : params?.[key];
      if (values.length > 1 || (configured !== undefined && configured !== null && values.length === 1 && String(configured) !== values[0])) return undefined;
    }
    const source = parameter(request, url, 'llmSource');
    if (source !== undefined && integer(source) !== '0') return undefined;
    if (body?.spaceId !== undefined && spaceText(body.spaceId) !== this.space) return undefined;
    const kind: Kind = url.pathname === '/api/model/list' ? 'list' : url.pathname === '/api/model/detail' ? 'read'
      : url.pathname === '/api/model/delete' ? 'delete' : ['/api/model/on', '/api/model/off'].includes(url.pathname) ? 'toggle'
      : body?.id === undefined || body.id === null ? 'create' : 'write';
    if (kind === 'list' && integer(body?.type) !== '2') return undefined;
    const queryId = parameter(request, url, 'modelId'), bodyId = body?.id;
    if (queryId !== undefined && bodyId !== undefined && String(queryId) !== String(bodyId)) return undefined;
    const id = kind === 'create' || kind === 'list' ? undefined : integer(method === 'get' ? queryId : bodyId, true);
    if (kind !== 'create' && kind !== 'list' && !id) return undefined;
    const entry = id ? this.models.get(id) : undefined, sequence = ++this.sequence;
    if (kind === 'read' && id) {
      this.modelReads.delete(id); this.modelReads.set(id, sequence);
      while (this.modelReads.size > this.limit) this.modelReads.delete(this.modelReads.keys().next().value!);
    } else if (kind === 'list') this.modelListRead = sequence;
    let expectedRevision: string | undefined;
    if (id && ['write', 'toggle', 'delete'].includes(kind)) {
      const query = parameter(request, url, 'revision'), value = body?.revision;
      const provided = query !== undefined || (!!body && Object.prototype.hasOwnProperty.call(body, 'revision'));
      const explicit = header(request.headers, 'If-Match');
      if (query !== undefined && value !== undefined && String(query) !== String(value)) return undefined;
      if (explicit !== undefined && explicit !== null) {
        const tag = modelEtag(explicit);
        if (tag?.id === id && (!provided || integer(query ?? value) === tag.revision)) expectedRevision = tag.revision;
      } else if (provided) expectedRevision = integer(query ?? value);
      else {
        // 列表刷新只能推进列表按钮条件，不能替已打开的编辑表单解锁。
        expectedRevision = kind === 'write' ? entry?.detailRevision : entry?.revision;
        if (expectedRevision !== undefined) {
          const headers = record(request.headers) || {}, tag = `"paiflow-model-${id}-${expectedRevision}"`;
          if (typeof headers.set === 'function') headers.set.call(request.headers, 'If-Match', tag);
          else headers['If-Match'] = tag;
          request.headers = headers;
        }
      }
    }
    return Object.freeze({ epoch: this.epoch, sequence, kind, resource: 'model', id, stamp: entry?.stamp,
      expectedRevision, generation: this.modelGeneration,
      modelReceipt: kind === 'read' || kind === 'list' ? undefined : url.pathname === '/api/model' ? 'validation' : 'boolean' });
  }
  private modelData(value: unknown): { id: string; revision: string } | undefined {
    const data = record(value);
    if (!data || spaceText(data.spaceId) !== this.space) return undefined;
    const id = integer(data.id ?? data.modelId, true), revision = integer(data.revision);
    if (!id || revision === undefined || (data.id !== undefined && integer(data.id, true) !== id)
      || (data.modelId !== undefined && integer(data.modelId, true) !== id) || (data.llmSource !== undefined && integer(data.llmSource) !== '0')) return undefined;
    return { id, revision };
  }
  private acceptModel(ticket: WorkflowRevisionTicket, envelope: JsonRecord, headers: unknown): boolean {
    if (ticket.kind === 'list') {
      const page = record(envelope.data);
      if (ticket.sequence !== this.modelListRead || ticket.generation !== this.modelGeneration || !Array.isArray(page?.records) || page.records.length > 200) return false;
      const rows = page.records.map(value => this.modelData(value));
      const seen = new Set<string>();
      for (const row of rows) {
        if (!row || seen.has(row.id)) return false;
        seen.add(row.id); const current = this.models.get(row.id);
        if (current && (current.stamp > ticket.sequence || BigInt(row.revision) < BigInt(current.revision))) return false;
      }
      for (const row of rows) {
        const current = this.models.get(row!.id);
        this.models.delete(row!.id); this.models.set(row!.id, { ...row!, stamp: ticket.sequence, detailRevision: current?.detailRevision });
      }
      this.trim(); return true;
    }
    const current = ticket.id ? this.models.get(ticket.id) : undefined;
    if (ticket.kind === 'delete') {
      const raw = header(headers, 'ETag'), receipt = modelEtag(raw);
      if (envelope.data !== true || ticket.expectedRevision === undefined || current?.stamp !== ticket.stamp
        || (current && current.revision !== ticket.expectedRevision)
        || (raw !== undefined && raw !== null && (!receipt || receipt.id !== ticket.id || BigInt(receipt.revision) <= BigInt(ticket.expectedRevision)))) return false;
      if (ticket.id) this.removeModel(ticket.id);
      this.modelGeneration++; return true;
    }
    if (ticket.kind !== 'read' && envelope.data !== (ticket.modelReceipt === 'validation' ? 'Model validation passed' : true)) return false;
    const data = record(envelope.data), body = data ? this.modelData(data) : undefined;
    const raw = header(headers, 'ETag'), tag = modelEtag(raw);
    if (raw !== undefined && raw !== null && !tag) return false;
    const identity = tag || body;
    if (!identity || (data && (!body || (tag && (tag.id !== body.id || tag.revision !== body.revision)))) || (ticket.id && identity.id !== ticket.id)) return false;
    const existing = this.models.get(identity.id);
    if (ticket.kind === 'read') {
      if (!body || this.modelReads.get(identity.id) !== ticket.sequence || existing?.stamp !== ticket.stamp
        || (existing && BigInt(identity.revision) < BigInt(existing.revision))) return false;
    } else if (ticket.kind === 'create') {
      if (existing) return false;
    } else if (ticket.expectedRevision === undefined || BigInt(identity.revision) <= BigInt(ticket.expectedRevision)
      || existing?.stamp !== ticket.stamp || (existing && existing.revision !== ticket.expectedRevision)) return false;
    const detailRevision = ticket.kind === 'toggle' ? existing?.detailRevision : identity.revision;
    this.models.delete(identity.id); this.models.set(identity.id, { ...identity, stamp: ticket.sequence, detailRevision });
    if (ticket.kind !== 'read') this.modelGeneration++;
    this.trim(); return true;
  }
  private removeModel(id: string): void { this.models.delete(id); this.modelReads.delete(id); }
  private removeAgent(id: string): void { this.agents.delete(id); this.agentReads.delete(id); }
  private remove(id: string): void {
    const entry = this.entries.get(id);
    if (entry) this.flows.delete(entry.flowId);
    this.entries.delete(id); this.reads.delete(id);
  }
}
