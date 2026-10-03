import {commandErrorStatus} from '@/composables/aippt/commandError'
import { cloneJson } from '@/views/aippt/render/clone'
import { reactive } from 'vue'
import type { Slide } from '@/types/aippt'
export type SaveIntent=((current:Slide,context:{idempotencyKey:string})=>Promise<Slide>) & {preserveHistoryBeforeOnRebase?:boolean}
export type HistorySaveIntent=(current:Slide,snapshot:Slide,context:{idempotencyKey:string})=>Promise<Slide>
export function createSlideSaveQueue(initial:Slide,onCommit:(slide:Slide)=>void=()=>{}) {
 const state=reactive({slide:cloneJson(initial),pending:0,status:'saved' as 'saved'|'saving'|'failed'|'conflict',error:'',draft:null as Slide|null,history:[] as Slide[],future:[] as Slide[]})
 type Item={intent:SaveIntent;resolve:(v:Slide)=>void;reject:(e:unknown)=>void;draft?:Slide;history:boolean;key:string;navigation?:'undo'|'redo';restore?:HistorySaveIntent;target?:Slide;before?:Slide}
 let tail=Promise.resolve(),committed=onCommit,disposed=false,activeItem:Item|null=null;const pending:Item[]=[]
 function run(){if(disposed||state.status==='conflict'||state.status==='failed')return;const item=pending.shift();if(!item)return;activeItem=item;state.status='saving'
  tail=tail.then(async()=>{const before=cloneJson(state.slide);if(item.history&&!item.before)item.before=cloneJson(before);try{
   if(item.navigation&&!item.target){const source=item.navigation==='undo'?state.history:state.future;const target=source[source.length-1];if(target)item.target=cloneJson(target)}
   if(item.target)state.draft=cloneJson(item.target)
   const context={idempotencyKey:item.key},next=item.navigation?item.target?await item.restore!(before,item.target,context):before:await item.intent(before,context)
   if(disposed)return;
   // 历史意图与普通编辑同队列，成功后同步移栈；失败不消耗任何历史。
   if(item.navigation&&item.target){const source=item.navigation==='undo'?state.history:state.future,destination=item.navigation==='undo'?state.future:state.history;source.pop();destination.push(before);if(destination.length>50)destination.shift()}
   else if(item.history){state.history.push(item.before||before);if(state.history.length>50)state.history.shift();state.future=[]}
   state.slide=next;state.pending--;state.draft=pending.length?cloneJson(pending[pending.length-1].draft||next):null;committed(next);activeItem=null;state.status='saved';item.resolve(next);run()
  }catch(error:any){if(disposed)return;state.error=error?.message||'保存失败，请保留草稿后重试';state.status=commandErrorStatus(error)===409?'conflict':'failed';activeItem=null;pending.unshift(item);item.reject(error)}})
 }
 function enqueue(intent:SaveIntent,draft?:Slide,history=true){if(disposed)return Promise.reject(new Error('编辑会话已关闭'));state.pending++;if(draft)state.draft=cloneJson(draft);const result=new Promise<Slide>((resolve,reject)=>pending.push({intent,resolve,reject,draft:draft?cloneJson(draft):undefined,history,key:crypto.randomUUID()}));if(state.status==='saved')run();return result}
 function navigateHistory(direction:'undo'|'redo',restore:HistorySaveIntent){if(disposed)return Promise.reject(new Error('编辑会话已关闭'));state.pending++;const result=new Promise<Slide>((resolve,reject)=>pending.push({intent:async current=>current,resolve,reject,history:false,key:crypto.randomUUID(),navigation:direction,restore}));if(state.status==='saved')run();return result}
 async function flush(){while(state.pending){await tail;if(state.status==='failed'||state.status==='conflict')throw new Error(state.error);if(disposed)throw new Error('编辑会话已关闭')}}
 function retry(server?:Slide){if(state.status==='saving')return;if(server){state.slide=cloneJson(server);pending.forEach(p=>{p.key=crypto.randomUUID();if(!p.intent.preserveHistoryBeforeOnRebase)p.before=undefined})}state.status='saved';state.error='';run()}
 function discard(server:Slide){if(state.status==='saving')throw new Error('请等待正在保存的操作结束');pending.splice(0).forEach(p=>p.reject(new Error('已放弃未保存操作')));state.pending=0;state.slide=cloneJson(server);state.draft=null;state.status='saved';state.history=[];state.future=[];committed(server)}
 function replaceServer(slide:Slide){if(!state.pending&&slide.revision>=state.slide.revision){if(slide.revision>state.slide.revision){state.history=[];state.future=[]}state.slide=cloneJson(slide);committed(slide)}}
 function setOnCommit(fn:(slide:Slide)=>void){committed=fn}
 function dispose(){disposed=true;activeItem?.reject(new Error('编辑会话已关闭'));activeItem=null;pending.splice(0).forEach(p=>p.reject(new Error('编辑会话已关闭')));state.pending=0;state.draft=null;state.history=[];state.future=[]}
 return {state,enqueue,navigateHistory,flush,retry,discard,replaceServer,setOnCommit,dispose}
}
export type SlideSaveQueue=ReturnType<typeof createSlideSaveQueue>
