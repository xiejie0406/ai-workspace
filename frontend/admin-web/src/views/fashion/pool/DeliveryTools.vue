<template>
<el-dialog :model-value="modelValue" title="已选方案：模特试衣与PPT提案" width="94%" :close-on-click-modal="false" @update:model-value="$emit('update:modelValue',$event)">
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="space" />
  <el-alert title="上部试衣，下部生成PPT。关闭和返回保留本工作单当前草稿版本的配置；后台任务独立执行。" type="info" :closable="false" class="space" />
  <section ref="imageSection" tabindex="-1" class="workspace-section">
    <div class="actions"><h2>模特试衣 / 换装</h2><el-button :loading="refreshing" @click="refreshTasks">刷新任务</el-button></div>
    <el-alert v-if="!providerAvailable" :title="blockedReason||'正在检查试衣服务状态'" type="warning" :closable="false" class="space" />
    <el-form label-position="top" :disabled="busy">
      <el-form-item label="试衣方案"><el-select v-model="targetIds" multiple filterable placeholder="选择当前页已选方案" style="width:100%"><el-option v-for="id in combinationIds" :key="id" :value="id" :label="names?.[id]||id" /></el-select></el-form-item>
      <el-card v-for="combination in targetCombinations" :key="combination.id" class="space">
        <template #header>{{names?.[combination.id]||combination.id}}：明确每件商品的服装位置</template>
        <el-form-item v-for="item in combination.items" :key="item.key" :label="item.name+' / '+item.mainSkuCode">
          <el-select :model-value="garmentPositions[combination.id]?.[item.key]" placeholder="请选择，系统不猜测服装位置" style="max-width:320px" @update:model-value="setPosition(combination.id,item.key,$event)">
            <el-option v-for="position in capabilities.supportedPositions" :key="position" :label="positionLabel(position)" :value="position" /><el-option label="保留原图（鞋帽等，未试衣）" value="original" />
          </el-select>
        </el-form-item>
        <p class="muted">上装和下装各最多一件；连衣裙单独试衣。保留原图的位置不会提交给试衣服务。</p>
      </el-card>
      <el-form-item label="模特照片"><input type="file" accept="image/png,image/jpeg" aria-label="上传模特照片" :disabled="busy" @change="uploadModel" /><span class="hint">PNG / JPEG，5KB～5MB；宽高均须150～4096像素，服务端核实尺寸。</span></el-form-item>
      <el-image v-if="modelPreview" :src="modelPreview" fit="contain" class="model-preview" /><p v-else-if="model" class="muted">已恢复此前上传的模特文件；需要查看或更换时重新选择本地照片。</p>
      <el-checkbox v-model="modelAuthorized">我有权使用此模特照片生成试衣图</el-checkbox>
      <p>服务：{{capabilities.providerCode||'未配置'}} / {{capabilities.model||'未配置'}}。估计单次费用：{{capabilities.estimatedUnitCost==null?'未知，暂不能提交付费任务':capabilities.estimatedUnitCost+' '+(capabilities.currency||'')}}；本次共 {{targetIds.length}} 项任务。</p>
      <el-form-item label="单次费用上限"><el-input-number v-model="maxCost" :min="0" :precision="2" /><span class="hint">{{capabilities.currency||'币种待确认'}}，每项任务共用此上限；月度预算由服务端校验。</span></el-form-item>
      <el-checkbox v-model="costConfirmed">已核对估计费用及单次上限，确认提交试衣任务</el-checkbox>
      <div class="actions space"><el-button type="primary" v-hasPermi="['fashion:quote:edit']" :loading="busy" :disabled="!canGenerate" @click="generateImages">提交模特试衣</el-button><span class="muted">每套一项任务，鞋帽等保留原图；结果需逐图复核。</span></div>
    </el-form>
    <el-empty v-if="!tasks.length" :image-size="80" description="尚无试衣任务" class="task-empty" />
    <el-card v-for="task in tasks" :key="task.id" class="space">
      <template #header><strong>{{names?.[task.combinationId]||task.combinationId}}</strong> <el-tag>{{statusLabel(task.status)}}</el-tag> <el-tag v-if="task.stale" type="danger">素材已变化，结果过期</el-tag></template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="任务编号">{{task.id}}</el-descriptions-item><el-descriptions-item label="冻结草稿版本">{{task.frozenInputs?.draftVersion??task.draftVersion??'未记录'}}</el-descriptions-item><el-descriptions-item label="冻结方案编号">{{task.frozenInputs?.combinationId??task.combinationId}}</el-descriptions-item><el-descriptions-item label="服务 / 模型">{{task.providerCode||'未记录'}} / {{task.model||'未记录'}}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{task.submittedAt||'未记录'}}</el-descriptions-item><el-descriptions-item label="完成时间">{{task.finishedAt||'尚未完成'}}</el-descriptions-item><el-descriptions-item label="查询重试 / 结果数">{{task.retryCount??'未记录'}} 次 / {{task.resultCount??task.results.length}} 张</el-descriptions-item>
      </el-descriptions>
      <p class="muted">以下为本任务冻结的服装输入；修改当前表单不会改变此记录。</p>
      <el-table v-if="task.frozenInputs?.products?.length" :data="task.frozenInputs.products" border>
        <el-table-column prop="productId" label="商品编号" min-width="170"/><el-table-column prop="mainSku" label="主SKU" min-width="160"/>
        <el-table-column label="冻结颜色" min-width="100"><template #default="{row}">{{row.color||'未记录'}}</template></el-table-column><el-table-column label="冻结尺码" min-width="100"><template #default>未记录</template></el-table-column>
        <el-table-column label="服装位置" min-width="150"><template #default="{row}">{{row.garment_position?positionLabel(row.garment_position):'未记录'}}</template></el-table-column><el-table-column label="素材版本" width="100"><template #default="{row}">{{row.visualVersion??'未记录'}}</template></el-table-column>
      </el-table>
      <p v-else class="muted">冻结服装明细未返回，请查询原任务；当前商品或表单内容不能代替提交记录。</p>
      <p v-if="task.errorMessage">{{task.errorMessage}}</p><p>实际费用：{{task.actualCost==null?'未知':task.actualCost+' '+(task.currency||'')}}；{{billingLabel(task.billingStatus)}}。{{coverageLabel(task)}}</p>
      <div class="actions"><el-button v-if="['queued','running','unknown','cancel_requested'].includes(task.status)" @click="queryTask(task.id)">查询原任务</el-button><el-button v-if="capabilities.supportsCancellation && ['queued','running','unknown'].includes(task.status)" v-hasPermi="['fashion:quote:edit']" @click="cancelTask(task)">请求取消</el-button><el-button v-if="['failed','cancelled','rejected'].includes(task.status)" v-hasPermi="['fashion:quote:edit']" :disabled="!canRetry(task)" @click="retryTask(task)">重新生成</el-button></div>
      <p v-if="task.status==='unknown'" class="muted">受理结果待确认，请查询原任务或人工对账；不会更换请求键重复付费。</p>
      <div v-for="(result,index) in task.results" :key="index" class="result"><template v-if="result.status==='success'"><el-image v-if="resultUrls[task.id+':'+index]" :src="resultUrls[task.id+':'+index]" :preview-src-list="[resultUrls[task.id+':'+index]]" fit="contain" class="result-image" /><el-button v-else @click="loadResult(task.id,index)">查看结果图 {{index+1}}</el-button><el-button v-hasPermi="['fashion:quote:edit']" :disabled="task.stale" @click="openReview(task,index)">复核此图</el-button></template><span v-else>结果 {{index+1}}：{{statusLabel(result.status||'尚未返回')}}</span></div>
    </el-card>
  </section>
  <section ref="pptSection" tabindex="-1" class="workspace-section">
    <h2>PPT提案</h2>
    <el-form label-width="120px" :disabled="busy">
      <el-form-item label="提案标题"><el-input v-model="title" maxlength="200" placeholder="输入对客展示的提案标题" /></el-form-item>
      <el-form-item label="导出范围"><el-radio-group v-model="exportScope"><el-radio value="page">当前页已选 {{combinationIds.length}} 套</el-radio><el-radio value="all" :disabled="selectedCount>100">全部已选 {{selectedCount}} 套</el-radio></el-radio-group></el-form-item>
      <el-alert v-if="selectedCount>100" title="每文件最多100套，请明确选择当前页分批生成，不会自动截断。" type="warning" :closable="false" class="space" />
      <el-form-item label="价格展示"><el-select v-model="priceMode"><el-option label="不展示价格" value="none" /><el-option label="团购价" value="group" /><el-option label="VIP价" value="vip" /></el-select></el-form-item>
      <el-form-item label="图片方式"><el-radio-group v-model="imageMode"><el-radio value="original">商品原图</el-radio><el-radio value="model">已采用试衣图＋商品原图</el-radio></el-radio-group></el-form-item>
    </el-form>
    <p>按已选方案的名称、顺序和展示规格输出；商品文字可编辑，图片嵌入文件。未复核、已拒绝和过期图不能交付。</p>
    <el-alert v-for="issue in preflight?.issues||[]" :key="issue" :title="issue" type="error" :closable="false" class="space" /><el-alert v-if="preflight?.ready" :title="'资料检查通过，可生成 '+preflight.combinationCount+' 套PPT提案'" type="success" :closable="false" class="space" />
    <div class="actions"><el-button v-hasPermi="['fashion:quote:export']" :loading="busy" @click="checkExport">检查导出内容</el-button><el-button type="primary" v-hasPermi="['fashion:quote:export']" :loading="busy" :disabled="!preflight?.ready||!title.trim()" @click="exportPpt">生成并下载PPTX</el-button></div>
    <p v-if="delivery">本次交付记录：{{delivery.title}} · {{delivery.status==='success'?'生成成功':delivery.status}}<template v-if="delivery.errorMessage"> · {{delivery.errorMessage}}</template></p>
  </section>
  <template #footer><el-button @click="$emit('update:modelValue',false)">关闭，返回已选方案</el-button></template>
  <el-dialog v-model="reviewVisible" title="复核模特试衣图" width="600px" append-to-body><el-image v-if="reviewTask && resultUrls[reviewTask.id+':'+resultNo]" :src="resultUrls[reviewTask.id+':'+resultNo]" fit="contain" class="model-preview" /><div class="checklist"><el-checkbox v-for="(label,itemKey) in checkLabels" :key="itemKey" v-model="checks[itemKey]">{{label}}</el-checkbox></div><el-input v-model="reason" type="textarea" :rows="3" maxlength="500" placeholder="不采用时说明原因" class="space" /><template #footer><el-button :loading="busy" @click="review(false)">不采用</el-button><el-button type="primary" :loading="busy" :disabled="!Object.values(checks).every(Boolean)" @click="review(true)">确认采用</el-button></template></el-dialog>
</el-dialog>
</template>
<script setup lang="ts">
import { computed,nextTick,onBeforeUnmount,reactive,ref,watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fashionRequest } from '@/api/fashion/client'
import { saveBlob } from '@/api/fashion/download'
import { createProposalDelivery,downloadProposalDelivery,type ProposalDelivery,type ProposalImageTask as ImageTask } from '@/api/fashion/proposal'
import type { RuoYiResult } from '@/api/fashion/types'
import type { PoolCombination } from '@/api/fashion/poolTypes'
import useUserStore from '@/store/modules/user'
import { ensurePptx,positionError,readWorkspace,saveWorkspace,workspaceKey,type GarmentPosition,type WorkspaceConfiguration } from './proposalWorkspaceState'
interface Capabilities{providerCode?:string;model?:string;supportedPositions:string[];requiresPublicUrls?:boolean;supportsCancellation?:boolean;estimatedUnitCost?:number|null;currency?:string}
interface Preflight{ready:boolean;issues:string[];combinationCount:number;maxPerFile:number}
const props=defineProps<{modelValue:boolean;kind:'image'|'ppt';poolId:string;rowVersion:number;selectedCount:number;combinationIds:string[];combinations?:PoolCombination[];names?:Record<string,string>}>()
defineEmits<{'update:modelValue':[boolean]}>()
const userStore=useUserStore(),key=computed(()=>workspaceKey(String(userStore.id),props.poolId,props.rowVersion)),base=computed(()=>'pools/'+props.poolId+'/proposal')
const busy=ref(false),refreshing=ref(false),error=ref(''),providerAvailable=ref(false),blockedReason=ref(''),tasks=ref<ImageTask[]>([])
const model=ref<{objectKey:string;sha256:string}>(),modelPreview=ref(''),modelAuthorized=ref(false),targetIds=ref<string[]>([]),resultUrls=reactive<Record<string,string>>({})
const garmentPositions=ref<Record<string,Record<string,GarmentPosition>>>({}),maxCost=ref<number>(),costConfirmed=ref(false)
const capabilities=ref<Capabilities>({supportedPositions:[]}),exportScope=ref<'page'|'all'>('page'),priceMode=ref('none'),imageMode=ref('original'),title=ref('套装选品提案'),preflight=ref<Preflight>(),delivery=ref<ProposalDelivery>()
const reviewVisible=ref(false),reviewTask=ref<ImageTask>(),resultNo=ref(0),reason=ref(''),checks=reactive({style:false,color:false,completeness:false,quality:false}),checkLabels={style:'款式正确',color:'颜色正确',completeness:'实际试衣位置齐全，未覆盖位置标明',quality:'纹理 / 标识及图像质量无明显异常'}
const requestKeys=ref<Record<string,string>>({}),imageSection=ref<HTMLElement>(),pptSection=ref<HTMLElement>(),targetCombinations=computed(()=>(props.combinations||[]).filter(item=>targetIds.value.includes(item.id)))
const canGenerate=computed(()=>providerAvailable.value&&Boolean(model.value)&&modelAuthorized.value&&costConfirmed.value&&capabilities.value.estimatedUnitCost!=null&&maxCost.value!=null&&maxCost.value>=capabilities.value.estimatedUnitCost&&targetIds.value.length>0)
let timer:ReturnType<typeof setTimeout>|undefined,revision=0
function configuration():WorkspaceConfiguration{return{model:model.value,modelAuthorized:modelAuthorized.value,targetIds:targetIds.value,garmentPositions:garmentPositions.value,exportScope:exportScope.value,priceMode:priceMode.value,imageMode:imageMode.value,title:title.value,maxCost:maxCost.value,requestKeys:requestKeys.value}}
function persist(){saveWorkspace(key.value,configuration())}
function restore(){const state=readWorkspace(key.value);model.value=state?.model;modelAuthorized.value=state?.modelAuthorized??false;targetIds.value=state?.targetIds??[];garmentPositions.value=state?.garmentPositions??{};exportScope.value=state?.exportScope??'page';priceMode.value=state?.priceMode??'none';imageMode.value=state?.imageMode??'original';title.value=state?.title??'套装选品提案';maxCost.value=state?.maxCost;requestKeys.value=state?.requestKeys??{};costConfirmed.value=false}
async function action(fn:()=>Promise<void>){if(busy.value)return;const current=key.value;busy.value=true;error.value='';try{await fn()}catch(e){if(current===key.value)error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false;persist()}}
async function refreshTasks(){const current=key.value;refreshing.value=true;try{const response=await fashionRequest<RuoYiResult<{providerAvailable:boolean;blockedReason:string;tasks:ImageTask[];capabilities?:Capabilities}>>({path:base.value+'/images',params:{rowVersion:props.rowVersion}});if(current!==key.value)return;providerAvailable.value=response.data.providerAvailable;blockedReason.value=response.data.blockedReason;tasks.value=response.data.tasks;capabilities.value=response.data.capabilities??{supportedPositions:[]};if(maxCost.value==null&&capabilities.value.estimatedUnitCost!=null)maxCost.value=capabilities.value.estimatedUnitCost}catch(e){if(current===key.value)error.value=e instanceof Error?e.message:'任务读取失败'}finally{refreshing.value=false}}
async function queryTask(id:string){const current=key.value;try{const response=await fashionRequest<RuoYiResult<ImageTask>>({path:base.value+'/images/'+id});if(current===key.value)tasks.value=tasks.value.map(task=>task.id===id?response.data:task)}catch(e){if(current===key.value)error.value=e instanceof Error?e.message:'任务查询失败'}}
async function uploadModel(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file)return;if(!['image/png','image/jpeg'].includes(file.type)||file.size<5*1024||file.size>5*1024*1024){ElMessage.warning('请上传5KB～5MB的PNG或JPEG模特照片，宽高均须150～4096像素');return}const current=key.value;await action(async()=>{const body=new FormData();body.append('file',file);const response=await fashionRequest<RuoYiResult<{objectKey:string;sha256:string}>>({path:base.value+'/models',method:'post',params:{rowVersion:props.rowVersion},body});if(current!==key.value)return;model.value=response.data;if(modelPreview.value)URL.revokeObjectURL(modelPreview.value);modelPreview.value=URL.createObjectURL(file);modelAuthorized.value=false;costConfirmed.value=false})}
function setPosition(combinationId:string,itemKey:string,value:GarmentPosition){garmentPositions.value={...garmentPositions.value,[combinationId]:{...garmentPositions.value[combinationId],[itemKey]:value}};costConfirmed.value=false}
function validateTarget(id:string){const combination=props.combinations?.find(item=>item.id===id);if(!combination)throw new Error('请回到目标方案所在页，再核对服装位置后提交');const message=positionError(combination.items,garmentPositions.value[id]||{},capabilities.value.supportedPositions);if(message)throw new Error(message)}
function requestKey(identity:string,newAttempt=false){if(newAttempt||!requestKeys.value[identity])requestKeys.value[identity]=crypto.randomUUID();persist();return requestKeys.value[identity]}
async function submitImage(combinationId:string,newAttempt=false){validateTarget(combinationId);const current=key.value,positions=garmentPositions.value[combinationId],identity=JSON.stringify([props.poolId,props.rowVersion,combinationId,model.value?.sha256,positions,maxCost.value]);const task=(await fashionRequest<RuoYiResult<ImageTask>>({path:base.value+'/images',method:'post',body:{rowVersion:props.rowVersion,combinationId,model:model.value,requestKey:requestKey(identity,newAttempt),garmentPositions:positions,maxCost:maxCost.value,costConfirmed:costConfirmed.value},timeoutMs:120000})).data;if(current===key.value)tasks.value=[task,...tasks.value.filter(item=>item.id!==task.id)]}
async function generateImages(){const current=key.value;await action(async()=>{if(!canGenerate.value)throw new Error('请核对模特许可、已知估计费用和单次费用上限');targetIds.value.forEach(validateTarget);for(const id of [...targetIds.value]){if(current!==key.value)throw new Error('已切换工作单或草稿，停止提交后续试衣任务');await submitImage(id)}ElMessage.success('试衣任务已受理，结果仍需人工复核')})}
function canRetry(task:ImageTask){return canGenerate.value&&Boolean(props.combinations?.some(item=>item.id===task.combinationId))}
async function retryTask(task:ImageTask){await action(()=>submitImage(task.combinationId,true))}
async function cancelTask(task:ImageTask){const current=key.value;await action(async()=>{const response=await fashionRequest<RuoYiResult<ImageTask>>({path:base.value+'/images/'+task.id+'/cancel',method:'post',body:{rowVersion:task.rowVersion}});if(current===key.value)tasks.value=tasks.value.map(item=>item.id===task.id?response.data:item)})}
async function loadResult(id:string,no:number){const current=key.value;await action(async()=>{const blob=await fashionRequest<Blob>({path:base.value+'/images/'+id+'/results/'+no,responseType:'blob'});if(current!==key.value)return;if(!blob.type.startsWith('image/'))throw new Error('服务未返回图片');const resultKey=id+':'+no;if(resultUrls[resultKey])URL.revokeObjectURL(resultUrls[resultKey]);resultUrls[resultKey]=URL.createObjectURL(blob)})}
function openReview(task:ImageTask,no:number){reviewTask.value=task;resultNo.value=no;reason.value='';Object.assign(checks,{style:false,color:false,completeness:false,quality:false});reviewVisible.value=true;void loadResult(task.id,no)}
async function review(adopt:boolean){if(!reviewTask.value)return;if(!adopt&&!reason.value.trim())return ElMessage.warning('请填写不采用原因');const current=key.value;await action(async()=>{const task=reviewTask.value!,response=await fashionRequest<RuoYiResult<ImageTask>>({path:base.value+'/images/'+task.id+'/review',method:'post',body:{rowVersion:task.rowVersion,resultNo:resultNo.value,adopt,checks:{...checks},reason:reason.value}});if(current!==key.value)return;tasks.value=tasks.value.map(item=>item.id===task.id?response.data:item);reviewVisible.value=false;preflight.value=undefined})}
function exportBody(){const adoptedTaskIds:Record<string,string>={};for(const task of tasks.value)if(task.status==='adopted'&&!task.stale&&!adoptedTaskIds[task.combinationId])adoptedTaskIds[task.combinationId]=task.id;return{rowVersion:props.rowVersion,combinationIds:exportScope.value==='all'?[]:props.combinationIds,priceMode:priceMode.value,imageMode:imageMode.value,adoptedTaskIds}}
async function checkExport(){const currentRevision=revision;await action(async()=>{if(exportScope.value==='page'&&!props.combinationIds.length)throw new Error('当前页没有已选方案');const response=await fashionRequest<RuoYiResult<Preflight>>({path:base.value+'/preflight',method:'post',body:exportBody()});if(currentRevision===revision)preflight.value=response.data;else error.value='配置已变化，请重新检查导出内容'})}
async function exportPpt(){const current=key.value;await action(async()=>{if(!preflight.value?.ready||!title.value.trim())throw new Error('请完成资料检查并填写提案标题');const body={poolId:props.poolId,title:title.value.trim(),export:exportBody()},identity=JSON.stringify(['delivery',body]);const record=(await createProposalDelivery({...body,requestKey:requestKey(identity,delivery.value?.status==='failed')})).data;if(current!==key.value)return;delivery.value=record;if(record.status!=='success')throw new Error(record.errorMessage||'同步生成未完成，请查看交付记录');const blob=await downloadProposalDelivery(record.id);if(current!==key.value)return;await ensurePptx(blob);saveBlob(blob,(record.title.replace(/[\\/:*?"<>|]/g,'-')||'套装提案')+'.pptx');ElMessage.success('PPTX生成成功，已开始下载')})}
function focusSection(){void nextTick(()=>{const element=props.kind==='image'?imageSection.value:pptSection.value;element?.scrollIntoView({block:'start',behavior:'smooth'});element?.focus({preventScroll:true})})}
function poll(){clearTimeout(timer);if(!props.modelValue)return;timer=setTimeout(async()=>{for(const task of tasks.value.filter(item=>['queued','running','cancel_requested'].includes(item.status)))await queryTask(task.id);poll()},5000)}
function clearUrls(){if(modelPreview.value)URL.revokeObjectURL(modelPreview.value);modelPreview.value='';for(const item of Object.keys(resultUrls)){URL.revokeObjectURL(resultUrls[item]);delete resultUrls[item]}}
watch([exportScope,priceMode,imageMode,title,()=>props.rowVersion,()=>props.combinationIds.join(',')],()=>{revision++;preflight.value=undefined})
watch([model,modelAuthorized,targetIds,garmentPositions,exportScope,priceMode,imageMode,title,maxCost,requestKeys],persist,{deep:true})
watch(key,(_,old)=>{if(old)saveWorkspace(old,configuration());clearTimeout(timer);clearUrls();tasks.value=[];preflight.value=undefined;delivery.value=undefined;reviewVisible.value=false;reviewTask.value=undefined;restore();if(props.modelValue)void refreshTasks()},{immediate:true})
watch(()=>props.modelValue,async visible=>{clearTimeout(timer);persist();if(visible){await refreshTasks();focusSection();poll()}},{immediate:true})
watch(()=>props.kind,()=>{if(props.modelValue)focusSection()})
onBeforeUnmount(()=>{persist();clearTimeout(timer);clearUrls()})
function positionLabel(value:string){return({top:'上装',bottom:'下装',dress:'连衣裙',original:'保留原图，未试衣'} as Record<string,string>)[value]||value}
function statusLabel(value:string){return({queued:'排队中',running:'生成中',unknown:'状态待确认',review:'待复核',adopted:'已采用',rejected:'未采用',failed:'失败',cancel_requested:'已请求取消',cancelled:'已取消',success:'生成成功'} as Record<string,string>)[value]||value}
function billingLabel(value?:string){return({unknown:'费用待对账',not_billed:'未计费',billed:'已计费',settled:'已对账',pending:'费用待确认'} as Record<string,string>)[value||'']||value||'费用状态未知'}
function coverageLabel(task:ImageTask){if(!task.coverage)return'覆盖范围尚未返回，请核对实际服装位置';const label=(itemKey:string)=>task.frozenInputs?.products?.find(item=>item.mainSkuKey===itemKey)?.mainSku||itemKey;return task.coverage.original.length?'未试衣、保留原图：'+task.coverage.original.map(label).join('、'):'全部所选服装位置参与试衣，结果仍需逐图核对'}
</script>
<style scoped>.space{margin:16px 0}.workspace-section{width:100%;padding:16px 0;border-top:1px solid var(--el-border-color-light);scroll-margin-top:20px}.workspace-section:focus{outline:2px solid var(--el-color-primary-light-5)}h2{font-size:20px;margin:0 0 16px}.model-preview{width:100%;height:260px;margin:12px 0}.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.result{display:flex;align-items:center;gap:12px;margin:12px 0;flex-wrap:wrap}.result-image{width:220px;height:260px}.muted,.hint{color:var(--el-text-color-secondary)}.hint{margin-left:12px}.checklist{display:flex;flex-direction:column}</style>
<style scoped>.task-empty{padding:12px 0}.task-empty :deep(.el-empty__description){margin-top:8px}</style>
