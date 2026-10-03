import { call, get, list, newKey, pathId, post, put, remove } from './client'
import type { Activity, Id, Invite, Job, Message, OperationPlan, OperationStatus, Provider, Query, RateLimits, Result } from '@/types/paismart'
import {normalizeJob} from './adapters'
export const operationStatus = () => get<OperationStatus>('/operations/status')
export const listProviders = () => list<Provider>('/providers', { pageNum: 1, pageSize: 100 })
export const getProvider = (scope: string) => get<Provider>(`/providers/${pathId(scope)}`)
export const saveProvider = (row: Provider) => call<Result<Provider>>(`/providers/${pathId(row.scope)}`, { method: 'put', data: row, headers: { 'If-Match': String(row.rowVersion), repeatSubmit: false } })
export const testProvider = (scope: string,id?:Id) => post<{ latencyMs: number; state: string; reason?: string }>(`/providers/${pathId(scope)}/test`, {id}, newKey())
export const getLimits = () => get<RateLimits>('/rate-limits')
export const saveLimits = (data: RateLimits) => put<RateLimits>('/rate-limits', data, data.rowVersion)
export const listInvites = (params: Query) => list<Invite>('/invites', params)
const inviteDates=(data:Partial<Invite>)=>({...data,expiresAt:data.expiresAt===undefined?undefined:data.expiresAt?new Date(data.expiresAt).toISOString():null})
export const saveInvite = (data: Partial<Invite>,key=newKey()) => data.id ? put<Invite>(`/invites/${pathId(data.id)}`, inviteDates(data), data.rowVersion) : post<Invite>('/invites', inviteDates(data), key)
export const batchInvites = (data: Partial<Invite> & { count: number },key=newKey()) => post<Invite[]>('/invites/batch', {...inviteDates(data),count:data.count}, key)
export const deleteInvite = (row: Invite) => remove<void>(`/invites/${pathId(row.id)}`, row.rowVersion)
export const listJobs = async (params: Query, signal?: AbortSignal) => {const response=await list<Job>('/operations/jobs',params,signal);return {...response,rows:response.rows.map(normalizeJob)}}
export const getJob = async (id: Id, signal?: AbortSignal, administrative=false) => {const response=await get<Job>(`${administrative?'/operations':''}/jobs/${pathId(id)}`,undefined,signal);return {...response,data:normalizeJob(response.data)}}
export const jobAction = async (id: Id, action: 'cancel' | 'retry') => {const response=await post<Job>(`/operations/jobs/${pathId(id)}/${action}`,{},newKey());return {...response,data:normalizeJob(response.data)}}
export const planOperation = (kind: string, data: Record<string, unknown>) => post<OperationPlan>(`/operations/${pathId(kind)}`, data, newKey())
export const getPlan = (id: Id) => get<OperationPlan>(`/operations/plans/${pathId(id)}`)
export const listPlans=(params:Query,signal?:AbortSignal)=>list<OperationPlan>('/operations/plans',params,signal)
export const approvePlan = (id: Id, version: string) => post<OperationPlan>(`/operations/plans/${pathId(id)}/approve`,{planVersion:version},newKey())
export const executePlan = async (id: Id, version: string) => {const response=await post<Job>(`/operations/plans/${pathId(id)}/execute`,{planVersion:version},newKey());return {...response,data:normalizeJob(response.data)}}
export const listActivities = (params: Query) => list<Activity>('/audit/activities', params)
export const auditConversations = (params: Query) => list<Record<string, unknown>>('/audit/conversations', params)
export const auditMessages=(id:Id,params:Query)=>list<Message>(`/audit/conversations/${pathId(id)}/messages`,params)
export const unresolvedModelCalls=()=>list<Record<string,unknown>>('/operations/model-calls',{})
export const unresolvedOcrCalls=()=>list<Record<string,unknown>>('/operations/ocr-calls',{})
export const reconcileModelCall=(id:Id,actualTokens:number,reason:string)=>post<Record<string,unknown>>(`/operations/model-calls/${pathId(id)}/reconcile`,{actualTokens,reason},newKey())
export const reconcileOcrCall=(id:Id,actualPages:number,reason:string)=>post<void>(`/operations/ocr-calls/${pathId(id)}/reconcile`,{actualPages,reason},newKey())
export const ocrHealth=()=>get<Record<string,unknown>>('/operations/ocr-health')
export const testOcrHealth=(file:File,key:string)=>{const data=new FormData();data.append('file',file);return call<Result<Record<string,unknown>>>('/operations/ocr-health',{method:'post',data,headers:{'Idempotency-Key':key,'Content-Type':'multipart/form-data',repeatSubmit:false},timeout:60000})}
