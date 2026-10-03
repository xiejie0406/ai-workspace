import request from '@/utils/request'
import type { ApsExpansion, ApsItem, ApsOperation, ApsOrder, ApsOrderLine, ApsRouteGraph, ApsRouteMigrationDiff, ApsRouteValidation, ApsRouteVersion } from '@/types/aps/manufacturing'

const routing = '/api/aps/v1/routings'
const orders = '/api/aps/v1/orders'

export const listItems = () => request({ url: `${routing}/items`, method: 'get' }) as Promise<ApsItem[]>
export const saveItem = (value: Partial<ApsItem>) => request({ url: value.id ? `${routing}/items/${value.id}` : `${routing}/items`, method: value.id ? 'put' : 'post', data: value }) as Promise<ApsItem>
export const listOperations = () => request({ url: `${routing}/operations`, method: 'get' }) as Promise<ApsOperation[]>
export const saveOperation = (value: Partial<ApsOperation>) => request({ url: value.id ? `${routing}/operations/${value.id}` : `${routing}/operations`, method: value.id ? 'put' : 'post', data: value }) as Promise<ApsOperation>
export const activateOperation = (id: string, rowVersion: number) => request({ url: `${routing}/operations/${id}/activate`, method: 'post', data: { rowVersion } }) as Promise<ApsOperation>
export const listRoutes = (itemId?: string) => request({ url: `${routing}/routes`, method: 'get', params: { itemId } }) as Promise<ApsRouteVersion[]>
export const createRoute = (value: Partial<ApsRouteVersion>) => request({ url: `${routing}/routes`, method: 'post', data: value }) as Promise<ApsRouteVersion>
export const getRoute = (id: string) => request({ url: `${routing}/routes/${id}`, method: 'get' }) as Promise<ApsRouteGraph>
export const saveRouteGraph = (id: string, value: object) => request({ url: `${routing}/routes/${id}/graph`, method: 'put', data: value }) as Promise<ApsRouteGraph>
export const copyRoute = (id: string, versionNo: string, changeNote?: string) => request({ url: `${routing}/routes/${id}/copy`, method: 'post', data: { versionNo, changeNote } }) as Promise<ApsRouteGraph>
export const validateRoute = (id: string) => request({ url: `${routing}/routes/${id}/validation`, method: 'get' }) as Promise<ApsRouteValidation>
export const publishRoute = (id: string, rowVersion: number) => request({ url: `${routing}/routes/${id}/publish`, method: 'post', data: { rowVersion } }) as Promise<ApsRouteVersion>

export const listOrders = () => request({ url: orders, method: 'get' }) as Promise<ApsOrder[]>
export const saveOrder = (value: object, imported = false) => request({ url: imported ? `${orders}/import` : orders, method: 'post', data: value }) as Promise<ApsOrder>
export const updateDraftOrder = (id:string,rowVersion:number,order:object) => request({url:`${orders}/${id}`,method:'put',data:{rowVersion,order}}) as Promise<ApsOrder>
export const getExpansion = (id: string) => request({ url: `${orders}/${id}/expansion`, method: 'get' }) as Promise<ApsExpansion>
export const expandOrder = (id: string) => request({ url: `${orders}/${id}/expand`, method: 'post' }) as Promise<ApsExpansion>
export const releaseOrder = (id: string, rowVersion: number) => request({ url: `${orders}/${id}/release`, method: 'post', data: { rowVersion } }) as Promise<ApsOrder>
export const previewRouteMigration = (lineId: string, targetRouteVersionId: string) => request({ url: `${orders}/lines/${lineId}/route-migration`, method: 'get', params: { targetRouteVersionId } }) as Promise<ApsRouteMigrationDiff>
export const confirmRouteMigration = (lineId: string, value: object) => request({ url: `${orders}/lines/${lineId}/route-migration`, method: 'post', data: value }) as Promise<ApsOrderLine>
