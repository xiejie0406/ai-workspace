import { useEffect } from 'react'
import { platformLogin } from '@/features/auth/token'
export default function AuthPage() {
  useEffect(() => { platformLogin('/aippt-web/projects') }, [])
  return <div className="grid min-h-screen place-items-center text-sm text-ink-muted">正在前往平台登录…</div>
}
