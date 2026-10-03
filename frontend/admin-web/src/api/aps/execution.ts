import request from '@/utils/request'
import type {
  ApsAdvanceExecutionPhaseInput,
  ApsChangeExecutionResourceInput,
  ApsCorrectProductionReportInput,
  ApsCreateExecutionRunInput,
  ApsCreateProductionReportInput,
  ApsExecutionRunDetail,
  ApsQualityDecisionInput,
  ApsQuantityMovementInput,
  ApsTransitionExecutionInput
} from '@/types/aps/execution'

const runs = '/api/aps/v1/execution-runs'
export interface ApsDispatchPlan {planVersionId:string;jobs:Array<{jobId:string;jobCode:string;plannedQty:number;remainingQty:number;uomCode:string}>}
export const listDispatchPlans=()=>request({url:`${runs}/dispatch-plans`,method:'get'}) as Promise<Array<{planVersionId:string;versionNo:number;versionName:string}>>
export const getDispatchPlan=(id:string)=>request({url:`${runs}/dispatch-plans/${id}`,method:'get'}) as Promise<ApsDispatchPlan>
export interface ApsRunOption {run:import('@/types/aps/execution').ApsExecutionRun;jobCode:string;orderNumbers:string;taskNames:string}
export interface ApsReportMember {id:string;memberNo:number;taskId:string;taskName:string;plannedQty:number;uomCode:string;remainingQty:number}
export interface ApsMovementTarget {materialDemandId:string;targetTaskId:string;orderNo:string;taskName:string;remainingDemandQty:number;reservedQty:number;uomCode:string;executionRuns:ApsRunOption[]}
export interface ApsResourceReplacement {resourceId:string;code:string;name:string}
export const listExecutionRuns=(params: {keyword?:string;status?:string;resourceId?:string}={})=>request({url:runs,method:'get',params}) as Promise<ApsRunOption[]>
export const getReportMembers=(id:string)=>request({url:`${runs}/${id}/report-members`,method:'get'}) as Promise<ApsReportMember[]>
export const getResourceReplacements=(id:string,replacedResourceId:string)=>request({url:`${runs}/${id}/resource-candidates`,method:'get',params:{replacedResourceId}}) as Promise<ApsResourceReplacement[]>
export const getMovementTargets=(id:string,lotId:string)=>request({url:`${runs}/${id}/output-lots/${lotId}/movement-targets`,method:'get'}) as Promise<ApsMovementTarget[]>
export const getQuantityEvents=(id:string,lotId:string)=>request({url:`${runs}/${id}/output-lots/${lotId}/quantity-events`,method:'get'}) as Promise<Array<{id:string;eventType:string;quantity:number;uomCode:string;occurredAt:string;reversalOfId?:string;reason?:string}>>

export const createExecutionRun = (idempotencyKey: string, data: ApsCreateExecutionRunInput) => request({
  url: runs,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>

export const getExecutionRun = (executionRunId: string) =>
  request({ url: `${runs}/${executionRunId}`, method: 'get' }) as Promise<ApsExecutionRunDetail>

export const transitionExecutionRun = (executionRunId: string, data: ApsTransitionExecutionInput) => request({
  url: `${runs}/${executionRunId}/transitions`, method: 'post', data
}) as Promise<ApsExecutionRunDetail>

export const changeExecutionResource = (
  executionRunId: string,
  idempotencyKey: string,
  data: ApsChangeExecutionResourceInput
) => request({
  url: `${runs}/${executionRunId}/resource-changes`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>

export const advanceExecutionPhase = (
  executionRunId: string,
  idempotencyKey: string,
  data: ApsAdvanceExecutionPhaseInput
) => request({
  url: `${runs}/${executionRunId}/phase-advances`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>

export const createProductionReport = (
  executionRunId: string,
  idempotencyKey: string,
  data: ApsCreateProductionReportInput
) => request({
  url: `${runs}/${executionRunId}/reports`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>

export const correctProductionReport = (
  reportId: string,
  idempotencyKey: string,
  data: ApsCorrectProductionReportInput
) => request({
  url: `/api/aps/v1/production-reports/${reportId}/corrections`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>

export const decideOutputLotQuality = (
  outputLotId: string,
  idempotencyKey: string,
  data: ApsQualityDecisionInput
) => request({
  url: `/api/aps/v1/output-lots/${outputLotId}/quality-decisions`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>

export const moveOutputLotQuantity = (
  outputLotId: string,
  idempotencyKey: string,
  data: ApsQuantityMovementInput
) => request({
  url: `/api/aps/v1/output-lots/${outputLotId}/quantity-movements`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsExecutionRunDetail>
