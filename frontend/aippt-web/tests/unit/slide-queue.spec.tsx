import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, useEffect } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { platformRequest } from '@/api/client'
import { deckKey, useReplaceSlideImage } from '@/features/deck/api'
import { registerSlideSaveHandlers } from '@/features/deck/slideSaveBridge'
import { createColumnsInsertion } from '@/features/deck/BlockInsertRail'
import { useSlideSaveQueue } from '@/features/deck/useSlideSaveQueue'
import type { Deck, DeckSlide } from '@/features/deck/types'

const envelope = (data: unknown, code = 200) => new Response(JSON.stringify({ code, data, msg: code === 409 ? '页面版本冲突' : '' }), { headers: { 'Content-Type': 'application/json' } })
const slide = (): DeckSlide => ({ id: 's', revision: 6, title: '测试页', position: 1, status: 'ready', layout_mode: 'flex', layout_id: 'title_body', speaker_notes: null, issues: [], error: null, outline_page_id: 'o', updated_at: '', blocks: [{ id: 'b', type: 'text', slot_id: 'body', text: '原内容', locked: false }], layout_tree: { id: 'root', type: 'column', children: [{ id: 'leaf', type: 'block', block_id: 'b' }] } })
let root: Root, client: QueryClient, q: ReturnType<typeof useSlideSaveQueue>, image: ReturnType<typeof useReplaceSlideImage>
const tick = () => act(async () => { await Promise.resolve(); await Promise.resolve() })
function Probe() {
  q = useSlideSaveQueue('p', 's'); image = useReplaceSlideImage('p')
  useEffect(() => registerSlideSaveHandlers('s', { projectId: 'p', ...q }), [q.flush, q.mutateAsync])
  return null
}
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 }, permissions: ['*:*:*'] }))))
  await platformRequest('/getInfo')
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  client.setQueryData(deckKey('p'), { project_id: 'p', title: '项目', theme_id: 'ivory', deck_revision: 4, theme_revision: 2, status: 'ready', total: 1, ready: 1, failed: 0, slides: [slide()] } satisfies Deck)
  root = createRoot(document.createElement('div'))
  await act(async () => { root.render(<QueryClientProvider client={client}><Probe /></QueryClientProvider>) })
})
afterEach(async () => { await act(async () => root.unmount()); client.clear(); vi.restoreAllMocks() })
describe('保留原保存队列接入中心 CAS', () => {
  it('延迟文字保存时换图必须排队，上传取前一正式回执revision', async () => {
    let release!: () => void; let server = slide(); const uploads: FormData[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/projects/p')) return envelope({ deck_revision: 5, theme_revision: 2 })
      if (url.endsWith('/image')) { uploads.push(init.body as FormData); server = { ...server, revision: 8 }; return envelope(server) }
      await new Promise<void>((resolve) => { release = resolve })
      server = { ...server, revision: 7, blocks: [{ ...server.blocks[0]!, text: '新内容' }] } as DeckSlide
      return envelope(server)
    }))
    act(() => q.commit('b', { type: 'text', text: '新内容' }))
    let pending!: Promise<DeckSlide>
    act(() => { pending = image.mutateAsync({ slideId: 's', blockId: 'b', revision: 6, file: new File(['png'], 'a.png', { type: 'image/png' }) }) })
    await tick(); expect(uploads).toHaveLength(0)
    await act(async () => { release(); await pending })
    expect(uploads).toHaveLength(1); expect(uploads[0]!.get('revision')).toBe('7')
    expect(client.getQueryData<Deck>(deckKey('p'))!.slides[0]!.revision).toBe(8)
  })
  it('已提交但回执断网后暂停，保留后续样式，重试原body/key后才继续', async () => {
    const writes: Array<{ body: string; key: string }> = []; let lost = true; let server = slide()
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/projects/p')) return envelope({ deck_revision: 6, theme_revision: 2 })
      writes.push({ body: init.body as string, key: new Headers(init.headers).get('Idempotency-Key')! })
      if (lost) { lost = false; server = { ...server, revision: 7 }; throw new TypeError('回执丢失') }
      if (url.endsWith('/style')) server = { ...server, revision: 8 }
      return envelope(server)
    }))
    act(() => { q.commit('b', { type: 'text', text: '保留正文' }); q.commitStyle('b', { weight: 700 }) })
    await tick(); expect(q.status).toBe('error'); expect(writes).toHaveLength(1)
    await expect(q.flush()).rejects.toThrow('保存尚未确认')
    await act(async () => { await q.retrySave(); await q.flush() })
    expect(writes).toHaveLength(3); expect(writes[1]).toEqual(writes[0]); expect(JSON.parse(writes[2]!.body).revision).toBe(7)
    expect(q.status).toBe('saved')
  })
  it('409明确重基保留草稿，撤销只恢复最新正式before，不回退远端修改', async () => {
    let first = true; const writes: Array<Record<string, unknown>> = []; const server = { ...slide(), revision: 9, blocks: [{ ...slide().blocks[0]!, text: '远端最新文字' }] } as DeckSlide
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/deck')) return envelope({ ...client.getQueryData<Deck>(deckKey('p'))!, slides: [server] })
      if (url.endsWith('/projects/p')) return envelope({ deck_revision: 8, theme_revision: 2 })
      const body = JSON.parse(init.body as string); writes.push(body)
      if (first) { first = false; return envelope(null, 409) }
      return envelope({ ...server, revision: Number(body.revision) + 1, blocks: [{ ...server.blocks[0], text: body.text }] })
    }))
    act(() => q.commit('b', { type: 'text', text: '我的草稿' })); await tick(); expect(q.status).toBe('conflict')
    await act(async () => { await q.retrySave(true); await q.flush() })
    expect(writes[1]).toMatchObject({ revision: 9, text: '我的草稿' })
    await act(async () => { q.undo(); await q.flush() })
    expect(writes[2]).toMatchObject({ text: '远端最新文字' })
  })
  it('关闭旧页后迟到保存不能重新填已清理的缓存', async () => {
    let release!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { release = resolve })))
    act(() => q.commit('b', { type: 'text', text: '旧页草稿' })); await tick()
    await act(async () => root.unmount()); client.clear()
    await act(async () => { release(envelope({ ...slide(), revision: 7 })); await Promise.resolve() })
    expect(client.getQueryData(deckKey('p'))).toBeUndefined()
  })
  it('多列第二次POST未知回执重放原子步骤，不重复已确认的第一个块', async () => {
    let server = slide(); let lost = true; const receipts = new Map<string, DeckSlide>(); const calls: Array<{ url: string; body: string; key: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      const key = new Headers(init.headers).get('Idempotency-Key')!; const body = JSON.parse(init.body as string); calls.push({ url, body: init.body as string, key })
      if (receipts.has(key)) return envelope(receipts.get(key))
      if (url.endsWith('/blocks')) {
        const id = `new${server.blocks.length}`
        server = { ...server, revision: server.revision + 1, blocks: [...server.blocks, { id, slot_id: id, type: 'text', text: '', locked: false }], layout_tree: { ...server.layout_tree!, children: [...server.layout_tree!.children, { id: `leaf${id}`, type: 'block', block_id: id }] } }
        receipts.set(key, structuredClone(server))
        if (server.blocks.length === 3 && lost) { lost = false; throw new TypeError('第二次回执丢失') }
      } else server = { ...server, revision: server.revision + 1, layout_tree: body.layout_tree }
      return envelope(server)
    }))
    const run = createColumnsInsertion('p', { parentId: 'root', index: 1 }, 2)
    await expect(run(slide())).rejects.toThrow('第二次回执丢失')
    const result = await run(slide())
    expect(result.blocks).toHaveLength(3); expect(calls.filter((call) => call.url.endsWith('/blocks'))).toHaveLength(3)
    expect(calls[2]).toEqual(calls[1]); expect(result.layout_tree!.children.some((node) => node.type === 'row' && node.children.length === 2)).toBe(true)
  })
})
