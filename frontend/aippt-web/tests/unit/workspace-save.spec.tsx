import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { platformRequest } from '@/api/client'
import { useAuthStore } from '@/features/auth/store'
import { ThemePanel } from '@/features/deck/ThemePanel'
import { useExportDeck } from '@/features/deck/api'
import { flushProjectSlides } from '@/features/deck/slideSaveBridge'
import { OutlineWorkspace } from '@/features/outline/OutlineWorkspace'
import { useProject } from '@/features/projects/api'
import type { ProjectDetail } from '@/features/projects/types'
import type { Outline } from '@/features/outline/types'
import * as download from '@/lib/download'

const ok = (data: unknown, code = 200) => new Response(JSON.stringify({ code, data, msg: code === 409 ? '版本冲突' : '' }), { headers: { 'Content-Type': 'application/json' } })
const initial = (): ProjectDetail => ({ id: 'p', title: '项目', audience: null, tone: 'professional', page_count: 5, theme_id: 'ivory', theme_overrides: {}, layout_mode: 'flex', content_density: 'medium', status: 'draft', sources: [], input_revision: 7, theme_revision: 3, deck_revision: 4, created_at: '', updated_at: '' })
const outline = (): Outline => ({ id: 'o', project_id: 'p', status: 'draft', revision: 6, error: null, input_signature: 'i', created_at: '', updated_at: '', pages: Array.from({ length: 5 }, (_, i) => ({ id: `page${i}`, title: `标题 ${i + 1}`, objective: '独立目标', key_points: ['要点一', '要点二'], layout_id: 'bullets', page_role: 'content', source_refs: [], visual: null })) })
let root: Root, container: HTMLDivElement, client: QueryClient
const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)) })
const button = (text: string) => [...container.querySelectorAll('button')].find((node) => node.textContent?.includes(text))!
const change = (node: HTMLInputElement, value: string) => act(() => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(node, value); node.dispatchEvent(new Event(node.type === 'color' ? 'change' : 'input', { bubbles: true })) })
const render = async (child: React.ReactNode) => { await act(async () => root.render(<QueryClientProvider client={client}><MemoryRouter>{child}</MemoryRouter></QueryClientProvider>)); await settle() }
function ThemeProbe() { const p = useProject('p').data; return p ? <ThemePanel project={p} /> : null }
let exporter: ReturnType<typeof useExportDeck>
function ExportProbe() { exporter = useExportDeck('p'); return null }
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 } })))); await platformRequest('/getInfo'); useAuthStore.setState({ permissions: ['*:*:*'] })
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); client.clear(); container.remove(); vi.restoreAllMocks() })
describe('原面板中心保存与失败恢复', () => {
  it('离开flush期间继续调整主题，首个回执不得提前完成flush或丢320ms待发意图', async () => {
    let server = initial(); const writes: Array<Record<string, unknown>> = []; const releases: Array<() => void> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/theme')) { const body = JSON.parse(init.body as string); writes.push(body); await new Promise<void>((resolve) => releases.push(resolve)); server = { ...server, theme_overrides: body.overrides, theme_revision: Number(body.theme_revision) + 1 }; return ok(server) }
      return ok(url.endsWith('/sources') ? [] : server)
    }))
    await render(<ThemeProbe />); change(container.querySelector<HTMLInputElement>('input[type=color]')!, '#112233')
    let done = false; let leaving!: Promise<void>; act(() => { leaving = flushProjectSlides('p').then(() => { done = true }) }); await settle()
    change(container.querySelector<HTMLInputElement>('input[type=color]')!, '#334455')
    await act(async () => { releases.shift()!(); await new Promise((resolve) => setTimeout(resolve, 40)) })
    expect(done).toBe(false)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 340)) }); expect(writes).toHaveLength(2)
    await act(async () => { releases.shift()!(); await leaving })
    expect(writes.map((write) => write.theme_revision)).toEqual([3, 4]); expect(done).toBe(true)
    expect(container.querySelector<HTMLInputElement>('input[type=color]')!.value).toBe('#334455')
  })
  it('主题320ms待发可flush，两次调整串行且CAS沿用前一正式回执', async () => {
    let server = initial(); const writes: Array<Record<string, unknown>> = []; let release!: () => void
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/theme')) { const body = JSON.parse(init.body as string); writes.push(body); if (writes.length === 1) await new Promise<void>((resolve) => { release = resolve }); server = { ...server, theme_id: body.theme_id, theme_overrides: body.overrides, theme_revision: Number(body.theme_revision) + 1 }; return ok(server) }
      return ok(url.endsWith('/sources') ? [] : server)
    }))
    await render(<ThemeProbe />); change(container.querySelector<HTMLInputElement>('input[type=color]')!, '#123456')
    let first!: Promise<void>; act(() => { first = flushProjectSlides('p') }); await settle(); expect(writes).toHaveLength(1)
    change(container.querySelector<HTMLInputElement>('input[type=color]')!, '#654321'); let second!: Promise<void>; act(() => { second = flushProjectSlides('p') }); await settle(); expect(writes).toHaveLength(1)
    await act(async () => { release(); await Promise.all([first, second]) }); await settle()
    expect(writes.map((write) => write.theme_revision)).toEqual([3, 4]); expect(container.querySelector<HTMLInputElement>('input[type=color]')!.value).toBe('#654321')
  })
  it('主题未知回执保留预览与body/key，显式重试后恢复可编辑', async () => {
    let server = initial(); let lost = true; const writes: Array<{ body: string; key: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/theme')) { const body = JSON.parse(init.body as string); writes.push({ body: init.body as string, key: new Headers(init.headers).get('Idempotency-Key')! }); server = { ...server, theme_overrides: body.overrides, theme_revision: 4 }; if (lost) { lost = false; throw new TypeError('回执丢失') }; return ok(server) }
      return ok(url.endsWith('/sources') ? [] : server)
    }))
    await render(<ThemeProbe />); change(container.querySelector<HTMLInputElement>('input[type=color]')!, '#123456')
    await act(async () => { await expect(flushProjectSlides('p')).rejects.toThrow('回执丢失') }); await settle(); expect(container.querySelector<HTMLInputElement>('input[type=color]')!.value).toBe('#123456'); expect(container.querySelector<HTMLInputElement>('input[type=color]')!.disabled).toBe(true)
    act(() => button('重试原操作').click()); await settle(); await act(async () => { await flushProjectSlides('p') }); expect(writes[1]).toEqual(writes[0]); expect(container.querySelector<HTMLInputElement>('input[type=color]')!.disabled).toBe(false)
  })
  it('大纲旧valid保存回执不覆盖在途新invalid草稿，invalid禁止确认旧提纲', async () => {
    const p = initial(); let formal = outline(); let release!: () => void; const writes: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (init.method === 'PATCH') { const body = JSON.parse(init.body as string); writes.push(body); await new Promise<void>((resolve) => { release = resolve }); formal = { ...formal, revision: 7, pages: body.pages }; return ok(formal) }
      return ok(url.endsWith('/outline') ? formal : url.endsWith('/sources') ? [] : p)
    }))
    await render(<OutlineWorkspace project={p} />)
    const title = container.querySelector<HTMLInputElement>('input[aria-label="第 1 页标题"]')!; change(title, '已发送标题')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 950)) }); expect(writes).toHaveLength(1)
    change(title, ''); await act(async () => { release() }); await settle()
    expect(title.value).toBe(''); expect(button('生成 PPT').disabled).toBe(true)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 950)) }); expect(writes).toHaveLength(1)
  })
  it('大纲只读角色不能编辑/排序/确认，仍完整预览标题和主题', async () => {
    useAuthStore.setState({ permissions: ['aippt:project:query', 'aippt:outline:query'] })
    vi.stubGlobal('fetch', vi.fn(async () => ok(outline())))
    await render(<OutlineWorkspace project={initial()} />)
    expect(container.querySelector('input[aria-label="第 1 页标题"]')!.matches(':disabled')).toBe(true); expect(button('生成 PPT').disabled).toBe(true); expect(container.textContent).toContain('确认大纲')
  })
  it.each(['failed', 'cancelled'])('导出明确%s终态新尝试换key，回读失败原形状问题保留', async (status) => {
    const writes: string[] = []; const verify = { passed: false, issues: [{ check: 'native_chart', slide_index: 2, shape: 'chart', message: '图表不可编辑' }], slide_count: 5, expected_slide_count: 5, diagnostics_truncated: false, total_issues: 1 }
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/exports') && init.method === 'POST') { writes.push(new Headers(init.headers).get('Idempotency-Key')!); return ok({ id: `e${writes.length}`, job_id: `j${writes.length}`, status: 'queued' }) }
      if (url.includes('/tasks/')) return ok({ status, error: '终态错误', verification_failure: verify })
      if (url.includes('/exports/e')) return ok({ status, verify })
      return ok(initial())
    }))
    await render(<ExportProbe />)
    await act(async () => { await expect(exporter.mutateAsync(false)).rejects.toMatchObject({ status: 422, detail: { issues: verify.issues } }) })
    await act(async () => { await expect(exporter.mutateAsync(false)).rejects.toMatchObject({ status: 422 }) })
    expect(writes).toHaveLength(2); expect(writes[1]).not.toBe(writes[0])
  })
  it('导出POST未知回执重放同body/key，成功私有鉴权下载一次', async () => {
    const writes: Array<{ key: string; body: string }> = []; let lost = true; const saved = vi.spyOn(download, 'saveBlob').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/exports')) { writes.push({ key: new Headers(init.headers).get('Idempotency-Key')!, body: init.body as string }); if (lost) { lost = false; throw new TypeError('POST回执未知') }; return ok({ id: 'e', job_id: 'j', status: 'queued' }) }
      if (url.endsWith('/tasks/j')) return ok({ status: 'succeeded' })
      if (url.endsWith('/exports/e')) return ok({ status: 'ready' })
      if (url.endsWith('/download')) return new Response('fixture bytes', { headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.presentationml.presentation' } })
      return ok(initial())
    }))
    await render(<ExportProbe />); await act(async () => { await expect(exporter.mutateAsync(false)).rejects.toThrow('POST回执未知') }); expect(saved).not.toHaveBeenCalled()
    await act(async () => { await exporter.mutateAsync(false) }); expect(writes[1]).toEqual(writes[0]); expect(saved).toHaveBeenCalledOnce()
  })
})
