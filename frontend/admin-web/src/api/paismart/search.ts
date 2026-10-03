import { get } from './client'
import type { Id, SearchResult } from '@/types/paismart'
export const hybridSearch = async (query: string, topK: number, spaceId?: Id, signal?: AbortSignal) => {const response=await get<SearchResult>('/search/hybrid', { query, topK, spaceId }, signal);return {...response,data:{...response.data,hits:response.data.hits.map(hit=>({...hit,query,chunkId:hit.chunkId??hit.id,retrievalMode:hit.retrievalMode??hit.scoreType??response.data.scoreType}))}}}
