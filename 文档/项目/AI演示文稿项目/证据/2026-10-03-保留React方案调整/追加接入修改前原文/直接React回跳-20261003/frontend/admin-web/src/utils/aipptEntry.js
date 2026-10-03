const defaultPath = '/aippt-web/projects'
const entryOrigin = 'https://aippt-entry.invalid'

// 登录回跳只接受本站 React 路径，平台 Token 始终由 cookie 读取。
export function normalizeAipptReturnTo(value) {
  if (typeof value !== 'string' || value.length > 4096 || !value.startsWith('/aippt-web/')) return defaultPath
  if (/[\\\x00-\x20\x7f]/.test(value)) return defaultPath
  try {
    const decoded = decodeURIComponent(value)
    if (/[\\\x00-\x20\x7f]/.test(decoded)) return defaultPath
    const path = decoded.split(/[?#]/)[0]
    if (path.split('/').some(part => part === '.' || part === '..')) return defaultPath
    const url = new URL(value, entryOrigin)
    if (url.origin !== entryOrigin || !url.pathname.startsWith('/aippt-web/')) return defaultPath
    if (/%(?:2f|5c|25)/i.test(url.pathname)) return defaultPath
    if ([...url.searchParams.keys()].some(key => /^(?:token|access_token|refresh_token|authorization|password|secret)$/i.test(key))) return defaultPath
    if (/(?:^|[?#&])(?:token|access_token|refresh_token|authorization|password|secret)=/i.test(decoded)) return defaultPath
    return url.pathname + url.search + url.hash
  } catch {
    return defaultPath
  }
}

export function aipptEntryPermission(path) {
  if (path.split(/[?#]/)[0].replace(/\/$/, '') === '/aippt-web/create') return 'aippt:project:add'
  return 'aippt:project:list'
}
