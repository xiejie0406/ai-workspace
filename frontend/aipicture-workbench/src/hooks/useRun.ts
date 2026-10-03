import { useApis, useHostRuntime } from '@/host/HostContext'
import { unwrap } from '@/api/client'
import { normalizeBusinessDto } from '@/api/normalize'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { isTerminal, type GenerateInput, type Run } from '@/api/runs'
import { useSmoothedProgress } from '@/hooks/useSmoothedProgress'
import { isNotFoundError, isRetryableReadError } from '@/lib/errors'

type Progress = Pick<Run, 'id' | 'event_seq' | 'tool' | 'status' | 'progress' | 'stage' | 'error' | 'result'>
type LiveProgress = Progress & { observedReadAt: number }

function sequence(frame: Progress): number | undefined {
  return Number.isSafeInteger(frame.event_seq) && (frame.event_seq as number) >= 0 ? frame.event_seq : undefined
}

/** 同序以正式 GET 为准；旧无序号契约以正式终态恢复，新的批量重试仍可用较高序号继续。 */
function latestFrame(snapshot: Run | null | undefined, live: LiveProgress | null, updatedAt: number): Progress | undefined {
  if (!snapshot) return live ?? undefined
  if (!live) return snapshot
  const formalSeq = sequence(snapshot), liveSeq = sequence(live)
  if (formalSeq !== undefined && liveSeq !== undefined) return liveSeq > formalSeq ? live : snapshot
  // 旧无序号宿主无法比较服务端事件顺序，只采信接帧之后实际完成的新正式 GET。
  return isTerminal(snapshot.status) || updatedAt > live.observedReadAt ? snapshot : live
}

const runKey = (id: string) => ['run', id]

export function useGenerate() {
  const { runs: runsApi } = useApis()
  return useMutation({ mutationFn: (input: GenerateInput) => runsApi.generate(input) })
}

/**
 * 合并两个进度来源：SSE 提供实时状态，快照接口提供候选图与刷新后的恢复能力。
 */
export function useRun(runId: string | null) {
  const { runs: runsApi } = useApis()
  const runtime = useHostRuntime()
  const queryClient = useQueryClient()
  const [live, setLive] = useState<LiveProgress | null>(null)
  const formalRead = useRef<{ data: Run | null | undefined; updatedAt: number }>({ data: undefined, updatedAt: 0 })
  // 无快照的重读会临时清空 Query.error；保留当前任务最后一次读取错误直至正式恢复。
  const [lastReadError, setLastReadError] = useState<{ runId: string; error: Error } | null>(null)
  const retainedError = lastReadError?.runId === runId ? lastReadError.error : null

  const snapshot = useQuery({
    queryKey: runKey(runId ?? ''),
    queryFn: () => runsApi.get(runId as string),
    enabled: Boolean(runId),
    refetchInterval: (query) => {
      const formal = query.state.data
      const current = live?.id === runId ? live : null
      if (formal === null || (formal && isTerminal(formal.status) && latestFrame(formal, current, query.state.dataUpdatedAt) === formal)) return false
      const readError = query.state.error ?? retainedError
      return readError && !isRetryableReadError(readError) ? false : 1500
    },
  })
  useLayoutEffect(() => { formalRead.current = { data: snapshot.data, updatedAt: snapshot.dataUpdatedAt } }, [snapshot.data, snapshot.dataUpdatedAt])

  const emptySnapshot = snapshot.data === null
  const loadError = snapshot.error ?? (snapshot.isSuccess || emptySnapshot ? null : retainedError)
  useEffect(() => {
    setLastReadError((previous) => {
      if (!runId || snapshot.isSuccess || emptySnapshot) return null
      if (snapshot.error) {
        return previous?.runId === runId && previous.error === snapshot.error
          ? previous : { runId, error: snapshot.error }
      }
      return previous?.runId === runId ? previous : null
    })
  }, [runId, snapshot.error, snapshot.isSuccess, emptySnapshot])

  const run = snapshot.data
  const current = live?.id === runId ? live : null
  const resolved = latestFrame(run, current, snapshot.dataUpdatedAt)
  const status = resolved?.status
  const snapshotUnavailable = Boolean(loadError) || emptySnapshot

  useEffect(() => {
    // 已结束的任务不必再挂 SSE，否则重连会反复打快照、刷新签名 URL
    if (!runId || snapshotUnavailable || (status && isTerminal(status))) return
    let active = true
    const signal = runtime.signal

    const close = runtime.subscribe(runId, (snapshot) => {
      if (!active || signal.aborted || !runtime.active) return
      const payload = normalizeBusinessDto(unwrap<Progress>(snapshot))
      if (payload.id !== runId) return
      setLive((previous) => {
        const formal = formalRead.current
        const next = { ...payload, observedReadAt: formal.updatedAt }
        if (previous?.id !== runId || latestFrame(formal.data, previous, formal.updatedAt) !== previous) return next
        const previousSeq = sequence(previous), nextSeq = sequence(payload)
        if (previousSeq !== undefined && nextSeq !== undefined) return nextSeq > previousSeq ? next : previous
        if (isTerminal(previous.status) && !isTerminal(payload.status)) return previous
        return next
      })
      if (isTerminal(payload.status)) {
        void queryClient.invalidateQueries({ queryKey: runKey(runId) })
      }
    }, () => {
      if (active && !signal.aborted && runtime.active) void queryClient.invalidateQueries({ queryKey: runKey(runId) })
    })
    return () => { active = false; close?.() }
  }, [runId, status, snapshotUnavailable, queryClient, runtime])

  const reported = resolved?.progress ?? 0
  const progress = useSmoothedProgress({
    reported,
    status,
    tool: resolved?.tool,
    token: runId,
  })

  return {
    status,
    tool: resolved?.tool,
    params: run?.params,
    progress,
    stage: resolved?.stage ?? '',
    error: resolved?.error ?? null,
    prompt: run?.prompt ?? null,
    candidates: run?.candidates ?? [],
    result: resolved?.result ?? {},
    isLoading: snapshot.isPending,
    notFound: emptySnapshot || isNotFoundError(loadError),
    loadError,
    refetch: snapshot.refetch,
  }
}
