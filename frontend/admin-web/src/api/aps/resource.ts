import request from '@/utils/request'
import type {
  ApsAvailability,
  ApsCandidate,
  ApsNetWindow,
  ApsReadinessIssue,
  ApsResource,
  ApsResourceSkill,
  ApsWorkCenter,
  ApsWorkshop
} from '@/types/aps/resource'

const base = '/api/aps/v1/resources'
export interface ApsDirectory {
  workshops: ApsWorkshop[]
  centers: ApsWorkCenter[]
  resources: Pick<ApsResource, 'id' | 'workshopId' | 'workCenterId' | 'code' | 'name' | 'type' | 'status'>[]
}
export const getResourceDirectory = () => request({ url: `${base}/directory`, method: 'get' }) as Promise<ApsDirectory>

export const listWorkshops = () => request({ url: `${base}/workshops`, method: 'get' }) as Promise<ApsWorkshop[]>
export const saveWorkshop = (value: Omit<ApsWorkshop, 'id'> & { id?: string }) => request({
  url: value.id ? `${base}/workshops/${value.id}` : `${base}/workshops`,
  method: value.id ? 'put' : 'post', data: value
}) as Promise<ApsWorkshop>

export const listWorkCenters = (workshopId: string) => request({
  url: `${base}/workshops/${workshopId}/centers`, method: 'get'
}) as Promise<ApsWorkCenter[]>
export const saveWorkCenter = (value: Omit<ApsWorkCenter, 'id'> & { id?: string }) => request({
  url: value.id ? `${base}/centers/${value.id}` : `${base}/centers`,
  method: value.id ? 'put' : 'post', data: value
}) as Promise<ApsWorkCenter>

export const listResources = (params: { workshopId?: string; workCenterId?: string }) => request({
  url: base, method: 'get', params
}) as Promise<ApsResource[]>
export const saveResource = (value: Omit<ApsResource, 'id'> & { id?: string }) => request({
  url: value.id ? `${base}/${value.id}` : base,
  method: value.id ? 'put' : 'post', data: value
}) as Promise<ApsResource>

export const listSkills = (resourceId: string) => request({
  url: `${base}/${resourceId}/skills`, method: 'get'
}) as Promise<ApsResourceSkill[]>
export const saveSkill = (resourceId: string, value: Partial<ApsResourceSkill>) => request({
  url: `${base}/${resourceId}/skills`, method: 'post', data: value
}) as Promise<ApsResourceSkill>

export const listAvailability = (resourceId: string) => request({
  url: `${base}/${resourceId}/availability`, method: 'get'
}) as Promise<ApsAvailability[]>
export const saveAvailability = (resourceId: string, value: Partial<ApsAvailability>) => request({
  url: `${base}/${resourceId}/availability`, method: 'post', data: value
}) as Promise<ApsAvailability>
export const listNetAvailability = (resourceId: string, from: string, to: string) => request({
  url: `${base}/${resourceId}/net-availability`, method: 'get', params: { from, to }
}) as Promise<ApsNetWindow[]>

export const listReadiness = (workshopId: string, at: string) => request({
  url: `${base}/workshops/${workshopId}/readiness`, method: 'get', params: { at }
}) as Promise<ApsReadinessIssue[]>
export const listCandidates = (workshopId: string, params: {
  skillCode: string; minimumLevel: number; start: string; end: string
}) => request({
  url: `${base}/workshops/${workshopId}/candidates`, method: 'get', params
}) as Promise<ApsCandidate[]>

export const exportPersonnelUrl = (workshopId?: string) => {
  const query = workshopId ? `?workshopId=${encodeURIComponent(workshopId)}` : ''
  return `${base}/personnel-export${query}`
}
