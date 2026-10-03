import type { PcMessage, PcToolCall } from '@/types/paicli'
/** 原模型请求没有执行裁决；只适配展示名称/参数，不补造status或result。 */
export function messageTools(message:PcMessage):PcToolCall[]{
  return (message.toolCalls || []).map(call=>{
    if('function' in call){let args:unknown=call.function.arguments;try{args=JSON.parse(call.function.arguments)}catch{ /* 保留原字符串 */ };return {id:call.id,toolName:call.function.name,arguments:args}}
    return call
  })
}
