import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { BrowserRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from '@/components/AppShell'
import { Dialog } from '@/components/ui/Dialog'
import { useAuthStore } from '@/features/auth/store'
import { guardSavedNavigation, useSaveNavigationGuard } from '@/hooks/useSaveNavigationGuard'

let root: Root, container: HTMLDivElement, pending: boolean, flush: () => Promise<void>
const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 35)) })
const until = async (check: () => boolean) => {
  const deadline = Date.now() + 1500
  while (!check() && Date.now() < deadline) await settle()
  expect(check()).toBe(true)
}
function Guarded({ dialog = false }: { dialog?: boolean }) {
  const error = useSaveNavigationGuard(() => pending, () => flush())
  const location = useLocation()
  return <><p data-current>{location.pathname}</p>{error && <p role="alert">{error}</p>}{dialog && <Dialog title="材料与参数" onClose={() => {}}><input aria-label="草稿" defaultValue="未保存材料" /></Dialog>}</>
}
async function mount(dialog = false) {
  await act(async () => root.render(<BrowserRouter basename="/aippt-web"><Routes><Route path="/projects/p" element={<Guarded />} /><Route path="/projects" element={<AppShell><Guarded dialog={dialog} /></AppShell>} /><Route path="/create" element={<p>新建目标</p>} /></Routes></BrowserRouter>)); await settle()
}
beforeEach(async () => {
  // JSDOM 的 history traversal 分两个定时阶段；上一case已结束的回放不得落到本case新idx。
  await new Promise((resolve) => setTimeout(resolve, 60))
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); document.cookie = 'Admin-Token=guard-session; Path=/'
  useAuthStore.setState({ permissions: ['*:*:*'] }); pending = true; flush = async () => {}
  container = document.createElement('div'); document.body.append(container); root = createRoot(container)
  window.history.replaceState({ idx: 0 }, '', '/aippt-web/projects')
})
afterEach(async () => { pending = false; await act(async () => root.unmount()); await new Promise((resolve) => setTimeout(resolve, 60)); container.remove(); vi.restoreAllMocks() })
describe('原BrowserRouter保存与导航', () => {
  it('延迟保存时两次Back都先恢复原索引，保存成功只去首个Back目标', async () => {
    window.history.pushState({ idx: 1 }, '', '/aippt-web/create'); window.history.pushState({ idx: 2 }, '', '/aippt-web/projects'); window.history.pushState({ idx: 3 }, '', '/aippt-web/projects/p')
    let release!: () => void; const gate = new Promise<void>((resolve) => { release = resolve }); const called = vi.fn(async () => { await gate; pending = false }); flush = called
    await mount(); const go = vi.spyOn(window.history, 'go') // 保留真实实现，记录restore已实际触发的屏障。
    act(() => window.history.back()); await until(() => window.history.state.idx === 3 && called.mock.calls.length === 1)
    act(() => window.history.back()); await until(() => go.mock.calls.filter(([delta]) => Number(delta) > 0).length >= 2 && window.history.state.idx === 3 && container.querySelector('[data-current]')?.textContent === '/projects/p')
    expect(window.location.pathname).toBe('/aippt-web/projects/p'); expect(window.history.state.idx).toBe(3); expect(container.querySelector('[data-current]')!.textContent).toBe('/projects/p'); expect(called).toHaveBeenCalledOnce()
    await act(async () => release()); await until(() => window.location.pathname === '/aippt-web/projects'); expect(window.history.state.idx).toBe(2)
  })
  it('保存失败保留原位置与草稿，错误原位可读而不回放Back', async () => {
    window.history.pushState({ idx: 1 }, '', '/aippt-web/projects/p'); flush = async () => { throw new Error('保存回执未确认') }
    await mount(); act(() => window.history.back()); await until(() => !!container.querySelector('[role=alert]') && window.history.state.idx === 1)
    expect(window.location.pathname).toBe('/aippt-web/projects/p'); expect(window.history.state.idx).toBe(1); expect(container.querySelector('[role=alert]')!.textContent).toBe('保存回执未确认'); expect(pending).toBe(true)
  })
  it.each(['first', 'second'])('%s次restore等待期间换会话，旧Back意图不得继续flush或回放', async (phase) => {
    window.history.pushState({ idx: 3 }, '', '/aippt-web/projects/p')
    let release!: () => void; const gate = new Promise<void>((resolve) => { release = resolve }); flush = vi.fn(() => gate)
    await mount(); const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    const pop = (idx: number) => { window.history.replaceState({ idx }, '', idx === 3 ? '/aippt-web/projects/p' : '/aippt-web/projects'); window.dispatchEvent(new PopStateEvent('popstate', { state: { idx } })) }
    act(() => pop(2)); await act(async () => { await Promise.resolve() })
    if (phase === 'second') {
      act(() => pop(3)); await act(async () => { await Promise.resolve(); await Promise.resolve() })
      expect(flush).toHaveBeenCalledOnce(); act(() => pop(2)); pending = false; await act(async () => release())
    }
    document.cookie = 'Admin-Token=changed-before-restore; Path=/'
    act(() => pop(3)); await settle()
    expect(go.mock.calls.some(([delta]) => delta === -1)).toBe(false)
    if (phase === 'first') expect(flush).not.toHaveBeenCalled()
  })
  it('材料Dialog可聚焦背景新建按钮，但其程序navigate仍先flush且失败不离开', async () => {
    const called = vi.fn(async () => { throw new Error('请保存材料草稿') }); flush = called
    await mount(true); const button = [...container.querySelectorAll('button')].find((node) => node.textContent?.includes('新建 PPT'))!
    button.focus(); expect(document.activeElement).toBe(button); await act(async () => button.click()); await settle()
    expect(called).toHaveBeenCalledOnce(); expect(window.location.pathname).toBe('/aippt-web/projects'); expect(container.querySelector('input[aria-label=草稿]')).not.toBeNull(); expect(container.textContent).toContain('请保存材料草稿')
  })
  it.each(['session', 'unmount'])('flush宏任务之后%s失效，不执行旧程序导航', async (kind) => {
    flush = async () => { window.setTimeout(() => { if (kind === 'session') document.cookie = 'Admin-Token=new-session; Path=/'; else void act(async () => root.unmount()); pending = false }, 0) }
    await mount(); const run = vi.fn(); await act(async () => { await guardSavedNavigation(run) }); expect(run).not.toHaveBeenCalled()
  })
  it('无进展立即fulfilled的flush不能饿死宏任务；组件关闭后停止等待', async () => {
    const called = vi.fn(async () => {}); flush = called; await mount(); const run = vi.fn()
    await act(async () => { window.setTimeout(() => { root.unmount(); pending = false }, 5); await guardSavedNavigation(run) })
    expect(called.mock.calls.length).toBeLessThan(100); expect(run).not.toHaveBeenCalled()
  })
})
