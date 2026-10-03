import {blankGroup,type PoolConditionDraft,type PoolFilterGroup,type PoolInheritance,type PoolRequest} from '@/api/fashion/poolTypes'

/** 四份原始条件只在工作单请求中持久化，活动组切换不移动或删除原值。 */
export const sharedConditionKeys: (keyof PoolFilterGroup)[] = ['quantity','warehouseCode','seasons','genders','brands','groupPriceMin','groupPriceMax','vipPriceMin','vipPriceMax','stockFreshness','requireAllSizes','hasMainImage','imagesConfirmed']
const clone = <T>(value:T):T => value===undefined?value:JSON.parse(JSON.stringify(value))
export function defaultInheritance():PoolInheritance {
  return Object.fromEntries(sharedConditionKeys.map(key=>[key,'follow']))
}
export function blankConditionDraft(defaultWarehouseCode=''):PoolConditionDraft {
  const totalConditions=blankGroup(0)
  totalConditions.label='总查询条件'
  totalConditions.warehouseCode=defaultWarehouseCode
  return {title:'',groups:[],priceBasis:'group',activeGroupCount:4,draftGroups:Array.from({length:4},(_,i)=>blankGroup(i)),totalConditions,inheritance:Array.from({length:4},defaultInheritance)}
}
export function effectiveGroup(group:PoolFilterGroup,total:PoolFilterGroup,inheritance:PoolInheritance):PoolFilterGroup {
  const result=clone(group)
  for(const [key,mode] of Object.entries(inheritance)) {
    if(mode==='follow'&&key!=='label')Object.assign(result,{[key]:clone(total[key as keyof PoolFilterGroup])})
  }
  return result
}
export function activeConditionGroups(form:PoolConditionDraft):PoolFilterGroup[] {
  return form.draftGroups.slice(0,form.activeGroupCount).map((group,i)=>effectiveGroup(group,form.totalConditions,form.inheritance[i]))
}
export function conditionRequest(form:PoolConditionDraft):PoolRequest {
  return {...clone(form),groups:activeConditionGroups(form)}
}
export function changeConditionMode(form:PoolConditionDraft,index:number,key:keyof PoolFilterGroup,mode:'follow'|'independent') {
  if(mode==='independent'&&form.inheritance[index][key]==='follow') {
    Object.assign(form.draftGroups[index],{[key]:clone(form.totalConditions[key])})
  }
  form.inheritance[index][key]=mode
}
export function restoreConditionDraft(request:PoolRequest):PoolConditionDraft {
  const result=blankConditionDraft()
  result.title=request.title;result.priceBasis=request.priceBasis;result.totalMin=request.totalMin;result.totalMax=request.totalMax
  const original=request.draftGroups??request.groups
  result.draftGroups=Array.from({length:4},(_,i)=>({...blankGroup(i),...clone(original[i]??blankGroup(i))}))
  result.activeGroupCount=request.activeGroupCount??(request.groups.length===3?3:request.groups.length>=4?4:2)
  result.totalConditions={...result.totalConditions,...clone(request.totalConditions??{})}
  // 历史请求没有主条件/继承关系，所有旧值保留为独立，不被新默认覆盖。
  result.inheritance=Array.from({length:4},(_,i)=>request.inheritance?.[i]?clone(request.inheritance[i]):{})
  result.groups=activeConditionGroups(result)
  return result
}
export function copyConditionGroup(form:PoolConditionDraft,source:number,target:number) {
  form.draftGroups[target]={...clone(form.draftGroups[source]),label:form.draftGroups[target].label}
  form.inheritance[target]=clone(form.inheritance[source])
}
export function resetConditionGroup(form:PoolConditionDraft,index:number) {
  form.draftGroups[index]=blankGroup(index);form.inheritance[index]=defaultInheritance()
}
