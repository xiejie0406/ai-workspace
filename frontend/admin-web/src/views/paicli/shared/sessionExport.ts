import {getExportSection,getSessionExport,getSessionLedger} from '@/api/paicli/sessions'
import type {PcExportSection} from '@/types/paicli'

const sections:PcExportSection[]=['events','plans','summaries','artifacts','toolCalls','approvals']
const MAX_BYTES=256*1024*1024, MAX_PAGES=4096
/** 全部续页成功后才交付文件；达到浏览器上限时明确停止，不能下载局部账本冒充完整导出。 */
export async function completeSessionExport(id:string,signal?:AbortSignal,progress?:(message:string)=>void):Promise<Blob>{
  const first=await getSessionExport(id,signal)
  if(first.schemaVersion!==1 || first.session?.id!==id || !first.sections)throw new Error('会话导出协议不完整，请核对中心版本。')
  const pieces:string[]=[],encoder=new TextEncoder();let bytes=0,pages=1
  function add(text:string){bytes+=encoder.encode(text).length;if(bytes>MAX_BYTES)throw new Error('完整导出超过浏览器 256MiB 上限，请使用分页账本与产物读取；尚未生成局部导出文件。');pieces.push(text)}
  function page(){if(signal?.aborted)throw new DOMException('读取已取消','AbortError');if(++pages>MAX_PAGES)throw new Error('导出续页超过 4096 批，请缩小读取范围；尚未生成局部导出文件。')}
  function entries(values:Record<string,unknown>[],state:{comma:boolean}){if(!Array.isArray(values))throw new Error('导出集合不是数组');for(const value of values){add(`${state.comma?',':''}${JSON.stringify(value)}`);state.comma=true}}
  add(`{"schemaVersion":1,"session":${JSON.stringify(first.session)},"endSequence":${first.endSequence},"rawPromptIncluded":${JSON.stringify(first.rawPromptIncluded)},"ledgerEntries":[`)
  const ledgerState={comma:false};entries(first.ledgerEntries,ledgerState)
  let cursor=first.ledgerCursor,more=first.ledgerHasMore
  while(more){page();progress?.(`正在收齐原始账本 · 第 ${pages} 批`);const next=(await getSessionLedger(id,cursor,first.endSequence,signal)).data;if(next.sessionId!==id||next.endSequence!==first.endSequence||next.rawPromptIncluded!==first.rawPromptIncluded||!Number.isSafeInteger(next.cursor)||next.cursor<=cursor)throw new Error('账本续页范围或游标不一致，完整导出已停止。');entries(next.entries,ledgerState);cursor=next.cursor;more=next.hasMore}
  add(']')
  for(const section of sections){const initial=first.sections[section];if(!initial)throw new Error(`中心缺少 ${section} 导出续页信息。`);add(`,"${section}":[`);const state={comma:false};entries(first[section],state);let after=String(initial.cursor),more=initial.hasMore;const end=String(initial.endId)
    while(more){page();progress?.(`正在收齐 ${section} · 第 ${pages} 批`);const next=(await getExportSection(id,section,after,end,signal)).data;const nextCursor=String(next.cursor);if(next.sessionId!==id||next.section!==section||String(next.endId)!==end||next.rawPromptIncluded!==first.rawPromptIncluded||!/^\d+$/.test(nextCursor)||!/^\d+$/.test(after)||BigInt(nextCursor)<=BigInt(after))throw new Error('元数据续页范围或游标不一致，完整导出已停止。');entries(next.entries,state);after=nextCursor;more=next.hasMore}
    add(']')
  }
  add('}');progress?.(`已收齐 ${pages} 批记录`);return new Blob(pieces,{type:'application/json;charset=utf-8'})
}
