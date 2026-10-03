import { call, get, list, pathId, post, remove, newKey } from './client'
import type { Document, Id, Query, Reference } from '@/types/paismart'
export const listDocuments = (params: Query, signal?: AbortSignal) => list<Document>('/documents', params, signal)
export const getDocument = (id: Id) => get<Document>(`/documents/${pathId(id)}`)
export const createText = (data: { title: string; body: string; spaceId: Id; visibility: string }, key: string = newKey()) => post<Document>('/documents/text', { ...data, requestKey: key }, key)
export const deleteDocument = (row: Document) => call<import('@/types/paismart').Result<Document>>(`/documents/${pathId(row.id)}`, { method: 'delete', params: { rowVersion: row.rowVersion }, headers: { 'If-Match': String(row.rowVersion) } })
export const documentAction = (row: Document, action: 'parse' | 'restore' | 'reindex' | 'vectorization' | 'retry') => { const key = newKey(); return post<Document>(`/documents/${pathId(row.id)}/${action}`, { contentVersion: row.contentVersion, rowVersion: row.rowVersion, expectedVersion: row.rowVersion, requestKey: key }, key) }
export const getDocumentContent = (id: Id, version: string|number, signal?: AbortSignal) => call<Blob>(`/documents/${pathId(id)}/content`, { params: { contentVersion: version }, responseType: 'blob', signal })
export const getDocumentPage = (id: Id, page: number, version: string|number, signal?: AbortSignal) => call<Blob>(`/documents/${pathId(id)}/pages/${page}`, { params: { contentVersion: version }, responseType: 'blob', signal })
export const downloadDocument = (id:Id,signal?:AbortSignal)=>call<Blob>(`/documents/${pathId(id)}/download`,{responseType:'blob',signal})
export const getReference = (id: Id, signal?: AbortSignal) => get<Reference>(`/references/${pathId(id)}`, undefined, signal)
