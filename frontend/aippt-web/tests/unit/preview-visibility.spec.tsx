import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SlideStage } from '@/features/deck/SlideStage'
import { Filmstrip } from '@/features/deck/Filmstrip'
import { getSlideSaveHandlers } from '@/features/deck/slideSaveBridge'
import type { DeckSlide } from '@/features/deck/types'
import { getTheme } from '@/render/design'

vi.mock('@/features/auth/store', () => ({ usePermission: () => true }))
vi.mock('@/features/deck/api', () => ({ useRetrySlide: () => ({ isPending: false, mutate: vi.fn() }) }))
vi.mock('@/features/deck/useSlideSaveQueue', () => ({
  useSlideSaveQueue: () => ({ commit: vi.fn(), commitStyle: vi.fn(), commitFlex: vi.fn(), commitCreateBlock: vi.fn(), commitDeleteBlock: vi.fn(), commitMutate: vi.fn(), mutateAsync: vi.fn(), flush: vi.fn().mockResolvedValue(undefined), pending: () => false, undo: vi.fn(), redo: vi.fn(), canUndo: false, canRedo: false, historyTick: 0, status: 'idle', error: null, refresh: vi.fn(), retrySave: vi.fn() }),
}))
// 原渲染算法另由黄金语料覆盖；此处使用有本地DOM草稿的子树，核对首次延迟/保留及正式props更新。
vi.mock('@/render/SlideView', () => ({
  SlideView: ({ slide, theme }: { slide: { id: string; blocks: Array<{ text: string }> }; theme: { id: string } }) => <div data-rendered-slide={slide.id} data-theme={theme.id}><input aria-label={'草稿-' + slide.id} defaultValue={slide.blocks[0]?.text} /><span>{slide.blocks[0]?.text}</span></div>,
}))
class Observer {
  static all: Observer[] = []
  callback: IntersectionObserverCallback
  nodes = new Set<Element>()
  options: IntersectionObserverInit
  closed = false
  constructor(callback: IntersectionObserverCallback, options: IntersectionObserverInit = {}) { this.callback = callback; this.options = options; Observer.all.push(this) }
  observe(node: Element) { this.nodes.add(node) }
  unobserve(node: Element) { this.nodes.delete(node) }
  disconnect() { this.closed = true; this.nodes.clear() }
  takeRecords() { return [] }
  emit(node: Element, visible: boolean) { this.callback([{ target: node, isIntersecting: visible, intersectionRatio: visible ? 1 : 0 } as IntersectionObserverEntry], this as unknown as IntersectionObserver) }
}
const slides = (): DeckSlide[] => Array.from({ length: 40 }, (_, i) => ({ id: 's' + (i + 1), project_id: 'p', title: '第' + (i + 1) + '页', position: i + 1, status: 'ready', revision: 1, layout_id: 'bullets', layout_mode: 'fixed', blocks: [{type: 'text', id: 'b' + i, slot_id: 'body', text: '第' + (i + 1) + '页'}], issues: [], error: null, speaker_notes: null } as DeckSlide))
let root: Root, box: HTMLDivElement
const select = vi.fn()
const renderStage = async (items = slides(), activeId = 's1', projectId = 'p', theme = getTheme('ivory')) => { await act(async () => root.render(<SlideStage projectId={projectId} slides={items} activeId={activeId} scrollRequest={null} theme={theme} locked={false} zoom={0.8} canZoomIn canZoomOut selection={null} onSelectionChange={vi.fn()} onActiveChange={select} onOpenRelayout={vi.fn()} onZoom={vi.fn()} />)) }
const renderFilm = async (items = slides(), activeId = 's1') => { await act(async () => root.render(<Filmstrip projectId="p" slides={items} theme={getTheme('ivory')} activeId={activeId} locked={false} busy={false} onSelect={select} onReorder={vi.fn()} onInsert={vi.fn()} onDuplicate={vi.fn()} onDelete={vi.fn()} />)) }
const signal = (node: Element, visible: boolean) => act(() => { for (const observer of Observer.all) if (!observer.closed && observer.nodes.has(node)) observer.emit(node, visible) })
beforeEach(() => { Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); Observer.all = []; vi.stubGlobal('IntersectionObserver', Observer); box = document.createElement('div'); document.body.append(box); root = createRoot(box) })
afterEach(async () => { await act(async () => root.unmount()); box.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('四十页首次渲染与原草稿保留', () => {
  it('画布只渲染选中页，未访问39页延迟，但所有原保存队列已注册', async () => {
    await renderStage()
    expect(box.querySelectorAll('article[data-slide-id]')).toHaveLength(40)
    expect(box.querySelectorAll('[data-rendered-slide]')).toHaveLength(1)
    expect(getSlideSaveHandlers('s40')?.projectId).toBe('p')
  })
  it('可见第40页渲染后离开仍保留原DOM草稿，更新主题和正式内容仍传入', async () => {
    await renderStage()
    const last = box.querySelector('[data-slide-id="s40"]')!
    signal(last, true)
    const draft = box.querySelector<HTMLInputElement>('[aria-label="草稿-s40"]')!
    expect(draft).not.toBeNull(); draft.value = '尚未提交的原草稿'
    signal(last, false)
    const updated = slides(); updated[39] = { ...updated[39], blocks: [{type: 'text', id: 'b39', slot_id: 'body', text: '中心正式新标题'}], revision: 2 }
    await renderStage(updated, 's1', 'p', getTheme('midnight'))
    expect(box.querySelector('[aria-label="草稿-s40"]')).toBe(draft)
    expect(draft.value).toBe('尚未提交的原草稿')
    expect(box.querySelector('[data-rendered-slide="s40"]')?.getAttribute('data-theme')).toBe('midnight')
    expect(box.querySelector('[data-rendered-slide="s40"]')?.textContent).toBe('中心正式新标题')
  })
  it('点击未进入视口的第40页立即渲染，切回首屏后保持访问缓存', async () => {
    await renderStage(); await renderStage(slides(), 's40')
    expect(box.querySelector('[data-rendered-slide="s40"]')).not.toBeNull()
    await renderStage()
    expect(box.querySelectorAll('[data-rendered-slide]')).toHaveLength(2)
  })
  it('缩略图首次不渲染40个图表树，进入视口及选中后保留同源预览', async () => {
    await renderFilm(); expect(box.querySelectorAll('[data-rendered-slide]')).toHaveLength(1)
    const last = box.querySelector('[data-preview-slide-id="s40"]')!
    expect(last).not.toBeNull(); signal(last, true)
    expect(box.querySelectorAll('[data-rendered-slide]')).toHaveLength(2)
    signal(last, false); await renderFilm()
    expect(box.querySelector('[data-rendered-slide="s40"]')).not.toBeNull()
  })
  it('项目切换后不沿旧可见缓存，旧observer回调不能开启新项目页', async () => {
    await renderStage(); const last = box.querySelector('[data-slide-id="s40"]')!
    const late = Observer.all.find((observer) => observer.nodes.has(last))!; signal(last, true)
    const next = slides().map((slide) => ({ ...slide, project_id: 'next' }))
    await renderStage(next, 's1', 'next')
    expect(box.querySelector('[data-rendered-slide="s40"]')).toBeNull()
    act(() => late.emit(last, true))
    expect(box.querySelector('[data-rendered-slide="s40"]')).toBeNull()
    expect(getSlideSaveHandlers('s40')?.projectId).toBe('next')
  })
})
