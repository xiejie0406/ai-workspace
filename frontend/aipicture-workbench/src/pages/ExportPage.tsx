import { useEffect, useState } from 'react'
import { Link, useParams } from '@/host/router'
import { useHost } from '@/host/HostContext'
import { useExportPack, useSession } from '@/hooks/useSessions'
import { defaultSelected, KIND_LABELS, packable } from '@/lib/marketing'
import { errorMessage, isNotFoundError } from '@/lib/errors'

/** 独立物料清单与画布下载页，正式文件由 Worker 渲染，浏览器不提交预览截图。 */
export default function ExportPage() {
  const { sessionId = '' } = useParams()
  const host = useHost()
  const { data: session, error, isPending, isSuccess, refetch } = useSession(sessionId || null)
  const [lastReadError, setLastReadError] = useState<{ sessionId: string; error: Error } | null>(null)
  const retainedError = lastReadError?.sessionId === sessionId ? lastReadError.error : null
  const loadError = error ?? (isSuccess || session === null ? null : retainedError)
  // 只在外部 Query 结果变更时校准本页保留值；pending 不清错误或用户选择。
  if (lastReadError && (!sessionId || isSuccess || session === null || lastReadError.sessionId !== sessionId)) setLastReadError(null)
  else if (error && (!lastReadError || lastReadError.error !== error)) setLastReadError({ sessionId, error })
  const pack = useExportPack(sessionId)
  const [selected, setSelected] = useState<string[]>([])
  const [transparent, setTransparent] = useState(false)
  useEffect(() => { if (session) setSelected(defaultSelected(session.assets, session.current_asset_id)) }, [sessionId, session?.current_asset_id])
  if (!sessionId) return <div className="p-8 text-sm">先从<Link to="/marketing" className="text-brand-strong mx-1">营销与导出</Link>选择会话。</div>
  const readFailure = loadError && <div className="text-danger mb-4 text-sm" role="alert"><p>{errorMessage(loadError)}</p><button className="text-brand-strong mt-2" onClick={() => { void refetch() }}>重新读取</button></div>
  if (session === null || isNotFoundError(loadError)) return <div className="p-8 text-sm">会话不存在。<Link to="/marketing" className="text-brand-strong mx-1">返回营销与导出</Link></div>
  if (!session) return <div className="p-8 text-sm">{readFailure || (isPending ? '正在读取导出物料…' : '正在读取会话…')}</div>
  const items = session.assets.filter((asset) => packable(asset, session.current_asset_id))
  const download = (format: 'png' | 'zip') => pack.mutate({
    format, asset_ids: format === 'zip' ? selected : [], backgroundMode: transparent ? 'transparent' : 'white',
  }, { onSuccess: ({ blob, filename }) => { void host.download(blob, filename) } })
  return <div className="mx-auto max-w-5xl px-8 py-10">
    <div className="mb-6 flex items-center justify-between"><div><h1 className="text-ink text-2xl font-semibold">物料导出清单</h1><p className="text-muted mt-1 text-sm">{session.title} · 画布 {session.document.width}×{session.document.height}</p></div><Link to={`/marketing/${sessionId}`} className="text-brand-strong text-sm">返回营销与导出</Link></div>
    {readFailure}
    <section className="border-line bg-paper mb-6 rounded-panel border p-5"><h2 className="text-ink text-sm font-medium">当前画布合成 PNG</h2><p className="text-muted mt-2 text-xs">默认白底。保留透明背景仅改变导出背景，不改变画布、原素材和正式文档。</p><label className="text-ink mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={transparent} onChange={(event) => setTransparent(event.target.checked)} />保留透明背景</label><button disabled={pack.isPending || !host.hasPermission('aipicture:export:download')} onClick={() => download('png')} className="bg-ink mt-4 rounded-control px-4 py-2 text-sm text-white disabled:opacity-40">{pack.isPending ? '渲染与打包中…' : '下载当前画布 PNG'}</button></section>
    <section className="border-line bg-paper rounded-panel border p-5"><div className="mb-4 flex items-center justify-between"><h2 className="text-ink text-sm font-medium">会话物料 ZIP · 含 manifest.json</h2><button onClick={() => setSelected(selected.length === items.length ? [] : items.map((asset) => asset.id))} className="text-brand-strong text-xs">全选 / 清空</button></div>
      <table className="w-full text-left text-sm"><thead><tr className="text-muted border-line border-b"><th className="py-2">选择</th><th>预览</th><th>类型</th><th>尺寸与格式</th><th>透明通道</th></tr></thead><tbody>{items.map((asset) => <tr key={asset.id} className="border-line border-b"><td><input aria-label={`选择物料 ${asset.id}`} type="checkbox" checked={selected.includes(asset.id)} onChange={(event) => setSelected((current) => event.target.checked ? [...current, asset.id] : current.filter((id) => id !== asset.id))} /></td><td className="py-3"><img src={asset.url} alt="" className="size-16 rounded-[8px] object-contain" /></td><td>{KIND_LABELS[asset.kind] || asset.kind}</td><td>{asset.width}×{asset.height} · {asset.image_format.toUpperCase()}</td><td>{asset.has_alpha ? '保留原文件透明度' : '无'}</td></tr>)}</tbody></table>
      <button disabled={!selected.length || pack.isPending || !host.hasPermission('aipicture:export:download')} onClick={() => download('zip')} className="bg-ink mt-4 rounded-control px-4 py-2 text-sm text-white disabled:opacity-40">{pack.isPending ? '打包中…' : `打包所选 ${selected.length} 张`}</button>
    </section>{pack.isError && <p className="text-danger mt-3 text-sm" role="alert">{errorMessage(pack.error)}</p>}
  </div>
}
