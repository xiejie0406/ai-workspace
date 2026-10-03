import type {Slide} from '@/types/aippt'
import type {SaveIntent} from './useSlideSaveQueue'
import {resolveInsertAnchor,wrapBlockIdsAsColumns,iterLeaves} from '@/views/aippt/editor/algorithms/flexTree'
import type {FlexNode} from '@/views/aippt/render/flexLayout'
import {cloneJson} from '@/views/aippt/render/clone'
import {commandErrorStatus} from './commandError'
type Sender=(slideId:string,action:'blocks'|'flex-layout',body:Record<string,unknown>,key:string)=>Promise<Slide>
type Command={action:'blocks'|'flex-layout';body:Record<string,unknown>;key:string;beforeIds:string[];rejected?:boolean}
function grouped(node:FlexNode,ids:string[]):boolean{if(node.type==='block')return false;if(node.type==='row'){const containing=node.children.filter(child=>iterLeaves(child).some(leaf=>ids.includes(leaf.block_id)));if(containing.length===ids.length&&containing.every(child=>child.type==='column'&&iterLeaves(child).filter(leaf=>ids.includes(leaf.block_id)).length===1)&&ids.every(id=>iterLeaves(node).some(leaf=>leaf.block_id===id)))return true}return node.children.some(child=>grouped(child,ids))}
export function createBlockInsertion(type:string,columns:number,selectedId:string|null,send:Sender,parentId?:string,index?:number,onProgress:(slide:Slide)=>void=()=>{}):SaveIntent{
 let actionKey='',next:Slide|undefined,anchor:{parentId:string;index:number}|undefined,pending:Command|undefined;const ids:string[]=[]
 async function execute(sid:string){const command=pending!;try{const result=await send(sid,command.action,cloneJson(command.body),command.key);if(command.action==='blocks'){const added=result.blocks.filter(block=>!command.beforeIds.includes(block.id));if(added.length!==1)throw new Error('新增块回执不完整，请读取正式页面核对');if(!ids.includes(added[0].id))ids.push(added[0].id)}pending=undefined;onProgress(result);return result}catch(e:any){if(commandErrorStatus(e)===409)command.rejected=true;throw e}}
 const intent:SaveIntent=async(s,c)=>{try{
  if(!s.layout_tree)throw new Error('请先解锁灵活布局')
  if(actionKey&&actionKey!==c.idempotencyKey){
   // 包列回执丢失时，最新正式树已满足目标，无需再包一次。
   if(ids.length===columns&&columns>1&&grouped(s.layout_tree,ids)){onProgress(s);return s}
   if(pending&&!pending.rejected){try{const result=await execute(s.id);next=result.revision>s.revision?result:s}catch(e){if(!pending?.rejected)throw e}}
   pending=undefined;next=next&&next.revision>s.revision?next:s;onProgress(next)
  }else next=next||s
  actionKey=c.idempotencyKey
  if(ids.some(id=>!next!.blocks.some(block=>block.id===id)))throw new Error('部分新增块已被移除，请核对正式页面后决定如何补充')
  anchor ||= parentId?{parentId,index:index??0}:resolveInsertAnchor(next.layout_tree!,selectedId)
  while(ids.length<columns){pending ||= {action:'blocks',body:{revision:next.revision,type,parent_id:anchor.parentId,index:anchor.index+ids.length},key:actionKey+'-'+ids.length,beforeIds:next.blocks.map(block=>block.id)};next=await execute(s.id)}
  if(columns>1&&!grouped(next.layout_tree!,ids)){if(!pending){const tree=wrapBlockIdsAsColumns(next.layout_tree!,ids);if(!tree)throw new Error('已有新增块无法组成连续多列，请核对位置后继续');pending={action:'flex-layout',body:{revision:next.revision,layout_tree:tree},key:actionKey+'-tree',beforeIds:[]}}next=await execute(s.id)}
  return next
 }catch(e:any){const position=ids.length<columns?`新增第${ids.length+1}列`:'组装列组';throw Object.assign(new Error(`${position}失败：${e.message||'请求失败'}。已确认${ids.length}个新增块保留，可核对草稿后重试或重新应用。`),{cause:e,status:commandErrorStatus(e),response:e.response})}}
 Object.defineProperty(intent,'preserveHistoryBeforeOnRebase',{get:()=>ids.length>0||!!pending&&!pending.rejected});return intent
}

