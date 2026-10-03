import { call, get, list, pathId, post } from './client'
import type { Id, Query, Result, Upload, Document } from '@/types/paismart'
export const listUploads=(params:Query,signal?:AbortSignal)=>list<Upload>('/uploads',params,signal)
export const supportedUploadTypes=()=>get<{extension:string;mimeType:string}[]>('/uploads/supported-types')
export const createUpload = (data: { filename: string; byteSize: number; sha256: string; spaceId: Id; visibility: string }, key: string) => post<Upload>('/uploads', { ...data, requestKey: key }, key)
export const uploadStatus = (id: Id) => get<Upload>(`/uploads/${pathId(id)}/status`)
export const putChunk = (id: Id, n: number, data: Blob, sha256: string, signal?: AbortSignal) => call<Result<Upload>>(`/uploads/${pathId(id)}/chunks/${n}`, { method: 'put', data, signal, timeout: 60_000, headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': sha256, repeatSubmit: false } })
export const mergeUpload = (id: Id, key: string) => post<Document>(`/uploads/${pathId(id)}/merge`, { requestKey: key }, key)
