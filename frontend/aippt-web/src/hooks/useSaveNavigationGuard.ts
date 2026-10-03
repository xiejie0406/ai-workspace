import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { tokenStore } from '@/features/auth/token'
type Guard = { pending: () => boolean; flush: () => Promise<void>; report: (message: string) => void }
type RegisteredGuard = { current: Guard; pop?: (event: PopStateEvent) => void }
// 仅登记现有表单/队列的回调，草稿继续由原组件、Zustand与Query持有。
const guards = new Set<RegisteredGuard>()
// window 自身的 popstate 不能靠迟注册 capture 抢在 Router 前；模块随 App 导入时先登记分发。
const dispatchPop = (event: PopStateEvent) => [...guards][0]?.pop?.(event)
let dispatcherWindow: Window | null = null
const ensurePopDispatcher = () => {
  if (dispatcherWindow === window) return
  dispatcherWindow?.removeEventListener('popstate', dispatchPop, true)
  dispatcherWindow = window; window.addEventListener('popstate', dispatchPop, true)
}
ensurePopDispatcher()
import.meta.hot?.dispose(() => { dispatcherWindow?.removeEventListener('popstate', dispatchPop, true); dispatcherWindow = null })
const pendingGuards = () => [...guards].filter((guard) => guard.current.pending())
async function drainSaves() {
  const token = tokenStore.get()
  const participants = new Set<RegisteredGuard>()
  const assertCurrent = () => { if (tokenStore.get() !== token || [...participants].some((guard) => !guards.has(guard))) throw new DOMException('页面或会话已变化', 'AbortError') }
  while (pendingGuards().length) {
    const active = pendingGuards()
    for (const guard of active) participants.add(guard)
    await Promise.all(active.map((guard) => guard.current.flush()))
    assertCurrent()
    // Query/React 的正式回执需经过一次提交；不能在旧闭包上连续微任务重调 flush。
    if (pendingGuards().length) await new Promise<void>((resolve) => window.setTimeout(resolve, 0))
    assertCurrent()
  }
  assertCurrent()
}
let programmaticNavigation = false
/** 原按钮的 navigate 也走同一批表单/队列回调，不替代 BrowserRouter。 */
export async function guardSavedNavigation(run: () => void) {
  if (programmaticNavigation) return
  if (!pendingGuards().length) { run(); return }
  programmaticNavigation = true
  try { await drainSaves(); run() }
  catch (cause) { const message = cause instanceof Error ? cause.message : '请先确认保存结果后离开'; for (const guard of guards) guard.current.report(message) }
  finally { programmaticNavigation = false }
}

/** 在原 BrowserRouter 内等待业务保存；复用浏览器历史索引，不建立路由或草稿状态库。 */
export function useSaveNavigationGuard(pending: () => boolean, flush: () => Promise<void>) {
  // 测试/嵌入的 window 更换时，仍在本次 Router layout effects 前恢复同一分发器。
  ensurePopDispatcher()
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const latest = useRef({ pending, flush, navigate, report: setError })
  latest.current = { pending, flush, navigate, report: setError }
  const index = useRef<number | undefined>(window.history.state?.idx)
  useEffect(() => { index.current = window.history.state?.idx }, [location.key])
  useEffect(() => {
    guards.add(latest)
    const leader = () => [...guards][0] === latest
    const anyPending = () => [...guards].some((guard) => guard.current.pending())
    let disposed = false; let navigating = false
    let restore: { origin: number; promise: Promise<void>; resolve: () => void } | null = null
    let replay: number | null = null
    const restoreOrigin = (origin: number, next: number) => {
      if (!restore) {
        let resolve!: () => void
        const promise = new Promise<void>((done) => { resolve = done })
        restore = { origin, promise, resolve }
      }
      window.history.go(origin - next)
      return restore.promise
    }
    const wait = async (token: string | null) => {
      const assertCurrent = () => { if (disposed || tokenStore.get() !== token) throw new DOMException('页面或会话已变化', 'AbortError') }
      assertCurrent()
      await drainSaves()
      while (restore) { await restore.promise; assertCurrent() }
      assertCurrent()
      setError(null)
    }
    const failed = (cause: unknown) => { if (!disposed) setError(cause instanceof Error ? cause.message : '请先确认保存结果后离开') }
    const unload = (event: BeforeUnloadEvent) => { if (leader() && anyPending()) { event.preventDefault(); event.returnValue = '' } }
    const click = (event: MouseEvent) => {
      if (!leader()) return
      if (event.button || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return
      const anchor = (event.target as Element | null)?.closest<HTMLAnchorElement>('a[href]')
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return
      const target = new URL(anchor.href, window.location.href)
      if (target.origin !== window.location.origin || !target.pathname.startsWith('/aippt-web/') || target.pathname + target.search === window.location.pathname + window.location.search) return
      const active = document.activeElement as HTMLElement | null
      if (active?.closest('[data-block-id]')) active.blur()
      if (!anyPending()) return
      event.preventDefault(); event.stopImmediatePropagation()
      if (navigating) return
      navigating = true
      const token = tokenStore.get()
      void wait(token).then(() => latest.current.navigate(target.pathname.slice('/aippt-web'.length) + target.search + target.hash)).catch(failed).finally(() => { navigating = false })
    }
    const pop = (event: PopStateEvent) => {
      if (!leader()) return
      const nextIndex = event.state?.idx as number | undefined
      if (restore) {
        event.stopImmediatePropagation()
        if (nextIndex === restore.origin) { const resolve = restore.resolve; restore = null; resolve() }
        else if (typeof nextIndex === 'number') window.history.go(restore.origin - nextIndex)
        return
      }
      if (replay !== null && nextIndex === replay) { replay = null; index.current = nextIndex; return }
      if (!anyPending() || typeof nextIndex !== 'number' || typeof index.current !== 'number' || nextIndex === index.current) { index.current = nextIndex; return }
      event.stopImmediatePropagation()
      const delta = index.current - nextIndex
      const restored = restoreOrigin(index.current, nextIndex)
      // 保存期间后续返回只恢复原位置；首个离开目标保持不变。
      if (navigating) return
      navigating = true
      const token = tokenStore.get()
      void restored.then(() => wait(token)).then(() => { replay = nextIndex; window.history.go(-delta) }).catch(failed).finally(() => { navigating = false })
    }
    window.addEventListener('beforeunload', unload)
    document.addEventListener('click', click, true)
    ;(latest as RegisteredGuard).pop = pop
    return () => { disposed = true; restore?.resolve(); guards.delete(latest); delete (latest as RegisteredGuard).pop; window.removeEventListener('beforeunload', unload); document.removeEventListener('click', click, true) }
  }, [])
  return error
}
