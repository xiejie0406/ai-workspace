import {commandErrorStatus} from '@/composables/aippt/commandError'
import { ref, computed, onBeforeUnmount } from 'vue'
import { api } from '@/api/aippt'
import type { Task } from '@/types/aippt'
const TERMINAL=new Set(['completed','failed','cancelled','succeeded','ready','partial'])
interface EventDelivery {status:'connected'|'degraded'|'disabled';reason:'REDIS_CONNECTED'|'REDIS_UNAVAILABLE'|'REDIS_DISABLED'}
const DELIVERY_REASONS={connected:'REDIS_CONNECTED',degraded:'REDIS_UNAVAILABLE',disabled:'REDIS_DISABLED'} as const
export function useTaskEvents(onEvent:(event:Record<string,unknown>)=>void) {
 const delivery=ref<EventDelivery|null>(null),deliveryMessage=computed(()=>delivery.value?.status==='degraded'?'Redis通知暂不可用，正在定时读取已保存进度':delivery.value?.status==='disabled'?'Redis通知未启用，正在定时读取已保存进度':'')
 const task=ref<Task|null>(null),disconnected=ref(false),error=ref('');let controller:AbortController|null=null,generation=0,disposed=false,seq=0,failures=0,timer:ReturnType<typeof setTimeout>|undefined,currentId='',scope:'task'|'deck'='task'
 function stop(){generation++;controller?.abort();controller=null;clearTimeout(timer);delivery.value=null}
 function receiveDelivery(data:Record<string,any>,own:number){if(own!==generation||disposed)return;const value=data.delivery;if(value&&typeof value.reason==='string'&&DELIVERY_REASONS[value.status as EventDelivery['status']]===value.reason)delivery.value={status:value.status,reason:value.reason}}
 function watch(id:string,nextScope:'task'|'deck'='task'){stop();scope=nextScope;if(!id||disposed)return;currentId=id;task.value=null;disconnected.value=false;seq=0;failures=0;error.value='';const own=generation;void connect(id,own)}
 function schedule(id:string,own:number){if(disposed||own!==generation)return;disconnected.value=true;const delay=Math.min(15000,1000*2**Math.min(failures++,4));timer=setTimeout(()=>connect(id,own),delay)}
 async function snapshot(id:string,own:number){if(own!==generation||disposed)return;const fresh=scope==='task'?await api.task(id):await api.deck(id);if(own!==generation||disposed)return;task.value=fresh as Task;onEvent({type:'snapshot',task:fresh,status:fresh.status});return TERMINAL.has(String(fresh.status||(fresh as Task).state))}
 function dispatch(data:Record<string,any>,eventId:number,own:number){if(own!==generation||disposed)return false;const next=Number(data.eventSeq||data.seq||eventId||0);if(next&&next<=seq)return false;seq=Math.max(seq,next);task.value=data.data?.snapshot||data.task||data.data||data as Task;onEvent(data);return scope==='task'&&TERMINAL.has(String(data.status||data.state||data.task?.status))}
 async function connect(id:string,own:number){if(disposed||own!==generation)return;const local=new AbortController();controller=local;let reader:ReadableStreamDefaultReader<Uint8Array>|undefined;let buffer='';const decoder=new TextDecoder()
  try{const stream=await api.events(id,seq,local.signal,scope);if(own!==generation||disposed){await stream.cancel();return}reader=stream.getReader();disconnected.value=false
   while(own===generation&&!disposed&&!local.signal.aborted){const part=await reader.read();buffer+=decoder.decode(part.value,{stream:!part.done});buffer=buffer.replace(/\r\n/g,'\n');if(buffer.length>1024*1024)throw new Error('任务事件超过允许大小，请重新读取状态');let index:number
    while((index=buffer.indexOf('\n\n'))>=0){const packet=buffer.slice(0,index);buffer=buffer.slice(index+2);const content=packet.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trimStart()).join('\n');if(!content)continue;const data=JSON.parse(content);const eventName=packet.match(/^event:\s*(.+)$/m)?.[1];receiveDelivery(data,own);if(eventName==='delivery'||data.type==='delivery')continue;if(eventName==='resync_required'||data.type==='resync_required'){const settled=await snapshot(id,own);if(own!==generation||disposed)return;if(settled){stop();return}seq=Number(data.eventSeq||data.seq||0);continue}if(dispatch(data,Number(packet.match(/^id:\s*(\d+)/m)?.[1]||0),own)){stop();return}if(scope==='deck'&&TERMINAL.has(String(data.state||data.status))&&await snapshot(id,own)){stop();return}failures=0}
    if(part.done)break
   }
   if(own===generation&&!disposed&&!local.signal.aborted){if(!await snapshot(id,own))schedule(id,own)}
  }catch(e:any){if(local.signal.aborted||own!==generation||disposed||e?.name==='AbortError'||e?.code==='ERR_CANCELED')return;const status=commandErrorStatus(e);if([401,403,404].includes(Number(status))){error.value='会话已失效或无权读取该任务，请按平台提示重新登录或返回项目';disconnected.value=true;return}error.value=e?.message||'任务进度连接中断';disconnected.value=true;try{if(await snapshot(id,own)){stop();return}}catch(snapshotError:any){if(own!==generation||disposed)return;const snapshotStatus=commandErrorStatus(snapshotError);if([401,403,404].includes(Number(snapshotStatus))){error.value='会话已失效或无权读取该任务，请按平台提示重新登录或返回项目';return}}schedule(id,own)
  }finally{try{await reader?.cancel()}catch{}reader?.releaseLock();if(controller===local)controller=null}
 }
 function reconnect(){if(currentId&&!disposed){stop();failures=0;const own=generation;void connect(currentId,own)}}
 onBeforeUnmount(()=>{disposed=true;stop()});return {task,disconnected,error,delivery,deliveryMessage,watch,stop,reconnect}
}
