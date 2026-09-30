<template>
  <div class="app-container">
    <el-alert v-if="!requirementCapability.enabled" :title="requirementCapabilityText" type="warning" :closable="false" show-icon class="mb20" />
    <el-alert v-else title="AI 输出都是待人工确认草案；需求和搭配必须分别采用。" type="info" :closable="false" show-icon class="mb20" />
    <el-card shadow="never" v-loading="loading">
      <template #header><div class="header"><span>方案工作台</span><div><el-button v-if="quote" v-hasPermi="['fashion:image:list']" @click="router.push({path:'/fashion/image',query:{quoteId:quote.id}})">图片工作台</el-button><el-button icon="Refresh" @click="loadAll">从 Java 刷新</el-button></div></div></template>
      <el-form :inline="true" @submit.prevent="loadAll"><el-form-item label="方案 ID"><el-input v-model="quoteId" style="width:230px" /></el-form-item><el-form-item><el-button type="primary" @click="loadAll">载入</el-button></el-form-item></el-form>
      <el-descriptions v-if="quote" :column="3" border>
        <el-descriptions-item label="方案">{{quote.title}}（{{quote.quoteNo}} / V{{quote.versionNo}}）</el-descriptions-item><el-descriptions-item label="客户">{{quote.customerName}}</el-descriptions-item><el-descriptions-item label="状态">{{quote.status}}</el-descriptions-item>
        <el-descriptions-item label="套数">{{quote.requestedQty}}</el-descriptions-item><el-descriptions-item label="每套预算">{{budgetPerSetText}}</el-descriptions-item><el-descriptions-item label="需求确认">{{quote.requirementConfirmed?'已确认':'待确认'}}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">自然语言需求分析</el-divider>
      <el-input v-model="sourceText" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="输入 8～2000 字客户选品要求；请勿粘贴联系电话或内部备注。" />
      <div class="actions"><el-button type="primary" :disabled="!requirementCapability.enabled||!quote||quote.status!=='draft'||sourceText.trim().length<8" :loading="starting" v-hasPermi="['fashion:ai:run:execute']" @click="startRequirement">开始分析</el-button><el-button v-if="requirementRun&&isPending(requirementRun)" type="danger" plain v-hasPermi="['fashion:ai:run:cancel']" @click="cancelTask(requirementRun,'requirement')">取消任务</el-button></div>
    </el-card>

    <el-card v-if="requirementRun" shadow="never" class="mt20">
      <template #header><div class="header"><span>需求任务 {{requirementRun.runNo}}</span><el-tag :type="statusType(requirementRun.status)">{{statusText(requirementRun.status)}}</el-tag></div></template>
      <el-descriptions :column="3" border><el-descriptions-item label="尝试次数">{{requirementRun.runAttempt}}</el-descriptions-item><el-descriptions-item label="步骤">{{requirementRun.currentStepNo}}</el-descriptions-item><el-descriptions-item label="采用状态">{{requirementRun.applyStatus}}</el-descriptions-item><el-descriptions-item v-if="requirementRun.errorCode" label="错误码">{{requirementRun.errorCode}}</el-descriptions-item><el-descriptions-item v-if="requirementRun.errorMessage" label="说明">{{requirementRun.errorMessage}}</el-descriptions-item></el-descriptions>
      <template v-if="requirementRun.output"><el-divider content-position="left">待确认结构化草稿</el-divider><pre>{{JSON.stringify(requirementRun.output,null,2)}}</pre><el-button v-if="requirementRun.status==='succeeded'&&requirementRun.applyStatus!=='applied'" type="success" :loading="applying" v-hasPermi="['fashion:ai:run:apply']" @click="applyRequirement">采用需求并标记人工确认</el-button></template>
    </el-card>

    <ManualSelection v-if="workspace && quote" :quote="quote" :workspace="workspace" @saved="loadAll" />
    <el-card v-if="workspace" shadow="never" class="mt20">
      <template #header><div class="header"><span>一至四品类选品搭配</span><div><el-tag>{{workspace.preview.selection_mode==='progressive'?'递进档位':'独立档位'}}</el-tag><el-button class="ml8" type="primary" :disabled="!canStartSelection" :loading="selectionStarting" v-hasPermi="['fashion:ai:run:execute']" @click="startSelection()">生成搭配</el-button></div></div></template>
      <el-alert v-if="!selectionCapability.enabled" :title="selectionCapabilityText" type="warning" :closable="false" show-icon />
      <el-alert v-else-if="!quote?.requirementConfirmed" title="先确认需求，再冻结候选并生成搭配。" type="warning" :closable="false" show-icon />
      <el-alert v-if="workspace.preview.shortages.length" title="存在候选不足" type="warning" :closable="false" show-icon class="mt12"><template #default><div v-for="item in workspace.preview.shortages" :key="item.category_code">{{item.category_code}}：{{item.reason}}</div></template></el-alert>

      <el-divider content-position="left">冻结候选（{{workspace.preview.frozen_candidates.length}} 个颜色款 / SKU 明细不另建表）</el-divider>
      <el-tabs>
        <el-tab-pane v-for="group in candidateGroups" :key="group.category" :label="`${group.category} ${group.items.length}`">
          <div class="candidate-grid"><el-card v-for="candidate in group.items" :key="candidate.candidate_ref" shadow="hover" class="candidate-card">
            <ProductThumbnail :product-id="candidate.variants[0].product_ref" />
            <div class="header"><strong>{{candidate.product_name}}</strong><el-tag size="small">{{candidate.color_name}}</el-tag></div>
            <div class="muted">{{candidate.source_ref}} / {{candidate.style_ref}}</div>
            <div>¥{{money(candidate.conservative_unit_price_minor)}} / 套 · 可用 {{candidate.total_available_qty}}</div>
            <div class="size-list"><el-tag v-for="sku in candidate.variants" :key="sku.product_ref" size="small" type="info">{{sku.size_code}} {{sku.available_qty}}</el-tag></div>
          </el-card></div>
        </el-tab-pane>
      </el-tabs>

      <el-divider content-position="left">当前组合</el-divider>
      <el-empty v-if="!selectedCombinations.length" description="尚未采用搭配" />
      <el-card v-for="combo in selectedCombinations" :key="combo.id" shadow="never" class="combo-card">
        <template #header><div class="header"><div><strong>{{combo.name}}</strong> <el-tag>{{combo.categoryCount}} 品类</el-tag></div><el-button v-hasPermi="['fashion:quote:edit']" size="small" type="danger" plain @click="removeCombo(combo)">移除组合</el-button><el-button size="small" :disabled="!selectionCapability.enabled" @click="startSelection(combo)">保持锁定重搭</el-button></div></template>
        <div class="muted">{{combo.reason}} · visualHash {{combo.visualHash.slice(0,12)}}…</div>
        <el-table :data="slotRows(combo)" size="small">
          <el-table-column prop="slotCode" label="槽位" width="90" /><el-table-column prop="categoryCode" label="品类" width="100" /><el-table-column prop="productName" label="颜色款" /><el-table-column prop="colorName" label="颜色" width="90" /><el-table-column label="尺码初始分配"><template #default="{row}"><el-tag v-for="size in row.sizes" :key="size.skuCode" size="small" type="info">{{size.sizeCode}} × {{size.qty}}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="190"><template #default="{row}"><el-button link type="primary" v-hasPermi="['fashion:quote:edit']" @click="toggleLock(combo,row.slotCode)">{{combo.lockedSlots.includes(row.slotCode)?'解锁':'锁定'}}</el-button><el-button link type="primary" :disabled="combo.lockedSlots.includes(row.slotCode)" v-hasPermi="['fashion:quote:edit']" @click="openReplace(combo,row)">替换</el-button></template></el-table-column>
        </el-table>
      </el-card>
    </el-card>

    <el-card v-if="selectionRun" shadow="never" class="mt20">
      <template #header><div class="header"><span>搭配任务 {{selectionRun.runNo}}</span><el-tag :type="statusType(selectionRun.status)">{{statusText(selectionRun.status)}}</el-tag></div></template>
      <el-alert v-if="selectionIsStale" title="方案版本已变化：此结果只能查看，不能覆盖当前组合。请重新生成。" type="error" :closable="false" show-icon class="mb12" />
      <el-descriptions :column="3" border><el-descriptions-item label="尝试次数">{{selectionRun.runAttempt}}</el-descriptions-item><el-descriptions-item label="步骤">{{selectionRun.currentStepNo}}</el-descriptions-item><el-descriptions-item label="采用状态">{{selectionRun.applyStatus}}</el-descriptions-item><el-descriptions-item v-if="selectionRun.errorCode" label="错误码">{{selectionRun.errorCode}}</el-descriptions-item><el-descriptions-item v-if="selectionRun.errorMessage" label="说明">{{selectionRun.errorMessage}}</el-descriptions-item></el-descriptions>
      <template v-if="selectionRun.output"><el-divider content-position="left">待采用搭配</el-divider><div v-for="tier in selectionOutputTiers" :key="tier.category_count" class="tier-result"><strong>{{tier.category_count}} 品类</strong><span>：{{tier.combinations?.length??0}} / {{tier.requested_candidate_count}} 个候选</span><div v-for="combo in tier.combinations" :key="combo.combo_key">{{combo.name}} · ¥{{money(combo.conservative_unit_price_minor)}} · {{combo.reason}}</div><div v-for="reason in tier.shortage_reasons" :key="reason" class="warning">候选不足：{{reason}}</div></div><div class="actions"><el-button type="success" :disabled="selectionIsStale||selectionRun.status!=='succeeded'||selectionRun.applyStatus==='applied'" :loading="selectionApplying" v-hasPermi="['fashion:ai:run:apply']" @click="applySelection">采用全部候选组合</el-button><el-button v-if="isPending(selectionRun)" type="danger" plain @click="cancelTask(selectionRun,'selection')">取消任务</el-button></div></template>
    </el-card>

    <el-drawer v-model="replaceVisible" title="替换颜色款" size="45%">
      <el-alert title="只显示同品类且仍满足当前价格、库存、预算、图片和排除项的 Java 候选。" type="info" :closable="false" show-icon />
      <el-card v-for="candidate in replaceCandidates" :key="candidate.candidate_ref" shadow="never" class="mt12"><div class="header"><div><strong>{{candidate.product_name}}</strong><div>{{candidate.color_name}} · ¥{{money(candidate.conservative_unit_price_minor)}} · 库存 {{candidate.total_available_qty}}</div></div><el-button type="primary" :loading="replacing" @click="replaceWith(candidate.candidate_ref)">选择</el-button></div></el-card>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import ManualSelection from './ManualSelection.vue'
import ProductThumbnail from '../components/ProductThumbnail.vue'
import { fashionRequest } from '@/api/fashion/client'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyRequirementRun, cancelRun, createRequirementRun, getRequirementCapability, getRun, type RunCapability } from '@/api/fashion/agent'
import { getQuote } from '@/api/fashion/quote'
import { applySelectionRun, createSelectionRun, getSelectionCapability, getSelectionWorkspace, replaceComboCandidate, updateComboLocks } from '@/api/fashion/selection'
import type { FashionQuote, FashionRun, FrozenSelectionCandidate, SelectionCombo, SelectionWorkspace } from '@/api/fashion/types'

type SelectionTierOutput={category_count:number;requested_candidate_count:number;combinations:Array<{combo_key:string;name:string;reason:string;conservative_unit_price_minor:number;combo_visual_hash:string}>;shortage_reasons:string[]}
type SlotRow={slotCode:string;categoryCode:string;productName:string;colorName:string;sizes:Array<{skuCode:string;sizeCode:string;qty:number}>}
const route=useRoute(),router=useRouter(),quoteId=ref(String(route.query.quoteId??'')),quote=ref<FashionQuote>(),workspace=ref<SelectionWorkspace>(),requirementRun=ref<FashionRun>(),selectionRun=ref<FashionRun>(),sourceText=ref('')
const loading=ref(false),starting=ref(false),applying=ref(false),selectionStarting=ref(false),selectionApplying=ref(false),replacing=ref(false),replaceVisible=ref(false),replaceCombo=ref<SelectionCombo>(),replaceSlot=ref<SlotRow>()
const requirementCapability=ref<RunCapability>({enabled:false,reason:'loading'}),selectionCapability=ref<RunCapability>({enabled:false,reason:'loading'})
let requirementTimer:number|undefined,selectionTimer:number|undefined,alive=true
const requirementCapabilityText=computed(()=>capabilityText(requirementCapability.value,'需求分析','requirement_agent_not_published'))
const selectionCapabilityText=computed(()=>capabilityText(selectionCapability.value,'选品搭配','selection_agent_not_published'))
const budgetPerSetText=computed(()=>workspace.value?.preview.budget_maximum_per_set_minor?`¥${money(workspace.value.preview.budget_maximum_per_set_minor)}`:'未限制')
const canStartSelection=computed(()=>Boolean(selectionCapability.value.enabled&&quote.value?.status==='draft'&&quote.value.requirementConfirmed&&workspace.value?.preview.frozen_candidates.length))
const selectedCombinations=computed(()=>workspace.value?.combinations.filter(combo=>combo.selected)??[])
const candidateGroups=computed(()=>{const groups=new Map<string,FrozenSelectionCandidate[]>();for(const item of workspace.value?.preview.frozen_candidates??[]){const values=groups.get(item.category_code)??[];values.push(item);groups.set(item.category_code,values)}return [...groups].map(([category,items])=>({category,items}))})
const selectionOutputTiers=computed(()=>((selectionRun.value?.output?.tiers??[]) as SelectionTierOutput[]))
const selectionIsStale=computed(()=>Boolean(selectionRun.value&&quote.value&&selectionRun.value.quoteRowVersion!==quote.value.rowVersion))
const replaceCandidates=computed(()=>workspace.value?.preview.frozen_candidates.filter(item=>item.category_code===replaceSlot.value?.categoryCode)??[])
async function removeCombo(combo:SelectionCombo){if(!quote.value)return;await ElMessageBox.confirm('移除此组合？历史记录会保留。','移除组合',{type:'warning'});await fashionRequest({path:`quotes/${quote.value.id}/selection/combinations/${combo.id}/remove`,method:'post',body:{quoteRowVersion:quote.value.rowVersion,comboRowVersion:combo.rowVersion}});await loadAll()}
async function loadCapabilities(){const [requirement,selection]=await Promise.all([getRequirementCapability(),getSelectionCapability()]);requirementCapability.value=requirement.data;selectionCapability.value=selection.data}
async function loadAll(){if(!quoteId.value.trim())return;loading.value=true;try{const [quoteResponse,workspaceResponse]=await Promise.all([getQuote(quoteId.value.trim()),getSelectionWorkspace(quoteId.value.trim())]);quote.value=quoteResponse.data;workspace.value=workspaceResponse.data;sourceText.value=quoteResponse.data.requirementText??'';await router.replace({query:{...route.query,quoteId:quoteId.value.trim()}})}finally{loading.value=false}}
function requestKey(prefix:string){return `${prefix}-${globalThis.crypto?.randomUUID?.()??`${Date.now()}-${Math.random().toString(36).slice(2)}`}`}
async function startRequirement(){if(!quote.value)return;starting.value=true;try{const r=await createRequirementRun(quote.value.id,sourceText.value.trim(),requestKey('requirement'));requirementRun.value=r.data;ElMessage.success('需求分析任务已创建');schedule('requirement')}finally{starting.value=false}}
async function startSelection(base?:SelectionCombo){if(!quote.value)return;selectionStarting.value=true;try{const r=await createSelectionRun(quote.value.id,requestKey('selection'),base?.id,base?.visualHash);selectionRun.value=r.data;ElMessage.success(base?'保持锁定重搭任务已创建':'选品搭配任务已创建');schedule('selection')}finally{selectionStarting.value=false}}
function schedule(kind:'requirement'|'selection'){const run=kind==='requirement'?requirementRun.value:selectionRun.value;if(!run||!isPending(run))return;clearTimer(kind);const id=window.setTimeout(()=>refreshRun(kind),1500);if(kind==='requirement')requirementTimer=id;else selectionTimer=id}
async function refreshRun(kind:'requirement'|'selection'){const current=kind==='requirement'?requirementRun.value:selectionRun.value;if(!alive||!current)return;const r=await getRun(current.id);if(kind==='requirement')requirementRun.value=r.data;else selectionRun.value=r.data;schedule(kind)}
async function cancelTask(run:FashionRun,kind:'requirement'|'selection'){await ElMessageBox.confirm('确认取消当前任务？','取消任务',{type:'warning'});const r=await cancelRun(run.id,run.rowVersion);if(kind==='requirement')requirementRun.value=r.data;else selectionRun.value=r.data;ElMessage.success('取消请求已提交');schedule(kind)}
async function applyRequirement(){if(!requirementRun.value||!quote.value)return;await ElMessageBox.confirm('AI 输出会作为人工确认后的需求写入当前方案，是否继续？','采用需求',{type:'warning'});applying.value=true;try{const r=await applyRequirementRun(requirementRun.value.id,requestKey('apply-requirement'),quote.value.rowVersion);quote.value=r.data;requirementRun.value=(await getRun(requirementRun.value.id)).data;await loadAll();ElMessage.success('需求草稿已采用并确认')}finally{applying.value=false}}
async function applySelection(){if(!selectionRun.value||!quote.value)return;await ElMessageBox.confirm('Java 将重新核对方案版本、候选商品、价格、库存和图片，再写入组合，是否继续？','采用搭配',{type:'warning'});const hashes:Record<string,string>={};for(const tier of selectionOutputTiers.value)for(const combo of tier.combinations)hashes[combo.combo_key]=combo.combo_visual_hash;selectionApplying.value=true;try{await applySelectionRun(selectionRun.value.id,requestKey('apply-selection'),quote.value.rowVersion,hashes);selectionRun.value=(await getRun(selectionRun.value.id)).data;await loadAll();ElMessage.success('搭配已采用并完成 SKU 尺码初始分配')}finally{selectionApplying.value=false}}
async function toggleLock(combo:SelectionCombo,slot:string){if(!quote.value)return;const slots=combo.lockedSlots.includes(slot)?combo.lockedSlots.filter(value=>value!==slot):[...combo.lockedSlots,slot];const r=await updateComboLocks(quoteId.value,combo,quote.value.rowVersion,slots);workspace.value=r.data;quote.value=r.data.quote;ElMessage.success(slots.includes(slot)?'槽位已锁定':'槽位已解锁')}
function openReplace(combo:SelectionCombo,row:SlotRow){replaceCombo.value=combo;replaceSlot.value=row;replaceVisible.value=true}
async function replaceWith(candidateRef:string){if(!quote.value||!replaceCombo.value||!replaceSlot.value)return;replacing.value=true;try{const r=await replaceComboCandidate(quote.value.id,replaceCombo.value,quote.value.rowVersion,replaceSlot.value.slotCode,candidateRef);workspace.value=r.data;quote.value=r.data.quote;replaceVisible.value=false;ElMessage.success('颜色款已替换，旧组合保留为非当前记录')}finally{replacing.value=false}}
function slotRows(combo:SelectionCombo):SlotRow[]{const rows=new Map<string,SlotRow>();for(const detail of combo.details){const row=rows.get(detail.slotCode)??{slotCode:detail.slotCode,categoryCode:detail.categoryCode,productName:detail.productName,colorName:detail.colorName,sizes:[]};row.sizes.push({skuCode:detail.skuCode,sizeCode:detail.sizeCode,qty:detail.qty});rows.set(detail.slotCode,row)}return [...rows.values()]}
function money(minor:number){return (minor/100).toFixed(2)}
function isPending(run:FashionRun){return ['queued','running','cancel_requested'].includes(run.status)}
function statusText(value:string){return({queued:'排队中',running:'执行中',succeeded:'已成功',failed:'失败',cancel_requested:'取消中',cancelled:'已取消'} as Record<string,string>)[value]??value}
function statusType(value:string){return value==='succeeded'?'success':value==='failed'?'danger':'warning'}
function capabilityText(value:RunCapability,name:string,missing:string){return({provider_disabled:`AI Runtime 未配置，${name}已禁用`,[missing]:`尚未发布${name} Agent 版本`,loading:'正在检查 AI 能力'} as Record<string,string>)[value.reason??'']??`${name}不可用：${value.reason??'未知原因'}`}
function clearTimer(kind:'requirement'|'selection'){const timer=kind==='requirement'?requirementTimer:selectionTimer;if(timer!==undefined)window.clearTimeout(timer);if(kind==='requirement')requirementTimer=undefined;else selectionTimer=undefined}
onMounted(async()=>{await loadCapabilities();if(quoteId.value)await loadAll()});onBeforeUnmount(()=>{alive=false;clearTimer('requirement');clearTimer('selection')})
</script>

<style scoped>.header,.actions{display:flex;align-items:center;justify-content:space-between;gap:8px}.actions{justify-content:flex-start;margin-top:16px}.mt20{margin-top:20px}.mt12{margin-top:12px}.mb20{margin-bottom:20px}.mb12{margin-bottom:12px}.ml8{margin-left:8px}.muted{color:var(--el-text-color-secondary);font-size:12px;margin:6px 0}.warning{color:var(--el-color-warning)}pre{max-height:420px;overflow:auto;padding:14px;background:var(--el-fill-color-light);white-space:pre-wrap;overflow-wrap:anywhere}.candidate-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}.candidate-card,.combo-card,.tier-result{margin-bottom:12px}.size-list{display:flex;gap:4px;flex-wrap:wrap;margin-top:8px}</style>
