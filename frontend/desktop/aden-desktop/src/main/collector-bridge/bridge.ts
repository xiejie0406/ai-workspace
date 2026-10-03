import { spawn, spawnSync, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { join } from 'node:path'
import { createHash, randomBytes } from 'node:crypto'
import type { SessionController, SessionContext } from '../auth/session-controller'
import type { AdenApiClient } from '../transport/api-client'
import { assertCollectorSession, collectorUuid, COLLECTOR_EXTENSION_ID, MAX_COLLECTOR_FRAME, parseCollectorRequest } from './protocol'
import { validateCaptureExtraction } from './extraction'
import type { CollectionLibraryTarget } from '../../shared/contracts/collection'

export interface CollectorBridgeOptions {
  helperPath: string
  session: SessionController
  api: AdenApiClient
  confirmPair: (workspaceId: string) => Promise<boolean>
  openLibrary: (target?: CollectionLibraryTarget) => void
  onFailure?: (code: string) => void
  pipePath?: string
}

/** 不监听TCP。带当前用户ACL的Windows管道由随包helper创建。 */
export class CollectorBridge {
  #child: ChildProcessWithoutNullStreams | null = null
  #context: SessionContext | null = null
  #connectionGeneration = 0
  #buffer = ''
  #queue: Promise<void> = Promise.resolve()
  #disposed = false
  #nonce: string | null = null
  #expiresAt: string | null = null
  #lastSequence = -1n
  readonly #acknowledgements = new Map<string, { fingerprint: string; response: Record<string, unknown> }>()

  #resetProof(): void {
    this.#nonce = null; this.#expiresAt = null; this.#lastSequence = -1n; this.#acknowledgements.clear()
  }

  constructor(readonly options: CollectorBridgeOptions) {}

  start(): void {
    if (process.platform !== 'win32' || this.#child) return
    const systemRoot = process.env.SystemRoot ?? 'C:\\Windows'
    let pipeName: string
    if (this.options.pipePath) {
      const prefix = '\\\\.\\pipe\\'
      if (!this.options.pipePath.startsWith(prefix)) throw new Error('采集管道路径必须是本机命名管道')
      pipeName = this.options.pipePath.slice(prefix.length)
    } else {
      const identity = spawnSync(join(systemRoot, 'System32', 'whoami.exe'), ['/user', '/fo', 'csv', '/nh'], {
        encoding: 'utf8', windowsHide: true, timeout: 5000
      })
      const sid = identity.stdout?.match(/S-1-5-(?:\d+-)*\d+/)?.[0]
      if (identity.status !== 0 || !sid) throw new Error('无法确定当前Windows用户')
      pipeName = `aden-collector-${sid}`
    }
    if (!/^aden-collector-[A-Za-z0-9-]{1,120}$/.test(pipeName)) throw new Error('采集管道名称不合法')
    const child = spawn(join(systemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'), [
      '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', this.options.helperPath, '-PipeName', pipeName
    ], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
    this.#child = child
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (data: string) => this.#receive(data))
    // 只报告稳定错误码，不把用户页面或PowerShell原始输出送入日志。
    child.stderr.on('data', () => this.options.onFailure?.('COLLECTOR_PIPE_ERROR'))
    child.on('error', () => this.options.onFailure?.('COLLECTOR_PIPE_UNAVAILABLE'))
    child.on('exit', () => {
      this.#context = null
      this.#child = null
      this.#resetProof()
      this.#connectionGeneration++
      if (!this.#disposed) this.options.onFailure?.('COLLECTOR_PIPE_STOPPED')
    })
  }

  dispose(): void {
    this.#disposed = true
    this.#context = null
    this.#connectionGeneration++
    this.#resetProof()
    this.#child?.stdin.end()
    this.#child?.kill()
    this.#child = null
  }

  #receive(data: string): void {
    this.#buffer += data
    if (Buffer.byteLength(this.#buffer) > MAX_COLLECTOR_FRAME * 3) {
      this.dispose()
      this.options.onFailure?.('COLLECTOR_PIPE_FRAME_LIMIT')
      return
    }
    let newline: number
    while ((newline = this.#buffer.indexOf('\n')) >= 0) {
      const line = this.#buffer.slice(0, newline).trim()
      this.#buffer = this.#buffer.slice(newline + 1)
      if (!line) continue
      let event: { kind?: string; message?: unknown }
      try { event = JSON.parse(line) } catch { this.dispose(); return }
      if (event.kind === 'connected' || event.kind === 'disconnected') {
        this.#context = null
        this.#connectionGeneration++
        this.#resetProof()
      } else if (event.kind === 'message') {
        const connectionGeneration = this.#connectionGeneration
        this.#queue = this.#queue.then(async () => {
          const response = await this.handle(event.message)
          if (connectionGeneration === this.#connectionGeneration && !this.#disposed) {
            const body = JSON.stringify(response)
            if (Buffer.byteLength(body) > MAX_COLLECTOR_FRAME) {
              this.#child?.stdin.write(JSON.stringify({ protocolVersion: 1, messageId: response.messageId, ok: false,
                error: { code: 'RESPONSE_TOO_LARGE', message: '响应过大，请在桌面端查看' } }) + '\n')
            } else this.#child?.stdin.write(body + '\n')
          }
        }).catch(() => { this.options.onFailure?.('COLLECTOR_BRIDGE_FAILED') })
      }
    }
  }

  async handle(value: unknown): Promise<Record<string, unknown>> {
    let messageId = 'invalid'
    try {
      const request = parseCollectorRequest(value)
      messageId = request.messageId
      if (request.type !== 'hello') {
        assertCollectorSession(this.#context, this.options.session.context())
        if (!this.#nonce || request.nonce !== this.#nonce || request.expiresAt !== this.#expiresAt) {
          throw Object.assign(new Error('连接已失效，请重新连接插件。'), { code: 'SESSION_REVOKED' })
        }
        if (Date.now() >= Date.parse(this.#expiresAt!)) throw Object.assign(new Error('连接确认已过期，请重新连接后继续。'), { code: 'COLLECTOR_PROOF_EXPIRED' })
        const fingerprint = createHash('sha256').update(canonicalJson(request)).digest('hex')
        const cached = this.#acknowledgements.get(messageId)
        if (cached) {
          if (cached.fingerprint !== fingerprint) throw Object.assign(new Error('同一消息编号的内容不一致。'), { code: 'COLLECTOR_REPLAY_CONFLICT' })
          return cached.response
        }
        const sequence = BigInt(request.sequence!)
        if (sequence <= this.#lastSequence) throw Object.assign(new Error('消息顺序已过期，请重新连接。'), { code: 'COLLECTOR_SEQUENCE_REJECTED' })
        this.#lastSequence = sequence
        let response: Record<string, unknown>
        const nonce = this.#nonce
        try {
          const data = await this.#dispatch(request.type, request.payload)
          response = { protocolVersion: 1, messageId, ok: true, data }
          if (Buffer.byteLength(JSON.stringify(response)) > MAX_COLLECTOR_FRAME) throw Object.assign(new Error('响应过大，请在桌面端查看。'), { code: 'RESPONSE_TOO_LARGE' })
        } catch (error) { response = collectorError(messageId, error) }
        if (nonce !== this.#nonce) return collectorError(messageId, Object.assign(new Error('连接已变化，请重新核对保存结果。'), { code: 'SESSION_REVOKED' }))
        this.#acknowledgements.set(messageId, { fingerprint, response })
        while (this.#acknowledgements.size > 64) this.#acknowledgements.delete(this.#acknowledgements.keys().next().value!)
        return response
      }
      const data = await this.#dispatch(request.type, request.payload)
      return { protocolVersion: 1, messageId, ok: true, data }
    } catch (error) {
      return collectorError(messageId, error)
    }
  }

  async #dispatch(type: string, payload: Record<string, unknown>): Promise<unknown> {
    const { session, api } = this.options
    if (type === 'hello') {
      if (payload.extensionId !== COLLECTOR_EXTENSION_ID) throw new Error('扩展身份不匹配')
      const context = session.context()
      if (!context.authenticated || !context.workspaceId) throw Object.assign(new Error('请先登录 Aden，后台将确定你的业务范围。'), { code: 'AUTH_REQUIRED' })
      const generation = this.#connectionGeneration
      if (!this.#context || !session.isCurrent(this.#context)) {
        if (!await this.options.confirmPair(context.workspaceId)) throw new Error('用户未允许插件连接')
      }
      const identity = await api.request<{ user?: { userId?: unknown } }>({ path: '/getInfo' })
      const principalId = String(identity.data.user?.userId ?? '')
      if (!/^[0-9]{1,20}$/.test(principalId)) throw new Error('无法确定当前账号身份')
      if (!session.isCurrent(context) || generation !== this.#connectionGeneration) throw Object.assign(new Error('连接期间会话已变化，请重新连接'), { code: 'SESSION_REVOKED' })
      this.#context = context
      this.#resetProof()
      this.#nonce = randomBytes(32).toString('hex')
      this.#expiresAt = new Date(Date.now() + 120_000).toISOString()
      return { connected: true, workspaceId: context.workspaceId, principalId, protocolVersion: 1,
        maxChunkBytes: 128 * 1024, maxFrameBytes: MAX_COLLECTOR_FRAME,
        proofRequired: true, nonce: this.#nonce, expiresAt: this.#expiresAt }
    }
    const bound = this.#context
    assertCollectorSession(bound, session.context())
    const base = `/api/v1/aden/collection/workspaces/${encodeURIComponent(bound.workspaceId!)}`
    if ('workspaceId' in payload) throw new Error('插件不能指定工作空间')
    if (type === 'library.open') {
      if (Object.keys(payload).some(key => !['view', 'captureId', 'taskId'].includes(key))) throw new Error('打开资料参数非法')
      if (payload.captureId !== undefined) collectorUuid(payload.captureId)
      if (payload.view !== undefined && payload.view !== 'task') throw new Error('打开资料目标非法')
      const target: CollectionLibraryTarget = payload.view === 'task' ? { view: 'task', ...(payload.taskId !== undefined ? { taskId: collectorUuid(payload.taskId) } : {}) } : {}
      this.options.openLibrary(target); return { opened: true }
    }
    if (type === 'capture.lookup') {
      if (typeof payload.sku !== 'string' || !/^\d{1,32}$/.test(payload.sku)) throw new Error('商品SKU非法')
      const response = await api.request({ path: `${base}/items/lookup?sku=${encodeURIComponent(payload.sku)}` })
      assertCollectorSession(bound, session.context())
      return response.data
    }
    let path: string
    let body: unknown = payload
    let method: 'POST' | 'GET' = 'POST'
    if (type === 'capture.prepare') {
      collectorUuid(payload.captureId)
      if (payload.source !== 'JD') throw new Error('插件只允许采集京东商品')
      validateCaptureExtraction(payload.extraction)
      path = `${base}/captures`
    } else {
      const captureId = collectorUuid(payload.captureId)
      if (type === 'capture.status') {
        path = `${base}/captures/${captureId}`
        method = 'GET'
        body = undefined
      } else if (type === 'capture.commit') {
        path = `${base}/captures/${captureId}/complete`
        const { captureId: _captureId, ...rest } = payload
        body = rest
      } else if (type === 'asset.chunk') {
        if (typeof payload.imageId !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(payload.imageId)) throw new Error('图片标识非法')
        path = `${base}/captures/${captureId}/assets/${encodeURIComponent(payload.imageId)}/chunks`
        const { captureId: _captureId, imageId: _imageId, ...rest } = payload
        body = rest
      } else throw new Error('采集操作不支持')
    }
    const response = await api.request({ path, method, body })
    assertCollectorSession(bound, session.context())
    return response.data
  }
}

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonicalJson((value as Record<string, unknown>)[key])).join(',') + '}'
  return JSON.stringify(value) ?? 'null'
}

function collectorError(messageId: string, error: unknown): Record<string, unknown> {
  const message = error instanceof Error ? error.message : '采集操作失败'
  const code = error && typeof error === 'object' && 'code' in error && typeof error.code === 'string' ? error.code : 'COLLECTOR_REQUEST_REJECTED'
  return { protocolVersion: 1, messageId, ok: false, error: { code, message: message.slice(0, 300) } }
}
