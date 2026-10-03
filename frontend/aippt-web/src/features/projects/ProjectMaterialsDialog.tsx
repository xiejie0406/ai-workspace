import { useQueryClient } from '@tanstack/react-query'
import { FileText, FolderOpen, Loader2, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { ApiError, newCommandKey, request } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { TextField } from '@/components/ui/TextField'
import { usePermission } from '@/features/auth/store'
import { tokenStore } from '@/features/auth/token'
import { outlineKey, useOutline } from '@/features/outline/api'
import { readProject, useProject } from '@/features/projects/api'
import { ACCEPTED_UPLOAD, TONE_LABEL, type Project, type ProjectSource } from '@/features/projects/types'
import { errorMessage } from '@/lib/errors'
import { useSaveNavigationGuard } from '@/hooks/useSaveNavigationGuard'

type Fields = Pick<Project, 'title' | 'audience' | 'tone' | 'page_count' | 'layout_mode' | 'content_density'>
const fields = (p: Project): Fields => ({ title: p.title, audience: p.audience, tone: p.tone, page_count: p.page_count, layout_mode: p.layout_mode, content_density: p.content_density })
export function changedProjectFields(base: Fields, draft: Fields): Partial<Fields> {
  return Object.fromEntries(Object.entries(draft).filter(([key, value]) => value !== base[key as keyof Fields]))
}
type Command = { path: string; method: 'POST' | 'PATCH' | 'DELETE'; body: string | FormData; key: string; kind: 'settings' | 'source' | 'unconfirm'; delta?: Partial<Fields>; sourceText?: string; sourceFile?: File; state: 'sending' | 'unknown' | 'conflict' }
type Section = { id?: string; ref?: string; level: number; heading?: string | null; text: string; locator: string }

/** 原工作台顶栏与项目卡菜单共用同一材料入口，不建立另一张业务页面。 */
export function ProjectMaterialsButton({ projectId: supplied }: { projectId?: string }) {
  const { projectId: routeId } = useParams()
  const projectId = supplied ?? routeId
  const canRead = usePermission('aippt:project:query')
  const [open, setOpen] = useState(false)
  if (!projectId || !canRead) return null
  return <><Button variant="ghost" size="sm" onClick={() => setOpen(true)}><FolderOpen className="size-3.5" />材料与参数</Button>{open && <ProjectMaterialsDialog key={projectId} projectId={projectId} onClose={() => setOpen(false)} />}</>
}

export function ProjectMaterialsDialog({ projectId, onClose }: { projectId: string; onClose: () => void }) {
  const queryClient = useQueryClient()
  const query = useProject(projectId)
  const canQuerySources = usePermission('aippt:source:query')
  const canAdd = usePermission('aippt:source:add')
  const canRemove = usePermission('aippt:source:remove')
  const canEdit = usePermission('aippt:project:edit')
  const canConfirm = usePermission('aippt:outline:confirm')
  const canOutline = usePermission('aippt:outline:query')
  const outline = useOutline(projectId, canOutline)
  const [baseline, setBaseline] = useState<Project | null>(null)
  const [draft, setDraft] = useState<Fields | null>(null)
  const [kind, setKind] = useState<'topic' | 'text'>('text')
  const [text, setText] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [selected, setSelected] = useState<ProjectSource | null>(null)
  const [sections, setSections] = useState<Section[]>([])
  const [viewBusy, setViewBusy] = useState(false)
  const [command, setCommand] = useState<Command | null>(null)
  const [review, setReview] = useState<Project | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const commandRef = useRef<Command | null>(null)
  const generation = useRef(0)
  const selection = useRef(0)
  const token = tokenStore.get()
  const identity = useRef({ projectId, token })
  identity.current = { projectId, token }
  const scope = () => ({ projectId, token: tokenStore.get(), generation: generation.current })
  const valid = (s: ReturnType<typeof scope>) => identity.current.projectId === s.projectId && identity.current.token === s.token && tokenStore.get() === s.token && generation.current === s.generation
  const setIntent = (c: Command | null) => { commandRef.current = c; setCommand(c) }
  const reset = (p: Project) => { setBaseline(p); setDraft(fields(p)); setReview(null) }
  const delta = baseline && draft ? changedProjectFields(fields(baseline), draft) : {}
  const dirty = Object.keys(delta).length > 0
  const leaveError = useSaveNavigationGuard(() => dirty || !!command || busy || !!text.trim() || files.length > 0, async () => { if (dirty || command || busy || text.trim() || files.length) throw new Error('请先保存或明确放弃材料与参数草稿后离开') })
  const locked = outline.data?.status === 'confirmed' || query.data?.status === 'generating'

  useEffect(() => {
    generation.current++; selection.current++
    setBaseline(null); setDraft(null); setSelected(null); setSections([]); setFiles([]); setText(''); setIntent(null); setReview(null); setBusy(false); setError(''); setViewBusy(false)
    return () => { generation.current++; selection.current++ }
  }, [projectId, token])
  useEffect(() => { if (query.data && !dirty && !commandRef.current) reset(query.data) }, [query.data, dirty])
  useEffect(() => {
    if (!(dirty || command || text.trim() || files.length)) return
    const guard = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', guard)
    return () => window.removeEventListener('beforeunload', guard)
  }, [dirty, command, text, files.length])

  const refresh = async (s: ReturnType<typeof scope>) => {
    await Promise.all([queryClient.invalidateQueries({ queryKey: ['projects'] }), canOutline ? queryClient.invalidateQueries({ queryKey: outlineKey(s.projectId), exact: true }) : Promise.resolve()])
  }
  const send = async (c: Command, s = scope()) => {
    if (!valid(s)) return false
    setBusy(true); setError(''); setIntent({ ...c, state: 'sending' })
    try {
      await request(c.path, { method: c.method, body: c.body, idempotencyKey: c.key })
      if (!valid(s)) return false
      await refresh(s)
      if (!valid(s)) return false
      if (c.kind === 'settings') { const p = await readProject(s.projectId); if (!valid(s)) return false; reset(p) }
      if (c.sourceText !== undefined) setText((current) => current === c.sourceText ? '' : current)
      if (c.sourceFile) setFiles((current) => current.filter((file) => file !== c.sourceFile))
      setIntent(null); setReview(null)
      return true
    } catch (cause) {
      if (!valid(s)) return false
      const definite = cause instanceof ApiError && cause.status < 500
      setIntent(definite && (cause.status !== 409 || c.kind === 'unconfirm') ? null : { ...c, state: cause instanceof ApiError && cause.status === 409 ? 'conflict' : 'unknown' })
      if (definite && c.kind === 'unconfirm') void refresh(s)
      setError(errorMessage(cause, '保存失败'))
      return false
    } finally { if (valid(s)) setBusy(false) }
  }
  const saveSettings = async () => {
    if (!baseline || !draft || busy || command || !canEdit || locked || !dirty) return
    if (!draft.title.trim() || draft.title.length > 200 || (delta.page_count !== undefined && (!Number.isInteger(draft.page_count) || draft.page_count < 5 || draft.page_count > 20))) { setError('名称需为1–200字；新的目标页数需为5–20。已有正式页数不改动时保持原值。'); return }
    await send({ path: `/projects/${projectId}`, method: 'PATCH', body: JSON.stringify({ ...delta, input_revision: baseline.input_revision }), key: newCommandKey(), kind: 'settings', delta, state: 'sending' })
  }
  const sourceAction = async (path: string, body: Record<string, unknown> | FormData, method: 'POST' | 'DELETE' = 'POST') => {
    const s = scope(); if (busy || command || locked) return false
    setBusy(true); setError('')
    try {
      const p = await readProject(s.projectId)
      if (!valid(s)) return false
      if (body instanceof FormData) body.append('input_revision', String(p.input_revision))
      const data = body instanceof FormData ? body : JSON.stringify({ ...body, input_revision: p.input_revision })
      return await send({ path, method, body: data, key: newCommandKey(), kind: 'source', sourceText: !(body instanceof FormData) && typeof body.text === 'string' ? body.text : undefined, sourceFile: body instanceof FormData ? body.get('file') as File : undefined, state: 'sending' }, s)
    } catch (cause) { if (valid(s)) setError(errorMessage(cause)); return false }
    finally { if (valid(s)) setBusy(false) }
  }
  const addText = async () => { if (!canAdd || !text.trim()) return; const original = text; if (await sourceAction(`/projects/${projectId}/sources`, { kind, text: original })) setText('') }
  const upload = async () => {
    if (!canAdd) return
    const s = scope(); const pending = [...files]
    for (const file of pending) {
      if (!valid(s)) return
      const form = new FormData(); form.append('file', file)
      if (!await sourceAction(`/projects/${s.projectId}/sources/upload`, form)) return
      if (!valid(s)) return
      setFiles((current) => current.filter((value) => value !== file))
    }
  }
  const view = async (source: ProjectSource) => {
    const s = scope(); const own = ++selection.current
    setSelected(source); setSections([]); setViewBusy(true); setError('')
    try { const result = await request<Section[]>(`/projects/${s.projectId}/sources/${source.id}/sections`); if (valid(s) && own === selection.current) setSections(result) }
    catch (cause) { if (valid(s) && own === selection.current) setError(errorMessage(cause)) }
    finally { if (valid(s) && own === selection.current) setViewBusy(false) }
  }
  const reviewLatest = async () => {
    const s = scope(); setBusy(true); setError('')
    try { const p = await readProject(s.projectId); if (valid(s)) setReview(p) }
    catch (cause) { if (valid(s)) setError(errorMessage(cause)) }
    finally { if (valid(s)) setBusy(false) }
  }
  const reapply = async () => {
    if (!review || !command || command.state !== 'conflict' || busy) return
    if (command.kind === 'settings') {
      const actual = command.delta ?? {}
      setBaseline(review); setDraft({ ...fields(review), ...actual })
      await send({ ...command, body: JSON.stringify({ ...actual, input_revision: review.input_revision }), key: newCommandKey(), state: 'sending' })
    } else {
      const body = command.body instanceof FormData ? (() => { const next = new FormData(); for (const [name, value] of command.body.entries()) if (name !== 'input_revision') next.append(name, value); next.set('input_revision', String(review.input_revision)); return next })() : JSON.stringify({ ...JSON.parse(command.body), input_revision: review.input_revision })
      await send({ ...command, body, key: newCommandKey(), state: 'sending' })
    }
  }
  const close = () => {
    if (busy || command) { setError('请先确认本次保存结果，或处理版本冲突后关闭。'); return }
    if ((dirty || text.trim() || files.length) && !window.confirm('还有未保存的材料或参数，确认放弃这些本地输入？')) return
    onClose()
  }
  const change = <K extends keyof Fields>(key: K, value: Fields[K]) => setDraft((current) => current ? { ...current, [key]: value } : current)

  return <Dialog title="材料与参数" description="补充材料、查看解析章节，或调整内容参数。大纲确认后需先取消确认才能修改。" onClose={close} className="max-w-3xl">
    {query.isPending && <Loader2 className="size-5 animate-spin" />}
    {query.isError && <p role="alert" className="text-sm text-negative">{errorMessage(query.error)}</p>}
    {locked && <div className="mb-4 rounded-xl bg-warning/10 p-3 text-sm"><p>内容参数与材料已锁定。</p>{canConfirm && outline.data?.status === 'confirmed' && <Button size="sm" variant="ghost" disabled={busy || !!command} onClick={() => void send({ path: `/projects/${projectId}/outline/unconfirm`, method: 'POST', body: JSON.stringify({ revision: outline.data!.revision }), key: newCommandKey(), kind: 'unconfirm', state: 'sending' })}>取消大纲确认以修改</Button>}</div>}
    {draft && <section className="space-y-3" aria-label="内容参数"><h3 className="text-sm font-semibold">内容参数</h3><fieldset disabled={busy || !!command || locked || !canEdit} className="grid gap-3 sm:grid-cols-2">
      <TextField label="项目名称" value={draft.title} onChange={(event) => change('title', event.target.value)} maxLength={200} />
      <TextField label="受众" value={draft.audience ?? ''} onChange={(event) => change('audience', event.target.value || null)} maxLength={100} />
      <label className="text-[13px]">表达风格<select aria-label="表达风格" value={draft.tone ?? 'professional'} onChange={(event) => change('tone', event.target.value as Fields['tone'])} className="mt-1 block h-10 w-full rounded-xl border border-line bg-surface px-3">{Object.entries(TONE_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <TextField label="目标页数" type="number" value={draft.page_count} onChange={(event) => change('page_count', Number(event.target.value))} hint="新的生成目标为5–20页；已有正式页数原样显示，未改动不提交。" />
      <label className="text-[13px]">布局方式<select aria-label="布局方式" value={draft.layout_mode} onChange={(event) => change('layout_mode', event.target.value as Fields['layout_mode'])} className="mt-1 block h-10 w-full rounded-xl border border-line bg-surface px-3"><option value="flex">自由布局</option><option value="fixed">固定版式</option></select></label>
      <label className="text-[13px]">内容密度<select aria-label="内容密度" value={draft.content_density} onChange={(event) => change('content_density', event.target.value as Fields['content_density'])} className="mt-1 block h-10 w-full rounded-xl border border-line bg-surface px-3"><option value="concise">精简</option><option value="medium">适中</option><option value="detailed">详细</option></select></label>
    </fieldset><Button size="sm" disabled={!dirty || busy || !!command || locked || !canEdit} onClick={() => void saveSettings()}>保存参数</Button></section>}
    <section className="mt-6 space-y-3" aria-label="项目材料"><h3 className="text-sm font-semibold">材料与解析章节</h3>
      {!canQuerySources && <p className="text-xs text-ink-muted">当前账号没有材料读取权限。</p>}
      <ul className="space-y-2">{query.data?.sources.map((source) => <li key={source.id} className="rounded-xl border border-line p-3"><div className="flex items-center gap-2"><FileText className="size-4 shrink-0" /><span className="min-w-0 flex-1 truncate text-sm">{source.filename ?? (source.kind === 'topic' ? '主题' : '粘贴文字')}</span><span className="text-xs text-ink-muted">{source.parse_status ?? 'ready'} · {source.char_count ?? 0} 字</span>{canQuerySources && <Button variant="ghost" size="sm" onClick={() => void view(source)}>查看章节</Button>}{canRemove && <Button variant="ghost" size="sm" disabled={locked || busy || !!command} aria-label={`删除材料 ${source.filename ?? source.id}`} onClick={() => { if (window.confirm('删除这份材料？')) void sourceAction(`/projects/${projectId}/sources/${source.id}`, {}, 'DELETE') }}><Trash2 className="size-3.5" /></Button>}</div>{source.warnings?.map((warning, i) => <p key={i} className="mt-1 text-xs text-warning">{warning}</p>)}{(source as ProjectSource & { error?: string }).error && <p className="mt-1 text-xs text-negative">{(source as ProjectSource & { error?: string }).error}</p>}</li>)}</ul>
      {selected && <div className="max-h-64 overflow-auto rounded-xl bg-surface-soft p-3"><h4 className="text-sm font-medium">{selected.filename ?? '材料'} · 解析章节</h4>{viewBusy && <Loader2 className="mt-2 size-4 animate-spin" />}{sections.map((section, i) => <article key={section.id ?? section.ref ?? i} className="mt-3 text-sm"><h5 className="font-medium">{section.heading ?? `章节 ${i + 1}`}</h5><p className="mt-1 whitespace-pre-wrap text-ink-soft">{section.text}</p><p className="mt-1 break-all text-xs text-ink-muted">{section.ref ?? section.id ?? section.locator}</p></article>)}</div>}
      {canAdd && <fieldset disabled={locked || busy || !!command} className="space-y-3"><select aria-label="材料类型" value={kind} onChange={(event) => setKind(event.target.value as 'topic' | 'text')} className="h-9 rounded-xl border border-line bg-surface px-3 text-sm"><option value="text">粘贴文字</option><option value="topic">主题</option></select><textarea aria-label="补充材料正文" value={text} onChange={(event) => setText(event.target.value)} rows={4} className="block w-full rounded-xl border border-line bg-surface p-3 text-sm" /><Button size="sm" disabled={!text.trim()} onClick={() => void addText()}><Plus className="size-3.5" />添加文字材料</Button><label className="block text-sm">补传文档<input aria-label="补传文档" className="mt-1 block w-full text-sm" type="file" multiple accept={ACCEPTED_UPLOAD} onChange={(event) => setFiles(Array.from(event.target.files ?? []))} /></label>{files.length > 0 && <p className="text-xs text-ink-muted">待上传：{files.map((file) => file.name).join('、')}</p>}<Button size="sm" disabled={!files.length} onClick={() => void upload()}>上传待传文档</Button></fieldset>}
    </section>
    {command && command.state !== 'sending' && <div className="mt-4 rounded-xl border border-warning/30 p-3 text-sm"><p>{command.state === 'unknown' ? '上次请求结果未确认。重试将使用完全相同的正文、文件和幂等键。' : '正式数据已变化。请先读取最新版本，核对后明确重新应用或放弃草稿。'}</p>{command.state === 'unknown' ? <Button size="sm" disabled={busy} onClick={() => void send(command)}>重试原操作</Button> : <Button size="sm" variant="ghost" disabled={busy} onClick={() => void reviewLatest()}><RefreshCw className="size-3.5" />核对最新版本</Button>}{review && <><dl className="mt-2 space-y-1"><div>最新名称：{review.title}</div><div>最新受众：{review.audience ?? '未设置'}；页数：{review.page_count}；版本：{review.input_revision}</div>{command.delta && Object.entries(command.delta).map(([key, value]) => <div key={key}>待重新应用 {key}：{String(value ?? '未设置')}</div>)}</dl><div className="mt-2 flex gap-2"><Button size="sm" disabled={busy} onClick={() => void reapply()}>重新应用我的修改</Button><Button size="sm" variant="ghost" disabled={busy} onClick={() => { reset(review); setIntent(null); setError('') }}>放弃草稿</Button></div></>}</div>}
    {busy && <p role="status" className="mt-3 text-xs text-ink-muted">正在确认保存结果…</p>}
    {error && <p role="alert" className="mt-3 whitespace-pre-wrap text-sm text-negative">{error}</p>}
    {leaveError && <p role="alert" className="mt-3 text-sm text-negative">{leaveError}</p>}
  </Dialog>
}
