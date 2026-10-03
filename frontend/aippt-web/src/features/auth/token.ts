/** 与若依同源会话共用 Cookie，不复制到 localStorage 或 URL。 */
export const tokenStore = {
  get(): string | null {
    const value = document.cookie.split('; ').find((part) => part.startsWith('Admin-Token='))
    if (!value) return null
    try { return decodeURIComponent(value.slice('Admin-Token='.length)) } catch { return null }
  },
  clear() { document.cookie = 'Admin-Token=; Max-Age=0; Path=/' },
}
export function platformLoginUrl(returnTo: string) {
  let safe = '/aippt-web/projects'
  try {
    if (returnTo.length > 4096 || /[\\\u0000-\u0020\u007f]/.test(returnTo)) return '/login?redirect=' + encodeURIComponent(safe)
    const decoded = decodeURIComponent(returnTo)
    if (/[\\\u0000-\u0020\u007f]/.test(decoded) || decoded.split(/[?#]/)[0]!.split('/').some((part) => part === '.' || part === '..')) return '/login?redirect=' + encodeURIComponent(safe)
    const target = new URL(returnTo, window.location.origin)
    const credential = /^(?:token|access_token|refresh_token|authorization|password|secret)$/i
    if (returnTo.startsWith('/aippt-web/') && target.origin === window.location.origin && !/%(?:2f|5c|25)/i.test(target.pathname) && ![...target.searchParams.keys()].some((key) => credential.test(key)) && !/(?:^|[?#&])(?:token|access_token|refresh_token|authorization|password|secret)=/i.test(decoded) && /^\/aippt-web\/(projects(?:\/[A-Za-z0-9_-]+)?|create|login)\/?$/.test(target.pathname)) safe = target.pathname + target.search + target.hash
  } catch { /* 非同源业务路径使用项目列表 */ }
  return '/login?redirect=' + encodeURIComponent(safe)
}
export function platformLogin(returnTo = window.location.pathname + window.location.search + window.location.hash) {
  window.location.assign(platformLoginUrl(returnTo))
}
