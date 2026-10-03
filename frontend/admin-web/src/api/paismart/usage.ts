import { get, list } from './client'
import type { Query, Usage, UsageOverview, UsageRecord } from '@/types/paismart'
export const myUsage = () => get<Usage>('/usage/me')
export const usageOverview = (params: Query) => get<UsageOverview>('/usage/overview', params)
export const usageRecords = (params: Query, signal?: AbortSignal) => list<UsageRecord>('/usage/records', params, signal)
