import { onBeforeUnmount, ref, shallowRef } from 'vue'
import { createTicket, getGeneration } from '@/api/paismart/chat'
import { newKey } from '@/api/paismart/client'
import type { Id, Reference, StreamEvent } from '@/types/paismart'
import { errorText } from './usePage'
const generationTerminal=(state:string)=>['COMPLETE','COMPLETED','STOPPED','INTERRUPTED','FAILED','DONE'].includes(state)
export function websocketAddress(base: string, ticket: string, returned?: string): string {
  const url = new URL(returned || `${base.replace(/\/$/, '')}/paismart/ws/chat`, location.origin)
  if (!['http:', 'https:', 'ws:', 'wss:'].includes(url.protocol) || url.hostname !== location.hostname) throw new Error('问答连接地址不在当前受信服务范围')
  url.protocol = url.protocol === 'https:' || url.protocol === 'wss:' ? 'wss:' : 'ws:'
  url.searchParams.set('ticket', ticket); return url.href
}
export function applyStreamEvent(previous: number, event: StreamEvent): boolean { return Number.isSafeInteger(event.sequence) && event.sequence > previous }
export function useChatStream(onFinished: () => void) {
  const state = ref('idle'), text = ref(''), error = ref(''), sequence = ref(0), generationId = ref<Id>(), conversationId = ref<Id>(), references = shallowRef<Reference[]>([]), tools = ref<{name:string;state:string}[]>([])
  let socket: WebSocket | undefined, heartbeat: ReturnType<typeof setInterval> | undefined, disposed = false
  let opening: Promise<WebSocket> | undefined
  let epoch = 0
  function close() { if (heartbeat) clearInterval(heartbeat); heartbeat = undefined; socket?.close(); socket = undefined }
  // 切换页面内会话只撤销本地订阅；不停止或重发服务端已有生成。
  function reset(id?: Id) { epoch++; close(); opening=undefined;state.value='idle';text.value='';error.value='';sequence.value=0;generationId.value=undefined;conversationId.value=id;references.value=[];tools.value=[] }
  async function connect(): Promise<WebSocket> {
    if (socket?.readyState === WebSocket.OPEN) return socket
    if (opening) return opening
    const version=epoch
    const pending = (async () => {
      state.value = 'connecting'; const ticket = (await createTicket()).data; if (disposed||version!==epoch) throw new Error('会话连接已撤销')
      const ws = new WebSocket(websocketAddress(import.meta.env.VITE_APP_BASE_API || '', ticket.ticket, ticket.url)); socket = ws
      return await new Promise<WebSocket>((resolve, reject) => {
        const timeout = setTimeout(() => { ws.close(); reject(new Error('问答连接超时，请重试')) }, 10000)
        let opened=false
        ws.onopen = () => { if(disposed||version!==epoch||socket!==ws){clearTimeout(timeout);ws.close();reject(new Error('会话连接已撤销'));return}opened=true;clearTimeout(timeout); heartbeat = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send('__chat_ping__') }, 20000); resolve(ws) }
        ws.onerror = () => { clearTimeout(timeout); reject(new Error('问答连接失败，请检查服务状态')) }
        ws.onclose = () => { clearTimeout(timeout);if(!opened)reject(new Error('问答连接在建立前已关闭'));if(socket!==ws||version!==epoch)return;if (heartbeat) clearInterval(heartbeat); heartbeat = undefined; if (!disposed && ['connecting', 'generating', 'stopping'].includes(state.value)) { state.value = 'disconnected'; error.value = '连接已中断，可恢复当前生成' } }
        ws.onmessage = ({ data }) => {
          if(socket!==ws||disposed||version!==epoch)return
          if (data === '__chat_pong__' || data === '__chat_ping__') return
          try {
            const event = JSON.parse(String(data)) as StreamEvent & {lastSequence?:number}
            if(event.conversationId&&conversationId.value&&String(event.conversationId)!==String(conversationId.value))return
            if(event.generationId&&Number.isSafeInteger(event.lastSequence)&&!event.kind&&!event.type){
              if(generationId.value&&String(event.generationId)!==String(generationId.value))return
              generationId.value=event.generationId;conversationId.value=event.conversationId??conversationId.value;sequence.value=event.lastSequence!;text.value=event.text??'';references.value=event.references??[]
              if(generationTerminal(event.state??'')){state.value=event.state==='STOPPED'?'stopped':event.state==='COMPLETE'?'done':'failed';onFinished()}else state.value='generating'
              return
            }
            if (!event.generationId || !Number.isSafeInteger(event.sequence)) return
            if (generationId.value && String(event.generationId) !== String(generationId.value)) return
            if (!applyStreamEvent(sequence.value, event)) return
            sequence.value = event.sequence; generationId.value = event.generationId; if (event.conversationId) conversationId.value = event.conversationId
            const kind = event.type || event.kind
            if (kind === 'delta' || kind === 'content' || kind === 'summary') text.value += event.text || event.content || ''
            if (kind === 'snapshot') text.value = event.text || event.content || ''
            if (event.references) references.value = event.references
            if (event.tool) {const existing=tools.value.find(tool=>tool.name===event.tool);if(existing)existing.state=event.state??'RUNNING';else tools.value.push({name:event.tool,state:event.state??'RUNNING'})}
            if (kind === 'error') { state.value = 'failed'; error.value = event.errorMessage || '生成失败，请查看用量和服务状态'; onFinished() }
            else if (['done', 'completed', 'stopped'].includes(kind || '')) { state.value = kind === 'stopped' ? 'stopped' : 'done'; onFinished() }
            else state.value = 'generating'
          } catch { error.value = '服务端流事件格式异常，当前结果未确认完成' }
        }
      })
    })()
    opening=pending
    try { return await pending } finally { if(opening===pending)opening = undefined }
  }
  async function send(id: Id, message: string) {
    if (['connecting', 'generating', 'stopping'].includes(state.value)) return
    reset(id);const version=epoch
    try { const ws = await connect();if(disposed||version!==epoch)return; state.value = 'generating'; ws.send(JSON.stringify({ type: 'chat', clientRequestId: newKey(), conversationId: id, message })) }
    catch (e) { if(!disposed&&version===epoch){state.value = 'failed'; error.value = errorText(e)} }
  }
  async function resume(id?: Id) {
    if (!id && !generationId.value) return
    const version=epoch,target=id??generationId.value!
    try {
      error.value = ''; const generation = (await getGeneration(target)).data
      if(disposed||version!==epoch)return
      if(conversationId.value&&String(generation.conversationId)!==String(conversationId.value))throw new Error('恢复结果不属于当前会话')
      generationId.value = generation.generationId; conversationId.value = generation.conversationId
      if (generation.text !== undefined) { text.value = generation.text; sequence.value = generation.lastSequence }
      references.value = generation.references ?? references.value
      if(generationTerminal(generation.state)){state.value=generation.state==='STOPPED'?'stopped':generation.state==='COMPLETE'?'done':'failed';onFinished();return}
      const ws = await connect();if(disposed||version!==epoch)return; state.value = 'generating'; ws.send(JSON.stringify({ type: 'resume', generationId: generation.generationId, lastSequence: sequence.value }))
    } catch (e) { if(!disposed&&version===epoch){state.value = 'failed'; error.value = errorText(e)} }
  }
  async function stop() { const version=epoch,id=generationId.value;if(!id)return;try { const ws = await connect();if(disposed||version!==epoch)return; ws.send(JSON.stringify({ type: 'stop', generationId: id })); state.value = 'stopping' } catch (e) { if(!disposed&&version===epoch)error.value = errorText(e) } }
  onBeforeUnmount(() => { disposed = true; reset() })
  return { state, text, error, sequence, generationId, conversationId, references, tools, send, resume, stop, close, reset }
}
