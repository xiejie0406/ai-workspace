import { lookup } from 'node:dns/promises'
import { request } from 'node:https'
import { isIP } from 'node:net'

export class CollectionError extends Error {
  constructor(readonly code: string, message: string) { super(message) }
}
export function normalizeProductUrl(input: string): string {
  let url: URL
  try { url = new URL(input) } catch { throw new CollectionError('INVALID_URL', '请输入完整商品HTTPS链接') }
  if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')
    || isIP(url.hostname) || !url.hostname.includes('.') || /(?:^|\.)(?:localhost|local|internal|test|invalid)$/.test(url.hostname)
    || input.length > 2048) throw new CollectionError('INVALID_URL', '仅允许不含账号信息的公网HTTPS商品链接')
  url.hash = ''
  // 只移除明确的营销参数；SKU及平台未知参数必须保留。
  for (const key of [...url.searchParams.keys()]) if (/^utm_|^(?:spm|spm_id_from|gclid|fbclid)$/.test(key)) url.searchParams.delete(key)
  url.searchParams.sort()
  return url.href
}
export function isPublicIpv4(address: string): boolean {
  if (isIP(address) !== 4) return false
  const [a, b, c] = address.split('.').map(Number)
  return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127)
    || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && (b === 168 || b === 0 || (b === 88 && c === 99)))
    || (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) || (a === 203 && b === 0 && c === 113))
}
export interface PublicResponse { url: string; body: string; contentType: string; status: number }
export type PublicReader = (url: string, signal: AbortSignal) => Promise<PublicResponse>
async function publicAddresses(hostname: string, signal: AbortSignal): Promise<{ address: string; family: number }[]> {
  let addresses = await lookup(hostname, { all: true, family: 4 })
  // 部分桌面代理使用基准测试网段作DNS虚拟映射；不能把虚拟地址直接放行。
  if (addresses.length && addresses.every(a => /^198\.(?:18|19)\./.test(a.address))) {
    const query = new URL('https://dns.google/resolve')
    query.searchParams.set('name', hostname); query.searchParams.set('type', 'A')
    const response = await fetch(query, { signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]), redirect: 'error', credentials: 'omit' })
    if (!response.ok) throw new CollectionError('DNS_UNAVAILABLE', '无法核实代理环境中的公网地址')
    const body = await response.text()
    if (body.length > 32768) throw new CollectionError('DNS_INVALID', '公网DNS响应无效')
    const data = JSON.parse(body) as { Status?: number; Answer?: { type: number; data: string }[] }
    if (data.Status !== 0 || !Array.isArray(data.Answer)) throw new CollectionError('DNS_INVALID', '公网DNS未返回有效地址')
    addresses = data.Answer.filter(a => a.type === 1).map(a => ({ address: a.data, family: 4 }))
  }
  if (!addresses.length || addresses.some(a => !isPublicIpv4(a.address))) throw new CollectionError('PRIVATE_ADDRESS', '目标解析到受限网络地址，已停止')
  return addresses
}
export async function readPublicPage(input: string, signal: AbortSignal, redirects = 0): Promise<PublicResponse> {
  signal.throwIfAborted()
  const url = new URL(normalizeProductUrl(input))
  const addresses = await publicAddresses(url.hostname, signal)
  signal.throwIfAborted()
  if (!addresses.length || addresses.some(a => !isPublicIpv4(a.address))) throw new CollectionError('PRIVATE_ADDRESS', '目标解析到受限网络地址，已停止')
  const result = await new Promise<PublicResponse & { location?: string }>((resolve, reject) => {
    let settled = false
    const finish = (error?: Error, value?: PublicResponse & { location?: string }): void => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (error) reject(error); else resolve(value!)
    }
    const req = request(url, {
      method: 'GET', signal, family: 4, autoSelectFamily: false, headers: { Accept: 'text/html,application/json;q=0.9', 'User-Agent': 'Aden-Public-Product-Collector/0.1', 'Accept-Encoding': 'identity' },
      lookup: (_host, _options, callback) => callback(null, addresses[0].address, 4)
    }, response => {
      const status = response.statusCode ?? 0
      if ([401, 403, 429].includes(status)) {
        response.destroy(); finish(new CollectionError('ACCESS_REQUIRED', `来源拒绝公开读取（HTTP ${status}），请用户处理登录或访问限制`)); return
      }
      if (status >= 300 && status < 400) {
        const location = response.headers.location
        response.destroy(); finish(undefined, { url: url.href, body: '', contentType: '', status, location }); return
      }
      if (status !== 200) { response.destroy(); finish(new CollectionError('HTTP_ERROR', `来源返回HTTP ${status}`)); return }
      if (response.headers['content-encoding'] && response.headers['content-encoding'] !== 'identity') {
        response.destroy(); finish(new CollectionError('ENCODING', '来源忽略未压缩请求，暂不支持该响应')); return
      }
      const limit = 6 * 1024 * 1024
      const chunks: Buffer[] = []; let size = 0
      response.on('data', (chunk: Buffer) => {
        size += chunk.length
        if (size > limit) { response.destroy(); req.destroy(); finish(new CollectionError('TOO_LARGE', '来源响应超过6MiB上限')); return }
        chunks.push(chunk)
      })
      response.on('error', () => finish(new CollectionError('NETWORK_ERROR', '来源连接中断')))
      response.on('end', () => finish(undefined, { url: url.href, body: Buffer.concat(chunks).toString('utf8'), contentType: String(response.headers['content-type'] ?? ''), status }))
    })
    const timer = setTimeout(() => { finish(new CollectionError('TIMEOUT', '来源读取超时，可稍后重试')); req.destroy() }, 20_000)
    req.on('error', () => finish(new CollectionError(signal.aborted ? 'CANCELLED' : 'NETWORK_ERROR', signal.aborted ? '用户已取消' : '无法连接商品来源')))
    req.end()
  })
  if (result.status >= 300 && result.status < 400) {
    const target = result.location ? new URL(result.location, url) : null
    if (!target || target.origin !== url.origin || redirects >= 3) throw new CollectionError('REDIRECT_REJECTED', '来源跳转超出允许范围，请使用最终公开商品链接')
    return readPublicPage(target.href, signal, redirects + 1)
  }
  return result
}
