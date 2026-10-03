import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { platformRequest } from '@/api/client'
import { useAuthStore } from '@/features/auth/store'
import { OutlineWorkspace } from '@/features/outline/OutlineWorkspace'
import type { Outline } from '@/features/outline/types'
import type { ProjectDetail } from '@/features/projects/types'
import ProjectsPage from '@/pages/ProjectsPage'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } })
const ref = '55555555-5555-4555-8555-555555555555'
const p = (): ProjectDetail => ({ id: 'p', title: '项目', audience: null, tone: 'professional', page_count: 5, theme_id: 'ivory', theme_overrides: {}, layout_mode: 'flex', content_density: 'medium', status: 'outline_ready', sources: [{ id: 'source', kind: 'document', filename: '原文.docx', sections: [{ id: ref, ref, level: 1, heading: '市场分析', text: '原文正文', locator: '1' }], warnings: [], char_count: 20, parse_status: 'ready' }], input_revision: 7, theme_revision: 3, deck_revision: 4, created_at: '', updated_at: '' } as unknown as ProjectDetail)
const outline = (): Outline => ({ id: 'o', project_id: 'p', status: 'draft', revision: 6, job_id: null, error: null, created_at: '', updated_at: '', pages: Array.from({ length: 5 }, (_, i) => ({ id: `11111111-1111-4111-8111-11111111111${i}`, title: `标题 ${i + 1}`, objective: '独立目标', key_points: ['要点一', '要点二'], layout_id: 'bullets', page_role: 'content', source_refs: [], visual: null })) })
let root: Root, container: HTMLDivElement, client: QueryClient
const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)) })
const render = async (child: React.ReactNode) => { await act(async () => root.render(<QueryClientProvider client={client}><MemoryRouter>{child}</MemoryRouter></QueryClientProvider>)); await settle() }
const change = (node: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement, value: string) => act(() => { const proto = node instanceof HTMLSelectElement ? HTMLSelectElement.prototype : node instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(node, value); node.dispatchEvent(new Event(node instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true })) })
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 } })))); await platformRequest('/getInfo'); useAuthStore.setState({ permissions: ['*:*:*'] })
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); client.clear(); container.remove(); vi.restoreAllMocks() })
describe('项目筛选与完整提纲字段', () => {
  it('已有大纲筛选发送正式outline_ready状态并保留结果', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => { requests.push(url); const status = new URL(url, 'http://localhost').searchParams.get('status'); return new Response(JSON.stringify({ code: 200, rows: status === 'outlined' ? [] : [p()], total: status === 'outlined' ? 0 : 1 }), { headers: { 'Content-Type': 'application/json' } }) }))
    await render(<ProjectsPage />)
    const select = container.querySelector<HTMLSelectElement>('[aria-label="项目状态"]')!
    const label = [...select.options].find((option) => option.textContent === '已有大纲')!
    change(select, label.value); await settle()
    expect(new URL(requests.at(-1)!, 'http://localhost').searchParams.get('status')).toBe('outline_ready')
    expect(container.textContent).toContain('项目')
    expect(container.textContent).not.toContain('没有符合条件的项目')
  })
  it('角色、11版式、visual和稳定章节引用进入实际PATCH，刷新保持', async () => {
    let formal = outline(); const writes: Array<{ body: Record<string, unknown>; key: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (init.method === 'PATCH') { const body = JSON.parse(init.body as string); writes.push({ body, key: new Headers(init.headers).get('Idempotency-Key')! }); formal = { ...formal, revision: formal.revision + 1, pages: body.pages }; return ok(formal) }
      return ok(url.endsWith('/outline') ? formal : p())
    }))
    await render(<OutlineWorkspace project={p()} />)
    const layout = container.querySelector<HTMLSelectElement>('[aria-label="第 1 页布局"]')
    expect(layout).not.toBeNull(); expect(layout!.options).toHaveLength(11)
    change(layout!, 'image-right'); change(container.querySelector<HTMLSelectElement>('[aria-label="第 1 页角色"]')!, 'summary')
    change(container.querySelector<HTMLTextAreaElement>('[aria-label="第 1 页配图意图"]')!, '城市办公协作场景')
    const source = container.querySelector<HTMLInputElement>(`input[type=checkbox][value="${ref}"]`)!
    expect(source).not.toBeNull(); await act(async () => source.click())
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 970)) })
    expect(writes).toHaveLength(1); expect(writes[0].key).toBeTruthy(); expect(writes[0].body.revision).toBe(6)
    expect((writes[0].body.pages as Outline['pages'])[0]).toMatchObject({ title: '标题 1', objective: '独立目标', key_points: ['要点一', '要点二'], source_refs: [ref], layout_id: 'image-right', page_role: 'summary', visual: '城市办公协作场景' })
    await act(async () => { await client.invalidateQueries({ queryKey: ['projects', 'p', 'outline'] }) }); await settle()
    expect(layout!.value).toBe('image-right'); expect(source.checked).toBe(true)
  })
  it('可选visual清空为null且没有伪造配图、role或来源默认改写', async () => {
    let formal = outline(); formal.pages[0].visual = '旧图'; const writes: Outline['pages'][] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => { if (init.method === 'PATCH') { const body = JSON.parse(init.body as string); writes.push(body.pages); formal = { ...formal, pages: body.pages, revision: 7 }; return ok(formal) }; return ok(url.endsWith('/outline') ? formal : p()) }))
    await render(<OutlineWorkspace project={p()} />)
    const visual = container.querySelector<HTMLTextAreaElement>('[aria-label="第 1 页配图意图"]'); expect(visual).not.toBeNull(); change(visual!, '')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 970)) })
    expect(writes).toHaveLength(1); expect(writes[0][0]).toMatchObject({ visual: null, page_role: 'content', layout_id: 'bullets', source_refs: [] })
  })
  it('已有10个章节可移除失效引用，但不能新增第11个', async () => {
    const formal = outline(); formal.pages[0].source_refs = Array.from({ length: 10 }, (_, i) => `old-ref-${i}`)
    vi.stubGlobal('fetch', vi.fn(async () => ok(formal))); await render(<OutlineWorkspace project={p()} />)
    const extra = container.querySelector<HTMLInputElement>(`input[type=checkbox][value="${ref}"]`); expect(extra).not.toBeNull(); expect(extra!.disabled).toBe(true)
    const old = container.querySelector<HTMLInputElement>('input[type=checkbox][value="old-ref-0"]')!; expect(old.checked).toBe(true); expect(old.disabled).toBe(false)
    await act(async () => old.click()); expect(extra!.disabled).toBe(false)
  })
  it('只读提纲的新字段不可改，裁剪warning原样可读且不当作请求正文', async () => {
    useAuthStore.setState({ permissions: ['aippt:outline:query'] })
    const formal = { ...outline(), warnings: ['来源模型输入按预算裁剪：15000→12000字'], source_budget: { original_chars: 15000, used_chars: 12000 } }
    const writes: string[] = []; vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => { if (init.method) writes.push(init.method); return ok(formal) }))
    await render(<OutlineWorkspace project={p()} />)
    expect(container.textContent).toContain(formal.warnings[0])
    for (const label of ['第 1 页布局', '第 1 页角色', '第 1 页配图意图']) expect(container.querySelector(`[aria-label="${label}"]`)!.matches(':disabled')).toBe(true)
    expect(writes).toHaveLength(0)
  })
  it('正式裁剪信息随提纲版本回读更新，旧响应缺省保持兼容', async () => {
    let formal: Outline & { warnings?: string[]; source_budget?: { original_chars: number; used_chars: number } } = outline()
    vi.stubGlobal('fetch', vi.fn(async () => ok(formal))); await render(<OutlineWorkspace project={p()} />)
    expect(container.textContent).not.toContain('来源模型输入按预算裁剪')
    formal = { ...formal, revision: 7, warnings: ['来源模型输入按预算裁剪：13000→11000字'], source_budget: { original_chars: 13000, used_chars: 11000 } }
    await act(async () => { await client.invalidateQueries({ queryKey: ['projects', 'p', 'outline'] }) }); await settle()
    expect(container.textContent).toContain(formal.warnings![0])
  })
})

