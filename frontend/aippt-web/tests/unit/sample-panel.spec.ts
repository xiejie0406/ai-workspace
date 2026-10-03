import { readFileSync } from 'node:fs'
import path from 'node:path'
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { platformRequest } from '@/api/client'
import { useAuthStore } from '@/features/auth/store'
import { SamplePanel } from '@/features/design/SamplePanel'
import { checkSampleDeck, downloadSampleDeck, fetchSampleDeck, sampleCatalogVersion } from '@/features/design/api'
import type { SampleExportCommand } from '@/features/design/types'
import { saveBlob } from '@/lib/download'
import { getTheme } from '@/render/design'

vi.mock('@/lib/download', async (original) => ({ ...await original<typeof import('@/lib/download')>(), saveBlob: vi.fn() }))
const raw = JSON.parse(readFileSync(path.resolve(import.meta.dirname, '../../../../contracts/aippt/catalog/sample-deck.json'), 'utf8'))
const sample = (theme = 'ivory') => ({ ...structuredClone(raw), theme_id: theme, catalog_version: sampleCatalogVersion })
const ok = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } })
const clean = { catalog_version: sampleCatalogVersion, can_export: true, fonts_available: true, issues: [] }
const warning = { ...clean, fonts_available: false, font_mode: 'estimate', issues: [{ severity: 'warning', code: 'font_estimate', slide_id: null, slot_id: null, message: '字体使用估算' }] }
const verify = { passed: false, slide_count: 11, expected_slide_count: 11, diagnostics_truncated: false, total_issues: 1, issues: [{ check: 'speaker_notes', slide_index: 1, shape: 'notes', message: '演讲备注未保留' }] }
let root: Root | null = null
let container: HTMLDivElement | null = null
const settle = () => act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve() })
async function mount(themeId = 'ivory') {
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
  await act(async () => root!.render(createElement(SamplePanel, { themeId, onClose: vi.fn() })))
  await settle()
}
function button(text: string) {
  const found = [...container!.querySelectorAll<HTMLButtonElement>('button')].find((node) => node.textContent?.trim() === text)
  if (!found) throw new Error(`未找到按钮：${text}`)
  return found
}
async function click(text: string) { await act(async () => button(text).click()); await settle() }
const command = (): SampleExportCommand => ({ key: crypto.randomUUID(), body: JSON.stringify({ theme_id: 'ivory', acknowledge_warnings: false }) })

beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.restoreAllMocks(); vi.mocked(saveBlob).mockClear()
  document.cookie = `Admin-Token=${crypto.randomUUID()}; Path=/`
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 200, user: { userId: 1 }, permissions: ['*:*:*'] }))))
  await platformRequest('/getInfo')
  useAuthStore.setState({ permissions: ['*:*:*'] })
})
afterEach(async () => {
  if (root) await act(async () => root!.unmount())
  container?.remove(); root = null; container = null; vi.restoreAllMocks(); vi.unstubAllGlobals()
})

describe('样例实际中心接口适配', () => {
  it('读取同版本原样例11页，不增加或改写正式内容引用', async () => {
    const value = sample(); const original = JSON.stringify(value)
    const fetcher = vi.fn().mockResolvedValue(ok(value)); vi.stubGlobal('fetch', fetcher)
    expect(await fetchSampleDeck('ivory')).toEqual(value)
    expect(value.slides).toHaveLength(11); expect(JSON.stringify(value)).toBe(original)
    expect(fetcher.mock.calls[0]![0]).toContain('/aippt/design/sample-deck?theme_id=ivory')
  })
  it.each(['catalog', 'theme', 'layout'])('拒绝不匹配的样例%s，不以本地旧资源渲染', async (kind) => {
    const value = sample()
    if (kind === 'catalog') value.catalog_version = 'old'
    if (kind === 'theme') value.theme_id = 'midnight'
    if (kind === 'layout') value.slides[0].layout_id = 'unknown-layout'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(value)))
    await expect(fetchSampleDeck('ivory')).rejects.toMatchObject({ status: kind === 'catalog' ? 409 : 502 })
  })
  it('质量accepted经任务成功再读取当前缓存，保留字体估算和警告', async () => {
    const calls: string[] = []; let check = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      if (url.includes('/tasks/')) return ok({ status: 'succeeded', output: { can_export: false, issues: [] } })
      return ok(++check === 1 ? { job_id: 'quality-job', status: 'queued', catalog_version: sampleCatalogVersion } : warning)
    }))
    expect(await checkSampleDeck('ivory')).toEqual(warning)
    expect(calls).toHaveLength(3); expect(calls[1]).toContain('/tasks/quality-job')
  })
  it('有效质量缓存不重复创建任务；未返回报告明确拒绝', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(ok(clean)).mockResolvedValueOnce(ok({ status: 'running', catalog_version: sampleCatalogVersion }))
    vi.stubGlobal('fetch', fetcher)
    expect(await checkSampleDeck('ivory')).toEqual(clean)
    await expect(checkSampleDeck('midnight')).rejects.toMatchObject({ status: 502 })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('异步export取独立制品id，用私有下载接口和中文文件名', async () => {
    const calls: Array<{ url: string; init: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init })
      if (url.endsWith('/sample-deck/exports')) return ok({ id: 'artifact', job_id: 'export-job', status: 'queued' })
      if (url.endsWith('/tasks/export-job')) return ok({ status: 'succeeded' })
      if (url.endsWith('/exports/artifact')) return ok({ id: 'artifact', status: 'ready', download_url: 'https://untrusted.invalid/never-used' })
      return new Response('offline-pptx', { headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.presentationml.presentation', 'Content-Disposition': "attachment; filename*=UTF-8''%E6%A0%B7%E4%BE%8B.pptx" } })
    }))
    const own = command(); await downloadSampleDeck(own)
    expect(calls.map((row) => row.url)).toEqual(expect.arrayContaining([expect.stringContaining('/tasks/export-job'), expect.stringContaining('/exports/artifact/download')]))
    expect(calls).toHaveLength(4)
    expect(JSON.parse(calls[0]!.init.body as string)).toEqual({ theme_id: 'ivory', acknowledge_warnings: false })
    expect(new Headers(calls[0]!.init.headers).get('Idempotency-Key')).toBe(own.key)
    expect(saveBlob).toHaveBeenCalledOnce(); expect(vi.mocked(saveBlob).mock.calls[0]![1]).toBe('样例.pptx')
  })
  it('未知POST回执重放同body/key；已确认job不重复POST', async () => {
    const writes: Array<{ body: string; key: string }> = []; let lost = true; let exportGet = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/sample-deck/exports')) {
        writes.push({ body: init.body as string, key: new Headers(init.headers).get('Idempotency-Key')! })
        if (lost) { lost = false; throw new TypeError('回执丢失') }
        return ok({ id: 'artifact', jobId: 'export-job' })
      }
      if (url.endsWith('/tasks/export-job')) return ok({ status: 'succeeded' })
      if (url.endsWith('/exports/artifact')) { if (++exportGet === 1) throw new TypeError('状态回执丢失'); return ok({ id: 'artifact', status: 'ready' }) }
      return new Response('offline-pptx')
    }))
    const own = command()
    await expect(downloadSampleDeck(own)).rejects.toThrow('回执丢失')
    await expect(downloadSampleDeck(own)).rejects.toThrow('状态回执丢失')
    await downloadSampleDeck(own)
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); expect(saveBlob).toHaveBeenCalledOnce()
  })
  it('真实字段形状的回读失败保留全部问题，且从不取下载/Blob', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      if (url.endsWith('/sample-deck/exports')) return ok({ id: 'artifact', jobId: 'export-job' })
      if (url.includes('/tasks/')) return ok({ status: 'failed', verification_failure: verify })
      return ok({ id: 'artifact', status: 'failed', verify })
    }))
    const own = command()
    await expect(downloadSampleDeck(own)).rejects.toMatchObject({ status: 422, detail: { ...verify, message: '样例 PPTX 回读验证未通过，未返回文件' } })
    expect(own.terminal).toBe(true); expect(calls.some((url) => url.endsWith('/download'))).toBe(false); expect(saveBlob).not.toHaveBeenCalled()
  })
  it('普通失败也是明确终态，允许界面下一次使用新命令', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ok(url.endsWith('/sample-deck/exports') ? { id: 'artifact', jobId: 'job' } : { id: 'artifact', status: 'failed', error: '算法任务失败' })))
    const own = command(); await expect(downloadSampleDeck(own)).rejects.toThrow('算法任务失败'); expect(own.terminal).toBe(true)
  })
  it('读取Blob期间关闭/取消，不能再触发迟到下载', async () => {
    const abort = new AbortController(); let release!: (value: Blob) => void
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/sample-deck/exports')) return ok({ id: 'artifact', jobId: 'job' })
      if (url.includes('/tasks/')) return ok({ status: 'succeeded' })
      if (url.endsWith('/exports/artifact')) return ok({ id: 'artifact', status: 'ready' })
      const response = new Response('offline-pptx'); vi.spyOn(response, 'blob').mockImplementation(() => new Promise((resolve) => { release = resolve })); return response
    }))
    const pending = downloadSampleDeck(command(), abort.signal); await settle(); abort.abort(); release(new Blob(['pptx']))
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' }); expect(saveBlob).not.toHaveBeenCalled()
  })
})

describe('原Dialog/渲染器内样例交互', () => {
  it('无design权限不发请求；有浏览权限保留四主题与11布局', async () => {
    useAuthStore.setState({ permissions: [] }); const fetcher = vi.fn().mockResolvedValue(ok(sample())); vi.stubGlobal('fetch', fetcher)
    await mount(); expect(fetcher).not.toHaveBeenCalled(); expect(container!.textContent).toContain('没有查看样例的权限')
    await act(async () => useAuthStore.setState({ permissions: ['aippt:design:query'] })); await settle()
    expect(container!.querySelector('[aria-label="样例主题"]')!.children).toHaveLength(4)
    expect(container!.querySelector('[aria-label="样例页面"]')!.children).toHaveLength(11)
    expect(button('检查样例质量').disabled).toBe(false); expect(button('导出样例 PPTX').disabled).toBe(true)
  })
  it('质量error阻断导出；warning展示估算且必须单独确认', async () => {
    let blocked = true
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ok(url.includes('/issues') ? blocked ? { ...clean, can_export: false, issues: [{ severity: 'error', slide_id: null, slot_id: 'image', message: '图片损坏' }] } : warning : sample())))
    await mount(); await click('检查样例质量')
    expect(button('导出样例 PPTX').disabled).toBe(true); expect(container!.textContent).toContain('图片损坏')
    blocked = false; await click('检查样例质量')
    expect(container!.textContent).toContain('文字溢出为估算值'); expect(button('导出样例 PPTX').disabled).toBe(true)
    await act(async () => container!.querySelector<HTMLInputElement>('input[type=checkbox]')!.click())
    expect(button('导出样例 PPTX').disabled).toBe(false)
  })
  it('切换主题取消旧质量回执，不覆盖新主题或编造页序', async () => {
    let release!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/issues')) return new Promise<Response>((resolve) => { release = resolve })
      return ok(sample(url.includes('midnight') ? 'midnight' : 'ivory'))
    }))
    await mount(); await click('检查样例质量')
    await act(async () => root!.render(createElement(SamplePanel, { themeId: 'midnight', onClose: vi.fn() }))); await settle()
    await act(async () => release(ok({ ...clean, issues: [{ severity: 'error', slide_id: 'private-uuid', slot_id: null, message: '旧主题问题' }] }))); await settle()
    expect(container!.textContent).not.toContain('旧主题问题'); expect(container!.querySelector('[aria-label="样例主题"] [aria-pressed=true]')!.textContent).toBe(getTheme('midnight').name)
    expect(button('导出样例 PPTX').disabled).toBe(true)
  })
  it('关闭弹层后旧读取不能重新写入页面或启动下载', async () => {
    let release!: (response: Response) => void; vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { release = resolve })))
    await mount(); await act(async () => root!.unmount()); root = null
    await act(async () => release(ok(sample()))); await settle()
    expect(container!.textContent).toBe(''); expect(saveBlob).not.toHaveBeenCalled()
  })
  it('仅按中心明确UUID映射定位样例页；未知页不推算或改写正式issue', async () => {
    const report = { ...clean, can_export: false, sample_slide_ids: { 'private-uuid': 's02' }, issues: [
      { severity: 'error', slide_id: 'private-uuid', slot_id: 'body', message: '第二页正文溢出' },
      { severity: 'error', slide_id: 'unknown-uuid', slot_id: 'image', message: '未映射的图片问题' },
    ] }
    const before = JSON.stringify(report)
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ok(url.includes('/issues') ? report : sample())))
    await mount(); await click('检查样例质量'); await click('第 2 页')
    expect(container!.querySelector('[aria-label="样例页面"] [aria-pressed=true]')!.textContent).toContain('2 ·')
    expect(container!.querySelectorAll('[aria-label="样例质量问题"] button')).toHaveLength(1)
    expect(JSON.stringify(report)).toBe(before)
  })
  it('只有样例浏览/导出权限即可消费样例任务，不额外要求deck查询权限', async () => {
    useAuthStore.setState({ permissions: ['aippt:design:query', 'aippt:export:create', 'aippt:export:download'] })
    const calls: string[] = []; let checks = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      if (url.includes('/issues')) return ok(++checks === 1 ? { job_id: 'quality-job', catalog_version: sampleCatalogVersion } : clean)
      if (url.includes('/tasks/')) return ok({ status: 'succeeded' })
      if (url.endsWith('/sample-deck/exports')) return ok({ id: 'artifact', jobId: 'export-job' })
      if (url.endsWith('/exports/artifact')) return ok({ id: 'artifact', status: 'ready' })
      if (url.endsWith('/download')) return new Response('offline-pptx')
      return ok(sample())
    }))
    await mount(); await click('检查样例质量'); expect(button('导出样例 PPTX').disabled).toBe(false)
    await click('导出样例 PPTX')
    expect(calls).toContainEqual(expect.stringContaining('/tasks/quality-job'))
    expect(calls).toContainEqual(expect.stringContaining('/tasks/export-job'))
    expect(saveBlob).toHaveBeenCalledOnce(); expect(container!.textContent).toContain('已开始下载')
  })
  it('目录变化的质量报告不能进入可导出状态', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ok(url.includes('/issues') ? { ...clean, catalog_version: 'another-version' } : sample())))
    await mount(); await click('检查样例质量')
    expect(button('导出样例 PPTX').disabled).toBe(true); expect(container!.textContent).toContain('样例质量目录版本与当前页面不一致')
  })
})
