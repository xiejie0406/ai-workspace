import type { Asset } from '@/api/assets'
import { formatBytes, formatDateTime } from '@/lib/format'
import { useApis, useHost, useHostRuntime, useToast } from '@/host/HostContext'
import { errorMessage } from '@/lib/errors'
import { useEffect, useRef, useState } from 'react'

export default function AssetCard({ asset, onSelect }: { asset: Asset; onSelect?: () => void }) {
  const { assets } = useApis()
  const host = useHost()
  const toast = useToast()
  const runtime = useHostRuntime()
  const mounted = useRef(true)
  const submitting = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const [downloading, setDownloading] = useState(false)
  const download = async () => {
    if (submitting.current || !runtime.active) return
    const signal = runtime.signal
    submitting.current = true
    setDownloading(true)
    try {
      const blob = await assets.download(asset.id)
      if (mounted.current && !signal.aborted && runtime.active) await host.download(blob, `${asset.id}.${asset.image_format.toLowerCase()}`)
    } catch (error) {
      if (mounted.current && !signal.aborted && runtime.active && !(error instanceof Error && error.name === 'AbortError')) toast(errorMessage(error), 'danger')
    } finally {
      if (mounted.current && !signal.aborted) { submitting.current = false; setDownloading(false) }
    }
  }
  return (
    <div
      className="border-line bg-paper hover:border-brand group overflow-hidden rounded-[18px] border text-left transition-colors"
    >
      <button type="button" onClick={onSelect} title="点击打开编辑" className="bg-canvas relative block aspect-square w-full">
        <img
          src={asset.url}
          alt=""
          loading="lazy"
          className="size-full object-contain transition-transform group-hover:scale-[1.02]"
        />
        {asset.has_alpha && (
          <span className="bg-ink/80 absolute top-2 left-2 rounded px-1.5 py-0.5 text-[10px] text-white">
            透明底
          </span>
        )}
      </button>

      <dl className="text-muted space-y-0.5 px-3 py-2.5 text-xs">
        <div className="text-ink font-medium">
          {asset.width} × {asset.height}
        </div>
        <div>
          {asset.image_format} · {formatBytes(asset.size_bytes)}
        </div>
        <div className="text-faint">{formatDateTime(asset.created_at)}</div>
      </dl>
      <button type="button" disabled={downloading || !host.hasPermission('aipicture:export:download')} onClick={() => { void download() }} className="text-brand-strong border-line w-full border-t p-2 text-xs disabled:opacity-40">{downloading ? '下载中…' : '下载原素材文件'}</button>
    </div>
  )
}
