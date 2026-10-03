import { fashionRequest, type FashionQuery } from './client'
import type { FashionCustomer, RuoYiResult } from './types'

export type CustomerView = FashionCustomer & { salespersonName?: string | null }
export interface CustomerPage { items: CustomerView[]; total: number; page: number; pageSize: number }
export interface CustomerQuery extends FashionQuery { status?: string; keyword?: string; page: number; pageSize: number }
export interface CustomerBody {
  code?: string
  name: string
  customerType: 'group_purchase' | 'wholesale'
  contactName?: string
  contactPhone?: string
  region?: string
  salespersonId?: number
  collaboratorIds: number[]
  internalNote?: string
  rowVersion?: number
}

export const listCustomers = (params: CustomerQuery, signal?: AbortSignal) =>
  fashionRequest<RuoYiResult<CustomerPage>>({ path: 'customers', params, signal })
export const createCustomer = (body: CustomerBody) =>
  fashionRequest<RuoYiResult<FashionCustomer>, CustomerBody>({ path: 'customers', method: 'post', body })
export const updateCustomer = (id: string, body: CustomerBody) =>
  fashionRequest<RuoYiResult<FashionCustomer>, CustomerBody>({ path: `customers/${id}`, method: 'put', body })
export const archiveCustomer = (id: string, rowVersion: number) =>
  fashionRequest<RuoYiResult<FashionCustomer>, { rowVersion: number }>({ path: `customers/${id}/archive`, method: 'put', body: { rowVersion } })
