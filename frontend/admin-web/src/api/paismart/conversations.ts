import { get, list, post, put, pathId, newKey } from './client'
import type { Conversation, Id, Message, Query } from '@/types/paismart'
export const listConversations = (params: Query, signal?: AbortSignal) => list<Conversation>('/conversations', params, signal)
export const createConversation = (title: string) => post<Conversation>('/conversations', { title }, newKey())
export const conversationMessages = (id: Id, params: Query = { pageNum: 1, pageSize: 100 }, signal?: AbortSignal) => list<Message>(`/conversations/${pathId(id)}/messages`, params, signal)
export const conversationAction = (row: Conversation, action: 'archive' | 'switch' | 'unarchive') => put<Conversation>(`/conversations/${pathId(row.id)}/${action}`, { expectedVersion: row.rowVersion }, row.rowVersion)
