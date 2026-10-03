import { CheckCircle2, Download, Loader2, RotateCcw } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { ApiError, newCommandKey } from '@/api/client'
import { usePermission } from '@/features/auth/store'
import { tokenStore } from '@/features/auth/token'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { classifyExportError, type ExportFailure } from '@/features/deck/exportErrors'
import { errorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import { getLayout, getTheme, themeList } from '@/render/design'
import { SlideView } from '@/render/SlideView'
import { checkSampleDeck, downloadSampleDeck, fetchSampleDeck } from './api'
import type { SampleDeck, SampleExportCommand, SampleQualityReport } from './types'

/** 原主题面板内的样例弹层；样例只展示目录内容，不改变用户的正式项目。 */
export function SamplePanel({ themeId, onClose }: { themeId: string; onClose: () => void }) {
  const canView = usePermission('aippt:design:query')
  const canCreate = usePermission('aippt:export:create')
  const canDownload = usePermission('aippt:export:download')
  const [selectedTheme, setSelectedTheme] = useState(themeId)
  const [deck, setDeck] = useState<SampleDeck | null>(null)
  const [index, setIndex] = useState(0)
  const [report, setReport] = useState<SampleQualityReport | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [busy, setBusy] = useState<'load' | 'check' | 'export' | null>('load')
  const [error, setError] = useState<string | null>(null)
  const [failure, setFailure] = useState<ExportFailure | null>(null)
  const [done, setDone] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [truncated, setTruncated] = useState<number | null>(null)
  const generation = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const command = useRef<SampleExportCommand | null>(null)

  useEffect(() => { setSelectedTheme(themeId) }, [themeId])

  useEffect(() => {
    const own = ++generation.current
    const abort = new AbortController(); controller.current?.abort(); controller.current = abort
    const token = tokenStore.get()
    setDeck(null); setReport(null); setIndex(0); setAcknowledged(false)
    setError(null); setFailure(null); setTruncated(null); setDone(false); setUncertain(false); command.current = null
    if (!canView) { setBusy(null); return () => abort.abort() }
    setBusy('load')
    const current = () => !abort.signal.aborted && generation.current === own && tokenStore.get() === token
    void fetchSampleDeck(selectedTheme, abort.signal).then((value) => {
      if (current()) setDeck(value)
    }).catch((cause: unknown) => {
      if (current()) setError(errorMessage(cause instanceof Error ? cause : null, '样例读取失败'))
    }).finally(() => { if (current()) setBusy(null) })
    return () => { abort.abort(); ++generation.current }
  }, [selectedTheme, canView, loadAttempt])

  useEffect(() => {
    const invalidate = () => {
      ++generation.current; controller.current?.abort(); command.current = null
      setDeck(null); setReport(null); setFailure(null); setTruncated(null); setBusy(null); setUncertain(false); setDone(false)
      setError('登录会话已变化，请关闭后重新打开样例')
    }
    window.addEventListener('aippt:session-change', invalidate)
    return () => { window.removeEventListener('aippt:session-change', invalidate); controller.current?.abort(); ++generation.current }
  }, [])

  const check = async () => {
    if (busy || !deck || !canView || uncertain) return
    const own = generation.current; const token = tokenStore.get()
    const abort = new AbortController(); controller.current = abort
    const current = () => !abort.signal.aborted && generation.current === own && tokenStore.get() === token
    setBusy('check'); setReport(null); setError(null); setFailure(null); setTruncated(null); setAcknowledged(false); setDone(false)
    try { const value = await checkSampleDeck(selectedTheme, abort.signal); if (current()) setReport(value) }
    catch (cause) { if (current()) setError(errorMessage(cause instanceof Error ? cause : null, '样例质量检查失败')) }
    finally { if (current()) setBusy(null) }
  }

  const errors = report?.issues.filter((issue) => issue.severity === 'error') ?? []
  const warnings = report?.issues.filter((issue) => issue.severity === 'warning') ?? []
  const allowed = report?.can_export === true && errors.length === 0
  const exportSample = async () => {
    if (busy || !deck || !allowed || !canView || !canCreate || !canDownload || (warnings.length > 0 && !acknowledged)) return
    const own = generation.current; const token = tokenStore.get()
    const abort = new AbortController(); controller.current = abort
    const current = () => !abort.signal.aborted && generation.current === own && tokenStore.get() === token
    command.current ??= { key: newCommandKey(), body: JSON.stringify({ theme_id: selectedTheme, acknowledge_warnings: acknowledged }) }
    const request = command.current
    setBusy('export'); setError(null); setFailure(null); setTruncated(null); setDone(false)
    try {
      await downloadSampleDeck(request, abort.signal)
      if (current()) { command.current = null; setUncertain(false); setDone(true) }
    } catch (cause) {
      if (current()) {
        const classified = classifyExportError(cause)
        setFailure(cause instanceof ApiError ? { ...classified, message: errorMessage(cause) } : classified)
        // 非终态的传输/服务故障，保持原 body/key/job 供用户重试。
        const known = request.terminal === true || (cause instanceof ApiError && cause.status < 500)
        if (cause instanceof ApiError && cause.detail && typeof cause.detail === 'object' && !Array.isArray(cause.detail)) {
          if (cause.detail.diagnostics_truncated === true && typeof cause.detail.total_issues === 'number') setTruncated(cause.detail.total_issues)
        }
        if (known) command.current = null
        setUncertain(!known)
      }
    } finally { if (current()) setBusy(null) }
  }

  return (
    <Dialog title="主题与版式样例" description="共享目录样例，展示四套主题与固定版式，不是当前项目的生成结果" onClose={onClose} className="max-w-5xl"
      footer={<>
        {done && <span role="status" className="mr-auto flex items-center gap-1.5 text-[13px] text-positive"><CheckCircle2 className="size-4" />已开始下载</span>}
        <Button variant="ghost" onClick={onClose}>关闭</Button>
        <Button disabled={!!busy || !allowed || !canCreate || !canDownload || (warnings.length > 0 && !acknowledged)} onClick={() => void exportSample()}>
          {busy === 'export' ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
          {busy === 'export' ? '正在导出…' : uncertain ? '重试原导出' : '导出样例 PPTX'}
        </Button>
      </>}>
      {!canView ? <p role="alert" className="text-sm text-negative">没有查看样例的权限</p> : <div className="flex flex-col gap-4">
        <div className="flex flex-wrap gap-2" aria-label="样例主题">
          {themeList.map((theme) => <Button key={theme.id} size="sm" variant={selectedTheme === theme.id ? 'accent' : 'ghost'} aria-pressed={selectedTheme === theme.id} disabled={busy === 'export' || uncertain} onClick={() => setSelectedTheme(theme.id)}>{theme.name}</Button>)}
        </div>
        {busy === 'load' && <p role="status" className="py-6 text-center text-sm text-ink-muted">正在读取样例…</p>}
        {error && <p role="alert" className="rounded-xl bg-negative/8 p-3 text-[13px] text-negative">{error}</p>}
        {error && !deck && !busy && <Button size="sm" variant="ghost" onClick={() => setLoadAttempt((value) => value + 1)}>重试读取样例</Button>}
        {deck && <>
          <p className="text-xs text-ink-muted">{deck.title} · {deck.slides.length} 页 · 目录 {deck.catalog_version}</p>
          <div className="overflow-hidden rounded-xl shadow-slide"><SlideView slide={deck.slides[index]!} theme={getTheme(selectedTheme)} slideIndex={index} /></div>
          <div className="flex flex-wrap gap-1.5" aria-label="样例页面">
            {deck.slides.map((slide, page) => <button key={slide.id} type="button" aria-pressed={index === page} onClick={() => setIndex(page)} className={cn('rounded-lg border px-2 py-1 text-xs', index === page ? 'border-accent bg-accent-soft text-accent' : 'border-line text-ink-soft hover:bg-surface-soft')}>{page + 1} · {getLayout(slide.layout_id).name}</button>)}
          </div>
          <div className="flex items-center gap-3 border-t border-line pt-3">
            <Button size="sm" variant="ghost" disabled={!!busy || uncertain} onClick={() => void check()}>{busy === 'check' ? <Loader2 className="size-3.5 animate-spin" /> : <RotateCcw className="size-3.5" />}{busy === 'check' ? '正在检查…' : '检查样例质量'}</Button>
            {report && <p role="status" className={cn('text-[13px]', allowed ? 'text-positive' : 'text-negative')}>{allowed ? `检查完成，${warnings.length} 项警告` : `质量问题阻止导出，${errors.length} 项错误`}</p>}
          </div>
        </>}
        {report && report.fonts_available !== true && <p className="rounded-xl bg-warning/8 p-3 text-[13px] text-warning">度量字体未就绪，文字溢出为估算值，并非精确结论。</p>}
        {report && report.issues.length > 0 && <ul className="flex flex-col gap-2" aria-label="样例质量问题">{report.issues.map((issue, position) => {
          const sampleId = issue.slide_id ? report.sample_slide_ids?.[issue.slide_id] : undefined
          const page = sampleId ? deck?.slides.findIndex((slide) => slide.id === sampleId) ?? -1 : -1
          return <li key={position} className={cn('rounded-lg p-2 text-xs', issue.severity === 'error' ? 'bg-negative/8 text-negative' : 'bg-warning/8 text-warning')}>
            {page >= 0 && <button type="button" className="mr-1 underline underline-offset-2" onClick={() => setIndex(page)}>第 {page + 1} 页</button>}
            {issue.slot_id && `${issue.slot_id} · `}{issue.message}
          </li>
        })}</ul>}
        {allowed && warnings.length > 0 && <label className="flex items-start gap-2 text-[13px] text-ink-soft"><input type="checkbox" checked={acknowledged} disabled={busy === 'export' || uncertain} onChange={(event) => setAcknowledged(event.target.checked)} className="mt-0.5 accent-accent" />我已了解以上警告，仍要导出样例</label>}
        {uncertain && <p role="status" className="text-xs text-warning">本次导出结果尚未确认；请重试原请求，不会重新创建已确认的导出任务。</p>}
        {failure && <div role="alert" className="rounded-xl bg-negative/8 p-3 text-[13px] text-negative"><p>{failure.message}</p>{failure.kind === 'verify' && <ul className="mt-2 space-y-1">{failure.issues.map((issue, position) => <li key={position} className="text-xs">{issue.slide_index != null && `第 ${issue.slide_index} 页 · `}{issue.shape && `${issue.shape} · `}{issue.message}</li>)}</ul>}{truncated != null && <p className="mt-2 text-xs">诊断已截断，原始问题共 {truncated} 项。</p>}</div>}
      </div>}
    </Dialog>
  )
}
