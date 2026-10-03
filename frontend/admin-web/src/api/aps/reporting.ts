import request from '@/utils/request'
export const getReportOrderCandidates=()=>request({url:'/api/aps/v1/reports/order-candidates',method:'get'}) as Promise<Array<{id:string;orderNo:string;customerName?:string;status:string}>>
import type {
  ApsDailyProductionReport,
  ApsDailyReportQuery,
  ApsLaborCapacityReport,
  ApsLaborReportQuery,
  ApsOrderDeliveryReport,
  ApsOrderReportQuery
} from '@/types/aps/reporting'

const reports = '/api/aps/v1/reports'

export const getDailyProductionReport = (params: ApsDailyReportQuery) => request({
  url: `${reports}/daily-production`, method: 'get', params: { ...params }
}) as Promise<ApsDailyProductionReport>

export const getLaborCapacityReport = (params: ApsLaborReportQuery) => request({
  url: `${reports}/labor-capacity`, method: 'get', params: { ...params }
}) as Promise<ApsLaborCapacityReport>

export const getOrderDeliveryReport = (params: ApsOrderReportQuery) => request({
  url: `${reports}/order-delivery`, method: 'get', params: { ...params }
}) as Promise<ApsOrderDeliveryReport>

export const exportDailyProductionReport = (params: ApsDailyReportQuery) => request({
  url: `${reports}/daily-production.csv`, method: 'get', params: { ...params }, responseType: 'blob'
}) as Promise<Blob>

export const exportLaborCapacityReport = (params: ApsLaborReportQuery) => request({
  url: `${reports}/labor-capacity.csv`, method: 'get', params: { ...params }, responseType: 'blob'
}) as Promise<Blob>

export const exportOrderDeliveryReport = (params: ApsOrderReportQuery) => request({
  url: `${reports}/order-delivery.csv`, method: 'get', params: { ...params }, responseType: 'blob'
}) as Promise<Blob>
