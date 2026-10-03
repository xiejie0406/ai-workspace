import { fashionRequest } from './client'
import type { RuoYiResult } from './types'

export interface BusinessTask {
  id:string;kind:'ai_run'|'try_on';taskType:string;relatedName:string;poolId?:string;combinationId?:string
  status:string;reviewStatus:string;adopted:boolean;createdAt:string;finishedAt?:string;productId?:string|null
  billingStatus:string;actualCost?:number|null;currency?:string;errorMessage?:string;detailPath:string
  ownerId?:string|number;ownerName?:string|null;sourceId?:string|null;submittedAt?:string|null
  providerCode?:string|null;model?:string|null;estimatedCost?:number|null
}
export interface ProposalDelivery {
  id:string;title:string;poolId:string;draftVersion:number;status:string;combinationCount:number
  pageCount?:number|null;fileSize?:number|null;downloadCount:number;createdAt:string;errorMessage?:string
  priceMode:'none'|'group'|'vip';imageMode:'original'|'model'
  poolTitle?:string|null;ownerId?:string|number;ownerName?:string|null;requestKey?:string
  customerId?:string|number|null;customerName?:string|null
}
/** 服务端白名单投影，只反映该任务提交时冻结的输入。 */
export interface FrozenTryOnProduct {
  productId?:string;mainSku?:string;mainSkuKey?:string;color?:string;visualVersion?:number;garment_position?:string
}
export interface ProposalImageTask {
  id:string;status:string;rowVersion:number;draftVersion:number;combinationId:string;stale:boolean
  errorMessage?:string|null;results:{status:string}[];billingStatus?:string;estimatedCost?:number|null;actualCost?:number|null;currency?:string
  coverage?:{supported:string[];original:string[];message?:string}
  providerCode?:string|null;model?:string|null;submittedAt?:string|null;finishedAt?:string|null;retryCount?:number;resultCount?:number
  frozenInputs?:{poolId?:string;draftVersion?:number;combinationId?:string;products?:FrozenTryOnProduct[]}
}
export interface ProposalExport {rowVersion:number;combinationIds:string[];priceMode:string;imageMode:string;adoptedTaskIds:Record<string,string>}
export interface ProposalDeliveryRequest {poolId:string;title:string;requestKey:string;export:ProposalExport}
export interface ProposalPage<T>{items:T[];total:number;page:number;pageSize:number}
export const listBusinessTasks=(page:number,pageSize:number,signal?:AbortSignal)=>fashionRequest<RuoYiResult<ProposalPage<BusinessTask>>>({path:'tasks',params:{page,pageSize},signal})
export const listProposalDeliveries=(page:number,pageSize:number,signal?:AbortSignal)=>fashionRequest<RuoYiResult<ProposalPage<ProposalDelivery>>>({path:'proposal-deliveries',params:{page,pageSize},signal})
export const createProposalDelivery=(body:ProposalDeliveryRequest)=>fashionRequest<RuoYiResult<ProposalDelivery>>({path:'proposal-deliveries',method:'post',body,timeoutMs:120000})
export const downloadProposalDelivery=(id:string)=>fashionRequest<Blob>({path:`proposal-deliveries/${id}/download`,responseType:'blob',timeoutMs:120000})
/** 任务聚合只展示服务端投影；详情也使用服务端已返回的受控相对路径。 */
export function getBusinessTaskDetail(path:string,signal?:AbortSignal){
  const relative=path.replace(/^\/fashion\//,'')
  if(!/^(ai\/runs\/[A-Za-z0-9_-]+|pools\/[A-Za-z0-9_-]+\/proposal\/images\/[A-Za-z0-9_-]+)$/.test(relative))throw new Error('任务详情地址无效，请刷新任务列表')
  return fashionRequest<RuoYiResult<Record<string,unknown>>>({path:relative,signal})
}
