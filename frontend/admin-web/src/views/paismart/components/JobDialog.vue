<template><el-dialog v-model="visible" title="处理进度" width="580px"><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-descriptions v-if="job" :column="1" border><el-descriptions-item label="作业">{{ job.jobId }}</el-descriptions-item><el-descriptions-item label="状态">{{ stateLabel(job.state) }}</el-descriptions-item><el-descriptions-item v-if="job.checkpoint" label="断点">{{ job.checkpoint }}</el-descriptions-item><el-descriptions-item v-if="job.errorMessage" label="原因">{{ job.errorMessage }}</el-descriptions-item></el-descriptions><template v-if="evaluationMode"><p v-if="loading&&!counts" class="ps-muted" role="status">正在读取评测计数…</p><el-alert v-if="countError" :title="countError" type="warning" :closable="false"/><el-descriptions v-if="counts" :column="1" border><el-descriptions-item label="总样本">{{ counts.total }}</el-descriptions-item><el-descriptions-item label="已处理">{{ counts.processed }}</el-descriptions-item><el-descriptions-item label="待处理">{{ counts.pending }}</el-descriptions-item><el-descriptions-item label="评测状态">{{ stateLabel(counts.state) }}</el-descriptions-item><el-descriptions-item v-if="counts.failed!==undefined" label="失败样本">{{ counts.failed }}</el-descriptions-item></el-descriptions><p v-if="counts" class="ps-muted">已处理包含成功和失败的已保存案例，不表示全部回答成功。</p></template><el-progress v-if="job?.progress !== undefined" :percentage="Math.min(100,Math.max(0,job.progress))"/><p class="ps-muted">受理成功仅表示作业已创建，最终结果以服务端状态为准。</p><template #footer><el-button @click="refresh" :loading="loading">刷新状态</el-button><el-button @click="visible=false">关闭</el-button></template></el-dialog></template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { getJob } from '@/api/paismart/operations'
import { evaluationReport } from '@/api/paismart/governance'
import type { EvaluationReport, Id, Job } from '@/types/paismart'
import { errorText, stateLabel, terminal } from '../composables/usePage'
const props=defineProps<{modelValue:boolean; initial?:Job;administrative?:boolean;evaluationId?:Id}>(),emit=defineEmits<{ 'update:modelValue':[boolean]; done:[] }>()
const visible=computed({get:()=>props.modelValue,set:v=>emit('update:modelValue',v)}),job=shallowRef<Job>(),error=ref(''),loading=ref(false)
type Counts={total:number;processed:number;pending:number;state:string;failed?:number}
type EvaluationJob=Job & {payload?:{evaluationId?:unknown}}
const counts=shallowRef<Counts>(),countError=ref('')
const evaluationMode=computed(()=>props.evaluationId!==undefined||props.initial?.kind==='EVALUATION_RUN'||job.value?.kind==='EVALUATION_RUN')
let timer:ReturnType<typeof setTimeout>|undefined,controller:AbortController|undefined,serial=0,active=true
const identifier=(value:unknown)=>typeof value==='string'&&value.trim()&&value.length<=160?value:typeof value==='number'&&Number.isSafeInteger(value)?String(value):undefined
const count=(value:unknown):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0
function reportCounts(report:EvaluationReport):Counts {
  const summary=report.summary,total=summary?.totalCases
  if(!count(total))throw new Error('报告未提供可靠的总样本数')
  const cases=Array.isArray(report.cases)?report.cases:undefined
  if(cases&&new Set(cases.map(c=>identifier(c.id))).size!==cases.length)throw new Error('报告案例身份重复或缺失')
  if(cases?.some(c=>!identifier(c.id)))throw new Error('报告案例身份尚未确认')
  let processed=summary.processedCases,pending=summary.pendingCases
  if(processed===undefined&&pending===undefined&&cases){processed=cases.length;pending=total-cases.length}
  if(!count(processed)||!count(pending)||processed+pending!==total||cases&&cases.length!==processed)throw new Error('总样本、已处理与待处理计数不一致')
  const failed=summary.failedCases
  if(failed!==undefined&&(!count(failed)||failed>processed))throw new Error('失败样本计数不一致')
  return {total,processed,pending,state:report.state,failed:failed as number|undefined}
}
async function refresh(){
  clearTimeout(timer);if(!visible.value||!job.value?.jobId)return
  controller?.abort();const request=new AbortController();controller=request
  const revision=++serial,id=String(job.value.jobId),expected=identifier(props.evaluationId),administrative=props.administrative
  const current=()=>active&&visible.value&&revision===serial&&!request.signal.aborted
  loading.value=true;error.value='';counts.value=undefined;countError.value=''
  try{
    const actual=(await getJob(id,request.signal,administrative)).data as EvaluationJob
    if(!current())return
    if(String(actual.jobId)!==id)throw new Error('作业回执与当前任务不一致')
    job.value=actual
    if(evaluationMode.value){
      try{
        const stored=identifier(actual.payload?.evaluationId)
        if(actual.kind!=='EVALUATION_RUN'||!stored||expected!==undefined&&expected!==stored)throw new Error('作业与评测身份不一致')
        const report=(await evaluationReport(stored,request.signal)).data
        if(!current())return
        if(identifier(report.id)!==stored)throw new Error('报告与当前评测身份不一致')
        counts.value=reportCounts(report)
      }catch(e){if(current())countError.value='计数未确认：'+errorText(e)}
    }
    if(current()&&terminal(actual.state))emit('done')
  }catch(e){if(current()){error.value=errorText(e);if(evaluationMode.value)countError.value='计数未确认：作业状态尚未确认'}}
  finally{if(current()){loading.value=false;if(job.value&&!terminal(job.value.state)&&!error.value&&!countError.value)timer=setTimeout(refresh,3000)}}
}
watch(()=>[props.modelValue,props.initial,props.evaluationId],()=>{
  serial++;clearTimeout(timer);controller?.abort();loading.value=false;counts.value=undefined;error.value='';countError.value=''
  job.value=props.modelValue?props.initial:undefined
  if(props.modelValue)void refresh()
},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{active=false;serial++;clearTimeout(timer);controller?.abort()})
</script>
