import { useQueryClient } from '@tanstack/react-query'
import { Check, RotateCcw } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { ApiError, newCommandKey } from '@/api/client'
import { Button } from '@/components/ui/Button'
import { usePermission } from '@/features/auth/store'
import { tokenStore } from '@/features/auth/token'
import { readProject, useUpdateProjectTheme } from '@/features/projects/api'
import { SamplePanel } from '@/features/design/SamplePanel'
import { registerThemeSaveHandlers } from '@/features/deck/slideSaveBridge'
import type { ProjectDetail } from '@/features/projects/types'
import { errorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import { themeList } from '@/render/design'
import { ThemeCover } from '@/render/ThemeCover'
import {
  BULLET_MARKERS,
  FONT_PRESETS,
  matchFontPresetId,
  PALETTE_KEYS,
  type PaletteKey,
  resolveTheme,
  SIZE_KEYS,
  type SizeKey,
  type ThemeOverrides,
} from '@/render/themeOverrides'

const DEBOUNCE_MS = 320

function asOverrides(raw: ProjectDetail['theme_overrides']): ThemeOverrides {
  if (!raw || typeof raw !== 'object') return {}
  return raw as ThemeOverrides
}

/**
 * 主题微调：换预设 + 颜色/字体/字号/圆角。
 * 本地先合并出预览，防抖后写入 theme_overrides。
 */
export function ThemePanel({
  project,
  disabled,
}: {
  project: ProjectDetail
  disabled?: boolean
}) {
  const titleId = useId()
  const queryClient = useQueryClient()
  const update = useUpdateProjectTheme(project.id)
  const [draft, setDraft] = useState<ThemeOverrides>(() => asOverrides(project.theme_overrides))
  const [themeId, setThemeId] = useState(project.theme_id)
  const [sampleOpen, setSampleOpen] = useState(false)
  const canSample = usePermission('aippt:design:query')
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<unknown>(null)
  const timerRef = useRef<number | null>(null)
  type Intent = { projectId: string; token: string | null; theme_id: string; overrides: ThemeOverrides; version: number; theme_revision?: number; key?: string }
  const nextRef = useRef<Intent | null>(null)
  const queueRef = useRef<Intent[]>([])
  const running = useRef(false)
  const pumpRef = useRef<() => Promise<void>>(async () => {})
  const paused = useRef(false)
  const dirtyRef = useRef(false)
  const versionRef = useRef(0)
  const alive = useRef(true)
  const waiters = useRef<Array<{ resolve: () => void; reject: (error: unknown) => void }>>([])
  const preview = useRef({ themeId, draft })
  preview.current = { themeId, draft }
  const pidRef = useRef(project.id)
  pidRef.current = project.id
  const detailKey = ['projects', project.id] as const

  const paintProject = (nextThemeId: string, nextOverrides: ThemeOverrides) => {
    queryClient.setQueryData<ProjectDetail>(detailKey, (current) =>
      current
        ? { ...current, theme_id: nextThemeId, theme_overrides: nextOverrides }
        : current,
    )
  }

  useEffect(() => {
    if (dirtyRef.current) return
    setThemeId(project.theme_id)
    setDraft(asOverrides(project.theme_overrides))
  }, [project.id, project.theme_id, project.theme_overrides, project.theme_revision])

  useEffect(
    () => { alive.current = true; return () => {
      alive.current = false
      if (timerRef.current != null) window.clearTimeout(timerRef.current)
      for (const waiter of waiters.current.splice(0)) waiter.reject(new DOMException('主题面板已关闭', 'AbortError'))
    } },
    [],
  )

  const resolved = resolveTheme(themeId, draft)
  const fontPresetId = matchFontPresetId(draft.fonts) ?? matchFontPresetId(resolved.fonts)
  const busy = disabled || paused.current

  const pump = async () => {
    if (running.current || paused.current || !alive.current) return
    running.current = true; setSaving(true)
    while (queueRef.current.length) {
      const intent = queueRef.current[0]!
      if (intent.projectId !== pidRef.current || intent.token !== tokenStore.get()) { queueRef.current.shift(); continue }
      const formal = queryClient.getQueryData<ProjectDetail>(['projects', intent.projectId])
      intent.theme_revision ??= formal?.theme_revision ?? project.theme_revision
      intent.key ??= newCommandKey()
      try {
        const saved = await update.mutateAsync({ theme_id: intent.theme_id, overrides: intent.overrides, theme_revision: intent.theme_revision, key: intent.key })
        if (!alive.current || intent.projectId !== pidRef.current || intent.token !== tokenStore.get()) break
        queueRef.current.shift(); setSaveError(null)
        if (intent.version === versionRef.current && !queueRef.current.length && !nextRef.current) { dirtyRef.current = false; setThemeId(saved.theme_id); setDraft(asOverrides(saved.theme_overrides)) }
        else paintProject(preview.current.themeId, preview.current.draft)
        // flush 之后用户仍可调整预览；等待者存在时将新待发意图接入同一串行队列。
        if (waiters.current.length && nextRef.current) {
          if (timerRef.current != null) { window.clearTimeout(timerRef.current); timerRef.current = null }
          queueRef.current.push(nextRef.current); nextRef.current = null
        }
      } catch (cause) {
        if (!alive.current || intent.projectId !== pidRef.current || intent.token !== tokenStore.get()) break
        paused.current = true; setSaveError(cause)
        paintProject(preview.current.themeId, preview.current.draft)
        for (const waiter of waiters.current.splice(0)) waiter.reject(cause)
        break
      }
    }
    running.current = false
    if (alive.current) setSaving(false)
    if (!paused.current && !queueRef.current.length && !nextRef.current) for (const waiter of waiters.current.splice(0)) waiter.resolve()
    else if (!paused.current && queueRef.current.length && alive.current) void pumpRef.current()
  }
  pumpRef.current = pump
  const promote = () => {
    if (timerRef.current != null) { window.clearTimeout(timerRef.current); timerRef.current = null }
    if (nextRef.current) { queueRef.current.push(nextRef.current); nextRef.current = null }
    void pump()
  }
  const flush = () => {
    promote()
    if (paused.current) return Promise.reject(saveError ?? new Error('请先处理主题保存错误'))
    if (!running.current && !queueRef.current.length) return Promise.resolve()
    return new Promise<void>((resolve, reject) => waiters.current.push({ resolve, reject }))
  }
  const flushRef = useRef(flush); flushRef.current = flush
  useEffect(() => registerThemeSaveHandlers(project.id, { flush: () => flushRef.current(), pending: () => dirtyRef.current || running.current || paused.current }), [project.id])
  useEffect(() => {
    dirtyRef.current = false; queueRef.current = []; nextRef.current = null; paused.current = false; versionRef.current++
    for (const waiter of waiters.current.splice(0)) waiter.reject(new DOMException('项目已切换', 'AbortError'))
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    setThemeId(project.theme_id); setDraft(asOverrides(project.theme_overrides)); setSaveError(null); setSampleOpen(false)
  }, [project.id])

  const enqueue = (id: string, next: ThemeOverrides, immediate = false) => {
    dirtyRef.current = true
    nextRef.current = { projectId: project.id, token: tokenStore.get(), theme_id: id, overrides: structuredClone(next), version: ++versionRef.current }
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    if (immediate) promote()
    else timerRef.current = window.setTimeout(promote, DEBOUNCE_MS)
  }

  const persistOverrides = (next: ThemeOverrides) => {
    enqueue(themeId, next)
  }

  const patchDraft = (recipe: (current: ThemeOverrides) => ThemeOverrides) => {
    const next = recipe(preview.current.draft)
    preview.current = { themeId, draft: next }
    setDraft(next); paintProject(themeId, next); persistOverrides(next)
  }

  const selectPreset = (id: string) => {
    if (busy || id === themeId) return
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    setThemeId(id)
    setDraft({})
    preview.current = { themeId: id, draft: {} }
    paintProject(id, {})
    enqueue(id, {}, true)
  }

  const resetOverrides = () => {
    if (busy) return
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    setDraft({})
    preview.current = { themeId, draft: {} }
    paintProject(themeId, {})
    enqueue(themeId, {}, true)
  }

  const setPalette = (key: PaletteKey, value: string) => {
    patchDraft((current) => ({
      ...current,
      palette: { ...current.palette, [key]: value.toUpperCase() },
    }))
  }

  const setSize = (key: SizeKey, sizePt: number) => {
    patchDraft((current) => ({
      ...current,
      text_styles: {
        ...current.text_styles,
        [key]: { size_pt: sizePt },
      },
    }))
  }

  const setFontPreset = (presetId: string) => {
    const preset = FONT_PRESETS.find((item) => item.id === presetId)
    if (!preset) return
    patchDraft((current) => ({
      ...current,
      fonts: structuredClone(preset.fonts),
    }))
  }

  const hasOverrides = Object.keys(draft).length > 0

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-5">
      <div>
        <h3 id={titleId} className="text-sm font-semibold tracking-tight">
          主题与样式
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-ink-muted">
          先选一套预设，再微调颜色、字体与字号；导出 PPTX 会使用相同样式
        </p>
      </div>

      <div className="flex flex-col gap-2">
        <p className="text-xs font-medium text-ink-soft">预设</p>
        <div className="flex flex-col gap-2">
          {themeList.map((theme) => (
            <button
              key={theme.id}
              type="button"
              disabled={busy}
              aria-pressed={theme.id === themeId}
              onClick={() => selectPreset(theme.id)}
              className={cn(
                'overflow-hidden rounded-xl border text-left transition-colors',
                theme.id === themeId
                  ? 'border-accent ring-1 ring-accent/30'
                  : 'border-line hover:border-line-strong',
                'disabled:opacity-50',
              )}
            >
              <ThemeCover theme={theme} title={project.title} />
              <div className="flex items-center gap-2 px-3 py-2">
                <span className="flex-1 truncate text-[13px] font-medium">{theme.name}</span>
                {theme.id === themeId && <Check className="size-3.5 shrink-0 text-accent" />}
              </div>
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-2.5">
        <p className="text-xs font-medium text-ink-soft">颜色</p>
        <ul className="flex flex-col gap-2">
          {PALETTE_KEYS.map(({ key, label }) => (
            <li key={key} className="flex items-center gap-3">
              <label className="w-16 shrink-0 text-[12px] text-ink-muted" htmlFor={`color-${key}`}>
                {label}
              </label>
              <input
                id={`color-${key}`}
                type="color"
                disabled={busy}
                value={resolved.palette[key]}
                onChange={(event) => setPalette(key, event.target.value)}
                className="h-8 w-10 cursor-pointer rounded border border-line bg-surface p-0.5 disabled:opacity-40"
              />
              <span className="font-mono text-[11px] text-ink-muted tabular-nums">
                {resolved.palette[key]}
              </span>
            </li>
          ))}
        </ul>
      </div>

      <div className="flex flex-col gap-2">
        <label htmlFor="font-preset" className="text-xs font-medium text-ink-soft">
          字体
        </label>
        <select
          id="font-preset"
          disabled={busy}
          value={fontPresetId ?? FONT_PRESETS[0]?.id}
          onChange={(event) => setFontPreset(event.target.value)}
          className={cn(
            'h-9 rounded-xl border border-line bg-surface px-3 text-[13px] text-ink',
            'focus:border-accent focus:outline-none disabled:opacity-40',
          )}
        >
          {FONT_PRESETS.map((preset) => (
            <option key={preset.id} value={preset.id}>
              {preset.label}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-3">
        <p className="text-xs font-medium text-ink-soft">字号</p>
        {SIZE_KEYS.map(({ key, label }) => {
          const value = Math.round(resolved.text_styles[key]?.size_pt ?? 14)
          return (
            <label key={key} className="flex flex-col gap-1.5">
              <span className="flex items-center justify-between text-[12px] text-ink-muted">
                <span>{label}</span>
                <span className="tabular-nums">{value} pt</span>
              </span>
              <input
                type="range"
                min={8}
                max={72}
                step={1}
                disabled={busy}
                value={value}
                onChange={(event) => setSize(key, Number(event.target.value))}
                className="w-full accent-[var(--color-accent)] disabled:opacity-40"
              />
            </label>
          )
        })}
      </div>

      <div className="flex flex-col gap-3">
        <p className="text-xs font-medium text-ink-soft">形状</p>
        <label className="flex flex-col gap-1.5">
          <span className="flex items-center justify-between text-[12px] text-ink-muted">
            <span>圆角</span>
            <span className="tabular-nums">{Math.round(resolved.shape.radius_pt)} pt</span>
          </span>
          <input
            type="range"
            min={0}
            max={24}
            step={1}
            disabled={busy}
            value={Math.round(resolved.shape.radius_pt)}
            onChange={(event) =>
              patchDraft((current) => ({
                ...current,
                shape: { ...current.shape, radius_pt: Number(event.target.value) },
              }))
            }
            className="w-full accent-[var(--color-accent)] disabled:opacity-40"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[12px] text-ink-muted">项目符号</span>
          <select
            disabled={busy}
            value={resolved.shape.bullet_marker}
            onChange={(event) =>
              patchDraft((current) => ({
                ...current,
                shape: {
                  ...current.shape,
                  bullet_marker: event.target.value as 'rule' | 'dot' | 'index',
                },
              }))
            }
            className={cn(
              'h-9 rounded-xl border border-line bg-surface px-3 text-[13px] text-ink',
              'focus:border-accent focus:outline-none disabled:opacity-40',
            )}
          >
            {BULLET_MARKERS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="ghost"
          size="sm"
          disabled={busy || !hasOverrides}
          onClick={resetOverrides}
        >
          <RotateCcw className="size-3.5" />
          重置微调
        </Button>
        {saving && <span className="text-xs text-ink-muted">保存中…</span>}
        {canSample && <Button variant="ghost" size="sm" onClick={() => setSampleOpen(true)}>查看设计样例</Button>}
      </div>

      {saveError != null && (
        <p role="alert" className="rounded-xl bg-negative/8 px-3 py-2 text-xs text-negative">
          {errorMessage(saveError instanceof Error ? saveError : new Error('主题保存失败'))}
          <button type="button" disabled={saving} className="ml-2 underline" onClick={() => void (async () => {
            try {
            const intent = queueRef.current[0]; if (!intent) return
            if (saveError instanceof ApiError && saveError.status === 409) {
              const latest = await readProject(intent.projectId)
              if (!alive.current || pidRef.current !== intent.projectId || tokenStore.get() !== intent.token) return
              if (!window.confirm(`主题正式版本已变为 ${latest.theme_revision}。核对最新主题 ${latest.theme_id} 后重新应用当前预览？`)) return
              intent.theme_revision = latest.theme_revision; intent.key = newCommandKey()
            }
            paused.current = false; setSaveError(null); void pump()
            } catch (cause) { if (alive.current) setSaveError(cause) }
          })()}> {saveError instanceof ApiError && saveError.status === 409 ? '核对最新主题后重试' : '重试原操作'}</button>
        </p>
      )}
      {sampleOpen && <SamplePanel themeId={themeId} onClose={() => setSampleOpen(false)} />}
    </section>
  )
}
