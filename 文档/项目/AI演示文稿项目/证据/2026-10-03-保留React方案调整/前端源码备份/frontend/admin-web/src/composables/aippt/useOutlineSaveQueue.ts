import {commandErrorStatus} from '@/composables/aippt/commandError'
import { reactive } from 'vue'
import { cloneJson } from '@/views/aippt/render/clone'
import type { Outline,OutlinePage } from '@/types/aippt'
/** 编辑中的空字段留作草稿，不发送必然被服务端拒绝的请求。 */
export function outlineDraftError(pages:OutlinePage[]):string|undefined {
 if(pages.length<5||pages.length>20)return '提纲需要5–20页，请调整后保存'
 if(pages.some(page=>!page.title.trim()||!page.objective.trim()))return '请补齐页面标题与目的后保存'
 if(pages.some(page=>page.key_points.length<2||page.key_points.length>5||page.key_points.some(point=>!point.trim())))return '请填写每页2–5条非空要点后保存'
}
export function createOutlineSaveQueue(initial:Outline,save:(body:{pages:OutlinePage[];revision:number;input_revision:number;page_count:number},key:string)=>Promise<Outline>) {
 const state=reactive({outline:cloneJson(initial),draft:null as OutlinePage[]|null,saving:false,error:'',paused:false,conflict:false});let active:Promise<void>|null=null,flushing:Promise<void>|null=null,pending:{pages:OutlinePage[];revision:number;input_revision:number;page_count:number;key:string}|null=null,sender=save
 function update(pages:OutlinePage[]){state.draft=cloneJson(pages)}
 function replace(outline:Outline){if(!state.draft&&!state.saving&&!state.paused)state.outline=cloneJson(outline)}
 function flush(inputRevision:number){if(flushing)return flushing;flushing=runFlush(inputRevision).finally(()=>{flushing=null});return flushing}
 async function runFlush(inputRevision:number){if(active)await active;if(state.paused)throw new Error(state.error);while(state.draft){const incomplete=outlineDraftError(state.draft);if(incomplete)throw new Error(incomplete);const intent=pending||{pages:cloneJson(state.draft),revision:state.outline.revision,input_revision:inputRevision,page_count:state.draft.length,key:crypto.randomUUID()};pending=intent;state.saving=true
  active=(async()=>{try{const next=await sender({pages:intent.pages,revision:intent.revision,input_revision:intent.input_revision,page_count:intent.page_count},intent.key);state.outline=cloneJson(next);if(JSON.stringify(state.draft)===JSON.stringify(intent.pages))state.draft=null;pending=null;state.error=''}catch(e:any){state.error=e?.message||'提纲保存失败';state.paused=true;state.conflict=commandErrorStatus(e)===409;throw e}finally{state.saving=false;active=null}})();await active
 }}
 function retry(server?:Outline){if(server){state.outline=cloneJson(server);pending=null}state.paused=false;state.error='';state.conflict=false}
 function discard(server:Outline){state.outline=cloneJson(server);state.draft=null;pending=null;state.paused=false;state.error='';state.conflict=false}
 function setSender(fn:typeof save){sender=fn}
 return{state,update,replace,flush,retry,discard,setSender}
}
export type OutlineSaveQueue=ReturnType<typeof createOutlineSaveQueue>
