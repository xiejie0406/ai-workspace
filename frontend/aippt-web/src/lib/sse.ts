import { requestStream } from '@/api/client'
/** 中心 SSE 仍由全站唯一 client 鉴权；解析有界、支持取消与分片。 */
export async function consumeEventStream<T>(path: string, signal: AbortSignal, onEvent: (event: T) => void | Promise<void>) {
  const response = await requestStream(path, signal)
  if (!response.body) throw new Error('SSE 未返回事件流')
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
  const parse = async () => {
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0 && !signal.aborted) {
      const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2)
      const data = frame.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n')
      if (data) await onEvent(JSON.parse(data) as T)
      boundary = buffer.indexOf('\n\n')
    }
  }
  const cancel = () => { void reader.cancel().catch(() => {}) }
  signal.addEventListener('abort', cancel, { once: true })
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) { buffer += decoder.decode(); await parse(); return }
      buffer += decoder.decode(value, { stream: true }); buffer = buffer.replace(/\r\n/g, '\n')
      if (buffer.length > 1024 * 1024) throw new Error('SSE 事件超出允许大小')
      await parse()
    }
  } finally { signal.removeEventListener('abort', cancel); await reader.cancel().catch(() => {}); reader.releaseLock() }
}
export function delay(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const abort = () => { clearTimeout(timer); resolve() }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, milliseconds)
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true })
  })
}
