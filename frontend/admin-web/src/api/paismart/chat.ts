import { get, post, pathId, newKey } from './client'
import type { FeedbackResult, Generation, Id } from '@/types/paismart'
export const createTicket = () => post<{ ticket: string; url?: string; expiresAt?: string }>('/chat/ws-ticket', {origin:location.origin}, newKey())
export const getGeneration = (id: Id) => get<Generation>(`/chat/generations/${pathId(id)}`)
export const getActiveGeneration = () => get<Generation | null>('/chat/active-generation')
export const sendFeedback = (messageId: Id, rating: 'good' | 'bad', reason: string, expectedVersion?: number) => post<FeedbackResult>('/chat/feedback', { messageId, rating, reason, ...(expectedVersion===undefined?{}:{expectedVersion}) }, newKey())
