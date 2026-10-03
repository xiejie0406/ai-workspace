import { ApiError, request } from '@/api/client'
import { tokenStore } from '@/features/auth/token'
export interface Task {
  id?: string; job_id?: string; jobId?: string; state?: string; status?: string
  result?: Record<string, unknown>; output?: Record<string, unknown>; error?: string
  progress?: number | { percent?: number; message?: string }
  verification_failure?: Record<string, unknown>
}
export function jobId(value: Task) { return value.job_id ?? value.jobId ?? value.id }
export async function waitTask(accepted: Task, signal?: AbortSignal): Promise<Task> {
  const id = jobId(accepted); if (!id) throw new ApiError(502, '任务响应缺少任务编号')
  const token = tokenStore.get()
  for (;;) {
    if (signal?.aborted || tokenStore.get() !== token) throw new DOMException('操作已取消', 'AbortError')
    const task = await request<Task>(`/tasks/${encodeURIComponent(id)}`, { signal })
    const state = task.status ?? task.state
    if (state === 'succeeded') return task
    if (state === 'failed' || state === 'cancelled') {
      const verify = task.verification_failure
      throw new ApiError(verify ? 422 : 500, verify ? { ...verify, message: task.error ?? '导出回读验证未通过' } : task.error ?? (state === 'cancelled' ? '任务已取消' : '任务失败'))
    }
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, 700)
      const abort = () => { clearTimeout(timer); reject(new DOMException('操作已取消', 'AbortError')) }
      signal?.addEventListener('abort', abort, { once: true })
    })
  }
}
