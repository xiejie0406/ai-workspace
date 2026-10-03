import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from '@/api/client'
import { tokenStore } from '@/features/auth/token'
import { consumeEventStream, delay } from '@/lib/sse'
interface Options<T> { path: string; active: boolean; terminalTypes: readonly string[]; onEvent?: (event: T) => void }
interface Frame { eventSeq: number; type: string; state?: string; data?: Record<string, unknown>; delivery?: { status: string; reason: string } }
export function useEventStream<T extends { type: string }>({ path, active, terminalTypes, onEvent }: Options<T>) {
  const [event, setEvent] = useState<T | null>(null)
  const [connectionError, setConnectionError] = useState(false)
  const [deliveryMessage, setDeliveryMessage] = useState<string | null>(null)
  const handlerRef = useRef(onEvent); handlerRef.current = onEvent
  const terminalRef = useRef(terminalTypes); terminalRef.current = terminalTypes
  useEffect(() => {
    setEvent(null); setConnectionError(false); setDeliveryMessage(null)
    if (!active) return
    const controller = new AbortController(); const token = tokenStore.get(); let seq = 0; let received = false
    const valid = () => !controller.signal.aborted && tokenStore.get() === token
    const outline = path.endsWith('/outline/events'); const snapshotPath = path.replace(/\/events$/, '')
    const emit = (next: Record<string, unknown>) => { if (!valid()) return; setEvent(next as T); setConnectionError(false); handlerRef.current?.(next as T) }
    const snapshot = async (): Promise<{ data: Record<string, unknown>; finished: boolean }> => {
      if (!valid()) throw new DOMException('操作已取消', 'AbortError')
      const data = await request<Record<string, unknown>>(snapshotPath, { signal: controller.signal })
      if (!valid()) throw new DOMException('操作已取消', 'AbortError')
      const status = String(data.status); const terminal = outline ? ['draft', 'confirmed', 'failed'].includes(status) : ['ready', 'partial'].includes(status)
      const total = Number(data.total ?? 0); const ready = Number(data.ready ?? 0); const failed = Number(data.failed ?? 0)
      emit({ type: terminal ? status === 'failed' ? 'failed' : 'completed' : 'snapshot', status, progress: outline ? terminal ? 100 : 0 : total ? Math.round((ready + failed) / total * 100) : 0, message: data.error ?? '', ready, failed, total })
      return { data, finished: terminal }
    }
    const connect = async () => {
      let attempt = 0
      while (valid()) {
        let finished = false
        try {
          // 每次重连先读取正式快照，随后再处理游标增量。
          const current = await snapshot(); if (!valid() || current.finished) return
          const eventPath = path
          await consumeEventStream<Frame>(`${eventPath}?afterSeq=${seq}`, controller.signal, async (frame) => {
            if (!valid()) return
            if (frame.delivery) {
              const pairs: Record<string, string> = { connected: 'REDIS_CONNECTED', degraded: 'REDIS_UNAVAILABLE', disabled: 'REDIS_DISABLED' }
              if (pairs[frame.delivery.status] === frame.delivery.reason) setDeliveryMessage(frame.delivery.status === 'degraded' ? 'Redis通知暂不可用，正在定时读取已保存进度' : frame.delivery.status === 'disabled' ? 'Redis通知未启用，正在定时读取已保存进度' : null)
            }
            if (frame.type === 'delivery') return
            if (frame.type === 'resync_required') {
              const latest = await snapshot(); if (!valid()) return
              seq = frame.eventSeq; received = true; finished = latest.finished; return
            }
            if (received && frame.eventSeq <= seq && frame.type !== 'snapshot') return
            if (!valid()) return
            seq = frame.eventSeq; received = true
            if (outline) {
              if (frame.type === 'snapshot' || frame.type === 'changed') { const latest = await snapshot(); if (!valid()) return; finished = latest.finished; return }
              const task = frame.data ?? {}; const raw = task.progress; const progress = typeof raw === 'number' ? raw : Number((raw as { percent?: number } | undefined)?.percent ?? 0)
              const type = frame.state === 'succeeded' ? 'completed' : frame.state === 'failed' || frame.state === 'cancelled' ? 'failed' : 'progress'
              emit({ type, status: type === 'completed' ? 'draft' : type === 'failed' ? 'failed' : 'generating', progress, message: (raw as { message?: string } | undefined)?.message ?? task.message ?? task.error ?? '' })
              finished = terminalRef.current.includes(type)
            } else { const latest = await snapshot(); if (!valid()) return; finished = latest.finished }
          })
          attempt = 0
        } catch (error) {
          if (!valid() || error instanceof ApiError && [401, 403].includes(error.status)) return
          setConnectionError(true); attempt++
        }
        if (finished || !valid()) return
        await delay(Math.min(15000, 1000 * 2 ** Math.min(attempt, 4)), controller.signal)
      }
    }
    void connect(); return () => controller.abort()
  }, [active, path])
  return { event, connectionError, deliveryMessage }
}
