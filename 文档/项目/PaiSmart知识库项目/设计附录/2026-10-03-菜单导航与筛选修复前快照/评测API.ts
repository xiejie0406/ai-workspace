import { get, list, post, pathId, newKey } from './client'
import type { Conflict, Evaluation, EvaluationReport, Id, IndexRevision, Job, Query, Review } from '@/types/paismart'
import {normalizeConflict,normalizeIndex} from './adapters'
export const listConflicts = async (params: Query, signal?: AbortSignal) => {const response=await list<Conflict>('/conflicts',params,signal);return {...response,rows:response.rows.map(normalizeConflict)}}
export const resolveConflict = (row: Conflict, action: string, note: string) => post<Job>(`/conflicts/${pathId(row.id)}/resolve`, { action, note, expectedVersion: row.rowVersion }, newKey())
export const listIndexes = async (signal?: AbortSignal) => {const response=await list<IndexRevision>('/indexes',{pageNum:1,pageSize:100},signal);return {...response,rows:response.rows.map(normalizeIndex)}}
export const rebuildIndex = (data: { targetProvider: string; fresh: boolean; expectedVersion?: number }) => post<Job>('/indexes/rebuild', data, newKey())
export const rollbackIndex = (row: IndexRevision) => post<Job>('/indexes/rollback', { indexId: row.id, expectedVersion: row.rowVersion }, newKey())
export const recoverIndex=()=>post<Job>('/indexes/recover',{},newKey())
export const listEvaluations = (params: Query, signal?: AbortSignal) => list<Evaluation>('/evaluations', params, signal)
export const createEvaluation = (data: { label: string; inlineJsonl: string; topK: number; spaceId?: Id }) => post<Job>('/evaluations', data, newKey())
export const evaluationReport = (id: Id, signal?: AbortSignal) => get<EvaluationReport>(`/evaluations/${pathId(id)}/report`, undefined, signal)
export const listReviews = (id: Id, caseId?: Id) => list<Review>(`/evaluations/${pathId(id)}/reviews`, { caseId, pageNum: 1, pageSize: 100 })
export const saveReview = (id: Id, data: { caseId: Id; conclusion: string; reason: string; expectedVersion: number;score?:number }) => post<Review>(`/evaluations/${pathId(id)}/reviews`, data, newKey())
