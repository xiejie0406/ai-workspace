import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from '@/host/router'

import type { Asset } from '@/api/assets'
import ProgressBar from '@/components/ui/ProgressBar'
import Button from '@/components/ui/Button'
import { useRun } from '@/hooks/useRun'
import { useCreateSession } from '@/hooks/useSessions'
import { useApis, useHost, useHostRuntime, useToast } from '@/host/HostContext'
import { errorMessage } from '@/lib/errors'
import { shortTitle } from '@/lib/format'
import { usePromptDraft } from '@/lib/promptDraft'

export default function CandidatesPage() {
  const { runId = '' } = useParams()
  const navigate = useNavigate()
  const host = useHost()
  const runtime = useHostRuntime()
  const { assets } = useApis()
  const toast = useToast()
  const { save: savePromptDraft } = usePromptDraft()
  const [picked, setPicked] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ asset: Asset; origin: HTMLButtonElement } | null>(null)
  const [downloading, setDownloading] = useState<string | null>(null)
  const mounted = useRef(true)
  useEffect(() => () => { mounted.current = false }, [])
  const { status, progress, stage, error, prompt, candidates, notFound, loadError, refetch } = useRun(runId || null)
  const createSession = useCreateSession()

  const download = async (asset: Asset, index: number) => {
    if (downloading || !runtime.active) return
    setDownloading(asset.id)
    try {
      const blob = await assets.download(asset.id)
      // 失活/换路由会销毁本组件及其请求；迟到的旧用户文件不能交给新实例。
      if (!mounted.current || !runtime.active) return
      await host.download(blob, `候选图-${index + 1}-${asset.id}.${asset.image_format.toLowerCase()}`)
    } catch (error) {
      if (mounted.current && runtime.active && !(error instanceof DOMException && error.name === 'AbortError')) {
        toast(errorMessage(error), 'danger')
      }
    } finally {
      if (mounted.current && runtime.active) setDownloading(null)
    }
  }

  // 采用一张进入编辑，同批其余候选一并带进会话图片墙
  const adopt = () =>
    picked &&
    createSession.mutate(
      {
        current_asset_id: picked,
        asset_ids: candidates.map((asset) => asset.id),
        title: prompt ? shortTitle(prompt, 80) : undefined,
      },
      { onSuccess: (session) => navigate(`/editor/${session.id}`) },
    )

  if (notFound) {
    return <Centered title="任务不存在" hint="链接可能已失效，回到创作页重新开始。">
      <Link to="/create" className="text-brand-strong mt-4 text-sm">返回创作</Link>
    </Centered>
  }

  if (loadError && !status) {
    return <Centered title="任务读取失败" hint={errorMessage(loadError)} alert>
      <Button className="mt-4" onClick={() => { void refetch() }}>重新读取</Button>
    </Centered>
  }

  if (status === 'failed' || status === 'canceled' || status === 'external_result_unknown') {
    return (
      <Centered title={status === 'external_result_unknown' ? '外部生成结果待核对' : status === 'canceled' ? '已取消未开始的生成' : '生成失败'} hint={error ?? '请保留任务编号，请管理员核对供应商结果。'}>
        <Link
          to="/create"
          className="bg-ink hover:bg-dark rounded-control mt-5 px-4 py-2 text-sm font-medium text-white"
        >
          返回重试
        </Link>
      </Centered>
    )
  }

  if (status !== 'succeeded') {
    return <Progress percent={progress} stage={stage} />
  }

  return (
    <div className="mx-auto max-w-5xl px-8 py-10">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-ink text-2xl font-semibold tracking-tight">选出一张</h1>
          <p className="text-muted mt-1 text-sm">
            挑一张满意的进入编辑，其余候选图会留在会话图片墙随时切回。
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-3">
          <Link to="/create" data-candidate-create onClick={() => savePromptDraft(prompt || '')}
            className="text-brand-strong text-sm">返回创作 / 重新生成</Link>
          <button
          type="button"
          disabled={!picked || createSession.isPending || !host.hasPermission('aipicture:session:create')}
          onClick={adopt}
          className="bg-ink hover:bg-dark rounded-control shrink-0 px-4 py-2 text-sm font-medium text-white transition-all duration-150 active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:active:scale-100"
        >
          {createSession.isPending ? '打开中…' : picked ? '进入编辑' : '先点选一张'}
          </button>
        </div>
      </header>
      {createSession.isError && <p className="text-danger mb-4 text-sm" role="alert">{errorMessage(createSession.error)}</p>}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {candidates.map((asset, index) => (
          <Candidate
            key={asset.id}
            asset={asset}
            index={index}
            selected={picked === asset.id}
            onSelect={() => setPicked(asset.id)}
            onPreview={(origin) => setPreview({ asset, origin })}
            onDownload={() => { void download(asset, index) }}
            downloading={downloading === asset.id}
            downloadDisabled={Boolean(downloading) || !host.hasPermission('aipicture:export:download')}
          />
        ))}
      </div>
      {preview && <CandidatePreview asset={preview.asset} index={candidates.findIndex((asset) => asset.id === preview.asset.id)}
        returnFocus={preview.origin}
        onClose={() => setPreview(null)} onDownload={() => { void download(preview.asset, candidates.findIndex((asset) => asset.id === preview.asset.id)) }}
        downloading={downloading === preview.asset.id} downloadDisabled={Boolean(downloading) || !host.hasPermission('aipicture:export:download')} />}
    </div>
  )
}

function Candidate({
  asset,
  index,
  selected,
  onSelect,
  onPreview,
  onDownload,
  downloading,
  downloadDisabled,
}: {
  asset: Asset
  index: number
  selected: boolean
  onSelect: () => void
  onPreview: (origin: HTMLButtonElement) => void
  onDownload: () => void
  downloading: boolean
  downloadDisabled: boolean
}) {
  return (
    <article className="border-line bg-paper rounded-panel overflow-hidden border">
      <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      aria-label={`选择候选图 ${index + 1}`}
      className={`rounded-panel group relative block w-full overflow-hidden border-2 bg-white transition-all duration-200 ${
        selected
          ? 'border-brand shadow-lift'
          : 'border-line hover:border-brand hover:-translate-y-0.5 hover:shadow-lift'
      }`}
    >
      <img
        src={asset.url}
        alt={`候选图 ${index + 1}`}
        loading="lazy"
        className="block max-h-[52vh] w-full object-contain transition-transform duration-300 group-hover:scale-[1.01]"
      />
      <span className="text-muted bg-paper/90 absolute top-2 left-2 rounded-full px-2 py-0.5 text-xs font-medium backdrop-blur">
        {index + 1}
      </span>
      {selected && (
        <span className="bg-brand absolute top-2 right-2 rounded-full px-2 py-0.5 text-xs font-medium text-white">
          已选
        </span>
      )}
      </button>
      <div className="text-muted flex items-center justify-between gap-3 px-3 py-2 text-xs">
        <span>{asset.width} × {asset.height} · {asset.image_format}</span>
        <div className="flex gap-3">
          <button type="button" aria-label={`放大候选图 ${index + 1}`} onClick={(event) => onPreview(event.currentTarget)} className="text-brand-strong">放大</button>
          <button type="button" aria-label={`下载候选图 ${index + 1}`} onClick={onDownload} disabled={downloadDisabled}
            className="text-brand-strong disabled:opacity-40">{downloading ? '下载中…' : '下载原图'}</button>
        </div>
      </div>
    </article>
  )
}

/** 预览留在当前 ShadowRoot；复用实例内已授权图片，不另建对象 URL。 */
function CandidatePreview({ asset, index, returnFocus, onClose, onDownload, downloading, downloadDisabled }: {
  asset: Asset; index: number; returnFocus: HTMLButtonElement | null; onClose: () => void; onDownload: () => void; downloading: boolean; downloadDisabled: boolean
}) {
  const dialog = useRef<HTMLDivElement>(null)
  useEffect(() => {
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus()
    return () => { if (returnFocus?.isConnected) returnFocus.focus() }
  }, [returnFocus])
  return <div className="bg-ink/80 fixed inset-0 z-50 flex items-center justify-center p-4" onClick={(event) => {
    if (event.target === event.currentTarget) onClose()
  }}>
    <div ref={dialog} role="dialog" aria-modal="true" aria-label={`候选图 ${index + 1} 大图预览`} tabIndex={-1}
      className="bg-paper rounded-panel flex max-h-[92vh] w-full max-w-6xl flex-col overflow-hidden"
      onKeyDown={(event) => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onClose() }
        if (event.key === 'Tab') {
          const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')]
          const origin = event.nativeEvent.composedPath()[0]
          const first = buttons[0], last = buttons.at(-1)
          if (event.shiftKey && origin === first) { event.preventDefault(); last?.focus() }
          else if (!event.shiftKey && origin === last) { event.preventDefault(); first?.focus() }
        }
      }}>
      <div className="border-line flex items-center justify-between gap-3 border-b px-4 py-3">
        <p className="text-ink text-sm">候选图 {index + 1} · {asset.width} × {asset.height} · {asset.image_format}</p>
        <div className="flex gap-4 text-sm">
          <button type="button" onClick={onClose} className="text-muted" aria-label="关闭大图预览">关闭</button>
          <button type="button" onClick={onDownload} disabled={downloadDisabled} className="text-brand-strong disabled:opacity-40">
            {downloading ? '下载中…' : '下载原图'}
          </button>
        </div>
      </div>
      <div className="bg-canvas min-h-0 overflow-auto p-4">
        <img src={asset.url} alt={`候选图 ${index + 1} 大图`} className="mx-auto max-h-[78vh] max-w-full object-contain" />
      </div>
    </div>
  </div>
}

function Progress({ percent, stage }: { percent: number; stage: string }) {
  return (
    <Centered title="正在生成" hint={stage || '任务已提交，正在排队'}>
      <ProgressBar value={percent} className="mt-6 h-1 w-64 rounded-full" />
      <p className="text-faint mt-2 text-xs tabular-nums">{percent}%</p>
    </Centered>
  )
}

function Centered({
  title,
  hint,
  children,
  alert,
}: {
  title: string
  hint: string
  children?: React.ReactNode
  alert?: boolean
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-8 text-center">
      <h1 className="text-ink text-xl font-semibold">{title}</h1>
      <p className="text-muted mt-1 max-w-md text-sm" role={alert ? 'alert' : undefined}>{hint}</p>
      {children}
    </div>
  )
}
