import { computed, onBeforeUnmount, onDeactivated, ref } from 'vue'
import { getSession, submitTurn } from '@/api/paicli/sessions'
import { getSessionEvents } from '@/api/paicli/events'
import type { PcEvent, PcSessionDetail, PcTurnInput } from '@/types/paicli'
import { errorText, isHistoricalSession, operationKeys, terminalTaskStates } from './model'

/** 页面只保存视图副本；中心事件与聚合详情始终为业务事实源。 */
export function useSession() {
  const detail = ref<PcSessionDetail>(), events = ref<PcEvent[]>([]), loading = ref(false), sending = ref(false), error = ref(''), streamState = ref<'IDLE' | 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'EXPIRED'>('IDLE'), cursor = ref('')
  const keys = operationKeys()
  let generation = 0, refreshGeneration = 0, selected = '', requestController: AbortController | undefined, streamController: AbortController | undefined
  const active = computed(() => { const current=detail.value; if (!current?.activeTaskId || terminalTaskStates.has(current.status)) return false; if (!current.latestTaskId && current.directoryPreparationTaskId === current.activeTaskId) return false; return true })
  function stopStream() { streamController?.abort(); streamController = undefined }
  function dispose() { generation++; refreshGeneration++; requestController?.abort(); stopStream(); loading.value = false; selected = ''; detail.value = undefined; events.value = []; cursor.value = ''; streamState.value = 'IDLE' }
  async function select(id: string) {
    dispose(); if (!id) return
    const current = ++generation; selected = id; loading.value = true; error.value = ''; requestController = new AbortController()
    try { const response = await getSession(id, requestController.signal); if (current !== generation) return; detail.value = response.data; cursor.value = response.data.eventCursor || ''; void subscribe() } catch (cause) { if (current === generation && !requestController.signal.aborted) error.value = errorText(cause) } finally { if (current === generation) loading.value = false }
  }
  async function refresh() {
    const id = selected, current = generation, refresh = ++refreshGeneration; if (!id) return
    const response = await getSession(id)
    if (current === generation && refresh === refreshGeneration && selected === id) detail.value = response.data
  }
  async function subscribe() {
    stopStream(); if (!selected) return
    const controller = new AbortController(), current = generation, id = selected
    streamController = controller; streamState.value = 'CONNECTING'
    // 连接出错停止自动轮询。用户重连保留已确认 cursor，避免无限失败循环。
    try {
      while (!controller.signal.aborted && current === generation) {
        const response = await getSessionEvents(id, cursor.value, controller.signal)
        if (controller.signal.aborted || current !== generation) return
        const batch = response.data
        if (!batch || !Array.isArray(batch.events) || typeof batch.cursor !== 'string') throw new Error('事件协议无效，保留已确认内容，请核对中心接口。')
        const seen = new Set(events.value.map(event => event.eventId))
        const incoming = batch.events.filter(event => { if (seen.has(event.eventId)) return false; seen.add(event.eventId); return true })
        if (incoming.length) {
          await refresh()
          if (controller.signal.aborted || current !== generation) return
          // 详情刷新失败不能提前记为已消费事件，否则重连去重后会跳过旧快照更新。
          events.value = [...events.value, ...incoming].sort((a,b) => a.sequence - b.sequence).slice(-1000)
        }
        if (controller.signal.aborted || current !== generation) return
        cursor.value = batch.cursor; streamState.value = 'CONNECTED'
        if (batch.terminal) return
        // 服务端若立即返回空批次，最多每秒一次，避免忙轮询。
        if (!incoming.length && !batch.hasMore) await pause(1000, controller.signal)
      }
    } catch (cause) {
      if (controller.signal.aborted || current !== generation) return
      const failure = cause as { response?: { status?: number; data?: { code?: number } } }
      streamState.value = failure.response?.status === 410 || failure.response?.data?.code === 410 ? 'EXPIRED' : 'DISCONNECTED'
      error.value = streamState.value === 'EXPIRED' ? '事件游标已过期，请重新加载完整会话后续取。' : `${errorText(cause)} 内容可能未完成。已确认事件位置：${cursor.value || '起点'}。`
    }
  }
  async function send(input: PcTurnInput) {
    const id = selected, current = generation; if (!id || sending.value || active.value) return false
    if (isHistoricalSession(detail.value)) { error.value = '此对话为只读历史，请新建对话后继续。'; return false }
    sending.value = true; error.value = ''; const intent = { id, ...input }
    try { const response = await submitTurn(id, input, keys.get(intent)); keys.done(intent); if (current !== generation) return false; if (detail.value) { detail.value.activeTaskId = response.data.taskId; detail.value.status = 'ENQUEUED'; detail.value.version = response.data.version }; await refresh(); void subscribe(); return true } catch (cause) { if (current === generation) error.value = errorText(cause); return false } finally { sending.value = false }
  }
  onBeforeUnmount(dispose); onDeactivated(() => { generation++; refreshGeneration++; requestController?.abort(); stopStream(); streamState.value = 'IDLE' })
  return { detail, events, loading, sending, error, streamState, cursor, active, select, refresh, subscribe, dispose, send }
}
function pause(ms: number, signal: AbortSignal) { return new Promise<void>(resolve => { const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve() }; const timer = setTimeout(finish, ms); signal.addEventListener('abort', finish, { once: true }); if (signal.aborted) finish() }) }
