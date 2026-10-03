import { useApis, useHost, useHostRuntime } from '@/host/HostContext'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { isTerminal } from '@/api/runs'
import {
  type SessionCreateInput,
  type SessionDetail,
  type SessionPatchInput,
  type ExportInput,
} from '@/api/sessions'
import { errorMessage } from '@/lib/errors'
import { useRun } from '@/hooks/useRun'
import { offersUndo, toolLabel } from '@/lib/tools'
import { useToast } from '@/host/HostContext'

const LIST_KEY = ['sessions']
const detailKey = (id: string) => ['session', id]
const historyKey = (id: string) => ['session', id, 'history']
type DocumentContext = { version: number; assetId: string; signal: AbortSignal; retry: boolean }
type ToolInvocation = { tool: string; params?: Record<string, unknown>; documentContext?: DocumentContext }

export function useSessions() {
  const { sessions: sessionsApi } = useApis()
  return useQuery({ queryKey: LIST_KEY, queryFn: sessionsApi.list })
}

export function useSession(id: string | null) {
  const { sessions: sessionsApi } = useApis()
  return useQuery({
    queryKey: detailKey(id ?? ''),
    queryFn: () => sessionsApi.get(id as string),
    enabled: Boolean(id),
  })
}

export function useSessionHistory(id: string | null) {
  const { sessions: sessionsApi } = useApis()
  return useQuery({
    queryKey: historyKey(id ?? ''),
    queryFn: () => sessionsApi.history(id as string),
    enabled: Boolean(id),
  })
}

function useCacheSession(id: string) {

  const queryClient = useQueryClient()
  return (detail: SessionDetail) => {
    queryClient.setQueryData(detailKey(id), detail)
    void queryClient.invalidateQueries({ queryKey: LIST_KEY })
    void queryClient.invalidateQueries({ queryKey: historyKey(id) })
  }
}

export function useCreateSession() {
  const { sessions: sessionsApi } = useApis()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: SessionCreateInput) => sessionsApi.create(input),
    onSuccess: (detail) => {
      queryClient.setQueryData(detailKey(detail.id), detail)
      void queryClient.invalidateQueries({ queryKey: LIST_KEY })
    },
  })
}

export function usePatchSession(id: string) {
  const { sessions: sessionsApi } = useApis()
  const runtime = useHostRuntime()
  const toast = useToast()
  const queryClient = useQueryClient()
  const cache = useCacheSession(id)
  return useMutation({
    mutationFn: (input: SessionPatchInput) => sessionsApi.patch(id, input),
    onMutate: () => ({ signal: runtime.signal }),
    onSuccess: (detail, _input, context) => {
      if (context && !context.signal.aborted && runtime.active) cache(detail)
    },
    onError: async (error, _input, context) => {
      if (!context || context.signal.aborted || !runtime.active || error.name === 'AbortError') return
      toast(errorMessage(error), 'danger')
      // 409 或丢响应只重读正式文档；用户明确再次确认才可 PATCH。
      await queryClient.invalidateQueries({ queryKey: detailKey(id) })
    },
  })
}

export function useExportPack(id: string) {
  const { sessions: sessionsApi } = useApis()
  const toast = useToast()
  return useMutation({
    mutationFn: (input: string[] | ExportInput) => sessionsApi.export(id, input),
    onError: (error) => toast(errorMessage(error), 'danger'),
  })
}

export function useSessionTools(id: string) {
  const { sessions: sessionsApi } = useApis()
  const host = useHost()
  const runtime = useHostRuntime()
  const toast = useToast()
  const queryClient = useQueryClient()
  const cache = useCacheSession(id)
  const busyRef = useRef(false)
  const [pendingRunId, setPendingRunId] = useState<string | null>(null)
  const { data: session } = useSession(id)
  // 刷新或 TagsView 重建后仍使用 Java 当前在途任务，不能仅依赖本地 mutation。
  const runId = session?.active_run_id ?? pendingRunId
  const live = useRun(runId)

  const invoke = useMutation({
    mutationFn: ({ tool, params, documentContext }: ToolInvocation) =>
      sessionsApi.invoke(id, tool, params, documentContext?.version),
    onSuccess: (body) => {
      cache(body.session)
      if (!isTerminal(body.run.status)) setPendingRunId(body.run.id)
    },
    onError: (error, input) => {
      if (error.name === 'AbortError') return
      if (input.documentContext) return reportDocumentFailure(error, input)
      toast(errorMessage(error), 'danger')
      void queryClient.invalidateQueries({ queryKey: detailKey(id) })
    },
  })

  const historyLock = useRef(false)
  const unlockHistory = () => {
    historyLock.current = false
  }

  const undo = useMutation({
    mutationFn: () => sessionsApi.undo(id),
    onSuccess: cache,
    onError: (error) => toast(errorMessage(error), 'danger'),
    onSettled: unlockHistory,
  })
  const redo = useMutation({
    mutationFn: () => sessionsApi.redo(id),
    onSuccess: cache,
    onError: (error) => toast(errorMessage(error), 'danger'),
    onSettled: unlockHistory,
  })

  const waiting = Boolean(runId && (!live.status || !isTerminal(live.status)))
  const busy = invoke.isPending || undo.isPending || redo.isPending || waiting
  useLayoutEffect(() => { busyRef.current = busy }, [busy])
  const canEdit = host.hasPermission('aipicture:session:edit')
  const toolBusy = busy || !host.hasPermission('aipicture:tool:execute')

  // 提示里的撤销要在完成那一刻才可用，用 ref 拿最新的 mutation，避免把它塞进 effect 依赖
  const undoRef = useRef(() => undo.mutate())
  undoRef.current = () => undo.mutate()
  const pendingTool = invoke.isPending ? invoke.variables?.tool : live.tool ?? invoke.variables?.tool
  // 一个任务只播报一次，后续依赖变化不会再弹提示
  const announced = useRef<string | null>(null)

  useEffect(() => {
    if (!runId || !live.status || !isTerminal(live.status)) return
    if (announced.current === runId) return
    announced.current = runId
    void queryClient.invalidateQueries({ queryKey: detailKey(id) })
    void queryClient.invalidateQueries({ queryKey: historyKey(id) })
    if (live.status === 'failed' || live.status === 'canceled' || live.status === 'external_result_unknown') {
      toast(live.error || '处理失败', 'danger')
      return
    }
    const label = toolLabel(pendingTool)
    toast(
      label ? `${label}完成` : live.stage || '已完成',
      'ok',
      canEdit && offersUndo(pendingTool)
        ? { action: { label: '撤销', run: () => undoRef.current() } }
        : undefined,
    )
  }, [runId, live.status, live.error, live.stage, pendingTool, canEdit, id, queryClient])

  /**
   * 当前在跑的是不是这一次调用。工具层同时只跑一个任务，界面据此只给发起的那个按钮
   * 换进度文案，其余按钮虽然一样禁用，但不会看起来像是也在生成。
   */
  const isRunning = (tool: string, match?: (params: Record<string, unknown>) => boolean) => {
    const pending = invoke.isPending ? invoke.variables : waiting ? { tool: live.tool, params: live.params } : undefined
    if (pending?.tool !== tool) return false
    return match ? match(pending.params ?? {}) : true
  }

  const runHistory = (action: () => void) => {
    if (historyLock.current || busy || !canEdit) return
    historyLock.current = true
    action()
  }

  async function reportDocumentFailure(error: Error, input: ToolInvocation) {
    const context = input.documentContext
    if (!context || context.signal.aborted || !runtime.active) return
    // 回正式快照后再交还画布；失败本身不代表原命令已经写入。
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: detailKey(id) }),
      queryClient.invalidateQueries({ queryKey: historyKey(id) }),
    ])
    if (context.signal.aborted || !runtime.active) return
    let retried = false
    toast(errorMessage(error), 'danger', context.retry ? { action: { label: '重试', run: () => {
      if (retried || context.signal.aborted || !runtime.active || busyRef.current) return
      const current = queryClient.getQueryData<SessionDetail>(detailKey(id))
      if (!current || (current.row_version ?? current.revision) !== context.version || current.current_asset_id !== context.assetId) {
        toast('画布已更新，原参数仍保留；请检查当前图层后重新操作', 'danger')
        return
      }
      retried = true
      // 固定原expectedVersion，后来的GET即使先更新Runtime也不能改写原版本。
      void invokeAsync(input.tool, input.params, { retry: true, expectedVersion: context.version }).catch(() => { /* 反馈由原命令链负责，不自动再次POST。 */ })
    } } } : undefined)
  }

  async function invokeAsync(tool: string, params?: Record<string, unknown>, documentCommand?: { retry?: boolean; expectedVersion?: number }) {
    const signal = runtime.signal
    const current = queryClient.getQueryData<SessionDetail>(detailKey(id))
    const version = documentCommand?.expectedVersion ?? current?.row_version ?? current?.revision
    let documentContext: DocumentContext | undefined
    if (documentCommand) {
      if (!current || version === undefined) throw new Error('画布尚未读取，请重新读取后操作')
      documentContext = { version, assetId: current.current_asset_id, signal, retry: documentCommand.retry === true }
    }
    const body = await invoke.mutateAsync({ tool, params, documentContext })
    try {
      if (signal.aborted) throw new DOMException('页面上下文已切换', 'AbortError')
      await sessionsApi.waitForRun(body.run, signal)
    } catch (error) {
      if (!signal.aborted && runtime.active && !(error instanceof Error && error.name === 'AbortError')) {
        if (documentContext) await reportDocumentFailure(error instanceof Error ? error : new Error(String(error)), { tool, params, documentContext })
        else toast(errorMessage(error), 'danger')
      }
      throw error
    }
  }

  return {
    invoke: (tool: string, params?: Record<string, unknown>) => invoke.mutate({ tool, params }),
    // 参数表单等待正式终态；queued/POST被接受不表示可以清除用户输入。
    invokeAsync,
    undo: () => runHistory(() => undo.mutate()),
    redo: () => runHistory(() => redo.mutate()),
    busy,
    toolBusy,
    canEdit,
    isRunning,
    pending: waiting,
    pendingStage: waiting ? live.stage : '',
    pendingProgress: waiting ? live.progress : 0,
    runLoadError: live.loadError,
    runNotFound: live.notFound,
    retryRun: () => { void live.refetch() },
    cancel: () => { if (runId) void sessionsApi.cancelRun(runId).then(() => queryClient.invalidateQueries({ queryKey: ['run', runId] })).catch((error) => toast(errorMessage(error), 'danger')) },
  }
}

export type SessionTools = ReturnType<typeof useSessionTools>
