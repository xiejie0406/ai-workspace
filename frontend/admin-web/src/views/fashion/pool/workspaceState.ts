import type {PoolConditionDraft,PoolSelection} from '@/api/fashion/poolTypes'
import {conditionRequest,restoreConditionDraft} from './conditionState'

/** 保存期间仍有新编辑时，只接回服务端版本号，保留尚未提交的新选择。 */
export function savedSelection(current:PoolSelection,saved:PoolSelection,issuedRevision:number,currentRevision:number) {
  return issuedRevision===currentRevision?{selection:saved,dirty:false}:{selection:{...current,poolId:saved.poolId,rowVersion:saved.rowVersion},dirty:true}
}
export function draftCacheValue(form:PoolConditionDraft,poolId?:string) {
  return JSON.stringify({version:1,poolId:poolId??null,request:conditionRequest(form)})
}
/** 浏览器只保留未提交查询输入；池快照、已选方案和草稿版本仍由Java读取。 */
export function readDraftCache(value:string|null):{poolId:string|null;form:PoolConditionDraft}|undefined {
  if(!value)return
  try {
    const raw=JSON.parse(value)
    if(raw.version!==1||raw.poolId!==null&&typeof raw.poolId!=='string'||!raw.request||typeof raw.request.title!=='string'||!Array.isArray(raw.request.groups)||!Array.isArray(raw.request.draftGroups)||raw.request.draftGroups.length!==4||![2,3,4].includes(raw.request.activeGroupCount)||!Array.isArray(raw.request.inheritance)||raw.request.inheritance.length!==4)return
    return {poolId:raw.poolId,form:restoreConditionDraft(raw.request)}
  } catch {return}
}
