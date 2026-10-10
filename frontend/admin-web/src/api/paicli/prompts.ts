import request from '@/utils/request'
import type { PcEnvelope, PcPromptTemplate } from '@/types/paicli'
export const listPromptTemplates=(directoryContextId:string)=>request({url:'/paicli/v1/prompt-templates',method:'get',params:{directoryContextId}}) as unknown as Promise<PcEnvelope<PcPromptTemplate[]>>
export const getPromptTemplate=(id:string)=>request({url:`/paicli/v1/prompt-templates/${encodeURIComponent(id)}`,method:'get'}) as unknown as Promise<PcEnvelope<PcPromptTemplate>>
export const savePromptTemplate=(data:Partial<PcPromptTemplate>,key:string)=>request({url:'/paicli/v1/prompt-templates',method:'post',data,headers:{'If-Match':String(data.version||0),'Idempotency-Key':key,repeatSubmit:false}}) as unknown as Promise<PcEnvelope<PcPromptTemplate>>
