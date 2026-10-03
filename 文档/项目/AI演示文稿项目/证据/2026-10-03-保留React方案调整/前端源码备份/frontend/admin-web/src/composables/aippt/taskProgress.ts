import type {Task} from '@/types/aippt'
/** 中心进度为结构体，旧任务的数值进度仍可展示。 */
export function taskProgress(task:Task|null|undefined){
 const progress=task?.progress,record=progress&&typeof progress==='object'?progress:undefined
 const value=Number(record?.percent??(typeof progress==='number'?progress:0))
 return{percent:Number.isFinite(value)?Math.round(Math.max(0,Math.min(100,value))):0,message:typeof record?.message==='string'?record.message:task?.message||''}
}
