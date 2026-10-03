import request from '@/utils/request'
import {businessErrorResponse} from './requestOptions'
import type { PcEnvelope, PcEventBatch } from '@/types/paicli'
/** 共享 request 持有认证；有限长轮询可取消，无 token URL、无独立认证栈。 */
export const getSessionEvents = (sessionId: string, after: string, signal: AbortSignal) => request({ url: `/paicli/v1/sessions/${encodeURIComponent(sessionId)}/events`, method: 'get', params: { after, limit: 200, waitSeconds: 20 }, timeout: 25000, signal, ...businessErrorResponse }) as unknown as Promise<PcEnvelope<PcEventBatch>>

