import type { ReactNode } from 'react'
import { useEffect } from 'react'
import { useLocation } from 'react-router'
import { platformLogin } from '@/features/auth/token'
import { useAuthStore, usePermission } from '@/features/auth/store'

export function RequireAuth({ children }: { children: ReactNode }) {
  const user = useAuthStore((state) => state.user)
  const restoring = useAuthStore((state) => state.restoring)
  const location = useLocation()
  const permitted = usePermission(location.pathname.startsWith('/create') ? 'aippt:project:add' : 'aippt:project:list')

  useEffect(() => { if (!restoring && !user) platformLogin('/aippt-web' + location.pathname + location.search + location.hash) }, [restoring, user, location])

  // 刷新页面时本地有 token 但用户信息还没换回来，
  // 此时不能判定为未登录，否则会闪一下登录页再跳回来。
  if (restoring) {
    return (
      <div className="flex min-h-screen items-center justify-center text-sm text-ink-muted">
        正在恢复登录状态…
      </div>
    )
  }

  if (!user) {
    return null
  }

  if (!permitted) return <div role="alert" className="grid min-h-screen place-items-center text-sm text-negative">没有访问此页面的权限</div>

  return children
}
