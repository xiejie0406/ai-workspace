import type { ApiClient } from '@/api/client'
import type { Asset } from '@/api/assets'

export type Ratio = '1:1' | '4:5' | '3:4' | '9:16' | '16:9'

export type RunStatus = 'planning' | 'queued' | 'waiting_confirmation' | 'running' | 'succeeded' | 'partial_success' | 'failed' | 'canceled' | 'external_result_unknown'

export type Run = {
  id: string
  event_seq?: number
  tool: string
  status: RunStatus
  progress: number
  stage: string
  error: string | null
  prompt: string | null
  candidates: Asset[]
  result: Record<string, unknown>
  params?: Record<string, unknown>
}

export type GenerateInput = {
  prompt: string
  ratio: Ratio
  count: number
  negative_prompt?: string
  reference_asset_ids?: string[]
  seed?: number
}

export const RATIO_LABELS: Record<Ratio, string> = {
  '1:1': '方形 1:1',
  '4:5': '竖版 4:5',
  '3:4': '竖版 3:4',
  '9:16': '长图 9:16',
  '16:9': '横版 16:9',
}

export function isTerminal(status: RunStatus) {
  return status === 'succeeded' || status === 'partial_success' || status === 'failed' || status === 'canceled' || status === 'external_result_unknown'
}

export const createRunsApi = (api: ApiClient) => ({
  generate: (input: GenerateInput) => api.post<Run>('/generations', input, 'aipicture:image:generate'),
  get: (id: string) => api.get<Run>(`/runs/${id}`),
  cancel: (id: string) => api.post<Run>(`/runs/${id}/cancel`, undefined, 'aipicture:tool:execute'),
})
