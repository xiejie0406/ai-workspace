import { get, list, post, put, remove, pathId, newKey } from './client'
import type { Id, Member, Query, Space } from '@/types/paismart'
export const listSpaces = (signal?: AbortSignal) => list<Space>('/spaces', { pageNum: 1, pageSize: 1000 }, signal)
export const createPersonalSpace = () => post<Space>('/spaces/personal', {}, newKey())
export const saveSpace = (data: Partial<Space>) => data.id ? put<Space>(`/spaces/${pathId(data.id)}`, data, data.rowVersion) : post<Space>('/spaces', data, newKey())
export const deleteSpace = (row: Space) => remove<void>(`/spaces/${pathId(row.id)}`, row.rowVersion)
export const listMembers = (params: Query, signal?: AbortSignal) => list<Member>('/members', params, signal)
export const saveMemberSpaces = (row: Member, assignments: {spaceId:Id;uploadAllowed:boolean;managerAllowed:boolean}[], primarySpaceId?: Id) => put<Member>(`/members/${pathId(row.id)}/spaces`, { assignments, primarySpaceId, rowVersion:row.rowVersion, expectedVersion: row.rowVersion }, row.rowVersion)
export const setPrimarySpace = (spaceId: Id) => put<void>('/members/me/primary-space', { spaceId })
export const grantCredits = (id: Id, data: { llmTokens: number; embeddingTokens: number; reason: string }, key: string) => post<void>(`/members/${pathId(id)}/credits`, { ...data, requestKey: key }, key)
export const myMembership = () => get<Member>('/members/me')
export const claimEligibility=(code:string)=>post<Member>('/members/me/eligibility',{code},newKey())
export const getQuotaPolicy=(id:Id)=>get<{mode:string;dailyLlmTokens:number;dailyEmbeddingTokens:number;rowVersion:number}>(`/members/${pathId(id)}/quota-policy`)
export const saveQuotaPolicy=(id:Id,data:{mode:string;dailyLlmTokens:number;dailyEmbeddingTokens:number;rowVersion:number})=>put<void>(`/members/${pathId(id)}/quota-policy`,data,data.rowVersion)
