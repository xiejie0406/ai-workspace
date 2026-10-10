import { get, list, post, put, pathId, newKey } from './client'
import type { Conversation, Id, Message, Query } from '@/types/paismart'
export const listConversations = (params: Query, signal?: AbortSignal) => list<Conversation>('/conversations', params, signal)
type ConversationResponse = Conversation & { space_id?: Id | null; row_version?: number; updated_at?: string }
const normalizeConversation = (row: ConversationResponse): Conversation => ({ ...row, spaceId: row.spaceId ?? row.space_id ?? null, rowVersion: row.rowVersion ?? row.row_version ?? 0, updatedAt: row.updatedAt ?? row.updated_at })
export const createConversation = async (title: string, spaceId?: Id) => {
  const response = await post<ConversationResponse>('/conversations', { title, ...(spaceId === undefined ? {} : { spaceId }) }, newKey())
  return { ...response, data: normalizeConversation(response.data) }
}
export const conversationMessages = (id: Id, params: Query = { pageNum: 1, pageSize: 100 }, signal?: AbortSignal) => list<Message>(`/conversations/${pathId(id)}/messages`, params, signal)
export const conversationAction = async (row: Conversation, action: 'archive' | 'switch' | 'unarchive') => {
  const response = await put<ConversationResponse>(`/conversations/${pathId(row.id)}/${action}`, { expectedVersion: row.rowVersion }, row.rowVersion)
  return { ...response, data: normalizeConversation(response.data) }
}
