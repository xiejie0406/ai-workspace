import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request, requestPrepared, requestBinary, newCommandKey, ApiError } from '@/api/client'
import { useEffect, useRef, useState } from 'react'
import { waitTask, type Task } from '@/api/task'
import type { ProjectDetail } from '@/features/projects/types'
import { readProject } from '@/features/projects/api'
import { tokenStore } from '@/features/auth/token'
import { getLayout } from '@/render/design'
import { replaceSlideInDeck } from '@/features/deck/cache'
import { flushProjectSlides, getSlideSaveHandlers, runSlideMutation } from '@/features/deck/slideSaveBridge'
import type { FlexBlockType } from '@/features/deck/flexTree'
import type {
  AiEditProposal,
  BlockUpdateBody,
  Deck,
  DeckGenerateAccepted,
  DeckPageResult,
  DeckSlide,
  ExportCheckReport,
  LayoutCandidate,
  RelayoutProposal,
} from '@/features/deck/types'
import { outlineKey } from '@/features/outline/api'
import { filenameFromDisposition, saveBlob } from '@/lib/download'
import type { BlockStyle } from '@/render/blockStyle'
import { clearRectOverrides, type FlexContainer } from '@/render/flexLayout'

export const deckKey = (projectId: string) => ['projects', projectId, 'deck'] as const
async function currentDeck(projectId: string, _queryClient: QueryClient) { return request<Deck>(`/projects/${projectId}/deck`) }

const projectKey = (projectId: string) => ['projects', projectId] as const
const slideLayoutsKey = (projectId: string, slideId: string) =>
  ['projects', projectId, 'deck', 'slides', slideId, 'layouts'] as const
export const deckQualityKey = (projectId: string) =>
  ['projects', projectId, 'deck', 'quality'] as const

/** 结构命令的幂等回执可能早于当前编辑，保留较新页及保存队列中的草稿。 */
function mergePageCommandResult(current: Deck | undefined, result: Deck): Deck {
  if (!current) return result
  if (current.deck_revision > result.deck_revision) return current
  const cached = new Map(current.slides.map((slide) => [slide.id, slide]))
  return {
    ...result,
    theme_id: current.theme_revision >= result.theme_revision ? current.theme_id : result.theme_id,
    theme_revision: Math.max(current.theme_revision, result.theme_revision),
    slides: result.slides.map((slide) => {
      const previous = cached.get(slide.id)
      if (!previous || (previous.revision <= slide.revision && !getSlideSaveHandlers(slide.id)?.pending?.())) return slide
      return { ...slide, ...previous, position: slide.position, revision: Math.max(previous.revision, slide.revision) }
    }),
  }
}

/**
 * 单页更新后写回 deck 缓存。
 *
 * 用 setQueryData 而不是 invalidate，是为了不冲掉正在编辑的 DOM；
 * 但质量报告依赖页面内容，必须跟着失效，否则用户改完问题仍看到旧报告。
 */
export function commitSlideToCache(
  queryClient: QueryClient,
  projectId: string,
  slide: DeckSlide,
) {
  queryClient.setQueryData<Deck>(deckKey(projectId), (current) =>
    replaceSlideInDeck(current, slide),
  )
  void queryClient.invalidateQueries({ queryKey: deckQualityKey(projectId) })
  void request<ProjectDetail>(`/projects/${projectId}`).then((project) => {
    queryClient.setQueryData<ProjectDetail>(['projects', projectId], (current) => current && project.input_revision >= current.input_revision && project.deck_revision >= current.deck_revision && project.theme_revision >= current.theme_revision ? { ...current, ...project } : current)
    queryClient.setQueryData<Deck>(deckKey(projectId), (current) => current ? { ...current, deck_revision: Math.max(current.deck_revision, project.deck_revision), theme_revision: Math.max(current.theme_revision, project.theme_revision) } : current)
  }).catch(() => {})
}

export function useDeck(projectId: string, enabled = true) {
  return useQuery({
    queryKey: deckKey(projectId),
    queryFn: () => request<Deck>(`/projects/${projectId}/deck`),
    enabled,
  })
}

/** 写操作共用：路径与请求体由调用方提供，成功后刷新页面列表与项目状态 */
function useDeckMutation<TVariables>(
  projectId: string,
  toRequest: (variables: TVariables) => { path: string; body?: unknown },
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: TVariables) => {
      const { path, body } = toRequest(variables)
      return requestPrepared<DeckGenerateAccepted | null>(path, 'POST', JSON.stringify(variables) ?? 'null', async () => {
        await flushProjectSlides(projectId)
        const deck = await currentDeck(projectId, queryClient)
        return JSON.stringify({ ...(body as object ?? {}), deck_revision: deck.deck_revision, ...(typeof variables === 'string' ? { revision: deck.slides.find((slide) => slide.id === variables)?.revision } : {}), ...((body as { regenerate_all?: boolean })?.regenerate_all ? { overwrite_acknowledged: true } : {}) })
      })
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: deckKey(projectId) })
      void queryClient.invalidateQueries({ queryKey: projectKey(projectId) })
    },
  })
}

export function useGenerateDeck(projectId: string) {
  return useDeckMutation<{ regenerateAll: boolean }>(projectId, ({ regenerateAll }) => ({
    path: `/projects/${projectId}/deck/generate`,
    body: { regenerate_all: regenerateAll },
  }))
}

export function useRetrySlide(projectId: string) {
  return useDeckMutation<string>(projectId, (slideId) => ({
    path: `/projects/${projectId}/deck/slides/${slideId}/retry`,
  }))
}

export function useCancelDeck(projectId: string) {
  return useDeckMutation<void>(projectId, () => ({
    path: `/projects/${projectId}/deck/cancel`,
  }))
}

interface ReplaceImageInput {
  slideId: string
  blockId: string
  revision: number
  file: File
}

export function useReplaceSlideImage(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ slideId, blockId, file }: ReplaceImageInput) => runSlideMutation(projectId, slideId, (current) => {
      const form = new FormData(); form.append('file', file); form.append('revision', String(current.revision))
      return request<DeckSlide>(`/projects/${projectId}/deck/slides/${slideId}/blocks/${blockId}/image`, { method: 'PUT', body: form })
    }),
    onSuccess: (slide) => commitSlideToCache(queryClient, projectId, slide),
  })
}

export function updateSlideBlock(
  projectId: string,
  slideId: string,
  blockId: string,
  body: BlockUpdateBody & { revision: number },
) {
  return request<DeckSlide>(
    `/projects/${projectId}/deck/slides/${slideId}/blocks/${blockId}`,
    { method: 'PATCH', body: JSON.stringify(body) },
  )
}

export function updateSlideBlockStyle(
  projectId: string,
  slideId: string,
  blockId: string,
  body: { revision: number; style: BlockStyle | null },
) {
  return request<DeckSlide>(
    `/projects/${projectId}/deck/slides/${slideId}/blocks/${blockId}/style`,
    { method: 'PATCH', body: JSON.stringify(body) },
  )
}

export function useReorderSlides(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (slideIds: string[]) => requestPrepared<Deck>(`/projects/${projectId}/deck/slides/order`, 'PUT', JSON.stringify(slideIds), async () => { await flushProjectSlides(projectId); const deck = await currentDeck(projectId, queryClient); return JSON.stringify({ slide_ids: slideIds, deck_revision: deck.deck_revision }) }),
    onMutate: async (slideIds) => {
      await queryClient.cancelQueries({ queryKey: deckKey(projectId) })
      const previous = queryClient.getQueryData<Deck>(deckKey(projectId))
      if (previous) {
        const byId = new Map(previous.slides.map((slide) => [slide.id, slide]))
        const slides = slideIds.flatMap((id, index) => {
          const slide = byId.get(id)
          return slide ? [{ ...slide, position: index + 1 }] : []
        })
        queryClient.setQueryData<Deck>(deckKey(projectId), { ...previous, slides })
      }
      return { previous }
    },
    onError: (_error, _slideIds, context) => {
      if (context?.previous) {
        // 只撤销本次乐观页序，不用等待开始时的整稿快照覆盖之后的编辑。
        queryClient.setQueryData<Deck>(deckKey(projectId), (current) => {
          if (!current) return context.previous
          const byId = new Map(current.slides.map((slide) => [slide.id, slide]))
          const previousIds = new Set(context.previous.slides.map((slide) => slide.id))
          const ordered = context.previous.slides.flatMap((slide) => {
            const latest = byId.get(slide.id)
            return latest ? [latest] : []
          }).concat(current.slides.filter((slide) => !previousIds.has(slide.id)))
          return { ...current, slides: ordered.map((slide, index) => ({ ...slide, position: index + 1 })) }
        })
      }
    },
    onSuccess: (result) => {
      queryClient.setQueryData<Deck>(deckKey(projectId), (current) => mergePageCommandResult(current, result))
      // 页序参与页面重复判定，排序后报告同样要重算
      void queryClient.invalidateQueries({ queryKey: deckQualityKey(projectId) })
    },
  })
}

/**
 * 整页增删复制：响应带整份 deck，合并时保护回执之后的编辑。
 *
 * 后端同一事务里还改了 page_count 与大纲页列表，所以项目详情与大纲也要失效。
 * 项目的 key 是 deck key 的前缀，必须 exact，否则会顺带把刚写进去的 deck 冲掉。
 */
function useDeckPageMutation<TVariables>(
  projectId: string,
  toRequest: (variables: TVariables) => {
    path: string
    method: 'POST' | 'DELETE'
    body?: unknown
  },
) {
  const queryClient = useQueryClient()
  const selection = useRef<{ path: string; intent: string; token: string | null; before: Deck } | null>(null)
  return useMutation({
    mutationFn: async (variables: TVariables) => {
      const { path, method, body } = toRequest(variables)
      const intent = JSON.stringify(variables) ?? 'null'
      const token = tokenStore.get()
      const deck = await requestPrepared<Deck>(path, method, intent, async () => {
        await flushProjectSlides(projectId)
        const before = await currentDeck(projectId, queryClient)
        selection.current = { path, intent, token, before }
        return JSON.stringify({ ...(body as object ?? {}), deck_revision: before.deck_revision })
      })
      const baseline = selection.current
      const before = baseline?.path === path && baseline.intent === intent && baseline.token === token ? baseline.before : deck
      const selected = method === 'DELETE' ? deck.slides[Math.min(Math.max(0, before.slides.findIndex((slide) => slide.id === variables)), deck.slides.length - 1)] : deck.slides.find((slide) => !before.slides.some((old) => old.id === slide.id))
      return { deck, slide_id: selected?.id ?? null } as DeckPageResult
    },
    onSuccess: (result) => {
      queryClient.setQueryData<Deck>(deckKey(projectId), (current) => mergePageCommandResult(current, result.deck))
      void queryClient.invalidateQueries({ queryKey: projectKey(projectId), exact: true })
      void queryClient.invalidateQueries({ queryKey: outlineKey(projectId) })
      void queryClient.invalidateQueries({ queryKey: deckQualityKey(projectId) })
    },
  })
}

/** afterSlideId 为 null 表示追加到末尾 */
export function useInsertSlide(projectId: string) {
  return useDeckPageMutation<string | null>(projectId, (afterSlideId) => ({
    path: `/projects/${projectId}/deck/slides`,
    method: 'POST',
    body: { after_slide_id: afterSlideId },
  }))
}

export function useDuplicateSlide(projectId: string) {
  return useDeckPageMutation<string>(projectId, (slideId) => ({
    path: `/projects/${projectId}/deck/slides/${slideId}/duplicate`,
    method: 'POST',
  }))
}

export function useDeleteSlide(projectId: string) {
  return useDeckPageMutation<string>(projectId, (slideId) => ({
    path: `/projects/${projectId}/deck/slides/${slideId}`,
    method: 'DELETE',
  }))
}

export function useSlideLayouts(projectId: string, slideId: string, enabled: boolean) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: slideLayoutsKey(projectId, slideId),
    queryFn: async () => { const candidates = await request<LayoutCandidate[]>(`/projects/${projectId}/deck/slides/${slideId}/layouts`); const selected = queryClient.getQueryData<Deck>(deckKey(projectId))?.slides.find((slide) => slide.id === slideId)?.layout_id; return candidates.map((item) => { const id = item.layout_id ?? (item as LayoutCandidate & { id: string }).id; return { ...item, layout_id: id, name: getLayout(id).name, usage: getLayout(id).usage, current: id === selected } }) },
    enabled,
  })
}

export function useSwitchSlideLayout(projectId: string, slideId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { layout_id: string; revision: number }) => runSlideMutation(projectId, slideId, (current) =>
      request<DeckSlide>(`/projects/${projectId}/deck/slides/${slideId}/layout`, {
        method: 'PUT',
        body: JSON.stringify({ ...body, revision: current.revision }),
      })),
    onSuccess: (slide) => {
      commitSlideToCache(queryClient, projectId, slide)
      void queryClient.invalidateQueries({ queryKey: slideLayoutsKey(projectId, slideId) })
    },
  })
}

export function createSlideBlock(
  projectId: string,
  slideId: string,
  body: {
    revision: number
    type: FlexBlockType
    parent_id: string
    index?: number
  },
  key?: string,
) {
  return request<DeckSlide>(`/projects/${projectId}/deck/slides/${slideId}/blocks`, {
    method: 'POST',
    body: JSON.stringify(body), idempotencyKey: key,
  })
}

export function deleteSlideBlock(
  projectId: string,
  slideId: string,
  blockId: string,
  body: { revision: number },
) {
  return request<DeckSlide>(
    `/projects/${projectId}/deck/slides/${slideId}/blocks/${blockId}`,
    { method: 'DELETE', body: JSON.stringify(body) },
  )
}

export function updateFlexLayout(
  projectId: string,
  slideId: string,
  body: { revision: number; layout_tree: FlexContainer },
  key?: string,
) {
  return request<DeckSlide>(
    `/projects/${projectId}/deck/slides/${slideId}/flex-layout`,
    { method: 'PUT', body: JSON.stringify({ ...body, layout_tree: clearRectOverrides(body.layout_tree) }), idempotencyKey: key },
  )
}

export function updateFlexState(
  projectId: string,
  slideId: string,
  body: {
    revision: number
    blocks: DeckSlide['blocks']
    layout_tree: FlexContainer | null
    layout_mode?: 'fixed' | 'flex'
    layout_id?: string
  },
) {
  return request<DeckSlide>(
    `/projects/${projectId}/deck/slides/${slideId}/flex-state`,
    { method: 'PUT', body: JSON.stringify(body) },
  )
}

export function unlockSlideFlex(
  projectId: string,
  slideId: string,
  body: { revision: number },
) {
  return request<DeckSlide>(
    `/projects/${projectId}/deck/slides/${slideId}/unlock-flex`,
    { method: 'POST', body: JSON.stringify(body) },
  )
}

export function useUnlockSlideFlex(projectId: string, slideId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (_body: { revision: number }) => runSlideMutation(projectId, slideId, (current) => unlockSlideFlex(projectId, slideId, { revision: current.revision })),
    onSuccess: (slide) => {
      commitSlideToCache(queryClient, projectId, slide)
      void queryClient.invalidateQueries({ queryKey: slideLayoutsKey(projectId, slideId) })
    },
  })
}

async function resolveProposal<T>(projectId: string, slideId: string, kind: string, body: unknown, signal: AbortSignal): Promise<T> {
  const handlers = getSlideSaveHandlers(slideId)
  if (!handlers || handlers.projectId !== projectId) throw new Error('页面保存队列尚未就绪')
  await handlers.flush()
  signal.throwIfAborted()
  if (getSlideSaveHandlers(slideId) !== handlers) throw new DOMException('页面已切换', 'AbortError')
  const deck = await request<Deck>(`/projects/${projectId}/deck`, { signal })
  if (getSlideSaveHandlers(slideId) !== handlers) throw new DOMException('页面已切换', 'AbortError')
  const slide = deck.slides.find((item) => item.id === slideId)
  if (!slide) throw new ApiError(404, '页面不存在')
  signal.throwIfAborted()
  const accepted = await request<Task>(`/projects/${projectId}/deck/slides/${slideId}/${kind}`, { method: 'POST', body: JSON.stringify({ ...(body as object), revision: slide.revision }), signal })
  const task = await waitTask(accepted, signal)
  const id = (task.result ?? task.output)?.proposal_id
  if (typeof id !== 'string') throw new ApiError(502, '候选任务未返回提案编号')
  const proposal = await request<Record<string, unknown>>(`/projects/${projectId}/proposals/${id}`, { signal })
  return { ...proposal, revision: proposal.expected_slide_revision ?? proposal.revision, candidates: Array.isArray(proposal.candidates) ? proposal.candidates.map((candidate: Record<string, unknown>) => ({ ...candidate, proposal_id: id })) : undefined } as T
}
export function applyRelayout(projectId: string, slideId: string, body: { revision: number; proposal_id: string; candidate_id: string }) { return request<DeckSlide>(`/projects/${projectId}/deck/slides/${slideId}/relayout/apply`, { method: 'POST', body: JSON.stringify(body) }) }

export function useProposeRelayout(projectId: string, slideId: string) {
  return useProposal<RelayoutProposal, { revision: number }>(projectId, slideId, 'relayout')
}
function useProposal<T, B>(projectId: string, slideId: string, kind: string) {
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [projectId, slideId])
  return useMutation({ mutationFn: (body: B) => { controller.current?.abort(); const abort = new AbortController(); controller.current = abort; return resolveProposal<T>(projectId, slideId, kind, body, abort.signal) } })
}

export function useProposeAiEdit(projectId: string, slideId: string) {
  return useProposal<AiEditProposal, {
      revision: number
      instruction: string
      history?: Array<{ instruction: string; note?: string | null }>
    }>(projectId, slideId, 'ai-edit')
}

export function useApplyAiEdit(projectId: string, slideId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { revision: number; proposal_id: string; op_id: string; side: 'before' | 'after' }) => runSlideMutation(projectId, slideId, (current) =>
      request<DeckSlide>(`/projects/${projectId}/deck/slides/${slideId}/ai-edit/apply`, { method: 'POST', body: JSON.stringify({ ...body, revision: current.revision }) })),
    onSuccess: (slide) => commitSlideToCache(queryClient, projectId, slide),
  })
}

/** 将中心异步质量任务还原为原面板消费的报告，结论仍来自 Worker。 */
export function useDeckQuality(projectId: string, enabled: boolean, deckRevision?: number, themeRevision?: number) {
  return useQuery({
    queryKey: [...deckQualityKey(projectId), deckRevision, themeRevision],
    queryFn: async ({ signal }): Promise<ExportCheckReport> => {
      await flushProjectSlides(projectId)
      let report = await request<Record<string, unknown>>(`/projects/${projectId}/deck/quality`, { signal })
      if (report.jobId || report.job_id) { await waitTask(report as Task, signal); report = await request<Record<string, unknown>>(`/projects/${projectId}/deck/quality`, { signal }) }
      if (typeof report.can_export !== 'boolean' || !Array.isArray(report.issues)) throw new ApiError(502, '质量任务尚未返回有效报告')
      return { ...report, export_allowed: report.can_export, fonts_precise: report.fonts_available === true } as ExportCheckReport
    }, enabled,
  })
}

/** 保留原「导出一次即下载」入口；未知回执重放，明确终态失败后允许新尝试。 */
export function useExportDeck(projectId: string, fallbackName = 'export.pptx') {
  const command = useRef<{ key: string; body: string; accepted?: Task & { id: string } } | null>(null)
  const controller = useRef<AbortController | null>(null)
  const [commandVersion, setCommandVersion] = useState<{ deck_revision: number; theme_revision: number } | null>(null)
  useEffect(() => { setCommandVersion(null); return () => { controller.current?.abort(); command.current = null } }, [projectId])
  const mutation = useMutation({
    mutationFn: async (acknowledgeWarnings: boolean = false) => {
      const token = tokenStore.get(); const abort = new AbortController(); controller.current = abort
      await flushProjectSlides(projectId)
      if (!command.current) {
        const project = await readProject(projectId)
        if (abort.signal.aborted) throw new DOMException('操作已取消', 'AbortError')
        command.current = { key: newCommandKey(), body: JSON.stringify({ deck_revision: project.deck_revision, theme_revision: project.theme_revision, acknowledge_warnings: acknowledgeWarnings }) }
        setCommandVersion({ deck_revision: project.deck_revision, theme_revision: project.theme_revision })
      }
      const own = command.current
      let accepted = own.accepted
      if (!accepted) {
        try { accepted = await request<Task & { id: string }>(`/projects/${projectId}/deck/exports`, { method: 'POST', body: own.body, idempotencyKey: own.key, signal: abort.signal }) }
        catch (cause) { if (cause instanceof ApiError && cause.status < 500 && !abort.signal.aborted) { command.current = null; setCommandVersion(null) }; throw cause }
      }
      own.accepted = accepted
      let taskError: unknown = null
      try { await waitTask(accepted, abort.signal) } catch (error) { taskError = error }
      if (abort.signal.aborted || tokenStore.get() !== token) throw new DOMException('操作已取消', 'AbortError')
      const state = await request<Task & { verify?: Record<string, unknown>; download_url?: string }>(`/exports/${accepted.id}`, { signal: abort.signal })
      if (state.status === 'failed' || state.status === 'cancelled') {
        command.current = null
        setCommandVersion(null)
        if (state.verify?.passed === false) throw new ApiError(422, { ...state.verify, message: '导出回读验证未通过，未返回文件' })
        throw taskError ?? new ApiError(500, state.error ?? '导出任务失败')
      }
      if (taskError) throw taskError
      if (state.status !== 'ready') throw new ApiError(502, '导出文件尚未就绪')
      const response = await requestBinary(`/exports/${accepted.id}/download`, { signal: abort.signal })
      const blob = await response.blob()
      if (abort.signal.aborted || tokenStore.get() !== token) throw new DOMException('操作已取消', 'AbortError')
      saveBlob(blob, filenameFromDisposition(response.headers.get('content-disposition'), fallbackName))
      command.current = null
      setCommandVersion(null)
    },
  })
  return { ...mutation, commandVersion }
}
