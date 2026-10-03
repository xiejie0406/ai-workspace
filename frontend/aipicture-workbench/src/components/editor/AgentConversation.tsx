import { useEffect, useRef, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'

import type { PlanStep, StepStatus, Turn } from '@/api/agent'
import { isTerminal } from '@/api/runs'
import Button from '@/components/ui/Button'
import ProgressBar from '@/components/ui/ProgressBar'
import { usePlanActions, useTurns } from '@/hooks/useAgent'
import { useRun } from '@/hooks/useRun'
import { useHost } from '@/host/HostContext'
import { errorMessage } from '@/lib/errors'
import { useSession } from '@/hooks/useSessions'
import type { LayerDocument } from '@/api/sessions'
import { planSummary } from '@/lib/planSummary'

const STEP_LABEL: Record<StepStatus, string> = {
  planning: '思考中',
  pending: '等待中',
  waiting: '待确认',
  waiting_confirmation: '待确认',
  queued: '排队中',
  running: '进行中',
  succeeded: '已完成',
  partial_success: '部分成功',
  failed: '未完成',
  canceled: '已取消',
  external_result_unknown: '外部结果待核对',
}

export default function AgentConversation({ sessionId }: { sessionId: string }) {
  const { data: turns = [], isPending, error, refetch } = useTurns(sessionId)
  const { data: session } = useSession(sessionId)
  const end = useRef<HTMLDivElement>(null)
  const latest = turns.at(-1)

  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end' })
  }, [turns.length, latest?.status])

  if (isPending) {
    return <p className="text-faint min-h-0 flex-1 px-4 py-4 text-xs">加载中…</p>
  }
  if (error) return <p className="text-danger px-4 py-4 text-xs" role="alert">{errorMessage(error)} <button onClick={() => { void refetch() }}>重新读取计划</button></p>

  if (turns.length === 0) {
    return (
      <p className="text-faint min-h-0 flex-1 px-4 py-4 text-xs leading-relaxed">
        用一句话说明要怎么改，例如「水平翻转」或「去背景再调亮一点」。多步计划会先列出再执行。
      </p>
    )
  }

  return (
    <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-3 py-3">
      {turns.map((turn) => (
        <TurnBlock key={turn.id} turn={turn} sessionId={sessionId} document={session?.document} />
      ))}
      <div ref={end} />
    </div>
  )
}

function TurnBlock({ turn, sessionId, document }: { turn: Turn; sessionId: string; document?: LayerDocument }) {
  return (
    <div className="space-y-2">
      <p className="bg-brand-soft text-brand-strong ml-6 rounded-[12px] px-3 py-2 text-xs leading-relaxed">
        {turn.goal}
      </p>

      {turn.status === 'planning' ? (
        <p className="text-muted mr-6 text-xs leading-relaxed" role="status">思考中，正在生成正式计划；请等待后台结果，避免重复发送。</p>
      ) : turn.error ? (
        <p className="text-danger mr-6 text-xs leading-relaxed">{turn.error}</p>
      ) : (
        <p className="text-ink mr-6 text-xs leading-relaxed">{turn.reply}</p>
      )}

      {turn.steps.map((step, index) => (
        <StepCard key={step.id} index={index + 1} step={step} sessionId={sessionId} turn={turn} document={document} />
      ))}

      <PlanActions turn={turn} sessionId={sessionId} />
      {(turn.status === 'queued' || turn.status === 'waiting_confirmation') && <p className="text-muted mr-6 text-[11px] leading-relaxed">文字规划和图像模型调用可能产生费用。执行前请核对每步范围与结果；取消后续不会撤销已发出的供应商请求。</p>}
    </div>
  )
}

function PlanActions({ turn, sessionId }: { turn: Turn; sessionId: string }) {
  const actions = usePlanActions(sessionId)
  const host = useHost()
  if (turn.status === 'queued' || turn.status === 'waiting_confirmation') {
    return (
      <div className="mr-6 flex gap-1.5">
        <Action disabled={actions.busy || !turn.plan_hash || !host.hasPermission('aipicture:plan:confirm')} onClick={() => actions.confirm(turn.id, turn.plan_hash || '')}>
          执行计划
        </Action>
        {actions.error && <p className="text-danger text-xs" role="alert">{errorMessage(actions.error)}</p>}
        {!turn.plan_hash && <p className="text-danger text-xs" role="alert">计划缺少确认校验值，请重新规划。</p>}
        <Action disabled={actions.busy || !host.hasPermission('aipicture:plan:cancel')} onClick={() => actions.cancel(turn.id)}>
          取消
        </Action>
      </div>
    )
  }
  if (turn.status === 'running') {
    return (
      <div className="mr-6">
        <Action disabled={actions.busy || !host.hasPermission('aipicture:plan:cancel')} onClick={() => actions.cancel(turn.id)}>
          取消后续
        </Action>
        {actions.error && <p className="text-danger text-xs" role="alert">{errorMessage(actions.error)}</p>}
      </div>
    )
  }
  if (turn.status === 'failed') {
    if (turn.steps.length === 0) {
      return <p className="text-muted mr-6 text-xs leading-relaxed">规划未产生步骤，请重新发送指令。</p>
    }
    return (
      <div className="mr-6">
        <Action disabled={actions.busy || turn.steps.some((step) => step.status === 'external_result_unknown') || !host.hasPermission('aipicture:plan:retry')} onClick={() => actions.retry(turn.id)}>
          重试失败步骤
        </Action>
        {actions.error && <p className="text-danger text-xs" role="alert">{errorMessage(actions.error)}</p>}
        {turn.steps.some((step) => step.status === 'external_result_unknown') && <p className="text-danger text-xs">外部结果待管理员核对，避免重复付费调用。</p>}
      </div>
    )
  }
  return null
}

function Action({
  children,
  disabled,
  onClick,
}: {
  children: ReactNode
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <Button variant="outline" size="sm" disabled={disabled} onClick={onClick}>
      {children}
    </Button>
  )
}

function StepCard({
  index,
  step,
  sessionId,
  turn,
  document,
}: {
  index: number
  step: PlanStep
  sessionId: string
  turn: Turn
  document?: LayerDocument
}) {
  const queryClient = useQueryClient()
  const { status, progress, stage, error, loadError, notFound, refetch } = useRun(step.run_id)
  const watched = useRef(false)
  const current = status ?? step.status
  const running = current === 'queued' || current === 'running'
  const summary = planSummary(step, turn, document)

  useEffect(() => {
    if (status === 'queued' || status === 'running') watched.current = true
    if (!watched.current || !status || !isTerminal(status)) return
    watched.current = false
    void queryClient.invalidateQueries({ queryKey: ['session', sessionId] })
    void queryClient.invalidateQueries({ queryKey: ['session', sessionId, 'history'] })
    void queryClient.invalidateQueries({ queryKey: ['session', sessionId, 'messages'] })
  }, [status, sessionId, queryClient])

  return (
    <div className="border-line mr-6 rounded-[12px] border px-3 py-2">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-ink text-xs font-medium">
          {index}. {step.label}
        </span>
        <span className="text-faint shrink-0 text-[10px] tabular-nums">
          {running ? `${progress}%` : STEP_LABEL[current]}
        </span>
      </div>
      <div className="text-muted mt-2 space-y-1 text-[11px] leading-relaxed">
        <p>{summary.scope}</p><p>{summary.output}</p><p>{summary.cost}</p>
        {summary.entries.length > 0 && <details><summary className="cursor-pointer">查看计划参数</summary><dl className="mt-1 space-y-1">{summary.entries.map(([label, value]) => <div key={label}><dt className="text-ink inline">{label}：</dt><dd className="inline break-words">{value}</dd></div>)}</dl></details>}
      </div>

      {running && (
        <>
          <ProgressBar value={progress} className="mt-2 h-0.5 rounded-full" />
          <p className="text-faint mt-1.5 text-[10px]">{stage}</p>
        </>
      )}

      {error && <p className="text-danger mt-1.5 text-[10px] leading-relaxed">{error}</p>}
      {(loadError || notFound) && <div className="text-danger mt-1.5 text-[10px] leading-relaxed" role="alert">
        {notFound ? '任务不存在，当前步骤状态无法读取。' : errorMessage(loadError)}
        <Button variant="outline" size="sm" className="mt-1" onClick={() => { void refetch() }}>重新读取任务</Button>
      </div>}
    </div>
  )
}
