<template>
  <div class="app-container">
    <el-alert title="方案是可修改草稿；复制会生成新版本，关闭后不可再编辑。切换客户会清空旧需求确认并重新校验当前价格事实。" type="info" :closable="false" show-icon class="mb20" />
    <el-form :inline="true" :model="query" @submit.prevent="load">
      <el-form-item label="关键词"><el-input v-model="query.keyword" clearable placeholder="方案号、标题、客户" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" clearable style="width:130px"><el-option label="草稿" value="draft" /><el-option label="已确认" value="confirmed" /><el-option label="已作废" value="void" /><el-option label="已关闭" value="closed" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="load">查询</el-button></el-form-item>
    </el-form>
    <div class="toolbar mb8"><el-button type="primary" v-hasPermi="['fashion:quote:list']" @click="router.push('/fashion/pool')">新建套装选品 / 继续选品</el-button><el-button type="primary" plain icon="Plus" v-hasPermi="['fashion:quote:add']" @click="openCreate">新建方案</el-button><el-button icon="Refresh" @click="load">刷新</el-button></div>
    <el-table v-loading="loading" :data="page.items" empty-text="没有可见方案">
      <el-table-column label="方案" min-width="180"><template #default="{row}"><strong>{{row.title}}</strong><br><code>{{row.quoteNo}} / V{{row.versionNo}}</code></template></el-table-column>
      <el-table-column prop="customerName" label="客户" min-width="140" /><el-table-column prop="requestedQty" label="套数" width="80" />
      <el-table-column label="预算" width="130"><template #default="{row}">{{row.budget==null?'未填写':`¥${row.budget} / ${row.budgetBasis==='total'?'总额':'每套'}`}}</template></el-table-column>
      <el-table-column label="需求" min-width="120"><template #default="{row}"><el-tag :type="row.requirementConfirmed?'success':'warning'">{{row.requirementConfirmed?'已确认':'待确认'}}</el-tag></template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="statusType(row.status)">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="360" fixed="right"><template #default="{row}"><el-button link type="primary" @click="workbench(row)">选品</el-button><el-button link type="primary" v-hasPermi="['fashion:quote:query']" @click="openPricing(row)">报价</el-button><el-button v-if="row.status==='confirmed'" link type="success" v-hasPermi="['fashion:quote:export']" @click="delivery(row)">交付</el-button><el-button v-if="row.status==='draft'" link type="primary" v-hasPermi="['fashion:quote:edit']" @click="openEdit(row)">编辑</el-button><el-button link type="primary" v-hasPermi="['fashion:quote:add']" @click="copy(row)">复制版本</el-button><el-button v-if="row.status==='draft'" link type="danger" v-hasPermi="['fashion:quote:edit']" @click="close(row)">关闭</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="page.total>0" :total="page.total" v-model:page="query.page" v-model:limit="query.pageSize" @pagination="load" />

    <el-dialog v-model="visible" :title="editingId?'编辑方案草稿':'新建方案草稿'" width="820px" append-to-body>
      <el-form :model="form" label-width="110px">
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="客户" required><el-select v-model="form.customerId" filterable style="width:100%"><el-option v-for="c in customers" :key="c.id" :label="`${c.code} / ${c.name}`" :value="c.id" /></el-select></el-form-item></el-col><el-col :span="12"><el-form-item label="方案标题" required><el-input v-model="form.title" /></el-form-item></el-col></el-row>
        <el-form-item label="需求原文"><el-input v-model="form.requirementText" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item>
        <el-row :gutter="16"><el-col :span="6"><el-form-item label="套数" required><el-input-number v-model="form.requestedQty" :min="1" :max="1000000" /></el-form-item></el-col><el-col :span="6"><el-form-item label="预算"><el-input-number v-model="form.budget" :min="0.01" :precision="2" /></el-form-item></el-col><el-col :span="6"><el-form-item label="预算口径"><el-select v-model="form.budgetBasis"><el-option label="总额" value="total" /><el-option label="每套" value="per_set" /></el-select></el-form-item></el-col><el-col :span="6"><el-form-item label="仓库"><el-select v-model="form.warehouseCode"><el-option v-for="w in warehouses" :key="w.value" :label="w.label" :value="w.value" /></el-select></el-form-item></el-col></el-row>
        <el-row :gutter="16"><el-col :span="8"><el-form-item label="报价模式"><el-select v-model="form.quoteMode"><el-option label="备选方案" value="alternatives" /><el-option label="组合报价" value="combined" /></el-select></el-form-item></el-col><el-col :span="8"><el-form-item label="递进档位"><el-switch v-model="form.progressive" /></el-form-item></el-col></el-row>
        <el-divider content-position="left">品类档位（1～4 档，每档候选 1～3）</el-divider>
        <div v-for="(tier,index) in form.tiers" :key="index" class="tier-row"><span>第 {{index+1}} 档</span><el-select v-model="tier.slots" multiple filterable collapse-tags style="flex:1"><el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" /></el-select><el-input-number v-model="tier.candidateCount" :min="1" :max="3" /><el-button link type="danger" @click="removeTier(index)">删除</el-button></div>
        <el-button :disabled="form.tiers.length>=4" @click="addTier">增加档位</el-button>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>

    <el-drawer v-model="pricingVisible" title="确定性报价与库存复核" size="82%" destroy-on-close>
      <div v-loading="pricingLoading" v-if="pricing">
        <el-alert title="金额由 Java BigDecimal 计算；备选报价互不相加，合并采购会跨组合汇总同一 SKU。正式确认后当前三层记录冻结。" type="info" :closable="false" show-icon class="mb16" />
        <el-descriptions :column="5" border class="mb16"><el-descriptions-item label="报价版本">{{pricing.quoteNo}} / V{{pricing.versionNo}}</el-descriptions-item><el-descriptions-item label="客户">{{pricing.customerName}}</el-descriptions-item><el-descriptions-item label="方案套数">{{pricing.requestedQty}}</el-descriptions-item><el-descriptions-item label="状态"><el-tag :type="pricing.status==='confirmed'?'success':'warning'">{{pricing.status==='confirmed'?'已确认':'草稿'}}</el-tag></el-descriptions-item><el-descriptions-item label="仓库">{{pricing.warehouseCode}}</el-descriptions-item></el-descriptions>
        <el-form label-width="105px" :disabled="pricing.status!=='draft'">
          <el-row :gutter="14"><el-col :span="6"><el-form-item label="采购口径"><el-select v-model="pricing.mode"><el-option label="备选报价" value="alternatives" /><el-option label="合并采购" value="combined" /></el-select></el-form-item></el-col><el-col :span="6"><el-form-item label="税费口径"><el-select v-model="pricing.taxMode"><el-option label="含税" value="included" /><el-option label="未税加税" value="excluded" /></el-select></el-form-item></el-col><el-col :span="6"><el-form-item label="税率 %"><el-input v-model="pricing.taxRate" :disabled="pricing.taxMode==='included'" placeholder="未税模式必填，0也要填" /></el-form-item></el-col><el-col :span="6"><el-form-item label="运费应税"><el-switch v-model="pricing.feeTaxable" /></el-form-item></el-col></el-row>
          <el-row :gutter="14"><el-col :span="6"><el-form-item label="优惠方式"><el-select v-model="pricing.discountType"><el-option label="优惠比例" value="percent" /><el-option label="固定优惠" value="fixed" /></el-select></el-form-item></el-col><el-col :span="6"><el-form-item label="优惠比例 %"><el-input v-model="pricing.discountRate" :disabled="pricing.discountType==='fixed'" /></el-form-item></el-col><el-col :span="6"><el-form-item label="固定优惠"><el-input v-model="pricing.fixedDiscount" :disabled="pricing.discountType==='percent'" /></el-form-item></el-col><el-col :span="6"><el-form-item label="运费"><el-input v-model="pricing.freight" /></el-form-item></el-col></el-row>
          <el-row :gutter="14"><el-col :span="6"><el-form-item label="有效天数"><el-input-number v-model="pricing.validDays" :min="1" :max="30" /></el-form-item></el-col><el-col :span="18"><el-form-item label="公开备注"><el-input v-model="pricing.publicNote" maxlength="2000" show-word-limit /></el-form-item></el-col></el-row>
        </el-form>
        <el-alert v-for="issue in pricing.issues" :key="`${issue.code}:${issue.message}`" :title="`${issue.code}：${issue.message}`" type="error" :closable="false" show-icon class="mb8" />
        <el-card v-for="combo in pricing.combos" :key="combo.id" shadow="never" class="mb12">
          <template #header><div class="pricing-header"><el-checkbox v-model="combo.selected" :disabled="pricing.status!=='draft'">{{combo.name}}（{{combo.categoryCount}} 品类 / {{combo.setQty}} 套）</el-checkbox><div><el-tag v-if="combo.allocationConfirmed" type="success">配比已确认</el-tag><el-tag v-if="combo.selectedImageId" :type="combo.selectedImageValid?'success':'danger'" class="ml8">采用图 {{combo.selectedImageNo}}{{combo.selectedImageValid?'':' 已失效'}}</el-tag></div></div></template>
          <el-table :data="combo.lines" size="small"><el-table-column prop="slotCode" label="槽位" width="90" /><el-table-column label="商品" min-width="190"><template #default="{row}">{{row.productName}}<br><code>{{row.skuCode}} / {{row.colorName}} / {{row.sizeCode}}</code></template></el-table-column><el-table-column label="当前价" width="100"><template #default="{row}">¥{{row.sourcePrice}}</template></el-table-column><el-table-column label="报价单价" width="145"><template #default="{row}"><el-input v-model="row.quotePrice" :disabled="pricing.status!=='draft'||!combo.selected" /></template></el-table-column><el-table-column label="数量" width="145"><template #default="{row}"><el-input-number v-model="row.qty" :min="1" :disabled="pricing.status!=='draft'||!combo.selected" /></template></el-table-column><el-table-column label="当前库存" width="120"><template #default="{row}">{{row.stockQty??'未知'}}<el-tag v-if="row.stockChanged" size="small" type="warning">已变化</el-tag></template></el-table-column><el-table-column label="事实变化" width="150"><template #default="{row}"><el-tag v-if="row.priceChanged" size="small" type="warning">价格</el-tag><el-tag v-if="row.imageChanged" size="small" type="warning">图片</el-tag></template></el-table-column></el-table>
          <div v-if="pricing.mode==='alternatives'" class="combo-total">最大可供 {{comboCapacity(combo.id)}} 套；商品 {{comboAmount(combo.id,'subtotal')}} − 优惠 {{comboAmount(combo.id,'discountAmount')}} ＋ 运费 {{comboAmount(combo.id,'freight')}} ＋ 税 {{comboAmount(combo.id,'taxAmount')}} ＝ <strong>¥{{comboAmount(combo.id,'totalAmount')}}</strong></div>
        </el-card>
        <el-card v-if="pricing.mode==='combined'" shadow="never" class="summary"><span>最大可供 {{pricing.maximumAvailableSets??'未知'}} 套；商品 {{pricing.subtotal??'—'}} − 优惠 {{pricing.discountAmount??'—'}} ＋ 运费 {{pricing.freight}} ＋ 税 {{pricing.taxAmount??'—'}}</span><strong>应付 ¥{{pricing.totalAmount??'—'}}</strong></el-card>
        <el-alert v-if="pricing.approvalRequired" :title="pricing.approvalValid?'负责人例外有效':'单价调整或优惠超过 10%，正式确认前需要负责人例外'" :type="pricing.approvalValid?'success':'warning'" :closable="false" show-icon class="mt12" />
        <div class="pricing-actions"><el-button v-if="pricing.status==='draft'" type="primary" :loading="pricingSaving" v-hasPermi="['fashion:quote:edit']" @click="savePricingDraft">保存并重新核价</el-button><el-button v-if="pricing.status==='draft'&&pricing.approvalRequired&&!pricing.approvalValid" v-hasPermi="['fashion:quote:edit']" @click="requestApproval">申请例外</el-button><el-button v-if="pricing.status==='draft'&&pricing.approvalRequired&&!pricing.approvalValid" v-hasPermi="['fashion:quote:approve']" @click="approvePricing">负责人批准</el-button><el-button v-if="pricing.status==='draft'" type="success" :disabled="pricing.issues.length>0||(pricing.approvalRequired&&!pricing.approvalValid)" v-hasPermi="['fashion:quote:confirm']" @click="confirmPricing">确认并冻结版本</el-button><el-button v-if="pricing.status==='confirmed'" v-hasPermi="['fashion:quote:add']" @click="copyPricingRevision">复制为新修订</el-button></div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import { listCustomers } from '@/api/fashion/customer'
import { closeQuote, copyQuote, createQuote, listQuotes, updateQuote, type QuoteDraftBody } from '@/api/fashion/quote'
import { approveQuote, confirmQuote, getQuotePricing, requestQuoteApproval, saveQuotePricing, type QuotePricingBody } from '@/api/fashion/pricing'
import type { FashionCustomer, FashionQuote, QuotePricingWorkspace, QuoteTier } from '@/api/fashion/types'

interface Option{label:string;value:string}
const router=useRouter(), loading=ref(false), saving=ref(false), visible=ref(false), editingId=ref('')
const query=reactive({status:'',keyword:'',page:1,pageSize:20}), page=reactive({items:[] as FashionQuote[],total:0})
const customers=ref<FashionCustomer[]>([]),categories=ref<Option[]>([]),warehouses=ref<Option[]>([])
const pricingVisible=ref(false),pricingLoading=ref(false),pricingSaving=ref(false),pricing=ref<QuotePricingWorkspace>()
const form=reactive<QuoteDraftBody>(blank())
let controller:AbortController|undefined
function blank():QuoteDraftBody{return{customerId:'',title:'',requirementText:'',requirements:{preferredColors:[],exclusions:[]},requestedQty:1,budget:undefined,budgetBasis:'total',quoteMode:'alternatives',progressive:true,tiers:[{count:1,slots:[],candidateCount:3}],warehouseCode:'',rowVersion:0}}
async function load(){controller?.abort();controller=new AbortController();loading.value=true;try{const r=await listQuotes(query,controller.signal);page.items=r.data.items;page.total=r.data.total}finally{loading.value=false}}
async function loadOptions(){const [c,cat,wh]=await Promise.all([listCustomers({status:'active',page:1,pageSize:100}),getDicts('fashion_product_category'),getDicts('fashion_warehouse')]);customers.value=c.data.items;categories.value=(cat.data??[]).filter((x:any)=>String(x.status)==='0').map((x:any)=>({label:String(x.dictLabel),value:String(x.dictValue)}));warehouses.value=(wh.data??[]).filter((x:any)=>String(x.status)==='0').map((x:any)=>({label:String(x.dictLabel),value:String(x.dictValue)}))}
function openCreate(){Object.assign(form,blank());editingId.value='';visible.value=true}
function extractRequirement(row:FashionQuote){const r=row.requirement as any;return{audience:r.audience,scene:r.scene,season:r.season,style:r.style,preferredColors:r.preferred_colors??[],exclusions:r.exclusions??[],deliveryDate:r.delivery_date}}
function extractTiers(row:FashionQuote):QuoteTier[]{const groups=(row.comboTemplate as any).groups??[];return groups.map((g:any)=>({count:Number(g.count),slots:[...(g.slots??[])],candidateCount:Number(g.candidate_count)}))}
function openEdit(row:FashionQuote){editingId.value=row.id;Object.assign(form,{customerId:row.customerId,title:row.title,requirementText:row.requirementText??'',requirements:extractRequirement(row),requestedQty:row.requestedQty,budget:row.budget,budgetBasis:row.budgetBasis,quoteMode:row.quoteMode,progressive:row.progressive,tiers:extractTiers(row),warehouseCode:row.warehouseCode,rowVersion:row.rowVersion});visible.value=true}
function addTier(){const previous=form.tiers.at(-1)?.slots??[];form.tiers.push({count:Math.min(4,previous.length+1),slots:[...previous],candidateCount:3})}
function removeTier(index:number){form.tiers.splice(index,1)}
async function save(){form.tiers.forEach(t=>t.count=t.slots.length);saving.value=true;try{if(editingId.value)await updateQuote(editingId.value,form);else await createQuote(form);ElMessage.success('方案草稿已保存');visible.value=false;await load()}finally{saving.value=false}}
async function copy(row:FashionQuote){await copyQuote(row.id);ElMessage.success('已生成新版本');await load()}
async function close(row:FashionQuote){await ElMessageBox.confirm(`确认关闭“${row.title}”？`,'关闭方案',{type:'warning'});await closeQuote(row.id,row.rowVersion);ElMessage.success('方案已关闭');await load()}
function workbench(row:FashionQuote){router.push({path:'/fashion/workbench',query:{quoteId:row.id}})}
function delivery(row:FashionQuote){router.push({path:'/fashion/delivery',query:{quoteId:row.id}})}
async function openPricing(row:FashionQuote){pricingVisible.value=true;pricingLoading.value=true;try{pricing.value=(await getQuotePricing(row.id)).data}finally{pricingLoading.value=false}}
function pricingBody():QuotePricingBody{const value=pricing.value!;if(value.taxMode==='included')value.taxRate=undefined;if(value.discountType==='percent')value.fixedDiscount='0.00';else value.discountRate='0.00';const selected=value.combos.filter(c=>c.selected);return{mode:value.mode,taxMode:value.taxMode,taxRate:value.taxRate,feeTaxable:value.feeTaxable,discountType:value.discountType,discountRate:value.discountRate,fixedDiscount:value.fixedDiscount,freight:value.freight,validDays:value.validDays,publicNote:value.publicNote,selectedComboIds:selected.map(c=>c.id),lines:selected.flatMap(c=>c.lines.map(line=>({detailId:line.id,qty:line.qty,quotePrice:line.quotePrice??line.sourcePrice??'0.00'}))),rowVersion:value.rowVersion}}
async function savePricingDraft(){if(!pricing.value)return;pricingSaving.value=true;try{pricing.value=(await saveQuotePricing(pricing.value.quoteId,pricingBody())).data;ElMessage.success('报价草稿已由 Java 重新核算')}finally{pricingSaving.value=false}}
async function requestApproval(){if(!pricing.value)return;const {value}=await ElMessageBox.prompt('填写本次单价或折扣例外原因','申请负责人例外',{inputPattern:/^.{2,500}$/,inputErrorMessage:'请输入 2～500 字原因'});pricing.value=(await requestQuoteApproval(pricing.value.quoteId,pricing.value.inputHash,value,pricing.value.rowVersion)).data;ElMessage.success('例外申请已记录')}
async function approvePricing(){if(!pricing.value)return;const {value}=await ElMessageBox.prompt('填写负责人批准说明；申请人不能自批','负责人批准',{inputPattern:/^.{2,500}$/,inputErrorMessage:'请输入 2～500 字说明'});pricing.value=(await approveQuote(pricing.value.quoteId,pricing.value.inputHash,value,pricing.value.rowVersion)).data;ElMessage.success('负责人例外已批准')}
async function confirmPricing(){if(!pricing.value)return;await ElMessageBox.confirm('确认后当前方案、组合和明细将冻结；后续修改必须复制新修订。是否继续？','确认正式报价',{type:'warning'});pricing.value=(await confirmQuote(pricing.value.quoteId,pricing.value.inputHash,pricing.value.rowVersion)).data;ElMessage.success('报价版本已确认并冻结');await load()}
async function copyPricingRevision(){if(!pricing.value)return;await copyQuote(pricing.value.quoteId);ElMessage.success('已复制三层记录为新修订');pricingVisible.value=false;await load()}
function comboAmount(id:string,field:'subtotal'|'discountAmount'|'freight'|'taxAmount'|'totalAmount'){const value=pricing.value?.calculatedCombos.find(c=>c.id===id)?.[field];return value??'—'}
function comboCapacity(id:string){return pricing.value?.calculatedCombos.find(c=>c.id===id)?.maximumAvailableSets??'未知'}
function statusLabel(status:string){return({draft:'草稿',confirmed:'已确认',void:'已作废',closed:'已关闭'} as Record<string,string>)[status]??status}
function statusType(status:string){return status==='confirmed'?'success':status==='draft'?'warning':status==='void'?'danger':'info'}
onMounted(async()=>{await loadOptions();await load()});onBeforeUnmount(()=>controller?.abort())
</script>

<style scoped>.toolbar{display:flex;gap:8px}.tier-row{display:flex;align-items:center;gap:12px;margin-bottom:12px}.tier-row>span{width:64px}.mb16{margin-bottom:16px}.mb12{margin-bottom:12px}.mb8{margin-bottom:8px}.mt12{margin-top:12px}.ml8{margin-left:8px}.pricing-header,.summary,.pricing-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.combo-total{text-align:right;margin-top:12px}.summary strong{font-size:22px;color:var(--el-color-primary)}.pricing-actions{justify-content:flex-end;margin-top:18px;flex-wrap:wrap}</style>
