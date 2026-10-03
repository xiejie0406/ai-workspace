import { useApis } from '@/host/HostContext'
import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { isTerminal } from '@/api/runs'

const turnsKey = (sessionId: string) => ['session', sessionId, 'messages']

function useRefreshTurn(sessionId: string) {

  const queryClient = useQueryClient()
  // 返回回读 Promise，让旧状态上的动作一直等待正式刷新完成。
  return () => Promise.all([
    queryClient.invalidateQueries({ queryKey: turnsKey(sessionId) }),
    queryClient.invalidateQueries({ queryKey: ['session', sessionId] }),
    queryClient.invalidateQueries({ queryKey: ['session', sessionId, 'history'] }),
  ])
}

export function useTurns(sessionId: string) {
  const { agent: agentApi } = useApis()
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: turnsKey(sessionId),
    queryFn: () => agentApi.turns(sessionId),
    // 第一步 SSE 结束时下一步可能还没写进计划，整轮 running 时接着拉，避免卡片停在「等待中」
    refetchInterval: (current) =>
      current.state.data?.some((turn) => turn.status === 'planning' || turn.status === 'running') ? 800 : false,
  })

  const latest = query.data?.at(-1)?.status
  const previous = useRef(latest)
  useEffect(() => {
    const wasRunning = previous.current === 'running'
    previous.current = latest
    if (!wasRunning || !latest || !isTerminal(latest)) return
    void queryClient.invalidateQueries({ queryKey: ['session', sessionId] })
    void queryClient.invalidateQueries({ queryKey: ['session', sessionId, 'history'] })
  }, [latest, sessionId, queryClient])

  useEffect(() => {
    previous.current = undefined
  }, [sessionId])

  return query
}

export function useSendMessage(sessionId: string) {
  const { agent: agentApi } = useApis()
  const refresh = useRefreshTurn(sessionId)
  return useMutation({
    mutationFn: (text: string) => agentApi.send(sessionId, text),
    // 共享请求可能先超时而 Java 仍在规划；失败也必须重新读取已保存的正式 turn。
    onSettled: refresh,
  })
}

export function usePlanActions(sessionId: string) {
  const { agent: agentApi } = useApis()
  const refresh = useRefreshTurn(sessionId)
  const confirm = useMutation({
    mutationFn: ({ turnId, planHash }: { turnId: string; planHash: string }) => agentApi.confirm(sessionId, turnId, planHash),
    onSettled: refresh,
  })
  const cancel = useMutation({
    mutationFn: (turnId: string) => agentApi.cancel(sessionId, turnId),
    onSettled: refresh,
  })
  const retry = useMutation({
    mutationFn: (turnId: string) => agentApi.retry(sessionId, turnId),
    onSettled: refresh,
  })
  return {
    confirm: (turnId: string, planHash: string) => confirm.mutate({ turnId, planHash }),
    cancel: (turnId: string) => cancel.mutate(turnId),
    retry: (turnId: string) => retry.mutate(turnId),
    busy: confirm.isPending || cancel.isPending || retry.isPending,
    error: confirm.error ?? cancel.error ?? retry.error,
  }
}
