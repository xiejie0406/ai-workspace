import { useCallback, type AnchorHTMLAttributes, type ReactNode, type MouseEvent } from 'react'
import { useHost } from './HostContext'
import type { HostRoute, BusinessPage, PageParams } from './HostContract'

/** 业务深链交给若依 router；组件内部不会写浏览器 history。 */
export function routeFromPath(path: string): HostRoute {
  const [segment, id] = path.replace(/^\//, '').split(/[/?]/)
  const page = (segment || 'guide') as BusinessPage
  const allowed: BusinessPage[] = ['create', 'editor', 'candidates', 'marketing', 'batch', 'guide', 'exports']
  if (!allowed.includes(page)) throw new Error('未知的图片编辑页面')
  return { page, params: id ? (page === 'candidates' || page === 'batch' ? { runId: decodeURIComponent(id) } : { sessionId: decodeURIComponent(id) }) : undefined }
}
export function useNavigate() {
  const host = useHost()
  return useCallback((path: string, options?: { replace?: boolean }) => host.navigate(routeFromPath(path), options), [host])
}
export function useParams(): PageParams { return useHost().params ?? {} }
type LinkProps = Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> & { to: string }
export function Link({ to, children, onClick, ...props }: LinkProps) {
  const navigate = useNavigate()
  const click = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event)
    if (event.defaultPrevented) return
    event.preventDefault(); navigate(to)
  }
  return <a {...props} href="#" onClick={click}>{children}</a>
}
export function NavLink({ className, children, to, ...props }: Omit<LinkProps, 'className' | 'children'> & {
  className?: string | ((value: { isActive: boolean }) => string)
  children?: ReactNode | ((value: { isActive: boolean }) => ReactNode)
}) {
  const host = useHost()
  const route = routeFromPath(to)
  const active = route.page === host.page && (!route.params || (route.params.sessionId === host.params?.sessionId && route.params.runId === host.params?.runId))
  return <Link {...props} to={to} aria-current={active ? 'page' : undefined} className={typeof className === 'function' ? className({ isActive: active }) : className}>
    {typeof children === 'function' ? children({ isActive: active }) : children}
  </Link>
}
