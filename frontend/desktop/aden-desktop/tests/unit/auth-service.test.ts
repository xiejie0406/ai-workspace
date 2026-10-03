import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { resolveRuntimeConfig } from '../../src/main/config/runtime-config'
import { SessionController } from '../../src/main/auth/session-controller'
import { AdenApiClient, type FetchLike } from '../../src/main/transport/api-client'
import { AdenAuthService } from '../../src/main/auth/auth-service'

const config = resolveRuntimeConfig('https://aden.example.test', false)
const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0x00, 0xff, 0xd9]).toString('base64')

describe('AdenAuthService', () => {
  it('accepts disabled captcha without synthetic image fields', async () => {
    const service = serviceFor([{ code: 200, captchaEnabled: false }])
    await expect(service.auth.captcha()).resolves.toEqual({ enabled: false, uuid: null, jpegDataUrl: null })
  })

  it('accepts a real JPEG signature and rejects arbitrary base64', async () => {
    const valid = serviceFor([{ code: 200, captchaEnabled: true, uuid: 'abcdef123456', img: jpeg }])
    await expect(valid.auth.captcha()).resolves.toMatchObject({ enabled: true, jpegDataUrl: `data:image/jpeg;base64,${jpeg}` })
    const invalid = serviceFor([{ code: 200, captchaEnabled: true, uuid: 'abcdef123456', img: Buffer.from('not-jpeg').toString('base64') }])
    await expect(invalid.auth.captcha()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('keeps token in main memory and calls getInfo immediately', async () => {
    const fixture = serviceFor([
      { code: 200, token: 'main-only-token' },
      { code: 200, user: { userName: 'synthetic' }, roles: ['operator'], permissions: ['aden:task:list'] },
      bootstrap().workspace, bootstrap()
    ])
    const result = await fixture.auth.login({ username: 'synthetic', password: 'secret', code: '1', uuid: 'u' })
    expect(result.permissions).toEqual(['aden:task:list'])
    expect(result.session.workspaceId).toBe(bootstrap().workspace.workspaceId)
    expect(result.bootstrap.workspace.workspaceId).toBe(result.session.workspaceId)
    expect(fixture.session.token()).toBe('main-only-token')
    expect(fixture.fetcher).toHaveBeenCalledTimes(4)
    const secondHeaders = new Headers((fixture.fetcher.mock.calls[1][1] as RequestInit).headers)
    expect(secondHeaders.get('Authorization')).toBe('Bearer main-only-token')
    expect(JSON.stringify(result)).not.toContain('main-only-token')
  })

  it('clears token when getInfo fails after login', async () => {
    const fixture = serviceFor([{ code: 200, token: 'temporary-token' }, { code: 500, msg: '拒绝' }])
    await expect(fixture.auth.login({ username: 'synthetic', password: 'secret' })).rejects.toMatchObject({ code: 'RUOYI_ERROR' })
    expect(fixture.session.token()).toBeNull()
  })
  it('never reports logged in when default scope or bootstrap fails', async () => {
    const fixture = serviceFor([{ code: 200, token: 'temporary-token' },
      { code: 200, user: {}, roles: [], permissions: ['aden:collection:read'] },
      bootstrap().workspace, { code: 403, msg: '范围权限已撤回' }])
    await expect(fixture.auth.login({ username: 'synthetic', password: 'secret' })).rejects.toMatchObject({ code: 'RUOYI_ERROR' })
    expect(fixture.session.token()).toBeNull()
    expect(fixture.session.context().workspaceId).toBeNull()
  })
  it('accepts a collection-only bootstrap without CORE records', async () => {
    const value = bootstrap(); value.tasks.items = []; value.runners = []; value.capabilities = []
    const fixture = serviceFor([{ code: 200, token: 'viewer-only-token' },
      { code: 200, user: {}, roles: ['viewer'], permissions: ['aden:collection:read'] }, value.workspace, value])
    const result = await fixture.auth.login({ username: 'viewer', password: 'secret' })
    expect(result.bootstrap.tasks.items).toEqual([])
    expect(result.bootstrap.capabilities).toEqual([])
    expect(result.permissions).toEqual(['aden:collection:read'])
  })
  it('maps a default-scope conflict to the typed configuration error and revokes login', async () => {
    const fixture = serviceFor([{ code: 200, token: 'temporary-token' },
      { code: 200, user: {}, roles: [], permissions: ['aden:collection:read'] },
      { code: 'ADEN_DEFAULT_WORKSPACE_UNAVAILABLE', message: 'unavailable' }], [200, 200, 409])
    await expect(fixture.auth.login({ username: 'viewer', password: 'secret' })).rejects.toMatchObject({ code: 'DEFAULT_WORKSPACE_UNAVAILABLE', status: 409, retryable: false })
    expect(fixture.session.token()).toBeNull()
    expect(fixture.fetcher).toHaveBeenCalledTimes(3)
  })
})

function serviceFor(bodies: unknown[], statuses: number[] = []) {
  const session = new SessionController()
  const fetcher = vi.fn<FetchLike>(async () => new Response(JSON.stringify(bodies.shift()), {
    status: statuses.shift() ?? 200, headers: { 'content-type': 'application/json' }
  }))
  const api = new AdenApiClient(config, session, fetcher)
  return { auth: new AdenAuthService(api), session, fetcher }
}

function bootstrap() { return JSON.parse(readFileSync(resolve('../../../contracts/aden/examples/current/operator/bootstrap-long-max-watermark.json'), 'utf8')) }
