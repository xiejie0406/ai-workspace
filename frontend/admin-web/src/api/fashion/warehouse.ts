import { fashionRequest } from './client'
import type { RuoYiResult } from './types'

export interface FashionWarehouse {
  code: string
  name: string
  status: 'active' | 'inactive'
  isDefault: boolean
  stockSkuCount: number
  lastStockAsOf: string | null
  sort: number
  remark: string
}
export type WarehouseInput = Pick<FashionWarehouse, 'code' | 'name' | 'status' | 'sort' | 'remark'>
export const listWarehouses = () => fashionRequest<RuoYiResult<{ items: FashionWarehouse[] }>>({ path: 'warehouses' })
export const createWarehouse = (body: WarehouseInput) => fashionRequest<RuoYiResult<void>>({ path: 'warehouses', method: 'post', body })
export const updateWarehouse = (code: string, body: Omit<WarehouseInput, 'code'>) => fashionRequest<RuoYiResult<void>>({ path: `warehouses/${encodeURIComponent(code)}`, method: 'put', body: {code,...body} })
export const setWarehouseStatus = (code: string, status: FashionWarehouse['status']) => fashionRequest<RuoYiResult<void>>({ path: `warehouses/${encodeURIComponent(code)}/status`, method: 'put', body: { status } })
export const setDefaultWarehouse = (code: string) => fashionRequest<RuoYiResult<void>>({ path: `warehouses/${encodeURIComponent(code)}/default`, method: 'put' })
export function activeWarehouseOptions(items: FashionWarehouse[]) {
  return items.filter(item => item.status === 'active').map(item => ({ label: `${item.name}（${item.code}）`, value: item.code, isDefault: item.isDefault }))
}
