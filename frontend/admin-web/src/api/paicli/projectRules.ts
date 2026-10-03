import request from '@/utils/request'
import type {PcEnvelope,PcProjectRule,PcRuleImport,PcRuleLayer,PcSourceArtifact} from '@/types/paicli'
export interface PcRuleInput {id?:string;workspaceId?:string;scope:'PERSONAL'|'PROJECT';layer:PcRuleLayer;sourcePath:string;content?:string;contentArtifactId?:string;imports:PcRuleImport[];enabled:boolean;version:number}
export const listProjectRules=(workspaceId:string)=>request({url:'/paicli/v1/project-rules',method:'get',params:{workspaceId}}) as unknown as Promise<PcEnvelope<PcProjectRule[]>>
export const getProjectRule=(id:string)=>request({url:`/paicli/v1/project-rules/${encodeURIComponent(id)}`,method:'get'}) as unknown as Promise<PcEnvelope<PcProjectRule>>
export const saveProjectRule=(data:PcRuleInput,key:string)=>request({url:'/paicli/v1/project-rules',method:'post',data,headers:{'If-Match':String(data.version),'Idempotency-Key':key,repeatSubmit:false}}) as unknown as Promise<PcEnvelope<PcProjectRule>>
export const uploadPaiSource=(workspaceId:string,file:File,key:string)=>{const data=new FormData();data.append('workspaceId',workspaceId);data.append('purpose','PAI_SOURCE');data.append('file',file);return request({url:'/paicli/v1/artifacts',method:'post',data,headers:{'Content-Type':'multipart/form-data',repeatSubmit:false,'Idempotency-Key':key}}) as unknown as Promise<PcEnvelope<PcSourceArtifact>>}
