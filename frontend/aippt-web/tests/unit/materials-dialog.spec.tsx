import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { platformRequest } from '@/api/client'
import { useAuthStore } from '@/features/auth/store'
import { ProjectMaterialsDialog } from '@/features/projects/ProjectMaterialsDialog'
import type { Project } from '@/features/projects/types'

const ok = (data: unknown, code = 200) => new Response(JSON.stringify({ code, data, msg: code === 409 ? '版本冲突' : '' }), { headers: { 'Content-Type': 'application/json' } })
const project = (id = 'p'): Project => ({ id, title: '正式名称'.repeat(20), audience: null, tone: 'professional', page_count: 40, theme_id: 'ivory', theme_overrides: {}, layout_mode: 'flex', content_density: 'medium', status: 'draft', input_revision: 7, theme_revision: 2, deck_revision: 4, created_at: '', updated_at: '' })
let root: Root, container: HTMLDivElement, client: QueryClient
const settle = async () => { await act(async () => { await new Promise((resolve) => setTimeout(resolve, 15)) }) }
const button = (label: string) => [...container.querySelectorAll('button')].find((element) => element.textContent?.includes(label))!
const input = (label: string) => { const id = [...container.querySelectorAll('label')].find((element) => element.textContent === label)?.htmlFor; return container.querySelector<HTMLInputElement>(`#${id}`)! }
const change = (element: HTMLInputElement | HTMLTextAreaElement, value: string) => act(() => { Object.getOwnPropertyDescriptor(element instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype, 'value')!.set!.call(element, value); element.dispatchEvent(new Event('input', { bubbles: true })) })
const render = async (id = 'p') => { await act(async () => root.render(<QueryClientProvider client={client}><MemoryRouter><ProjectMaterialsDialog projectId={id} onClose={vi.fn()} /></MemoryRouter></QueryClientProvider>)); await settle() }
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 } })))); await platformRequest('/getInfo')
  useAuthStore.setState({ permissions: ['*:*:*'] }); client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => { await act(async () => root.unmount()); client.clear(); container.remove(); vi.restoreAllMocks() })
describe('原工作台材料与参数入口', () => {
  it('正式40页及超过60字名称完整显示，未操作不脏；只改名称省略page_count', async () => {
    const writes: Record<string, unknown>[] = []; let server = project()
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (init.method === 'PATCH') { const body = JSON.parse(init.body as string); writes.push(body); server = { ...server, title: body.title, input_revision: 8 }; return ok(server) }
      return ok(url.endsWith('/outline') ? { status: 'empty', revision: 0, pages: [] } : url.endsWith('/sources') ? [] : server)
    }))
    await render(); expect(input('项目名称').value).toBe(server.title); expect(input('目标页数').value).toBe('40'); expect(button('保存参数').disabled).toBe(true)
    change(input('项目名称'), '新名称'); act(() => button('保存参数').click()); await settle()
    expect(writes).toEqual([{ title: '新名称', input_revision: 7 }]); expect(input('目标页数').value).toBe('40')
  })
  it('dirty期间正式40→39不借新revision；409核对后仅重应用实际改字段', async () => {
    const writes: Record<string, unknown>[] = []; let server = project(); let conflict = true
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (init.method === 'PATCH') { const body = JSON.parse(init.body as string); writes.push(body); if (conflict) return ok(null, 409); server = { ...server, ...body, input_revision: 9 }; return ok(server) }
      return ok(url.endsWith('/outline') ? { status: 'empty', revision: 0, pages: [] } : url.endsWith('/sources') ? [] : server)
    }))
    await render(); change(input('项目名称'), '我的名称'); server = { ...server, page_count: 39, input_revision: 8, audience: '远端受众', tone: 'plain' }
    await act(async () => { await client.invalidateQueries({ queryKey: ['projects', 'p'], exact: true }) }); await settle()
    expect(input('目标页数').value).toBe('40'); act(() => button('保存参数').click()); await settle()
    expect(writes[0]).toEqual({ title: '我的名称', input_revision: 7 })
    act(() => button('核对最新版本').click()); await settle(); expect(container.textContent).toContain('远端受众')
    conflict = false; act(() => button('重新应用我的修改').click()); await settle()
    expect(writes[1]).toEqual({ title: '我的名称', input_revision: 8 }); expect(input('目标页数').value).toBe('39'); expect(input('受众').value).toBe('远端受众')
  })
  it('用户手改非法新目标40拒绝发请求，合法目标10可正常保存', async () => {
    let server = { ...project(), page_count: 12 }; const writes: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => { if (init.method === 'PATCH') { writes.push(JSON.parse(init.body as string)); server = { ...server, page_count: 10, input_revision: 8 }; return ok(server) }; return ok(url.endsWith('/outline') ? { status: 'empty', pages: [] } : url.endsWith('/sources') ? [] : server) }))
    await render(); change(input('目标页数'), '40'); act(() => button('保存参数').click()); await settle(); expect(writes).toHaveLength(0); expect(container.textContent).toContain('新的目标页数需为5–20')
    change(input('目标页数'), '10'); act(() => button('保存参数').click()); await settle(); expect(writes).toEqual([{ page_count: 10, input_revision: 7 }])
  })
  it('已提交断网后禁后续编辑，重试原正文/key，回读期间仍禁用', async () => {
    const writes: Array<{ body: string; key: string }> = []; let server = project(); let lost = true; let blockRead = false; let release!: () => void
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (init.method === 'PATCH') { writes.push({ body: init.body as string, key: new Headers(init.headers).get('Idempotency-Key')! }); server = { ...server, title: '已提交名称', input_revision: 8 }; if (lost) { lost = false; throw new TypeError('回执断网') }; blockRead = true; return ok(server) }
      if (blockRead && url.endsWith('/projects/p')) await new Promise<void>((resolve) => { release = resolve })
      return ok(url.endsWith('/outline') ? { status: 'empty', pages: [] } : url.endsWith('/sources') ? [] : server)
    }))
    await render(); change(input('项目名称'), '已提交名称'); act(() => button('保存参数').click()); await settle(); expect(input('项目名称').disabled).toBe(false); expect(input('项目名称').closest('fieldset')!.disabled).toBe(true)
    act(() => button('重试原操作').click()); await settle(); expect(input('项目名称').closest('fieldset')!.disabled).toBe(true)
    blockRead = false; await act(async () => { release() }); await settle(); expect(writes[1]).toEqual(writes[0]); expect(button('保存参数').disabled).toBe(true)
  })
  it('只有source:add的合法用户仍能补传，上传项目revision延迟后切B不发送A文件', async () => {
    useAuthStore.setState({ permissions: ['aippt:project:query', 'aippt:source:add', 'aippt:source:query'] })
    let reads = 0; let release!: (response: Response) => void; const uploads: string[] = []
    vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit) => {
      if (url.endsWith('/projects/p') && ++reads === 2) return new Promise<Response>((resolve) => { release = resolve })
      if (url.endsWith('/upload')) uploads.push(url)
      return Promise.resolve(ok(url.endsWith('/sources') ? [] : project(url.includes('/projects/b') ? 'b' : 'p')))
    }))
    await render(); const file = container.querySelector<HTMLInputElement>('input[type=file]')!; expect(file.closest('fieldset')!.disabled).toBe(false)
    act(() => { Object.defineProperty(file, 'files', { value: [new File(['a'], 'a.txt')] }); file.dispatchEvent(new Event('change', { bubbles: true })) }); act(() => button('上传待传文档').click()); await settle()
    await render('b'); await act(async () => { release(ok(project())) }); await settle(); expect(uploads).toHaveLength(0); expect(container.textContent).not.toContain('a.txt')
  })
  it('章节A迟到不覆盖后来选中的章节B', async () => {
    let release!: (response: Response) => void; const sources = ['a', 'b'].map((id) => ({ id, kind: 'document', filename: `${id}.txt`, parse_status: 'ready', warnings: [], sections: [], char_count: 1 }))
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/a/sections')) return new Promise<Response>((resolve) => { release = resolve })
      return Promise.resolve(ok(url.endsWith('/b/sections') ? [{ id: 'sec-b', heading: 'B章节', text: 'B内容', level: 1 }] : url.endsWith('/sources') ? sources : url.endsWith('/outline') ? { status: 'empty', pages: [] } : project()))
    }))
    await render(); const views = [...container.querySelectorAll('button')].filter((element) => element.textContent === '查看章节'); act(() => views[0]!.click()); await settle(); act(() => views[1]!.click()); await settle(); expect(container.textContent).toContain('B内容')
    await act(async () => release(ok([{ heading: 'A章节', text: 'A迟到内容' }]))); await settle(); expect(container.textContent).not.toContain('A迟到内容'); expect(container.textContent).toContain('B内容')
  })
})
