import request from '@/utils/request'
import type { PcEnvelope } from '@/types/paicli'
import type { PcTerminal, PcTerminalRenderer, PcTerminalTicket } from '@/types/paicli-terminal'
const base = '/paicli/v1/terminals'
const url = (id: string) => `${base}/${encodeURIComponent(id)}`
export const createTerminal = (data: { sessionId: string; renderer: PcTerminalRenderer; cols: number; rows: number }) => request({ url: base, method: 'post', data, headers: { repeatSubmit: false } }) as unknown as Promise<PcEnvelope<PcTerminal>>
export const getTerminal = (id: string) => request({ url: url(id), method: 'get' }) as unknown as Promise<PcEnvelope<PcTerminal>>
export const terminalTicket = (id: string, clientId: string) => request({ url: `${url(id)}/tickets`, method: 'post', data: { clientId }, headers: { repeatSubmit: false } }) as unknown as Promise<PcEnvelope<PcTerminalTicket>>
export const claimTerminal = (id: string, clientId: string, expectedWriterEpoch: number) => request({ url: `${url(id)}/claim`, method: 'post', data: { clientId, expectedWriterEpoch }, headers: { repeatSubmit: false } }) as unknown as Promise<PcEnvelope<PcTerminal>>
export const stopTerminal = (id: string) => request({ url: `${url(id)}/stop`, method: 'post', data: { cancelTask: false }, headers: { repeatSubmit: false } }) as unknown as Promise<PcEnvelope<PcTerminal>>
export const completeTerminalAttachments = (id: string, data: { clientId: string; writerEpoch: number; requestId: string; state: 'READY' | 'FAILED' | 'CANCELLED'; artifactIds: string[] }) => request({ url: `${url(id)}/attachments`, method: 'post', data, headers: { repeatSubmit: false } }) as unknown as Promise<PcEnvelope<unknown>>
export const decideTerminalAuthorization = (id: string, data: { clientId: string; writerEpoch: number; requestId: string; decision: 'APPROVE' | 'REJECT' }) => request({ url: `${url(id)}/authorizations`, method: 'post', data, headers: { repeatSubmit: false } }) as unknown as Promise<PcEnvelope<unknown>>
/** 和共享 request 使用同一 API 前缀，不携带 JWT，不接受任意 WebSocket 主机。 */
export function terminalStreamUrl() {
  const endpoint = new URL(`${String(import.meta.env.VITE_APP_BASE_API || '').replace(/\/$/, '')}/paicli/v1/terminal-stream`, window.location.origin)
  if (endpoint.origin !== window.location.origin) throw new Error('终端连接必须经过当前若依站点的同源代理。')
  endpoint.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return endpoint.href
}
