<template>
  <section v-if="taskId" class="pc-panel" aria-label="配置操作的真实任务状态">
    <div class="pc-card-title"><h2>{{ title || '配置操作' }} · 任务 {{ taskId }}</h2><el-tag>{{ stateName(task?.status || 'ENQUEUED') }}</el-tag></div>
    <p class="pc-muted">{{ !canRead ? '当前角色没有后台任务读取权限，请由本人核对回执。' : waiting ? '正在查询中心回执，尚未确认完成。' : terminal ? '已取得中心状态；请继续核对工具结果与批准。' : '本页已停止自动等待，可刷新或到后台任务继续核对。' }}</p>
    <el-alert v-if="error || task?.failureReason" :title="error || task?.failureReason" type="error" :closable="false" />
    <el-collapse v-if="task?.tools.length || task?.events.length"><el-collapse-item title="实际工具与事件结果"><pre v-for="tool in task?.tools" :key="tool.id" class="pc-pre">{{ safeText({ tool:tool.toolName,status:tool.status,result:tool.result,error:tool.error }) }}</pre><pre v-for="event in task?.events" :key="event.eventId" class="pc-pre">{{ safeText({ type:event.type,payload:event.payload }) }}</pre></el-collapse-item></el-collapse>
    <div class="pc-actions"><el-button v-hasPermi="['paicli:task:read']" :loading="waiting" @click="check">刷新任务结果</el-button><router-link v-hasPermi="['paicli:task:read']" :to="{ path:'/paicli/tasks',query:{ directoryContextId,taskId } }">进入执行记录与确认</router-link></div>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getTask } from '@/api/paicli/tasks'
import { checkPermi } from '@/utils/permission'
import type { PcTaskDetail } from '@/types/paicli'
import { errorText, safeText, stateName, terminalTaskStates } from '../model'
const props = defineProps<{ taskId?: string; directoryContextId: string; title?: string }>(), emit = defineEmits<{ settled: [task: PcTaskDetail] }>()
const task = ref<PcTaskDetail>(), waiting = ref(false), error = ref(''), canRead = computed(()=>checkPermi(['paicli:task:read'])), terminal = computed(() => !!task.value && (terminalTaskStates.has(task.value.status) || task.value.status === 'RECONCILE_REQUIRED'))
let generation = 0, controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined
function stop() { generation++; controller?.abort(); if(timer) clearTimeout(timer); timer = undefined; waiting.value = false }
async function check() {
  stop(); if(!props.taskId || !canRead.value) return
  const id=props.taskId, workspace=props.directoryContextId, g=generation, abort=controller=new AbortController(); waiting.value=true; error.value=''
  try { for(let attempt=0;attempt<20;attempt++) { const response=await getTask(id,abort.signal); if(abort.signal.aborted || g!==generation || id!==props.taskId || workspace!==props.directoryContextId)return; task.value=response.data; if(terminal.value){ emit('settled',response.data);return }; await new Promise<void>(resolve=>{const finish=()=>{if(timer)clearTimeout(timer);abort.signal.removeEventListener('abort',finish);resolve()};timer=setTimeout(finish,2000);abort.signal.addEventListener('abort',finish,{once:true})}) } }
  catch(cause){if(g===generation&&!abort.signal.aborted)error.value=errorText(cause)}finally{if(g===generation)waiting.value=false}
}
watch(()=>`${props.directoryContextId}:${props.taskId || ''}`,()=>{task.value=undefined;void check()},{immediate:true})
onBeforeUnmount(stop)
</script>
