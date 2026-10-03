import { useEffect, useState } from 'react'
import { requestBinary } from '@/api/client'
import { tokenStore } from '@/features/auth/token'
const objects = new Map<string, { url: string; refs: number }>()
export function clearPrivateImages() { for (const value of objects.values()) URL.revokeObjectURL(value.url); objects.clear() }
/** 正式 block.url 保留私有路径；仅展示层持有鉴权 Blob URL。 */
export function usePrivateImage(source: string | null | undefined) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    setUrl(null)
    if (!source) return
    if (!/^\/aippt\/assets\/[A-Za-z0-9-]+$/.test(source)) return
    const token = tokenStore.get(); const key = `${token}:${source}`; const controller = new AbortController()
    let held = false
    const use = (value: { url: string; refs: number }) => { value.refs++; held = true; setUrl(value.url) }
    const cached = objects.get(key)
    if (cached) use(cached)
    else void requestBinary(source.slice('/aippt'.length), { signal: controller.signal }).then((response) => response.blob()).then((blob) => {
      if (controller.signal.aborted || tokenStore.get() !== token) return
      const value = objects.get(key) ?? { url: URL.createObjectURL(blob), refs: 0 }
      objects.set(key, value); use(value)
    }).catch(() => {})
    return () => {
      controller.abort()
      const value = objects.get(key)
      if (held && value && --value.refs === 0) { URL.revokeObjectURL(value.url); objects.delete(key) }
    }
  }, [source])
  return url
}
