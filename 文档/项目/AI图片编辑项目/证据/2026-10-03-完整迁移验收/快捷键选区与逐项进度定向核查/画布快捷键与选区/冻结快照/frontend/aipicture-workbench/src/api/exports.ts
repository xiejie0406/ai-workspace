import { ApiError, type ApiClient } from './client'
import { isTerminal, type Run } from './runs'
import type { HostRuntime } from '@/host/HostRuntime'
import { errorMessage, isRetryableReadError } from '@/lib/errors'

const WAIT_LIMIT_MS = 15 * 60 * 1000

function checkContext(runtime: HostRuntime, signal: AbortSignal) {
  if (signal.aborted || !runtime.active) throw new DOMException('已取消本地任务等待', 'AbortError')
}

async function waitRead(runtime: HostRuntime, signal: AbortSignal) {
  checkContext(runtime, signal)
  await new Promise<void>((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', abort)
      reject(new DOMException('已取消本地任务等待', 'AbortError'))
    }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, 1000)
    signal.addEventListener('abort', abort, { once: true })
  })
  checkContext(runtime, signal)
}

/** 只恢复同一资源的 GET；沿用 useRun 的错误分类，不重发有副作用的 POST。 */
export async function readWithRecovery<T>(api: ApiClient, runtime: HostRuntime, path: string,
  deadline = Date.now() + WAIT_LIMIT_MS, signal = runtime.signal): Promise<T> {
  let lastError: unknown
  while (true) {
    checkContext(runtime, signal)
    if (Date.now() > deadline) throw new ApiError(408, `${path} 仍无法读取，请稍后重读。${lastError ? errorMessage(lastError) : ''}`)
    try {
      const value = await api.get<T>(path)
      checkContext(runtime, signal)
      return value
    } catch (error) {
      checkContext(runtime, signal)
      if (!isRetryableReadError(error)) throw error
      lastError = error
      await waitRead(runtime, signal)
    }
  }
}

export async function awaitRun(api: ApiClient, runtime: HostRuntime, started: Run & { run_id?: string; run?: Run }, signal = runtime.signal) {
  const id = started.run_id ?? started.run?.id ?? started.id
  if (!id) throw new ApiError(502, '任务未返回编号')
  let run = started.run ?? started
  const deadline = Date.now() + WAIT_LIMIT_MS
  while (!isTerminal(run.status)) {
    checkContext(runtime, signal)
    if (Date.now() > deadline) throw new ApiError(408, `任务仍在运行，请稍后查看 ${id}`)
    await waitRead(runtime, signal)
    const current = await readWithRecovery<Run | null>(api, runtime, `/runs/${id}`, deadline, signal)
    if (!current) throw new ApiError(404, '任务不存在，无法读取正式结果')
    run = current
  }
  checkContext(runtime, signal)
  if (run.status !== 'succeeded' && run.status !== 'partial_success') throw new ApiError(400, run.error || '任务未完成')
  return run
}

/** 导出登记异步任务，完成后授权下载；最多等待 15 分钟，失活/卸载立即取消本地等待。 */
export async function awaitExport(api: ApiClient, runtime: HostRuntime, path: string, input: unknown) {
  const signal = runtime.signal
  runtime.requirePermission('aipicture:export:download')
  const started = await api.post<Run & { run_id?: string; run?: Run }>(path, input, 'aipicture:export:download')
  const run = await awaitRun(api, runtime, started, signal)
  const exportId = run.result?.export_id
  if (!exportId) throw new ApiError(502, '导出任务没有返回文件编号')
  checkContext(runtime, signal)
  const blob = await api.blob(`/exports/${encodeURIComponent(String(exportId))}/download`)
  checkContext(runtime, signal)
  const format = (input as { format?: string } | undefined)?.format
  return { blob, filename: String(run.result.filename || (format === 'png' ? '画布.png' : '物料.zip')) }
}
