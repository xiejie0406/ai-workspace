import request from '@/utils/request'
import type {
  ApsPlanListItem,
  ApsPlanRequestStatus,
  ApsCreatePlanRequestInput,
  ApsCreatePlanLockInput,
  ApsCreateAdjustmentInput,
  ApsAdjustmentAccepted,
  ApsCreateStructuralAdjustmentInput,
  ApsStructuralAdjustmentAccepted,
  ApsPublishPlanInput,
  ApsPlanPublished,
  ApsDiscardCandidateInput,
  ApsPlanCandidateDiscarded,
  ApsPlanVersionComparison,
  ApsPlanVersionDetail
} from '@/types/aps/planning'

const planVersions = '/api/aps/v1/plan-versions'
export interface ApsPlanReadiness { ready:boolean;checkedAt:string;inputHash?:string;issues:{code:string;objectType:string;objectId:string;field?:string;message:string}[] }
export const checkPlanReadiness = (data:ApsCreatePlanRequestInput) => request({url:'/api/aps/v1/readiness',method:'post',data}) as Promise<ApsPlanReadiness>

export const listPlanVersions = () => request({ url: planVersions, method: 'get' }) as Promise<ApsPlanListItem[]>
export const createPlanRequest = (idempotencyKey: string, data: ApsCreatePlanRequestInput) => request({
  url: '/api/aps/v1/plan-requests', method: 'post', headers: { 'Idempotency-Key': idempotencyKey }, data
}) as Promise<ApsPlanRequestStatus>
export const getPlanRequestStatus = (requestId: string) => request({
  url: `/api/aps/v1/plan-requests/${requestId}`, method: 'get'
}) as Promise<ApsPlanRequestStatus>
export const cancelPlanRequest = (requestId: string) => request({
  url: `/api/aps/v1/plan-requests/${requestId}/cancel`, method: 'post'
}) as Promise<ApsPlanRequestStatus>

export const getPlanVersionDetail = (planVersionId: string) =>
  request({ url: `${planVersions}/${planVersionId}`, method: 'get' }) as Promise<ApsPlanVersionDetail>

export const comparePlanVersion = (planVersionId: string, baseVersionId?: string) =>
  request({
    url: `${planVersions}/${planVersionId}/comparison`,
    method: 'get',
    params: baseVersionId ? { baseVersionId } : undefined
  }) as Promise<ApsPlanVersionComparison>

export const createPlanLock = (planVersionId: string, data: ApsCreatePlanLockInput) =>
  request({ url: `${planVersions}/${planVersionId}/locks`, method: 'post', data }) as Promise<ApsPlanVersionDetail>

export const deletePlanLock = (
  planVersionId: string,
  lockId: string,
  expectedPlanRowVersion: number,
  expectedLockRowVersion: number
) => request({
  url: `${planVersions}/${planVersionId}/locks/${lockId}`,
  method: 'delete',
  params: { expectedPlanRowVersion, expectedLockRowVersion }
}) as Promise<ApsPlanVersionDetail>

export const createPlanAdjustment = (
  planVersionId: string,
  idempotencyKey: string,
  data: ApsCreateAdjustmentInput
) => request({
  url: `${planVersions}/${planVersionId}/adjustments`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsAdjustmentAccepted>

export const createPlanStructuralAdjustment = (
  planVersionId: string,
  idempotencyKey: string,
  data: ApsCreateStructuralAdjustmentInput
) => request({
  url: `${planVersions}/${planVersionId}/structural-adjustments`,
  method: 'post',
  headers: { 'Idempotency-Key': idempotencyKey },
  data
}) as Promise<ApsStructuralAdjustmentAccepted>

export const publishPlanVersion = (planVersionId: string, data: ApsPublishPlanInput) =>
  request({ url: `${planVersions}/${planVersionId}/publish`, method: 'post', data }) as Promise<ApsPlanPublished>

export const discardPlanCandidate = (planVersionId: string, data: ApsDiscardCandidateInput) =>
  request({ url: `${planVersions}/${planVersionId}/discard`, method: 'post', data }) as Promise<ApsPlanCandidateDiscarded>
