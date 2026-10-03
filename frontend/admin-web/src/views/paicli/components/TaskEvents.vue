<template>
  <section aria-label="任务完整事件分页"><p class="pc-muted">按当前任务过滤的事件 · 固定末尾 {{ page?.endSequence ?? '待读取' }} · 每批最多 200 条</p><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-table :data="page?.events || []" max-height="400"><el-table-column prop="sequence" label="序号" width="80" /><el-table-column prop="type" label="事件" width="150" /><el-table-column label="上报内容"><template #default="{row}"><pre class="pc-pre">{{safeText(row.payload)}}</pre><ArtifactEvidence :result="row.payload" :artifact-id="eventArtifactId(row.payload)" :session-id="task.sessionId" /></template></el-table-column></el-table><div class="pc-actions"><el-button :loading="busy" :disabled="position===0" @click="previous">上一批事件</el-button><el-button :loading="busy" :disabled="!page?.hasMore" @click="next">下一批事件</el-button><el-button :loading="busy" @click="start">刷新事件范围</el-button></div></section>
</template>
<script setup lang="ts">
import {onBeforeUnmount,ref,watch} from 'vue'
import {checkPermi} from '@/utils/permission'
import {getTaskEvents} from '@/api/paicli/tasks'
import type {PcEventBatch,PcTaskDetail} from '@/types/paicli'
import {safeText} from '../model'
import ArtifactEvidence from './ArtifactEvidence.vue'
import {useOperations} from '../useOperations'
const props=defineProps<{task:PcTaskDetail}>(),{busy,error,run}=useOperations(),page=ref<PcEventBatch>(),position=ref(0)
function eventArtifactId(payload:Record<string,unknown>){const id=payload.eventArtifactId;return typeof id === 'string' && /^\d+$/.test(id)?id:undefined}
let generation=0,offsets=['0'],controller:AbortController|undefined
async function load(after:string,end?:number){if(!checkPermi(['paicli:task:read']))return false;controller?.abort();controller=new AbortController();const id=props.task.id,g=++generation;let ok=false;await run(async()=>{const value=(await getTaskEvents(id,after,end,controller!.signal)).data;if(g===generation&&id===props.task.id){page.value=value;ok=true}});return ok}
async function start(){if(busy.value)return;if(await load('0')){position.value=0;offsets=['0']}}
async function next(){const current=page.value;if(!current?.hasMore||busy.value)return;if(await load(current.cursor,current.endSequence)){offsets[position.value+1]=current.cursor;position.value++}}
async function previous(){const current=page.value;if(!current||position.value===0||busy.value)return;if(await load(offsets[position.value-1]!,current.endSequence))position.value--}
watch(()=>props.task,task=>{generation++;controller?.abort();position.value=0;offsets=['0'];page.value={events:task.events,cursor:task.eventsCursor || '0',endSequence:task.endSequence,hasMore:!!task.eventsHasMore,terminal:['COMPLETED','FAILED','CANCELLED'].includes(task.status)}},{immediate:true});onBeforeUnmount(()=>{generation++;controller?.abort()})
</script>
