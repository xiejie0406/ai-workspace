<template><el-dialog v-model="visible" title="处理进度" width="580px"><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-descriptions v-if="job" :column="1" border><el-descriptions-item label="作业">{{ job.jobId }}</el-descriptions-item><el-descriptions-item label="状态">{{ stateLabel(job.state) }}</el-descriptions-item><el-descriptions-item v-if="job.checkpoint" label="断点">{{ job.checkpoint }}</el-descriptions-item><el-descriptions-item v-if="job.errorMessage" label="原因">{{ job.errorMessage }}</el-descriptions-item></el-descriptions><el-progress v-if="job?.progress !== undefined" :percentage="Math.min(100,Math.max(0,job.progress))"/><p class="ps-muted">受理成功仅表示作业已创建，最终结果以服务端状态为准。</p><template #footer><el-button @click="refresh" :loading="loading">刷新状态</el-button><el-button @click="visible=false">关闭</el-button></template></el-dialog></template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { getJob } from '@/api/paismart/operations'
import type { Job } from '@/types/paismart'
import { errorText, stateLabel, terminal } from '../composables/usePage'
const props=defineProps<{modelValue:boolean; initial?:Job;administrative?:boolean}>(),emit=defineEmits<{ 'update:modelValue':[boolean]; done:[] }>()
const visible=computed({get:()=>props.modelValue,set:v=>emit('update:modelValue',v)}),job=shallowRef<Job>(),error=ref(''),loading=ref(false)
let timer:ReturnType<typeof setTimeout>|undefined,controller:AbortController|undefined
async function refresh(){clearTimeout(timer);if(!job.value?.jobId)return;controller?.abort();controller=new AbortController();loading.value=true;error.value='';try{job.value=(await getJob(job.value.jobId,controller.signal,props.administrative)).data;if(terminal(job.value.state))emit('done')}catch(e){if(!controller.signal.aborted)error.value=errorText(e)}finally{loading.value=false;if(visible.value&&job.value&&!terminal(job.value.state)&&!error.value)timer=setTimeout(refresh,3000)}}
watch(()=>props.modelValue, v=>{clearTimeout(timer);controller?.abort();if(v){job.value=props.initial;void refresh()}}, {immediate:true})
onBeforeUnmount(()=>{clearTimeout(timer);controller?.abort()})
</script>
