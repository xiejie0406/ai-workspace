<template><div class="app-container ps-page"><h2>检索评测与诊断</h2><el-alert v-if="error||pageError" :title="error||pageError" type="error" :closable="false"/><div class="ps-toolbar"><el-button v-hasPermi="['paismart:eval:run']" type="primary" @click="openEvaluation">发起评测</el-button><el-button :loading="loading" @click="load()">刷新</el-button></div><el-form inline><el-form-item label="评测名称"><el-input v-model="query.keyword" clearable @keyup.enter="load(true)"/></el-form-item><el-form-item label="状态"><el-select v-model="query.state" clearable><el-option v-for="s in ['QUEUED','RUNNING','COMPLETE','COMPLETED_WITH_ERRORS','CANCELED']" :key="s" :label="stateLabel(s)" :value="s"/></el-select></el-form-item><el-button @click="load(true)">查询</el-button></el-form><el-table :data="rows" v-loading="loading"><el-table-column prop="label" label="评测名称" min-width="240"/><el-table-column label="状态" width="130"><template #default="{row}">{{stateLabel(row.state)}}</template></el-table-column><el-table-column prop="topK" label="Top K" width="100"/><el-table-column prop="createdAt" label="创建时间" min-width="180"/><el-table-column label="操作" width="240"><template #default="{row}"><el-button link type="primary" @click="report(row.id)">报告与复核</el-button><el-button v-if="row.jobId" link @click="showJob(row)">处理进度</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="total,prev,pager,next" @current-change="load()"/>
  <el-dialog v-model="open" title="发起受控评测" width="min(800px,95vw)">
    <el-alert title="评测会消耗检索与模型预算；使用当前账号允许的知识范围，最多 500 个样本、900000 字节。" type="info" :closable="false" class="ps-section"/>
    <el-form label-width="100px">
      <el-form-item label="评测名称" required><el-input v-model="form.label" maxlength="100"/></el-form-item>
      <el-form-item label="知识范围"><el-select v-model="form.spaceId" clearable placeholder="全部有权空间"><el-option v-for="s in spaces" :key="s.id" :label="s.name" :value="s.id"/></el-select></el-form-item>
      <el-form-item label="Top K"><el-input-number v-model="form.topK" :min="1" :max="20"/></el-form-item>
      <el-form-item label="随机种子"><el-input-number v-model="form.seed" :min="Number.MIN_SAFE_INTEGER" :max="Number.MAX_SAFE_INTEGER" :step="1"/><span class="ps-muted">整数，默认 42；随评测配置冻结。</span></el-form-item>
      <el-form-item label="评测集来源"><el-radio-group v-model="source"><el-radio value="INLINE">内联 JSONL</el-radio><el-radio value="FILE">上传本地文件</el-radio><el-radio value="DATASET">受控评测集</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="source==='INLINE'" label="JSONL 内容" required><el-input v-model="form.inlineJsonl" type="textarea" :rows="10" placeholder='每行一个 JSON 对象，包含 question 或 query，以及独立参考答案 referenceAnswer；可选 expectedDocumentIds/expectedFileNames'/></el-form-item>
      <template v-if="source==='FILE'">
        <el-form-item label="本地文件" required><input type="file" accept=".jsonl" aria-label="上传本地 JSONL 评测集" :disabled="uploading" @change="chooseFile"/></el-form-item>
        <el-form-item label="上传确认"><el-button :loading="uploading" :disabled="!file||!!uploaded" @click="uploadFile">上传评测集</el-button><span v-if="uploaded">已保存：{{uploaded.label}}，{{uploaded.caseCount}} 个样本</span><span v-else class="ps-muted">上传标签使用评测名称；先保存受控评测集，再提交评测，上传不会自动运行模型。</span></el-form-item>
      </template>
      <template v-if="source==='DATASET'">
        <el-form-item label="受控评测集" required><el-select v-model="datasetId" :loading="datasetLoading" placeholder="选择后台登记资源或本人上传集" filterable><el-option v-for="dataset in datasets" :key="dataset.id" :value="dataset.id" :label="`${dataset.label} · ${dataset.source==='RESOURCE'?'登记资源':'上传集'} · ${dataset.caseCount} 例`"/></el-select><el-button :loading="datasetLoading" @click="loadDatasets()">刷新清单</el-button></el-form-item>
        <el-pagination v-model:current-page="datasetQuery.pageNum" :page-size="datasetQuery.pageSize" :total="datasetTotal" layout="total,prev,pager,next" @current-change="loadDatasets()"/>
        <el-alert v-if="datasetError" :title="datasetError" type="error" :closable="false"/>
        <p v-if="!datasetLoading&&!datasetError&&!datasets.length" class="ps-muted">暂无可用受控评测集，可上传本地 JSONL 文件。</p>
      </template>
      <p v-if="selectedDataset" class="ps-muted">评测集：{{selectedDataset.label}} · {{selectedDataset.byteSize}} 字节 · SHA-256：{{selectedDataset.sha256}}</p>
    </el-form>
    <el-alert v-if="formError" :title="formError" type="error" :closable="false"/>
    <template #footer><el-button @click="open=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="uploading" @click="submit">提交评测</el-button></template>
  </el-dialog><JobDialog v-model="jobOpen" :initial="job" @done="load()"/></div></template>
<script setup lang="ts">
import { computed,onMounted,ref,shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { createEvaluation,listEvaluations,listEvaluationDatasets,uploadEvaluationDataset } from '@/api/paismart/governance'
import { newKey } from '@/api/paismart/client'
import { listSpaces } from '@/api/paismart/spaces'
import type { Evaluation,EvaluationDataset,EvaluationRequest,Id,Job,Space } from '@/types/paismart'
import JobDialog from '../../components/JobDialog.vue'
import { errorText,stateLabel,usePage } from '../../composables/usePage'
import { validateDataset,validateEvaluationSeed } from '../../composables/evaluationDataset'
import '../../common.css'
const router=useRouter(),{rows,total,query,loading,error,load}=usePage(listEvaluations),spaces=shallowRef<Space[]>([]),job=shallowRef<Job>(),pageError=ref(''),formError=ref(''),open=ref(false),jobOpen=ref(false),saving=ref(false),form=ref({label:'',spaceId:undefined as Id|undefined,topK:5,seed:42,inlineJsonl:''})
const {rows:datasets,total:datasetTotal,query:datasetQuery,loading:datasetLoading,error:datasetError,load:loadDatasets}=usePage(listEvaluationDatasets)
const source=ref<'INLINE'|'FILE'|'DATASET'>('INLINE'),datasetId=ref<string>(),file=shallowRef<File>(),uploaded=shallowRef<EvaluationDataset>(),uploading=ref(false)
const selectedDataset=computed(()=>source.value==='FILE'?uploaded.value:source.value==='DATASET'?datasets.value.find(dataset=>dataset.id===datasetId.value):undefined)
let uploadKey=newKey(),uploadLabel='',submissionKey=newKey(),submissionBody=''
function openEvaluation(){open.value=true;formError.value='';void loadDatasets()}
function chooseFile(event:Event){file.value=(event.target as HTMLInputElement).files?.[0];uploaded.value=undefined;uploadKey=newKey();uploadLabel='';formError.value='';if(file.value&&!file.value.name.toLowerCase().endsWith('.jsonl')){formError.value='仅支持 .jsonl 评测集文件';file.value=undefined;return}if(file.value&&file.value.size>900000){formError.value='评测集文件不能超过 900000 字节';file.value=undefined}}
async function uploadFile(){if(!file.value||uploading.value)return;uploading.value=true;formError.value='';try{const label=form.value.label.trim();if(!label)throw new Error('请先填写评测名称，作为上传标签');if(label!==uploadLabel){uploadLabel=label;uploadKey=newKey()}uploaded.value=(await uploadEvaluationDataset(file.value,uploadKey,label)).data;await loadDatasets(true)}catch(e){formError.value=errorText(e)}finally{uploading.value=false}}
async function submit(){if(saving.value||uploading.value)return;formError.value='';try{if(!form.value.label.trim())throw new Error('请填写评测名称');const seed=validateEvaluationSeed(form.value.seed);if(!Number.isInteger(form.value.topK)||form.value.topK<1||form.value.topK>20)throw new Error('Top K 必须为 1 至 20 的整数');const config={label:form.value.label,spaceId:form.value.spaceId,topK:form.value.topK,seed};let data:EvaluationRequest;if(source.value==='INLINE'){validateDataset(form.value.inlineJsonl);data={...config,inlineJsonl:form.value.inlineJsonl}}else{const id=source.value==='FILE'?uploaded.value?.id:datasetId.value;if(!id)throw new Error(source.value==='FILE'?'请先上传评测集文件':'请选择受控评测集');data={...config,datasetId:id}}const signature=JSON.stringify(data);if(signature!==submissionBody){submissionBody=signature;submissionKey=newKey()}saving.value=true;job.value=(await createEvaluation(data,submissionKey)).data;submissionBody='';open.value=false;jobOpen.value=true;await load()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
function report(id:Id){void router.push({path:'/paismart/review',query:{evaluationId:String(id)}})}
function showJob(row:Evaluation){job.value={jobId:row.jobId!,state:row.jobState||row.state};jobOpen.value=true}
onMounted(async()=>{void load();try{spaces.value=(await listSpaces()).rows}catch(e){pageError.value=errorText(e)}})
</script>



