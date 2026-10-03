import type { ApiClient } from '@/api/client'
import type { RunStatus } from '@/api/runs'

export type StepStatus =
  | 'planning'
  | 'pending'
  | 'waiting'
  | 'waiting_confirmation'
  | 'queued'
  | 'running'
  | 'succeeded'
  | 'partial_success'
  | 'failed'
  | 'canceled'
  | 'external_result_unknown'

export type PlanStep = {
  id: string
  tool: string
  label: string
  params: Record<string, unknown>
  depends_on: string[]
  run_id: string | null
  status: StepStatus
}

export type Turn = {
  id: string
  revision: number
  goal: string
  reply: string
  status: RunStatus
  error: string | null
  created_at: string
  steps: PlanStep[]
  plan_hash?: string
  mask_asset_id?: string | null
  mask_width?: number | null
  mask_height?: number | null
}

export const createAgentApi = (api: ApiClient) => ({
  turns: (sessionId: string) => api.get<Turn[]>(`/sessions/${sessionId}/messages`),
  send: (sessionId: string, text: string) =>
    api.post<Turn>(`/sessions/${sessionId}/messages`, { text }, 'aipicture:tool:execute'),
  confirm: (sessionId: string, turnId: string, planHash: string) =>
    api.post<Turn>(`/sessions/${sessionId}/messages/${turnId}/confirm`, { plan_hash: planHash }, 'aipicture:plan:confirm'),
  cancel: (sessionId: string, turnId: string) =>
    api.post<Turn>(`/sessions/${sessionId}/messages/${turnId}/cancel`, undefined, 'aipicture:plan:cancel'),
  retry: (sessionId: string, turnId: string) =>
    api.post<Turn>(`/sessions/${sessionId}/messages/${turnId}/retry`, undefined, 'aipicture:plan:retry'),
})
