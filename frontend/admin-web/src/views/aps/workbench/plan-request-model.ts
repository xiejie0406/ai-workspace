import type { ApsSiteContext } from '@/api/aps/context'
import type { ApsCreatePlanRequestInput } from '@/types/aps/planning'
import { siteTimeToUtc } from '@/components/aps/shared/site-time'
export function businessPlanInput(site:ApsSiteContext,workshopIds:string[],orderIds:string[],start:string,end:string,maxSolveSeconds:number):ApsCreatePlanRequestInput {
  if(!site.siteCode || !workshopIds.length || !orderIds.length)throw new Error('请选择站点范围、车间和已释放订单')
  const startAt=siteTimeToUtc(start,site.zoneId),endAt=siteTimeToUtc(end,site.zoneId)
  if(endAt<=startAt)throw new Error('排程结束须晚于开始')
  const now=new Date().toISOString()
  return {siteCode:site.siteCode,workshopIds:[...workshopIds],orderIds:[...orderIds],capturedAt:now,definitionRevision:0,executionRevision:0,
    horizon:{startAt,detailEndAt:endAt,endAt,planningAnchorAt:now,timeUnitSeconds:60,displayTimeZone:site.zoneId},
    parameters:{maxSolveSeconds,randomSeed:1,solverSearchThreads:1,absoluteGapLimit:0,relativeGapLimit:0}}
}
export function issueLocation(type:string,id:string):string {
  const value=encodeURIComponent(id)
  if(['RESOURCE','PERSON','MACHINE'].includes(type))return `/aps/resources?resourceId=${value}`
  if(['WORKSHOP','WORK_CENTER'].includes(type))return `/aps/resources?${type==='WORKSHOP'?'workshopId':'centerId'}=${value}`
  if(type.startsWith('ORDER')||['TASK','LOT','MATERIAL_DEMAND'].includes(type))return `/aps/orders?objectId=${value}`
  return `/aps/routes?objectId=${value}`
}
export function planStatusLabel(status: string): string {
  return ({ DRAFT: '等待求解', SOLVING: '正在求解', FEASIBLE: '可行候选', CONFLICT: '约束冲突',
    PUBLISHED: '当前正式计划', SUPERSEDED: '历史正式计划', CANCEL_REQUESTED: '正在取消',
    CANCELLED: '已取消', FAILED: '求解失败' } as Record<string, string>)[status] || status
}
