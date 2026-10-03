import type { Asset } from '@/api/assets'
import type { ApiClient } from '@/api/client'
import type { HostRuntime } from '@/host/HostRuntime'
import { awaitExport } from '@/api/exports'
import type { Run } from '@/api/runs'
import type { BatchOp } from '@/lib/batch'

export type BatchItem = {
  id?: string
  source: Asset
  status: 'pending' | 'running' | 'succeeded' | 'failed' | 'canceled' | 'external_result_unknown'
  error: string | null
  outputs: Asset[]
}

export type Batch = {
  run: Run
  items: BatchItem[]
  created_at: string
}

export type BatchInput = {
  asset_ids: string[]
  operations: BatchOp[]
  formats: Array<'png' | 'jpg'>
}

export type ExportPack = {
  blob: Blob
  filename: string
}

export const createBatchesApi = (api: ApiClient, runtime: HostRuntime) => ({
  create: async (input: BatchInput) => {
    const batch = await api.post<Batch>('/batches', input, 'aipicture:batch:execute')
    if (!batch.run?.id) throw new Error('批量任务未返回母任务快照')
    return batch.run
  },
  list: () => api.get<Batch[]>('/batches'),
  get: (id: string) => api.get<Batch>(`/batches/${id}`),
  export: (id: string) => awaitExport(api, runtime, `/batches/${id}/export`, { format: 'zip' }),
  cancel: (id: string) => api.post<Batch>(`/batches/${id}/cancel`, undefined, 'aipicture:batch:cancel'),
  retry: (id: string, itemId: string) => api.post<Batch>(`/batches/${id}/items/${itemId}/retry`, undefined, 'aipicture:batch:retry'),
})
