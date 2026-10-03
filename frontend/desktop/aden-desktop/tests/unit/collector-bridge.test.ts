import { afterEach, describe, expect, it, vi } from 'vitest'
import { CollectorBridge } from '../../src/main/collector-bridge/bridge'
import { COLLECTOR_EXTENSION_ID, parseCollectorRequest } from '../../src/main/collector-bridge/protocol'
import { SessionController } from '../../src/main/auth/session-controller'
import type { AdenApiClient } from '../../src/main/transport/api-client'

const workspace = '11111111-1111-4111-8111-111111111111'
const capture = '22222222-2222-4222-8222-222222222222'
function fixture() {
  const session = new SessionController()
  session.authenticate('test-only-token')
  session.selectWorkspace(workspace)
  const request = vi.fn().mockImplementation(async (input: { path: string }) => ({ data: input.path === '/getInfo' ? { user: { userId: 100 } } : { captureId: capture } }))
  const confirmPair = vi.fn().mockResolvedValue(true)
  const bridge = new CollectorBridge({ helperPath: 'unused', session, api: { request } as unknown as AdenApiClient,
    confirmPair, openLibrary: vi.fn() })
  let sequence = 0n
  let proof: Record<string, unknown> = {}
  const envelope = (type: string, payload: unknown, counter = (++sequence).toString()) => ({ protocolVersion: 1, messageId: 'test-' + counter, type, payload, ...(type === 'hello' ? {} : { nonce: proof.nonce, sequence: counter, expiresAt: proof.expiresAt }) })
  const send = async (type: string, payload: unknown) => {
    const result = await bridge.handle(envelope(type, payload))
    if (type === 'hello' && result.ok) proof = result.data as Record<string, unknown>
    return result
  }
  return { session, request, confirmPair, bridge, send, envelope, getProof: () => proof }
}

describe('collector trusted bridge', () => {
  afterEach(() => vi.restoreAllMocks())
  it('rejects unpaired requests and mismatched extension identity', async () => {
    const f = fixture()
    expect((await f.send('capture.prepare', { captureId: capture, source: 'JD' })).ok).toBe(false)
    expect((await f.send('hello', { extensionId: 'wrong' })).ok).toBe(false)
    expect(f.request).not.toHaveBeenCalled()
    expect(f.confirmPair).not.toHaveBeenCalled()
  })
  it('pairs explicitly and fixes workspace, rejecting a switched session', async () => {
    const f = fixture()
    expect((await f.send('hello', { extensionId: COLLECTOR_EXTENSION_ID })).ok).toBe(true)
    expect((await f.send('capture.prepare', { captureId: capture, source: 'JD', extraction: extraction() })).ok).toBe(true)
    expect(f.request).toHaveBeenCalledWith(expect.objectContaining({ path: `/api/v1/aden/collection/workspaces/${workspace}/captures` }))
    f.session.selectWorkspace('33333333-3333-4333-8333-333333333333')
    expect((await f.send('capture.status', { captureId: capture })).ok).toBe(false)
    expect(f.request).toHaveBeenCalledTimes(2)
  })
  it('does not accept workspace override or arbitrary operations', async () => {
    const f = fixture()
    await f.send('hello', { extensionId: COLLECTOR_EXTENSION_ID })
    f.request.mockClear()
    expect((await f.send('capture.prepare', { captureId: capture, source: 'JD', workspaceId: workspace })).ok).toBe(false)
    expect((await f.send('shell', { command: 'anything' })).ok).toBe(false)
    expect(f.request).not.toHaveBeenCalled()
  })
  it('invalidates a pairing if the account changes during confirmation', async () => {
    const f = fixture()
    f.confirmPair.mockImplementation(async () => { f.session.clear(); return true })
    expect((await f.send('hello', { extensionId: COLLECTOR_EXTENSION_ID })).ok).toBe(false)
  })
  it('deduplicates full-message acknowledgements and rejects replay payload changes', async () => {
    const f = fixture(); await f.send('hello', { extensionId: COLLECTOR_EXTENSION_ID })
    const message = f.envelope('capture.prepare', { captureId: capture, source: 'JD', extraction: extraction() })
    const first = await f.bridge.handle(message)
    expect(first.ok).toBe(true)
    expect(await f.bridge.handle(message)).toEqual(first)
    expect(f.request).toHaveBeenCalledTimes(2)
    const conflict = await f.bridge.handle({ ...message, payload: { ...(message.payload as Record<string, unknown>), source: 'changed' } })
    expect(conflict).toMatchObject({ ok: false, error: { code: 'COLLECTOR_REPLAY_CONFLICT' } })
    expect(f.request).toHaveBeenCalledTimes(2)
  })
  it('rejects expired, forged, stale and legacy proofs while preserving large sequence precision', async () => {
    const f = fixture(); await f.send('hello', { extensionId: COLLECTOR_EXTENSION_ID })
    expect(await f.bridge.handle(f.envelope('capture.status', { captureId: capture }, '9007199254740992'))).toMatchObject({ ok: true })
    expect(await f.bridge.handle(f.envelope('capture.status', { captureId: capture }, '9007199254740993'))).toMatchObject({ ok: true })
    expect(await f.bridge.handle({ ...f.envelope('capture.status', { captureId: capture }, '9007199254740992'), messageId: 'new-with-old-sequence' })).toMatchObject({ ok: false, error: { code: 'COLLECTOR_SEQUENCE_REJECTED' } })
    expect(await f.bridge.handle({ protocolVersion: 1, type: 'capture.status', messageId: 'legacy', payload: { captureId: capture } })).toMatchObject({ ok: false, error: { code: 'COLLECTOR_UPGRADE_REQUIRED' } })
    const message = f.envelope('capture.status', { captureId: capture }, '9007199254740994')
    expect(await f.bridge.handle({ ...message, nonce: '0'.repeat(64) })).toMatchObject({ ok: false, error: { code: 'SESSION_REVOKED' } })
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse(String(f.getProof().expiresAt)) + 1)
    expect(await f.bridge.handle(message)).toMatchObject({ ok: false, error: { code: 'COLLECTOR_PROOF_EXPIRED' } })
  })
  it('rejects oversized messages and unexpected envelope fields', () => {
    expect(() => parseCollectorRequest({ protocolVersion: 1, messageId: 'a', type: 'hello', payload: {}, token: 'x' })).toThrow()
    expect(() => parseCollectorRequest({ protocolVersion: 1, messageId: 'a', type: 'hello', payload: { data: 'x'.repeat(300000) } })).toThrow()
  })
})

function extraction() {
  return { protocolVersion: 1, documentId: 'fixture-document', pageFingerprint: 'a'.repeat(64), selectionRevision: '1', confirmationRevision: '1', selectedSections: ['basic'], sections: [{ sectionId: 'basic', documentId: 'fixture-document', pageFingerprint: 'a'.repeat(64), selectionRevision: '1', rangeRevision: '0', extractedRangeRevision: '0', resultRevision: '1', sourceIds: ['dom-12345678'] }] }
}
