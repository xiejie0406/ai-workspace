import request from '@/utils/request'
export interface ApsSiteContext { siteCode: string; zoneId: string }
export interface ApsUserCandidate { userId: string; userName: string; displayName: string }
export const getSiteContext = () => request({ url: '/api/aps/v1/site', method: 'get' }) as Promise<ApsSiteContext>
export const listUserCandidates = (keyword = '') => request({ url: '/api/aps/v1/resources/user-candidates', method: 'get', params: { keyword } }) as Promise<ApsUserCandidate[]>
