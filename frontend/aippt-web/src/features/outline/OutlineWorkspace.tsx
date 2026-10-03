import { Check, GripVertical, Loader2, Plus, RefreshCw, Sparkles, Trash2, X } from 'lucide-react'
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError, request } from '@/api/client'
import { usePermission } from '@/features/auth/store'
import { tokenStore } from '@/features/auth/token'
import { WorkbenchHeader } from '@/components/WorkbenchHeader'
import { Button } from '@/components/ui/Button'
import { outlineKey, useGenerateOutline, useOutline, useUpdateOutline } from '@/features/outline/api'
import type { Outline, OutlinePage } from '@/features/outline/types'
import { useConfirmAndGenerate } from '@/features/outline/useConfirmAndGenerate'
import { useOutlineProgress } from '@/features/outline/useOutlineProgress'
import { readProject, useUpdateProject } from '@/features/projects/api'
import { PAGE_COUNT_RANGE, type ProjectDetail } from '@/features/projects/types'
import { moveItem, useDragSort } from '@/hooks/useDragSort'
import { errorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import { layouts, themeList } from '@/render/design'
import { ThemeCover } from '@/render/ThemeCover'
import { useSaveNavigationGuard } from '@/hooks/useSaveNavigationGuard'

const MAX_KEY_POINTS = 5
const MIN_KEY_POINTS = 2
const PAGE_ROLE_LABEL: Record<OutlinePage['page_role'], string> = { cover: '封面', toc: '目录', section: '章节', content: '正文', summary: '总结' }
type SourceChoice = { ref: string; label: string }

export function OutlineWorkspace({ project }: { project: ProjectDetail }) {
  const canGenerate = usePermission('aippt:outline:generate')
  const outlineQuery = useOutline(project.id)
  const generate = useGenerateOutline(project.id)
  const outline = outlineQuery.data ?? null
  const progress = useOutlineProgress(project.id, outline?.status === 'generating')

  if (outlineQuery.isPending) {
    return (
      <Shell title={project.title}>
        <CenterCard>
          <Loader2 className="mx-auto size-5 animate-spin text-ink-muted" />
          <p className="mt-4 text-sm text-ink-muted">正在读取大纲…</p>
        </CenterCard>
      </Shell>
    )
  }

  if (outline?.status === 'generating') {
    return (
      <Shell title={project.title}>
        <CenterCard>
          <p className="text-sm font-medium">{progress.event?.message ?? '正在规划每一页…'}</p>
          <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-line">
            <div
              className="h-full rounded-full bg-accent transition-[width] duration-500"
              style={{ width: `${progress.event?.progress ?? 8}%` }}
            />
          </div>
          {progress.deliveryMessage && <p role="status" className="mt-3 text-xs text-warning">{progress.deliveryMessage}</p>}
          <p className="mt-3 text-xs text-ink-muted">
            {progress.connectionError ? '进度连接中断，正在重连…' : '大纲只规划目标与要点，不生成正文'}
          </p>
        </CenterCard>
      </Shell>
    )
  }

  if (outline == null || outline.status === 'failed') {
    return (
      <Shell title={project.title}>
        <CenterCard>
          <p className="text-sm text-ink-soft">
            {outline?.error ?? (outline ? '大纲生成失败。' : '还没有大纲。')}
          </p>
          <Button
            className="mt-5"
            disabled={generate.isPending || !canGenerate}
            onClick={() => generate.mutate()}
          >
            <RefreshCw className={cn('size-4', generate.isPending && 'animate-spin')} />
            {generate.isPending ? '正在提交…' : '重新生成大纲'}
          </Button>
          {generate.isError && (
            <p role="alert" className="mt-3 text-xs text-negative">
              {errorMessage(generate.error)}
            </p>
          )}
        </CenterCard>
      </Shell>
    )
  }

  return <OutlineEditor project={project} outline={outline} />
}

function OutlineEditor({ project, outline }: { project: ProjectDetail; outline: Outline }) {
  const queryClient = useQueryClient()
  const canEdit = usePermission('aippt:outline:edit')
  const canGenerate = usePermission('aippt:outline:generate')
  const canConfirm = usePermission('aippt:outline:confirm')
  const canGenerateDeck = usePermission('aippt:deck:generate')
  const canLaunch = canConfirm && canGenerateDeck
  const canTheme = usePermission('aippt:theme:edit')
  const [pages, setPages] = useState<OutlinePage[]>(outline.pages)
  const [themeId, setThemeId] = useState(project.theme_id)
  const sourceChoices: SourceChoice[] = project.sources.flatMap((source, sourceIndex) =>
    (source.sections ?? []).flatMap((section, sectionIndex) => {
      const stable = section as typeof section & { id?: string; ref?: string }
      const ref = stable.ref ?? stable.id
      return ref ? [{ ref, label: `${source.filename ?? `材料 ${sourceIndex + 1}`} · ${section.heading ?? `章节 ${sectionIndex + 1}`}` }] : []
    }),
  )
  const target = project.page_count

  const save = useUpdateOutline(project.id)
  const updateProject = useUpdateProject(project.id)
  const regenerate = useGenerateOutline(project.id)
  const launch = useConfirmAndGenerate(project.id)
  const [recoveryError, setRecoveryError] = useState<Error | null>(null)

  const pagesRef = useRef(pages); pagesRef.current = pages
  const formalRef = useRef(JSON.stringify(outline.pages))
  const pendingSave = useRef<Promise<void> | null>(null)
  const failedInput = useRef<{ revision: number; pages: OutlinePage[] } | null>(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    const previous = formalRef.current; formalRef.current = JSON.stringify(outline.pages)
    if (JSON.stringify(pagesRef.current) === previous) setPages(outline.pages)
  }, [outline.pages, outline.revision])

  const dirty = useMemo(
    () => JSON.stringify(pages) !== JSON.stringify(outline.pages),
    [outline.pages, pages],
  )
  const countValid = pages.length === target
  const incomplete = pages.some(pageIncomplete)
  // 刚点「+ 要点」的空行：暂停 autosave，避免 normalize 后空行被立刻清掉
  const draftingPoint = pages.some((page) => page.key_points.some((point) => point.trim().length === 0))
  const persisting = save.isPending || updateProject.isPending
  const saveDraft = async ({ revision, pages: nextPages }: { revision: number; pages: OutlinePage[] }) => {
    const token = tokenStore.get()
    setRecoveryError(null)
    const task = (async () => {
      failedInput.current = { revision, pages: nextPages }
      const currentProject = queryClient.getQueryData<ProjectDetail>(['projects', project.id]) ?? project
      if (nextPages.length !== currentProject.page_count) await updateProject.mutateAsync({ page_count: nextPages.length })
      if (!alive.current || tokenStore.get() !== token) throw new DOMException('页面或会话已变化', 'AbortError')
      await save.mutateAsync({ revision, pages: nextPages })
      if (!alive.current || tokenStore.get() !== token) return
      failedInput.current = null; save.reset(); updateProject.reset(); launch.reset()
    })()
    pendingSave.current = task
    try { await task } finally { if (pendingSave.current === task) pendingSave.current = null }
  }
  const flushDraft = async () => {
    if (launch.isPending || launch.unconfirmed) throw new Error('确认与生成请求结果尚未确认，请先重试原操作')
    if (pendingSave.current) await pendingSave.current
    if (save.isError || updateProject.isError) throw new Error('请先处理大纲保存错误')
    if (dirty) {
      if (!canEdit || incomplete || draftingPoint) throw new Error('请完整填写大纲后再保存或离开')
      const formal = queryClient.getQueryData<Outline>(outlineKey(project.id)) ?? outline
      await saveDraft({ revision: formal.revision, pages: normalizePages(pagesRef.current) })
    }
  }
  const leaveError = useSaveNavigationGuard(() => dirty || persisting || launch.isPending || launch.unconfirmed, flushDraft)
  const autosave = useAutosave({
    revision: outline.revision,
    pages,
    pageCount: target,
    dirty,
    draftingPoint,
    // 页数不一致时仍保存：先对齐 page_count，避免增删页后改动卡在「待保存」
    enabled: canEdit && dirty && !incomplete && !draftingPoint && !launch.isPending && !launch.unconfirmed && !save.isError && !updateProject.isError,
    saving: persisting,
    save: async ({ revision, pages: nextPages }) => {
      try { await saveDraft({ revision, pages: nextPages }) } catch { /* 原动作错误原位展示，草稿暂停等待明确恢复 */ }
    },
  })

  const drag = useDragSort((from, to) => setPages((current) => moveItem(current, from, to)))

  const changePage = (index: number, next: OutlinePage) =>
    setPages((current) => current.map((page, i) => (i === index ? next : page)))

  const startGeneration = async () => {
    if (!canLaunch || incomplete || draftingPoint || launch.isPending || persisting || save.isError || updateProject.isError) return
    // 页数被用户改过时先对齐目标，再走确认+生成
    try {
      if (!countValid) await updateProject.mutateAsync({ page_count: pages.length })
    // 确认成功后大纲状态变为 confirmed，页面自身会切到编辑工作台，无需跳转
    launch.mutate({
      revision: outline.revision,
      ...(dirty ? { pages: normalizePages(pages) } : {}),
      ...(themeId === project.theme_id ? {} : { themeId }),
    })
    } catch (cause) { if (alive.current) setRecoveryError(cause instanceof Error ? cause : new Error('启动生成失败')) }
  }

  const actionError = recoveryError ?? launch.error ?? save.error ?? updateProject.error ?? regenerate.error
  const saveFailure = save.error ?? updateProject.error
  const saveConflict = saveFailure instanceof ApiError && saveFailure.status === 409

  return (
    <Shell
      title={project.title}
      meta={
        <span
          className={cn(
            'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium tabular-nums',
            countValid ? 'bg-surface-soft text-ink-muted' : 'bg-warning/12 text-warning',
          )}
        >
          {pages.length} / {target} 页
        </span>
      }
      actions={
        <>
          <span className="mr-1 text-xs text-ink-muted">{autosave}</span>
          <Button
            variant="ghost"
            size="sm"
            disabled={!canGenerate || regenerate.isPending || launch.isPending || launch.unconfirmed || persisting || dirty}
            onClick={() => {
              launch.reset()
              save.reset()
              updateProject.reset()
              regenerate.mutate()
            }}
          >
            <RefreshCw className={cn('size-3.5', regenerate.isPending && 'animate-spin')} />
            重新生成
          </Button>
          <Button
            size="sm"
            disabled={!canLaunch || incomplete || draftingPoint || launch.isPending || launch.unconfirmed || persisting || save.isError || updateProject.isError}
            onClick={() => void startGeneration()}
          >
            {launch.isPending ? <Loader2 className="size-4 animate-spin" /> : <Sparkles className="size-4" />}
            {launch.isPending ? '启动中…' : '生成 PPT'}
          </Button>
        </>
      }
    >
      <div className="mx-auto grid max-w-6xl gap-8 px-6 py-8 lg:grid-cols-[minmax(0,1fr)_19rem]">
        <div>
          <div className="mb-5">
            <h2 className="text-xl font-semibold tracking-tight">确认大纲</h2>
            <p className="mt-1.5 text-sm text-ink-muted">
              改标题、改要点、增删页、拖拽排序，改完自动保存。确认后开始生成 16:9 页面。
            </p>
          </div>

          {(outline.warnings?.length ?? 0) > 0 && (
            <div role="status" className="mb-4 rounded-xl bg-warning/10 px-4 py-3 text-sm text-warning">
              {outline.warnings!.map((warning, index) => <p key={index}>{warning}</p>)}
              <p className="mt-1 text-xs">完整材料仍保留；该提示说明本版提纲规划使用的模型输入范围。</p>
            </div>
          )}

          {actionError && (
            <p role="alert" className="mb-4 rounded-xl bg-negative/8 px-4 py-3 text-sm text-negative">
              {errorMessage(actionError)}
              {launch.unconfirmed && launch.variables && <Button variant="ghost" size="sm" disabled={!canLaunch || launch.isPending} onClick={() => launch.mutate(launch.variables!)}>重试原确认与生成</Button>}
              {failedInput.current && <Button variant="ghost" size="sm" disabled={persisting} onClick={() => void (async () => {
                try {
                let next = failedInput.current; if (!next) return
                const token = tokenStore.get()
                if (saveConflict) {
                  const latest = await request<Outline>(`/projects/${project.id}/outline`)
                  const latestProject = await readProject(project.id)
                  if (!alive.current || tokenStore.get() !== token) return
                  if (!window.confirm(`最新大纲版本 ${latest.revision}，第一页「${latest.pages[0]?.title ?? ''}」。核对后将重新应用本地大纲草稿，是否继续？`)) return
                  queryClient.setQueryData(outlineKey(project.id), latest)
                  queryClient.setQueryData<ProjectDetail>(['projects', project.id], (current) => current ? { ...current, ...latestProject } : current)
                  next = { revision: latest.revision, pages: normalizePages(pagesRef.current) }
                }
                try { await saveDraft(next) } catch { /* 保留原失败与草稿 */ }
                } catch (cause) { if (alive.current) setRecoveryError(cause instanceof Error ? cause : new Error('读取最新大纲失败')) }
              })()}> {saveConflict ? '核对最新大纲后重试' : '重试原保存'}</Button>}
            </p>
          )}
          {leaveError && <p role="alert" className="mb-4 text-sm text-negative">{leaveError}</p>}

          <fieldset disabled={!canEdit || launch.isPending || launch.unconfirmed}><ol className="flex flex-col gap-3">
            {pages.map((page, index) => (
              <PageCard
                key={page.id}
                page={page}
                index={index}
                sourceChoices={sourceChoices}
                dragProps={canEdit ? drag.itemProps(index) : {}}
                dragOver={drag.overIndex === index}
                dragging={drag.draggingIndex === index}
                canRemove={pages.length > PAGE_COUNT_RANGE.min}
                onChange={(next) => changePage(index, next)}
                onRemove={() => setPages((current) => current.filter((_, i) => i !== index))}
              />
            ))}
          </ol>

          {pages.length < PAGE_COUNT_RANGE.max && (
            <button
              type="button"
              onClick={() => setPages((current) => [...current, blankPage(current.length + 1)])}
              className="mt-3 flex w-full items-center justify-center gap-1.5 rounded-2xl border border-dashed border-line-strong py-3.5 text-[13px] text-ink-muted transition-colors hover:border-accent hover:text-accent"
            >
              <Plus className="size-4" />
              添加一页
            </button>
          )}
          </fieldset>

          {!countValid && (
            <p className="mt-3 text-center text-xs text-ink-muted">
              页数已改为 {pages.length}，保存时会同步目标页数。
            </p>
          )}
          {pages.length === PAGE_COUNT_RANGE.min && (
            <p className="mt-3 text-center text-xs text-ink-muted">最少 {PAGE_COUNT_RANGE.min} 页。</p>
          )}
          {incomplete && (
            <p className="mt-3 text-center text-xs text-warning">
              每页都需要标题、目标和至少两条要点。
            </p>
          )}
          {draftingPoint && (
            <p className="mt-3 text-center text-xs text-ink-muted">填写或删除空要点后自动保存。</p>
          )}
        </div>

        <aside className="flex flex-col gap-3 lg:sticky lg:top-20 lg:self-start">
          <div>
            <h3 className="text-sm font-semibold tracking-tight">外观</h3>
            <p className="mt-1 text-xs text-ink-muted">决定成品的字体、配色与气质；切换后点「生成 PPT」时生效</p>
          </div>
          {themeList.map((theme) => (
            <button
              key={theme.id}
              type="button"
              disabled={!canTheme || launch.isPending || launch.unconfirmed}
              aria-pressed={theme.id === themeId}
              onClick={() => setThemeId(theme.id)}
              className={cn(
                'group overflow-hidden rounded-2xl border bg-surface text-left transition-all',
                theme.id === themeId
                  ? 'border-accent shadow-card ring-1 ring-accent'
                  : 'border-line hover:border-line-strong hover:shadow-card',
              )}
            >
              <ThemeCover theme={theme} title={project.title} />
              <div className="flex items-center gap-2 px-3 py-2.5">
                <span className="flex-1 truncate text-[13px] font-medium">{theme.name}</span>
                {theme.id === themeId && <Check className="size-3.5 shrink-0 text-accent" />}
              </div>
            </button>
          ))}
          <p className="mt-1 text-xs leading-relaxed text-ink-muted">
            配图会自动匹配；素材不可用时会退化为纯文字版式，不会留下空位。
          </p>
        </aside>
      </div>
    </Shell>
  )
}

function PageCard({
  page,
  index,
  sourceChoices,
  dragProps,
  dragOver,
  dragging,
  canRemove,
  onChange,
  onRemove,
}: {
  page: OutlinePage
  index: number
  sourceChoices: SourceChoice[]
  dragProps: Record<string, unknown>
  dragOver: boolean
  dragging: boolean
  canRemove: boolean
  onChange: (page: OutlinePage) => void
  onRemove: () => void
}) {
  const keyPoints = page.key_points
  const selectedRefs = page.source_refs ?? []
  const available = new Map(sourceChoices.map((choice) => [choice.ref, choice]))
  const refs = [...available.values(), ...selectedRefs.filter((ref) => !available.has(ref)).map((ref) => ({ ref, label: `已失效或无权限读取的来源 · ${ref}` }))]

  const changePoint = (pointIndex: number, value: string) => {
    const next = [...keyPoints]
    next[pointIndex] = value
    onChange({ ...page, key_points: next })
  }

  return (
    <li
      {...dragProps}
      className={cn(
        'group relative rounded-2xl border bg-surface px-4 py-4 transition-all',
        dragOver ? 'border-accent' : 'border-line hover:shadow-card',
        dragging && 'opacity-50',
      )}
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 flex items-center gap-1 text-ink-muted">
          <GripVertical className="size-4 cursor-grab opacity-0 transition-opacity group-hover:opacity-100" />
          <span className="w-5 text-[13px] font-semibold tabular-nums">{index + 1}</span>
        </span>

        <div className="min-w-0 flex-1">
          <input
            aria-label={`第 ${index + 1} 页标题`}
            value={page.title}
            maxLength={100}
            onChange={(event) => onChange({ ...page, title: event.target.value })}
            className="w-full rounded-lg bg-transparent px-1.5 py-1 text-[15px] font-semibold tracking-tight transition-colors hover:bg-surface-soft focus:bg-surface-soft focus:outline-none"
          />
          <textarea
            aria-label={`第 ${index + 1} 页目标`}
            value={page.objective}
            rows={2}
            maxLength={300}
            placeholder="这一页要让人明白什么"
            onChange={(event) => onChange({ ...page, objective: event.target.value })}
            className="mt-1 w-full resize-none rounded-lg bg-transparent px-1.5 py-1 text-[13px] leading-relaxed text-ink-muted transition-colors hover:bg-surface-soft focus:bg-surface-soft focus:text-ink-soft focus:outline-none"
          />

          <ul className="mt-1.5 flex flex-col gap-0.5">
            {keyPoints.map((point, pointIndex) => (
              <li key={pointIndex} className="flex items-center gap-2">
                <span className="size-1.5 shrink-0 rounded-full bg-accent/70" />
                <input
                  aria-label={`第 ${index + 1} 页要点 ${pointIndex + 1}`}
                  value={point}
                  maxLength={200}
                  onChange={(event) => changePoint(pointIndex, event.target.value)}
                  className="min-w-0 flex-1 rounded-lg bg-transparent px-1.5 py-1 text-[13px] text-ink-soft transition-colors hover:bg-surface-soft focus:bg-surface-soft focus:outline-none"
                />
                {keyPoints.length > MIN_KEY_POINTS && (
                  <button
                    type="button"
                    aria-label={`删除第 ${index + 1} 页要点 ${pointIndex + 1}`}
                    onClick={() =>
                      onChange({
                        ...page,
                        key_points: keyPoints.filter((_, i) => i !== pointIndex),
                      })
                    }
                    className="grid size-5 shrink-0 place-items-center rounded-md text-ink-muted/70 transition-all hover:bg-surface-soft hover:text-negative"
                  >
                    <X className="size-3" />
                  </button>
                )}
              </li>
            ))}
          </ul>

          {keyPoints.length < MAX_KEY_POINTS && (
            <button
              type="button"
              onClick={() => onChange({ ...page, key_points: [...keyPoints, ''] })}
              className="mt-1 ml-3.5 text-xs text-ink-muted transition-colors hover:text-accent"
            >
              + 要点
            </button>
          )}

          <details className="mt-3 rounded-xl border border-line bg-surface-soft/50 px-3 py-2 text-xs">
            <summary className="cursor-pointer font-medium text-ink-soft">来源、页型与配图</summary>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <label className="flex flex-col gap-1 text-ink-muted">页面角色
                <select aria-label={`第 ${index + 1} 页角色`} value={page.page_role} onChange={(event) => onChange({ ...page, page_role: event.target.value as OutlinePage['page_role'] })} className="rounded-lg border border-line bg-surface px-2 py-1.5 text-ink-soft">
                  {Object.entries(PAGE_ROLE_LABEL).map(([role, label]) => <option key={role} value={role}>{label}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-ink-muted">建议版式
                <select aria-label={`第 ${index + 1} 页布局`} value={page.layout_id} onChange={(event) => onChange({ ...page, layout_id: event.target.value })} className="rounded-lg border border-line bg-surface px-2 py-1.5 text-ink-soft">
                  {!layouts.has(page.layout_id) && <option value={page.layout_id}>未知版式 · {page.layout_id}</option>}
                  {[...layouts.values()].map((layout) => <option key={layout.id} value={layout.id}>{layout.name}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-ink-muted sm:col-span-2">配图意图（可选，最多120字）
                <textarea aria-label={`第 ${index + 1} 页配图意图`} value={page.visual ?? ''} maxLength={120} rows={2} placeholder="用一句话描述配图，留空表示不配图" onChange={(event) => onChange({ ...page, visual: event.target.value || null })} className="resize-y rounded-lg border border-line bg-surface px-2 py-1.5 text-ink-soft" />
              </label>
            </div>
            <fieldset className="mt-3 flex flex-col gap-1.5">
              <legend className="mb-1 text-ink-muted">引用章节 · {selectedRefs.length} / 10</legend>
              {refs.length === 0 && <p className="text-ink-muted">暂无可引用章节，可在「材料与参数」补充材料。</p>}
              {refs.map((choice) => <label key={choice.ref} className="flex items-start gap-2 break-all text-ink-soft"><input type="checkbox" aria-label={`第 ${index + 1} 页引用 ${choice.label}`} value={choice.ref} checked={selectedRefs.includes(choice.ref)} disabled={!selectedRefs.includes(choice.ref) && selectedRefs.length >= 10} onChange={(event) => onChange({ ...page, source_refs: event.target.checked ? [...selectedRefs, choice.ref] : selectedRefs.filter((ref) => ref !== choice.ref) })} className="mt-0.5 accent-accent" /><span>{choice.label}</span></label>)}
            </fieldset>
          </details>
        </div>

        {canRemove && (
          <button
            type="button"
            aria-label={`删除第 ${index + 1} 页`}
            onClick={onRemove}
            className="grid size-7 shrink-0 place-items-center rounded-lg text-ink-muted/70 transition-all hover:bg-negative/8 hover:text-negative"
          >
            <Trash2 className="size-3.5" />
          </button>
        )}
      </div>
    </li>
  )
}

/** 大纲改动自动落库；同一时刻只允许一个保存在飞，避免 revision 撞车 */
function useAutosave({
  revision,
  pages,
  pageCount,
  dirty,
  draftingPoint,
  enabled,
  save,
  saving,
}: {
  revision: number
  pages: OutlinePage[]
  pageCount: number
  dirty: boolean
  draftingPoint: boolean
  enabled: boolean
  save: (input: { revision: number; pages: OutlinePage[] }) => void | Promise<void>
  saving: boolean
}): string {
  const savedOnceRef = useRef(false)
  const saveRef = useRef(save)
  saveRef.current = save

  useEffect(() => {
    if (!dirty || !enabled || saving) return
    const timer = window.setTimeout(() => {
      savedOnceRef.current = true
      void saveRef.current({ revision, pages: normalizePages(pages) })
    }, 900)
    return () => window.clearTimeout(timer)
  }, [dirty, enabled, saving, pages, revision, pageCount])

  if (saving) return '保存中…'
  if (dirty && draftingPoint) return '编辑中…'
  if (dirty) return '待保存'
  return savedOnceRef.current ? '已保存' : ''
}

function Shell({
  title,
  meta,
  actions,
  children,
}: {
  title: string
  meta?: ReactNode
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="flex min-h-screen flex-col">
      <WorkbenchHeader title={title} meta={meta}>
        {actions}
      </WorkbenchHeader>
      <div className="bg-aurora flex-1">{children}</div>
    </div>
  )
}

function CenterCard({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto max-w-lg px-6 py-24">
      <div className="rounded-3xl border border-line bg-surface px-8 py-10 text-center shadow-card">
        {children}
      </div>
    </div>
  )
}

/** 新页给可用的占位内容而不是空白：空标题、空目标在服务端是非法的 */
function blankPage(index: number): OutlinePage {
  return {
    id: crypto.randomUUID(),
    title: `第 ${index} 页`,
    objective: '这一页要说明的结论',
    key_points: ['要点一', '要点二'],
    source_refs: [],
    layout_id: 'bullets',
    page_role: 'content',
  }
}

/** 去掉空白要点再落库，避免生成时出现空条目 */
function normalizePages(pages: OutlinePage[]): OutlinePage[] {
  return pages.map((page) => ({
    ...page,
    title: page.title.trim(),
    objective: page.objective.trim(),
    key_points: page.key_points.map((point) => point.trim()).filter((point) => point.length > 0),
  }))
}

function pageIncomplete(page: OutlinePage): boolean {
  const points = page.key_points.filter((point) => point.trim().length > 0)
  return page.title.trim().length === 0 || page.objective.trim().length === 0 || points.length < MIN_KEY_POINTS || !layouts.has(page.layout_id)
}
