import { useApis } from '@/host/HostContext'
import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { type BatchInput } from '@/api/batches'
import { isTerminal } from '@/api/runs'
import { errorMessage } from '@/lib/errors'
import { useRun } from '@/hooks/useRun'
import { useToast } from '@/host/HostContext'

const LIST_KEY = ['batches']
const batchKey = (id: string) => ['batch', id]

export function useBatches() {
  const { batches: batchesApi } = useApis()
  return useQuery({ queryKey: LIST_KEY, queryFn: batchesApi.list })
}

export function useBatch(id: string | null) {
  const { batches: batchesApi } = useApis()
  const live = useRun(id)
  const queryClient = useQueryClient()
  const detail = useQuery({
    queryKey: batchKey(id ?? ''),
    queryFn: () => batchesApi.get(id as string),
    enabled: Boolean(id),
    refetchInterval: (current) => {
      const savedStatus = current.state.data?.run.status
      if (savedStatus && isTerminal(savedStatus)) return false
      const status = live.status ?? savedStatus
      return id && status && !isTerminal(status) ? 1200 : false
    },
  })

  useEffect(() => {
    if (!id || !live.status || !isTerminal(live.status)) return
    void queryClient.invalidateQueries({ queryKey: batchKey(id) })
    void queryClient.invalidateQueries({ queryKey: LIST_KEY })
  }, [id, live.status, queryClient])

  return { ...detail, live }
}

export function useCreateBatch() {
  const { batches: batchesApi } = useApis()
  const toast = useToast()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: BatchInput) => batchesApi.create(input),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: LIST_KEY })
      queryClient.setQueryData(batchKey(run.id), undefined)
    },
    onError: (error) => toast(errorMessage(error), 'danger'),
  })
}

export function useExportBatch(id: string) {
  const { batches: batchesApi } = useApis()
  const toast = useToast()
  return useMutation({
    mutationFn: () => batchesApi.export(id),
    onError: (error) => toast(errorMessage(error), 'danger'),
  })
}

export function useBatchActions(id: string) {
  const { batches } = useApis()
  const queryClient = useQueryClient()
  const toast = useToast()
  const refresh = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: batchKey(id) }),
    queryClient.invalidateQueries({ queryKey: ['run', id] }),
    queryClient.invalidateQueries({ queryKey: LIST_KEY }),
  ])
  const cancel = useMutation({ mutationFn: () => batches.cancel(id), onSettled: refresh, onError: (error) => toast(errorMessage(error), 'danger') })
  const retry = useMutation({ mutationFn: (itemId: string) => batches.retry(id, itemId), onSettled: refresh, onError: (error) => toast(errorMessage(error), 'danger') })
  return { cancel, retry }
}
