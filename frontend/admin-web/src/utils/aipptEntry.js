const defaultPath = '/aippt-web/projects'
const returnOrigin = 'https://aippt-return.invalid'

// 登录回跳只接受本站 React 路径，平台 Token 始终由 cookie 读取。
export function normalizeAipptReturnTo(value) {
  if (typeof value !== 'string' || value.length > 4096 || !value.startsWith('/aippt-web/')) return defaultPath
  if (/[\\\x00-\x20\x7f]/.test(value)) return defaultPath
  try {
    const decoded = decodeURIComponent(value)
    if (/[\\\x00-\x20\x7f]/.test(decoded)) return defaultPath
    const path = decoded.split(/[?#]/)[0]
    if (path.split('/').some(part => part === '.' || part === '..')) return defaultPath
    const url = new URL(value, returnOrigin)
    if (url.origin !== returnOrigin || !url.pathname.startsWith('/aippt-web/')) return defaultPath
    if (/%(?:2f|5c|25)/i.test(url.pathname)) return defaultPath
    if ([...url.searchParams.keys()].some(key => /^(?:token|access_token|refresh_token|authorization|password|secret)$/i.test(key))) return defaultPath
    if (/(?:^|[?#&])(?:token|access_token|refresh_token|authorization|password|secret)=/i.test(decoded)) return defaultPath
    return url.pathname + url.search + url.hash
  } catch {
    return defaultPath
  }
}

// 仅处理原 React 同源回跳；其他平台页面仍走既有 VueRouter 登录分支。
export function resolveAipptLoginReturnTo(value, query = {}) {
  if (typeof value !== 'string' || !value.startsWith('/aippt-web/')) return null
  const url = new URL(normalizeAipptReturnTo(value), returnOrigin)
  for (const [key, raw] of Object.entries(query)) {
    if (key === 'redirect') continue
    const values = Array.isArray(raw) ? raw : [raw]
    if (values.some(item => item !== null && typeof item !== 'string')) return defaultPath
    url.searchParams.delete(key)
    for (const item of values) url.searchParams.append(key, item ?? '')
  }
  return normalizeAipptReturnTo(url.pathname + url.search + url.hash)
}
