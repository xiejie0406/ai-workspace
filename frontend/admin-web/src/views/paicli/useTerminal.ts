import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { claimTerminal, completeTerminalAttachments, createTerminal, decideTerminalAuthorization, getTerminal, stopTerminal, terminalStreamUrl, terminalTicket } from '@/api/paicli/terminals'
import type { PcTerminal, PcTerminalAttachmentRequest, PcTerminalAuthorizationRequest, PcTerminalExportArtifact, PcTerminalFrame, PcTerminalRenderer } from '@/types/paicli-terminal'
import { errorText } from './model'

interface TerminalDisplay { reset: () => void; write: (data: string) => Promise<void> }
const ended = new Set(['EXITED', 'FAILED', 'LOST'])
/** 只持有终端画面的临时连接；不解析命令，也不重复提交编码任务。 */
export function useTerminal(display: TerminalDisplay) {
  const terminal = shallowRef<PcTerminal>(), error = ref(''), busy = ref(false), connected = ref(false), replaying = ref(false), canWrite = ref(false), outputSeq = ref(0), unconfirmedInputs = ref(0), attachmentRequest = shallowRef<PcTerminalAttachmentRequest>()
  const authorizationRequest = shallowRef<PcTerminalAuthorizationRequest>()
  const exportArtifacts = ref<PcTerminalExportArtifact[]>([])
  const clientId = crypto.randomUUID(), writable = computed(() => connected.value && !replaying.value && canWrite.value && terminal.value?.state === 'RUNNING')
  let socket: WebSocket | undefined, generation = 0, inputSeq = 0, pending = new Set<number>(), timer: ReturnType<typeof setTimeout> | undefined, heartbeat: ReturnType<typeof setInterval> | undefined, retries = 0, suspended = false
  function send(frame: Record<string, unknown>) { if (socket?.readyState !== WebSocket.OPEN) return false; try { socket.send(JSON.stringify(frame)); return true } catch (cause) { fail(cause); return false } }
  function epochs() { return { terminalId: terminal.value?.terminalId, hostEpoch: terminal.value?.hostEpoch, writerEpoch: terminal.value?.writerEpoch } }
  function closeTransport() { generation++; clearTimeout(timer); clearInterval(heartbeat); timer = undefined; heartbeat = undefined; const old = socket; socket = undefined; old?.close(1000, 'browser-detached'); connected.value = false; canWrite.value = false; replaying.value = false }
  function detach() { suspended = true; closeTransport() }
  function dispose() { detach(); terminal.value = undefined; attachmentRequest.value = undefined; authorizationRequest.value = undefined; exportArtifacts.value = []; inputSeq = 0; pending.clear(); unconfirmedInputs.value = 0; outputSeq.value = 0; error.value = ''; display.reset() }
  async function connect() {
    const currentTerminal = terminal.value; if (!currentTerminal || ended.has(currentTerminal.state)) return
    closeTransport(); suspended = false; const current = generation; error.value = ''; replaying.value = true
    try {
      const ticket = (await terminalTicket(currentTerminal.terminalId, clientId)).data
      if (current !== generation || suspended) return
      if (!Array.isArray(ticket.protocols) || ticket.protocols[0] !== 'paicli-terminal' || !ticket.protocols[1]?.startsWith('paicli-ticket.')) throw new Error('中心终端票据协议无效。')
      const ws = new WebSocket(terminalStreamUrl(), ticket.protocols); socket = ws
      let receivedSeq = 0, queuedWrites = Promise.resolve(), stateReceived = false
      ws.onopen = () => {
        if (current !== generation) return
        connected.value = true
        // 每次重连从完整 VT 流恢复；未知输入永不重发。
        send({ type: 'HELLO', afterOutputSeq: 0, reset: true })
        heartbeat = setInterval(() => send({ type: 'PING' }), 15000)
      }
      ws.onmessage = event => {
        if (current !== generation || typeof event.data !== 'string') return
        try {
          const frame = JSON.parse(event.data) as PcTerminalFrame
          if (frame.type === 'PING') { send({ type: 'PONG' }); return }
          if (frame.type === 'PONG') return
          if (frame.terminalId && frame.terminalId !== currentTerminal.terminalId) throw new Error('终端流返回了错误的实例。')
          if (frame.hostEpoch !== undefined && frame.hostEpoch !== ticket.hostEpoch) throw new Error('终端进程版本已改变，请核对会话后重新打开。')
          if (frame.type === 'STATE') {
            if (!frame.state || !Number.isSafeInteger(frame.writerEpoch) || !Number.isSafeInteger(frame.inputSeq)) throw new Error('终端状态协议无效。')
            const previousEpoch = terminal.value?.writerEpoch
            terminal.value = { ...currentTerminal, ...terminal.value, ...frame, terminalId: currentTerminal.terminalId } as PcTerminal
            canWrite.value = frame.canWrite === true; inputSeq = Math.max(previousEpoch === frame.writerEpoch ? inputSeq : 0, frame.inputSeq ?? 0); stateReceived = true
            if (previousEpoch !== frame.writerEpoch) { pending.clear(); unconfirmedInputs.value = 0; attachmentRequest.value = undefined; authorizationRequest.value = undefined }
            if (ended.has(frame.state)) { canWrite.value = false; attachmentRequest.value = undefined; authorizationRequest.value = undefined }
          } else if (frame.type === 'REPLAY_START') {
            replaying.value = true; receivedSeq = 0; outputSeq.value = 0
            queuedWrites = queuedWrites.then(() => { if (current === generation) display.reset() })
          } else if (frame.type === 'OUTPUT') {
            if (typeof frame.data !== 'string' || !Number.isSafeInteger(frame.outputSeq) || frame.outputSeq !== receivedSeq + 1) throw new Error('终端输出不连续，停止输入并完整重连。')
            receivedSeq = frame.outputSeq
            queuedWrites = queuedWrites.then(async () => {
              if (current !== generation) return
              await display.write(frame.data!)
              if (current !== generation) return
              outputSeq.value = frame.outputSeq!; send({ type: 'OUTPUT_ACK', ...epochs(), outputSeq: frame.outputSeq })
            }).catch(fail)
          } else if (frame.type === 'REPLAY_END') {
            queuedWrites = queuedWrites.then(() => { if (current !== generation) return; if (!stateReceived) throw new Error('缺少终端状态，不能恢复输入。'); replaying.value = false; retries = 0 }).catch(fail)
          } else if (frame.type === 'INPUT_ACK') {
            if (frame.writerEpoch !== terminal.value?.writerEpoch) return
            if (!Number.isSafeInteger(frame.inputSeq) || frame.inputSeq === undefined || frame.inputSeq < 1 || frame.inputSeq > inputSeq) throw new Error('输入确认序号超出本窗口实际发送范围，已停止输入，请核对原终端。')
            for (const seq of pending) if (seq <= frame.inputSeq) pending.delete(seq)
            unconfirmedInputs.value = pending.size
          } else if (frame.type === 'ATTACHMENT_REQUEST') {
            if (frame.requestId && frame.writerEpoch !== undefined && frame.writerEpoch === terminal.value?.writerEpoch && canWrite.value) attachmentRequest.value = { requestId: frame.requestId, writerEpoch: frame.writerEpoch, source: frame.source }
          } else if (frame.type === 'AUTHORIZATION_REQUEST') {
            if (frame.requestId && frame.writerEpoch !== undefined && frame.writerEpoch === terminal.value?.writerEpoch && canWrite.value) authorizationRequest.value = { requestId: frame.requestId, writerEpoch: frame.writerEpoch, operation: frame.operation || '', command: frame.command || '', arguments: frame.arguments || '', reason: frame.reason || '原命令请求额外授权。', approvalId:frame.approvalId, directoryContextId:frame.directoryContextId, version:frame.version, preview:frame.preview, decision:frame.decision, feedback:frame.feedback, revisedArguments:frame.revisedArguments, inputHash:frame.inputHash, frozenTargets:frame.frozenTargets, taskId:frame.taskId, taskSessionId:frame.taskSessionId, taskVersion:frame.taskVersion }
          } else if (frame.type === 'EXPORT_READY') {
            if (frame.sessionId !== terminal.value?.sessionId || !Array.isArray(frame.artifacts) || frame.artifacts.length > 64 || frame.artifacts.some(item => !item || !/^[1-9][0-9]*$/.test(item.id) || typeof item.fileName !== 'string')) throw new Error('终端导出通知的归属或产物信息无效。')
            const known = new Set(exportArtifacts.value.map(item => item.id)); exportArtifacts.value = [...exportArtifacts.value, ...frame.artifacts.filter(item => !known.has(item.id))].slice(-64)
          } else if (frame.type === 'INPUT_REJECTED') {
            throw new Error(`原 CLI 未接受输入（${frame.reason || '输入序列或进程状态不符'}）。请核对会话与终端，未确认输入不会自动重发。`)
          } else if (frame.type === 'ERROR' || frame.type === 'FAILED') {
            error.value = frame.message || frame.reason || '终端托管失败，请核对 Runner。'
          }
        } catch (cause) { fail(cause) }
      }
      ws.onerror = () => { if (current === generation) error.value = '终端连接中断；未确认按键不会自动重发。' }
      ws.onclose = event => {
        if (current !== generation) return
        connected.value = false; canWrite.value = false; replaying.value = false; clearInterval(heartbeat)
        if (unconfirmedInputs.value) error.value = `有 ${unconfirmedInputs.value} 个输入包未确认，请先核对终端画面与会话账本。它们不会自动重发。`
        else if (!error.value) error.value = '网页已分离；原 CLI 和后台任务的状态请在重新连接后核对。'
        if (!suspended && !ended.has(terminal.value?.state ?? '') && event.code !== 1008 && retries < 3) { const delay = 1000 * 2 ** retries++; timer = setTimeout(() => void connect(), delay) }
      }
    } catch (cause) { if (current === generation) { error.value = errorText(cause); replaying.value = false } }
  }
  function fail(cause: unknown) { error.value = errorText(cause); suspended = true; closeTransport() }
  async function open(sessionId: string, renderer: PcTerminalRenderer, cols: number, rows: number, terminalId?: string) {
    if (busy.value) return
    busy.value = true; error.value = ''; const opening = generation
    try {
      if (terminal.value?.sessionId === sessionId && !ended.has(terminal.value.state)) { if (!connected.value) await connect(); return }
      const opened = terminalId && !terminal.value ? (await getTerminal(terminalId)).data : (await createTerminal({ sessionId, renderer, cols, rows })).data
      if (opening !== generation) return
      if (opened.sessionId !== sessionId) throw new Error('此终端不属于当前编码会话。')
      dispose(); terminal.value = opened; retries = 0; await connect()
    } catch (cause) { if (opening === generation) error.value = errorText(cause) } finally { busy.value = false }
  }
  function input(data: string, binary = false) {
    if (!writable.value || !data || ((attachmentRequest.value || authorizationRequest.value) && data !== '\u0003')) return false
    if (new TextEncoder().encode(data).length > 16384) { error.value = '单个终端输入包超过 16KB，请缩减后再次输入。'; return false }
    if (pending.size >= 256 || (socket?.bufferedAmount ?? 0) > 65536) { error.value = '终端输入尚未确认，请等待确认后继续，避免重复输入。'; return false }
    const seq = inputSeq + 1
    if (!send({ type: 'INPUT', ...epochs(), inputSeq: seq, ...(binary ? { encoding: 'base64', dataBase64: btoa(data) } : { data }) })) return false
    inputSeq = seq
    pending.add(seq); unconfirmedInputs.value = pending.size; return true
  }
  function resize(cols: number, rows: number) { if (writable.value) send({ type: 'RESIZE', ...epochs(), cols: Math.max(20, Math.min(400, cols)), rows: Math.max(5, Math.min(120, rows)) }) }
  async function claim() { const id = terminal.value?.terminalId, expectedWriterEpoch = terminal.value?.writerEpoch; if (!id || expectedWriterEpoch === undefined || busy.value) return; busy.value = true; error.value = ''; try { const result = await claimTerminal(id, clientId, expectedWriterEpoch); if (id !== terminal.value?.terminalId) return; terminal.value = result.data; attachmentRequest.value = undefined; authorizationRequest.value = undefined; pending.clear(); unconfirmedInputs.value = 0; inputSeq = result.data.inputSeq; if (!connected.value) await connect() } catch (cause) { if (id === terminal.value?.terminalId) error.value = errorText(cause) } finally { busy.value = false } }
  async function stop() { const id = terminal.value?.terminalId; if (!id || busy.value) return; busy.value = true; error.value = ''; try { const stopped = (await stopTerminal(id)).data; if (id === terminal.value?.terminalId) { terminal.value = stopped; canWrite.value = false } } catch (cause) { if (id === terminal.value?.terminalId) error.value = errorText(cause) } finally { busy.value = false } }
  async function attachments(artifactIds: string[], state: 'READY' | 'FAILED' | 'CANCELLED' = 'READY') {
    const request = attachmentRequest.value, id = terminal.value?.terminalId; if (!request || !id || !writable.value) return false
    error.value = ''
    try { await completeTerminalAttachments(id, { clientId, writerEpoch: request.writerEpoch, requestId: request.requestId, state, artifactIds }); if (attachmentRequest.value?.requestId === request.requestId) attachmentRequest.value = undefined; return true } catch (cause) { error.value = errorText(cause); return false }
  }
  async function authorize(decision: 'APPROVE' | 'REJECT') {
    const request = authorizationRequest.value, id = terminal.value?.terminalId; if (!request || !id || !writable.value || busy.value) return false
    busy.value = true; error.value = ''
    try { await decideTerminalAuthorization(id, { clientId, writerEpoch: request.writerEpoch, requestId: request.requestId, decision }); if (authorizationRequest.value?.requestId === request.requestId) authorizationRequest.value = undefined; return true } catch(cause) { error.value = errorText(cause); return false } finally { busy.value = false }
  }
  onBeforeUnmount(detach)
  return { terminal, error, busy, connected, replaying, canWrite, writable, outputSeq, unconfirmedInputs, attachmentRequest, authorizationRequest, exportArtifacts, open, connect, input, resize, claim, stop, attachments, authorize, detach, dispose }
}
