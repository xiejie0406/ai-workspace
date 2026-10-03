import type { QueryClient } from '@tanstack/react-query'
import { useAuthStore } from '@/features/auth/store'
import { tokenStore } from '@/features/auth/token'
import { clearPrivateImages } from '@/render/usePrivateImage'

/** 会话切换只清理原Query/Zustand与Blob缓存，在getInfo完成前移除旧用户与权限。 */
export function startPlatformSession(queryClient: QueryClient) {
  let currentToken = tokenStore.get()
  let restoringToken: string | null | undefined
  const reset = () => {
    const token = tokenStore.get()
    if (currentToken === token && restoringToken === token) return
    currentToken = token; restoringToken = token; queryClient.clear(); clearPrivateImages()
    useAuthStore.setState({ user: null, permissions: [], roles: [], restoring: true })
    void useAuthStore.getState().restore().finally(() => { if (restoringToken === token) restoringToken = undefined })
  }
  window.addEventListener('aippt:session-change', reset)
  const timer = window.setInterval(() => { if (tokenStore.get() !== currentToken) reset() }, 1000)
  return () => { window.clearInterval(timer); window.removeEventListener('aippt:session-change', reset) }
}
