import { Component, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { flushSync } from 'react-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Workbench from './Workbench'
import { HostContext, createBusinessState } from './host/HostContext'
import { HostRuntime } from './host/HostRuntime'
import type { AiPictureHost, AiPictureMount } from './host/HostContract'
import { promptDraft } from './lib/promptDraft'
import css from './index.css?inline'

export type { AiPictureHost, AiPictureMount, BusinessPage, HostRoute } from './host/HostContract'
const mounts = new WeakMap<HTMLElement, AiPictureMount>()
const permissionNames = [
  'asset:list', 'asset:upload', 'asset:query', 'session:list', 'session:query', 'session:create', 'session:edit', 'image:generate',
  'tool:execute', 'plan:confirm', 'plan:retry', 'plan:cancel', 'batch:list', 'batch:execute', 'batch:retry', 'batch:cancel', 'export:download',
]
const permissionFingerprint = (host: AiPictureHost) => permissionNames.map((name) => Number(host.hasPermission(`aipicture:${name}`))).join('')

class RenderBoundary extends Component<{ children: ReactNode; onError: (message: string) => void }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  componentDidCatch(error: Error) { this.props.onError(error.message) }
  render() { return this.state.failed ? <div className="p-8 text-sm" role="alert">图片工作台加载失败，请刷新当前页面。输入与正式会话仍由服务器保存。</div> : this.props.children }
}

/** 库入口。CSS/Tailwind 仅注入本实例的 ShadowRoot。 */
export function mount(container: HTMLElement, host: AiPictureHost): AiPictureMount {
  mounts.get(container)?.unmount()
  const shadow = container.shadowRoot ?? container.attachShadow({ mode: 'open' })
  const style = document.createElement('style'); style.textContent = css
  const target = document.createElement('div'); target.className = 'aipicture-root'
  shadow.append(style, target)
  const root = createRoot(target)
  const runtime = new HostRuntime(host)
  runtime.mountRoot = target
  let state = createBusinessState(runtime)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 5000, gcTime: 0, refetchOnWindowFocus: false }, mutations: { retry: false, gcTime: 0 } } })
  let mounted = true
  let boundaryVersion = 0
  let permissionKey = permissionFingerprint(host)
  const reset = () => {
    // 先同步退出旧组件和 QueryObserver，再清 cache；否则失活后 observer
    // 的延迟退订会重新为已移除 query 登记五分钟 GC timer。
    flushSync(() => root.render(null))
    runtime.reset(); state.canvas.dispose(); queryClient.clear()
    state = createBusinessState(runtime); boundaryVersion++
  }
  const render = () => root.render(runtime.active ? <RenderBoundary key={boundaryVersion} onError={(message) => runtime.host.notify({ message, type: 'error' })}>
    <HostContext.Provider value={state}><QueryClientProvider client={queryClient}><Workbench /></QueryClientProvider></HostContext.Provider>
  </RenderBoundary> : null)
  const handle: AiPictureMount = {
    update(next) {
      if (!mounted) return
      const previous = runtime.host
      const identityChanged = String(previous.identity.userId) !== String(next.identity.userId)
      // 角色/权限函数由平台重新提供时清理敏感缓存，即使 userId 未变化。
      const nextPermissionKey = permissionFingerprint(next)
      const permissionsChanged = permissionKey !== nextPermissionKey
      const routeChanged = previous.page !== next.page || previous.params?.sessionId !== next.params?.sessionId || previous.params?.runId !== next.params?.runId
      if (identityChanged || permissionsChanged || routeChanged || next.active === false) {
        if (identityChanged) promptDraft(previous.identity.userId).clear()
        reset()
      }
      permissionKey = nextPermissionKey; runtime.host = next; render()
    },
    unmount() {
      if (!mounted) return
      mounted = false; root.unmount(); runtime.dispose(); state.canvas.dispose(); queryClient.clear()
      style.remove(); target.remove(); mounts.delete(container)
    },
  }
  mounts.set(container, handle); render(); return handle
}
export function update(container: HTMLElement, host: AiPictureHost) { mounts.get(container)?.update(host) }
export function unmount(container: HTMLElement) { mounts.get(container)?.unmount() }
