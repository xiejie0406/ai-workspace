import { get, list, newKey, pathId, post, put, remove } from './client'
import type { Id, Order, Query, RechargePackage } from '@/types/paismart'
export const listPackages = () => list<RechargePackage>('/recharge/packages', { pageNum: 1, pageSize: 100 })
export const adminPackages = () => list<RechargePackage>('/recharge/admin/packages', { pageNum: 1, pageSize: 100 })
export const savePackage = (data: Partial<RechargePackage>) => data.id ? put<RechargePackage>(`/recharge/admin/packages/${pathId(data.id)}`, data, data.rowVersion) : post<RechargePackage>('/recharge/admin/packages', data, newKey())
export const deletePackage = (row: RechargePackage) => remove<void>(`/recharge/admin/packages/${pathId(row.id)}`, row.rowVersion)
export const createOrder = (data: { packageId?: Id; customAmountFen?: number }, key: string) => post<Order>('/recharge/orders', data, key)
export const getOrder = (tradeNo: string) => get<Order>(`/recharge/orders/${pathId(tradeNo)}`)
export const listOrders = (params:Query,signal?:AbortSignal) => list<Order>('/recharge/orders',params,signal)
