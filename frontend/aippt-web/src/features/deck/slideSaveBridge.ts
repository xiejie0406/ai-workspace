import { tokenStore } from '@/features/auth/token'
import type { FlexBlockType } from '@/features/deck/flexTree'
import type { DeckSlide } from '@/features/deck/types'
import type { FlexContainer } from '@/render/flexLayout'

export type InsertDragPayload =
  | { kind: 'block'; type: FlexBlockType }
  | { kind: 'columns'; count: 2 | 3 }

export type SlideSaveHandlers = {
  projectId: string
  commitFlex: (tree: FlexContainer) => void
  commitCreateBlock: (body: {
    type: FlexBlockType
    parent_id: string
    index?: number
  }) => void
  commitDeleteBlock: (blockId: string) => void
  commitMutate: (run: (slide: DeckSlide) => Promise<DeckSlide>) => void
  mutateAsync: (run: (slide: DeckSlide) => Promise<DeckSlide>) => Promise<DeckSlide>
  flush: () => Promise<void>
  pending?: () => boolean
  undo: () => boolean
}

const registry = new Map<string, SlideSaveHandlers>()
const themeRegistry = new Map<string, { flush: () => Promise<void>; pending: () => boolean }>()
export function registerThemeSaveHandlers(projectId: string, handlers: { flush: () => Promise<void>; pending: () => boolean }) {
  themeRegistry.set(projectId, handlers)
  return () => { if (themeRegistry.get(projectId) === handlers) themeRegistry.delete(projectId) }
}

type InsertDragStarter = (
  payload: InsertDragPayload,
  clientX: number,
  clientY: number,
) => void

const insertDragRegistry = new Map<string, InsertDragStarter>()

/** SlidePage 挂载时注册，供侧栏/换排布等跨树组件走同一保存队列 */
export function registerSlideSaveHandlers(
  slideId: string,
  handlers: SlideSaveHandlers,
): () => void {
  registry.set(slideId, handlers)
  return () => {
    if (registry.get(slideId) === handlers) registry.delete(slideId)
  }
}

export function getSlideSaveHandlers(slideId: string): SlideSaveHandlers | null {
  return registry.get(slideId) ?? null
}

/**
 * 注册某页的插入拖放启动器。
 * starter 收到 payload（块类型或预设）与指针 client 坐标后开始画布内拖放。
 */
export function registerInsertDragStarter(
  slideId: string,
  starter: InsertDragStarter,
): () => void {
  insertDragRegistry.set(slideId, starter)
  return () => {
    if (insertDragRegistry.get(slideId) === starter) insertDragRegistry.delete(slideId)
  }
}

export function requestInsertDrag(
  slideId: string,
  payload: InsertDragPayload,
  clientX: number,
  clientY: number,
): boolean {
  const starter = insertDragRegistry.get(slideId)
  if (!starter) return false
  starter(payload, clientX, clientY)
  return true
}

export async function flushProjectSlides(projectId: string) {
  const token = tokenStore.get()
  await Promise.all([...registry.values()].filter((value) => value.projectId === projectId).map((value) => value.flush()).concat(themeRegistry.get(projectId)?.flush() ?? []))
  if (tokenStore.get() !== token) throw new DOMException('登录会话已变化', 'AbortError')
}
export function projectHasPendingSaves(projectId: string) {
  return [...registry.values()].some((value) => value.projectId === projectId && value.pending?.()) || themeRegistry.get(projectId)?.pending() === true
}
export async function runSlideMutation(projectId: string, slideId: string, run: (slide: DeckSlide) => Promise<DeckSlide>) {
  const handlers = getSlideSaveHandlers(slideId)
  if (!handlers || handlers.projectId !== projectId) throw new Error('页面保存队列尚未就绪')
  return handlers.mutateAsync(run)
}
