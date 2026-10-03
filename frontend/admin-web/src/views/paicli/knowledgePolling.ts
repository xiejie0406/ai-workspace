import type { PcEnvelope, PcPendingReceipt } from '@/types/paicli'
/** 等待中心已排队的查询，最多20次；取消页面等待不会宣称取消正式任务。 */
export async function awaitKnowledge<T>(fetchResult: () => Promise<PcEnvelope<T | PcPendingReceipt>>, signal: AbortSignal, pending: (receipt: PcPendingReceipt) => void, attempts = 20, delayMs = 2000): Promise<T | undefined> {
  for (let attempt = 0; attempt < attempts; attempt++) {
    signal.throwIfAborted()
    const response = await fetchResult(); signal.throwIfAborted()
    const data = response.data
    if (!isPending(data)) return data as T
    pending(data)
    if (attempt + 1 < attempts) await pause(delayMs,signal)
  }
  return undefined
}
function isPending(value: unknown): value is PcPendingReceipt { return !!value && typeof value === 'object' && 'status' in value && value.status === 'PENDING' && 'taskId' in value && typeof value.taskId === 'string' && 'sessionId' in value && typeof value.sessionId === 'string' }
function pause(delayMs: number, signal: AbortSignal) { return new Promise<void>((resolve,reject) => { const abort = () => { clearTimeout(timer); signal.removeEventListener('abort',abort); reject(signal.reason ?? new DOMException('页面等待已取消','AbortError')) }, timer = setTimeout(() => { signal.removeEventListener('abort',abort); resolve() },delayMs); signal.addEventListener('abort',abort,{ once:true }); if (signal.aborted) abort() }) }
