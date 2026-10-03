import { Buffer } from 'node:buffer'
import { AdenApiClient, isRecord } from '../transport/api-client'
import { AdenTransportError } from '../transport/error-mapper'
import type { SessionContext } from './session-controller'
import { parseWorkspaceBootstrap, parseWorkspaceList } from '../../shared/contracts/operator-bootstrap'
import type { WorkspaceBootstrapSnapshot, WorkspaceSnapshot } from '../../shared/generated/operator-contracts'

export interface CaptchaChallenge {
  readonly enabled: boolean
  readonly uuid: string | null
  readonly jpegDataUrl: string | null
}

export interface LoginInput {
  readonly username: string
  readonly password: string
  readonly code?: string
  readonly uuid?: string
}

export interface AuthenticatedUser {
  readonly user: Record<string, unknown>
  readonly roles: readonly string[]
  readonly permissions: readonly string[]
  readonly session: SessionContext
  readonly workspace: WorkspaceSnapshot
  readonly bootstrap: WorkspaceBootstrapSnapshot
}

export class AdenAuthService {
  constructor(private readonly api: AdenApiClient) {}

  async captcha(): Promise<CaptchaChallenge> {
    const response = await this.api.request<unknown>({ path: '/captchaImage', authenticated: false })
    const body = requireRecord(response.data, response.correlationId)
    const enabled = body.captchaEnabled
    if (typeof enabled !== 'boolean') {
      throw invalidResponse(response.correlationId, '验证码开关缺失')
    }
    if (!enabled) return Object.freeze({ enabled: false, uuid: null, jpegDataUrl: null })
    if (typeof body.uuid !== 'string' || !/^[A-Za-z0-9_-]{8,128}$/.test(body.uuid)) {
      throw invalidResponse(response.correlationId, '验证码 UUID 非法')
    }
    if (typeof body.img !== 'string' || !isJpegBase64(body.img)) {
      throw invalidResponse(response.correlationId, '验证码不是有效 JPEG base64')
    }
    return Object.freeze({ enabled: true, uuid: body.uuid, jpegDataUrl: `data:image/jpeg;base64,${body.img}` })
  }

  async login(input: LoginInput): Promise<AuthenticatedUser> {
    validateLoginInput(input)
    const login = await this.api.request<unknown>({
      path: '/login',
      method: 'POST',
      authenticated: false,
      body: {
        username: input.username,
        password: input.password,
        code: input.code ?? '',
        uuid: input.uuid ?? ''
      }
    })
    const body = requireRecord(login.data, login.correlationId)
    if (typeof body.token !== 'string' || body.token.length < 8 || body.token.length > 8192) {
      throw invalidResponse(login.correlationId, '登录响应没有有效 Token')
    }
    this.api.session.authenticate(body.token)
    const epoch = this.api.session.context().sessionEpoch
    try {
      return await this.getInfo()
    } catch (error) {
      if (this.api.session.context().sessionEpoch === epoch) this.api.session.clear()
      throw error
    }
  }

  async getInfo(): Promise<AuthenticatedUser> {
    const response = await this.api.request<unknown>({ path: '/getInfo' })
    const body = requireRecord(response.data, response.correlationId)
    const user = requireRecord(body.user, response.correlationId)
    const roles = stringArray(body.roles, response.correlationId, 'roles')
    const permissions = stringArray(body.permissions, response.correlationId, 'permissions')
    const resolved = await this.api.request<unknown>({ path: '/api/v1/aden/default-workspace' }).catch(error => {
      if (error instanceof AdenTransportError && error.status === 409) throw new AdenTransportError('DEFAULT_WORKSPACE_UNAVAILABLE', '账号业务范围未配置或不可用，请联系管理员。', 409, error.correlationId, false)
      throw error
    })
    const workspace = parseWorkspaceList({ items: [resolved.data] }).items[0]
    if (!workspace || workspace.status !== 'ACTIVE') throw invalidResponse(resolved.correlationId, '默认业务范围不可用，请联系管理员配置。')
    this.api.session.selectWorkspace(workspace.workspaceId)
    const snapshot = await this.api.request<unknown>({ path: `/api/v1/aden/workspaces/${workspace.workspaceId}/bootstrap?taskLimit=50` })
    const bootstrap = parseWorkspaceBootstrap(snapshot.data, permissions.includes('aden:collection:read'))
    if (bootstrap.workspace.workspaceId !== workspace.workspaceId) throw invalidResponse(snapshot.correlationId, '登录数据范围不一致，请重新登录。')
    return Object.freeze({ user, roles, permissions, workspace, bootstrap, session: this.api.session.context() })
  }

  async logout(): Promise<SessionContext> {
    try {
      if (this.api.session.token()) {
        await this.api.request<unknown>({ path: '/logout', method: 'POST' })
      }
    } finally {
      if (this.api.session.token()) this.api.session.clear()
    }
    return this.api.session.context()
  }
}

function validateLoginInput(input: LoginInput): void {
  if (!input.username.trim() || input.username.length > 64 || input.password.length < 1 || input.password.length > 256) {
    throw new TypeError('用户名或密码格式非法')
  }
  if (input.code !== undefined && input.code.length > 16) throw new TypeError('验证码过长')
  if (input.uuid !== undefined && input.uuid.length > 128) throw new TypeError('验证码 UUID 过长')
}

function isJpegBase64(value: string): boolean {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value) || value.length % 4 !== 0 || value.length > 2_000_000) return false
  try {
    const bytes = Buffer.from(value, 'base64')
    if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff
        || bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== 0xd9) return false
    return bytes.toString('base64') === value
  } catch {
    return false
  }
}

function requireRecord(value: unknown, correlationId: string): Record<string, unknown> {
  if (!isRecord(value)) throw invalidResponse(correlationId, '服务响应必须是 JSON object')
  return value
}

function stringArray(value: unknown, correlationId: string, field: string): readonly string[] {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw invalidResponse(correlationId, `${field} 必须是字符串数组`)
  }
  return Object.freeze([...value]) as readonly string[]
}

function invalidResponse(correlationId: string, message: string): AdenTransportError {
  return new AdenTransportError('INVALID_RESPONSE', message, 200, correlationId, false)
}
