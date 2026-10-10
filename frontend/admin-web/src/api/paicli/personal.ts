import request from '@/utils/request'
import type { PcEnvelope, PcPersonalContext, PcDirectoryContext, PcPersonalSettings, PcTask, PcEmbeddingRelease } from '@/types/paicli'
const base = '/paicli/v1/personal'
export const getPersonalContext = () => request({ url: `${base}/context`, method: 'get' }) as unknown as Promise<PcEnvelope<PcPersonalContext>>
export const preparePersonalContext = (key: string) => request({ url: `${base}/bootstrap`, method: 'post', data: {}, headers: { 'Idempotency-Key': key, repeatSubmit: false } }) as unknown as Promise<PcEnvelope<PcPersonalContext>>
export const listPersonalDirectories = () => request({ url: `${base}/directories`, method: 'get' }) as unknown as Promise<PcEnvelope<PcDirectoryContext[]>>
export const requestPersonalDirectory = (path:string,key:string) => request({url:`${base}/directories`,method:'post',data:{path},headers:{'Idempotency-Key':key,repeatSubmit:false}}) as unknown as Promise<PcEnvelope<PcTask>>
export const savePersonalSettings = (data:Pick<PcPersonalSettings,'modelId'|'searchConfig'|'embeddingConfig'|'embeddingMode'|'embeddingReleaseId'>,version:number,key:string) => request({url:`${base}/settings`,method:'put',data,headers:{'If-Match':String(version),'Idempotency-Key':key,repeatSubmit:false}}) as unknown as Promise<PcEnvelope<PcPersonalContext>>
export const listEmbeddingReleases = () => request({url:`${base}/embedding/releases`,method:'get'}) as unknown as Promise<PcEnvelope<PcEmbeddingRelease[]>>
export const verifyPersonalEmbedding = (version:number,configurationVersion:number,configFingerprint:string,key:string) => request({url:`${base}/embedding/verify`,method:'post',data:{confirmCost:true,configurationVersion,configFingerprint},headers:{'If-Match':String(version),'Idempotency-Key':key,repeatSubmit:false}}) as unknown as Promise<PcEnvelope<PcTask>>
export const selectPersonalEmbedding = (data:{embeddingMode:'AUTO'|'PLATFORM'|'DISABLED';embeddingReleaseId?:string|null},version:number,key:string) => request({url:`${base}/embedding/selection`,method:'put',data,headers:{'If-Match':String(version),'Idempotency-Key':key,repeatSubmit:false}}) as unknown as Promise<PcEnvelope<PcPersonalContext>>
