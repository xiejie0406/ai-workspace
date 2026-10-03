import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/features/auth/store'
import { EditorWorkspace } from '@/features/deck/EditorWorkspace'
import { ExportDialog } from '@/features/deck/ExportDialog'
import type { Deck, ExportCheckReport } from '@/features/deck/types'
import type { ProjectDetail } from '@/features/projects/types'

const state = vi.hoisted(() => ({
  generating: false,
  quality: null as unknown,
  exporter: null as unknown,
}))
const mutation = () => ({ mutate: vi.fn(), isPending: false, error: null })
vi.mock('@/features/deck/api', () => ({
  useDeck: (id: string) => ({ data: deck(id, state.generating), isPending: false }),
  useGenerateDeck: () => mutation(), useCancelDeck: () => mutation(),
  useReorderSlides: () => mutation(), useInsertSlide: () => mutation(),
  useDuplicateSlide: () => mutation(), useDeleteSlide: () => mutation(),
  useRetrySlide: () => mutation(), useDeckQuality: () => state.quality,
  useExportDeck: () => state.exporter,
}))
vi.mock('@/features/deck/useDeckProgress', () => ({ useDeckProgress: () => ({ event: null, deliveryMessage: null }) }))
vi.mock('@/hooks/useSaveNavigationGuard', () => ({ useSaveNavigationGuard: () => null }))
vi.mock('@/features/projects/ProjectMaterialsDialog', () => ({ ProjectMaterialsButton: () => null }))
vi.mock('@/features/deck/EditorRail', () => ({ EditorRail: () => null }))
vi.mock('@/features/deck/SlideStage', () => ({ SlideStage: () => null }))
vi.mock('@/features/deck/RelayoutPanel', () => ({ RelayoutDock: () => null }))
vi.mock('@/render/SlideView', () => ({ SlideView: ({ slide }: { slide: { id: string } }) => <span data-slide={slide.id}>原生页面预览</span> }))

function deck(id = 'p', generating = false): Deck {
  return {
    id, project_id: id, title: '焦点与诊断核对', theme_id: 'ivory', status: generating ? 'generating' : 'ready',
    deck_revision: 4, theme_revision: 3, ready: 3, failed: 0, total: 3,
    slides: Array.from({ length: 3 }, (_, i) => ({
      id: `${id}-s${i + 1}`, project_id: id, title: `第${i + 1}页`, position: i + 1,
      layout_id: 'bullets', layout_mode: 'fixed', layout_tree: null, blocks: [], issues: [],
      status: 'ready', revision: 1, speaker_notes: null, error: null, created_at: '', updated_at: '',
    })),
  } as Deck
}
function project(id = 'p'): ProjectDetail {
  return { id, title: '焦点与诊断核对', audience: null, tone: 'professional', page_count: 5,
    theme_id: 'ivory', theme_overrides: {}, layout_mode: 'fixed', content_density: 'medium', status: 'ready',
    sources: [], input_revision: 1, theme_revision: 3, deck_revision: 4, created_at: '', updated_at: '' } as ProjectDetail
}
let root: Root, container: HTMLDivElement
const fullscreenDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'requestFullscreen')
const fullscreenElementDescriptor = Object.getOwnPropertyDescriptor(document, 'fullscreenElement')
const exitFullscreenDescriptor = Object.getOwnPropertyDescriptor(document, 'exitFullscreen')
const render = async (child: React.ReactNode) => { await act(async () => root.render(<MemoryRouter>{child}</MemoryRouter>)) }
const click = async (node: HTMLElement) => { await act(async () => { node.click() }) }
const key = async (value: string) => { await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true }))) }
const byText = (value: string) => [...container.querySelectorAll('button')].find(node => node.textContent?.trim() === value)!
const present = () => container.querySelector<HTMLElement>('[aria-label="演示放映"]')
async function openPresentation() {
  const trigger = byText('演示'); trigger.focus(); await click(trigger)
  expect(present()).not.toBeNull()
  return trigger
}
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  document.cookie = 'Admin-Token=focus-session-a; Path=/'
  useAuthStore.setState({ permissions: ['*:*:*'] })
  state.generating = false
  state.quality = { data: { export_allowed: true, fonts_precise: false, issues: [] } as ExportCheckReport, isError: false, isFetching: false, isPending: false, error: null, refetch: vi.fn() }
  state.exporter = { isError: false, isPending: false, error: null, commandVersion: null, mutate: vi.fn() }
  vi.stubGlobal('fetch', vi.fn(() => { throw new Error('此隔离组件检查禁止网络请求') }))
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
})
afterEach(async () => {
  await act(async () => root.unmount()); container.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals()
  if (fullscreenDescriptor) Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', fullscreenDescriptor)
  else delete (HTMLElement.prototype as unknown as { requestFullscreen?: unknown }).requestFullscreen
  if (fullscreenElementDescriptor) Object.defineProperty(document, 'fullscreenElement', fullscreenElementDescriptor)
  else delete (document as unknown as { fullscreenElement?: unknown }).fullscreenElement
  if (exitFullscreenDescriptor) Object.defineProperty(document, 'exitFullscreen', exitFullscreenDescriptor)
  else delete (document as unknown as { exitFullscreen?: unknown }).exitFullscreen
})

describe('原放映退出恢复有效入口焦点', () => {
  it('从第3页放映，点击进度再Esc恢复入口；不改变编辑器原选页', async () => {
    await render(<EditorWorkspace project={project()} />)
    const third = container.querySelectorAll<HTMLButtonElement>('nav[aria-label="页面列表"] button[aria-current]')[2]!
    await click(third)
    const trigger = await openPresentation()
    expect(present()!.querySelector('[aria-label="第 3 页"]')?.getAttribute('aria-current')).toBe('true')
    const progress = present()!.querySelector<HTMLButtonElement>('[aria-label="第 1 页"]')!
    progress.focus(); await click(progress); await key('Escape')
    expect(present()).toBeNull(); expect(document.activeElement).toBe(trigger)
    expect(third.getAttribute('aria-current')).toBe('true')
    expect(document.body.style.overflow).not.toBe('hidden')
  })
  it('拒绝全屏仍可放映，点击退出后恢复入口', async () => {
    const request = vi.fn().mockRejectedValue(new Error('浏览器拒绝全屏'))
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, value: request })
    await render(<EditorWorkspace project={project()} />); const trigger = await openPresentation()
    await key('F'); expect(request).toHaveBeenCalledTimes(1); expect(present()).not.toBeNull()
    const exit = present()!.querySelector<HTMLButtonElement>('[aria-label="退出放映"]')!
    exit.focus(); await click(exit); expect(document.activeElement).toBe(trigger)
  })
  it('项目切换关闭旧放映，不把旧入口焦点写入新项目', async () => {
    await render(<EditorWorkspace project={project('a')} />); const trigger = await openPresentation()
    const focus = vi.spyOn(trigger, 'focus')
    await render(<EditorWorkspace project={project('b')} />)
    expect(present()).toBeNull(); expect(focus).not.toHaveBeenCalled()
  })
  it('退出时会话已变化不恢复旧入口', async () => {
    await render(<EditorWorkspace project={project()} />); const trigger = await openPresentation()
    const focus = vi.spyOn(trigger, 'focus'); document.cookie = 'Admin-Token=focus-session-b; Path=/'
    await key('Escape'); expect(present()).toBeNull(); expect(focus).not.toHaveBeenCalled()
  })
  it('入口已因生成状态卸载时，退出不聚焦断开节点', async () => {
    await render(<EditorWorkspace project={project()} />); const trigger = await openPresentation()
    const focus = vi.spyOn(trigger, 'focus'); state.generating = true
    await render(<EditorWorkspace project={project()} />); expect(trigger.isConnected).toBe(false)
    await key('Escape'); expect(focus).not.toHaveBeenCalled()
  })
  it('平台会话变化通知关闭旧放映，卸载不延迟抢新焦点', async () => {
    await render(<EditorWorkspace project={project()} />); const trigger = await openPresentation()
    const focus = vi.spyOn(trigger, 'focus'); document.cookie = 'Admin-Token=focus-session-b; Path=/'
    await act(async () => window.dispatchEvent(new Event('aippt:session-change')))
    expect(present()).toBeNull(); expect(focus).not.toHaveBeenCalled()
  })
  it('实际退出全屏事件晚于卸载时，等事件完成后只恢复一次入口', async () => {
    await render(<EditorWorkspace project={project()} />); const trigger = await openPresentation()
    const focus = vi.spyOn(trigger, 'focus')
    let fullscreen: Element | null = present()
    let finish!: () => void
    const exit = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreen })
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exit })
    const close = present()!.querySelector<HTMLButtonElement>('[aria-label="退出放映"]')!
    close.focus(); await click(close)
    expect(present()).toBeNull(); expect(exit).toHaveBeenCalledTimes(1); expect(focus).not.toHaveBeenCalled()
    fullscreen = null
    await act(async () => { finish(); document.dispatchEvent(new Event('fullscreenchange')) })
    expect(document.activeElement).toBe(trigger); expect(focus).toHaveBeenCalledTimes(1)
    await act(async () => document.dispatchEvent(new Event('fullscreenchange')))
    expect(focus).toHaveBeenCalledTimes(1)
  })
  it('等待退出全屏期间切换项目与会话，迟到事件不抢新入口焦点', async () => {
    await render(<EditorWorkspace project={project('a')} />); const trigger = await openPresentation()
    const focus = vi.spyOn(trigger, 'focus')
    let fullscreen: Element | null = present()
    let finish!: () => void
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreen })
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: () => new Promise<void>(resolve => { finish = resolve }) })
    await click(present()!.querySelector<HTMLButtonElement>('[aria-label="退出放映"]')!)
    document.cookie = 'Admin-Token=focus-session-b; Path=/'
    await render(<EditorWorkspace project={project('b')} />)
    const nextTrigger = byText('演示'); nextTrigger.focus()
    // React 可复用同一按钮节点；排除本行主动聚焦，只检查后续全屏回执。
    focus.mockClear()
    fullscreen = null
    await act(async () => { finish(); document.dispatchEvent(new Event('fullscreenchange')) })
    expect(focus).not.toHaveBeenCalled(); expect(document.activeElement).toBe(nextTrigger)
  })
})

describe('普通导出保留中心回读失败的定位与有界诊断', () => {
  // 独立预期来自中心 VerifyReport 契约，shape/check不是通用异常正文。
  const failure = (issues: Array<Record<string, unknown>>, meta: Record<string, unknown> = {}) => {
    state.exporter = { isError: true, isPending: false, commandVersion: null, mutate: vi.fn(), error: new ApiError(422, {
      passed: false, message: '导出回读验证未通过，未返回文件', slide_count: 3, expected_slide_count: 3,
      issues, diagnostics_truncated: false, total_issues: issues.length, ...meta,
    }) }
  }
  it('普通Dialog显示原页号、中文检查项、形状及中心截断原始数量', async () => {
    failure([{ check: 'native_chart', slide_index: 2, shape: '收入图表', message: '图表不可编辑' }], { diagnostics_truncated: true, total_issues: 3200 })
    await render(<ExportDialog projectId="p" deck={deck()} onClose={vi.fn()} onLocate={vi.fn()} />)
    const alert = container.querySelector('[role="alert"]')!
    expect(alert.textContent).toContain('第 2 页'); expect(alert.textContent).toContain('原生图表')
    expect(alert.textContent).toContain('收入图表'); expect(alert.textContent).toContain('图表不可编辑')
    expect(alert.textContent).toContain('诊断已截断'); expect(alert.textContent).toContain('3200')
    expect(container.textContent).toContain('估算值')
  })
  it('完整备注诊断保留文本转义且不虚构截断', async () => {
    failure([{ check: 'speaker_notes', slide_index: 1, shape: '<img src=x onerror=alert(1)>', message: '备注原文不一致' }])
    await render(<ExportDialog projectId="p" deck={deck()} onClose={vi.fn()} onLocate={vi.fn()} />)
    const alert = container.querySelector('[role="alert"]')!
    expect(alert.textContent).toContain('演讲备注'); expect(alert.textContent).toContain('<img src=x onerror=alert(1)>')
    expect(alert.querySelector('img')).toBeNull(); expect(alert.textContent).not.toContain('诊断已截断')
  })
  it('意外超长Unicode诊断按契约预算有界显示并说明本地截断', async () => {
    failure([{ check: 'native_image', slide_index: 3, shape: '🙂'.repeat(201), message: '🚀'.repeat(1001) }])
    await render(<ExportDialog projectId="p" deck={deck()} onClose={vi.fn()} onLocate={vi.fn()} />)
    const alert = container.querySelector('[role="alert"]')!
    const text = alert.textContent!
    expect(text).toContain('原生图片'); expect(text).not.toContain('🙂'.repeat(201)); expect(text).not.toContain('🚀'.repeat(1001))
    expect(text).toContain('诊断已截断'); expect(text).not.toContain('\uFFFD')
  })
})
