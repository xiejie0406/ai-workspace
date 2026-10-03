<template>
  <div class="app-container fashion-import">
    <el-alert title="默认导入13个基本商品字段。价格、库存和图片从商品或库存上下文维护；未选字段、缺失可选列和空单元格不会覆盖已有值。" type="info" :closable="false" show-icon class="mb20" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb20" />
    <el-card shadow="never" v-loading="initializing">
      <el-form label-width="110px">
        <el-form-item label="导入字段">
          <div class="field-options"><el-checkbox v-for="key in basicFields" :key="key" :model-value="selectedFields.includes(key)" :disabled="requiredFields.includes(key)" @update:model-value="toggleField(key,Boolean($event))">{{fields?.mapping[key]}}{{requiredFields.includes(key)?'（必填）':''}}</el-checkbox></div>
        </el-form-item>
        <el-form-item label="选品身份"><div class="field-options"><el-checkbox v-for="key in identityFields" :key="key" :model-value="selectedFields.includes(key)" @update:model-value="toggleField(key,Boolean($event))">{{fields?.mapping[key]}}</el-checkbox></div></el-form-item>
        <el-collapse class="mb20"><el-collapse-item title="高级商品字段（按需选择，保留旧模板兼容）"><div class="field-options"><el-checkbox v-for="key in advancedFields" :key="key" :model-value="selectedFields.includes(key)" @update:model-value="toggleField(key,Boolean($event))">{{fields?.mapping[key]}}</el-checkbox></div></el-collapse-item></el-collapse>
        <el-form-item label="商品模板"><el-button :disabled="!fields" :loading="downloading" @click="downloadSelectedTemplate">下载已选 {{selectedFields.length}} 列模板</el-button></el-form-item>
        <el-form-item label="来源范围"><el-select v-model="sourceCode" style="width:240px"><el-option v-for="item in sources" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="业务时间"><el-date-picker v-model="asOf" type="datetime" :clearable="false" /></el-form-item>
        <el-form-item label="文件"><input type="file" accept=".csv,.xlsx" aria-label="选择商品导入文件" @change="selectFile"></el-form-item>
        <el-collapse class="mb20"><el-collapse-item title="已选字段映射（填写文件中的实际列名）"><el-form-item v-for="key in selectedFields" :key="key" :label="fields?.mapping[key]"><el-input v-model="mapping[key]" style="max-width:360px" /></el-form-item></el-collapse-item></el-collapse>
        <el-form-item label="显式清空"><el-checkbox-group v-model="clearFields"><el-checkbox v-for="key in clearableFields" :key="key" :value="key">{{fields?.mapping[key]}}</el-checkbox></el-checkbox-group><span class="hint">仅选中且允许清空的字段；预览中核对清空差异。</span></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" :disabled="!file || !sourceCode || !fields" v-hasPermi="['fashion:product:import']" @click="preview">校验并预览</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-card v-if="batch" shadow="never" class="mt20">
      <template #header><div class="header"><span>{{batch.batchNo}} · {{batch.status}}</span><el-button type="success" :loading="loading" :disabled="batch.status !== 'validated'" v-hasPermi="['fashion:product:import']" @click="publish">确认生效</el-button></div></template>
      <el-descriptions :column="3" border><el-descriptions-item label="数据行">{{batch.actualCount}}</el-descriptions-item><el-descriptions-item label="错误行">{{batch.errorCount}}</el-descriptions-item><el-descriptions-item label="导入字段">{{selectedFields.length}} 列</el-descriptions-item></el-descriptions>
      <el-button class="mt20" @click="exportImportErrors(batch.details)">下载错误明细</el-button>
      <el-table :data="batch.details" class="mt20" empty-text="没有数据行">
        <el-table-column type="expand"><template #default="{row}"><div class="diff-fields"><div v-for="field in productFieldDiffs(row)" :key="field.key"><strong>{{fields?.mapping[field.key] || field.key}}</strong>：{{display(field.before)}} → {{display(field.after)}} <el-tag v-if="field.cleared" type="warning">清空</el-tag><span v-else-if="!field.changed">（保留原值）</span></div></div></template></el-table-column>
        <el-table-column prop="sourceRowNo" label="文件行" width="80" /><el-table-column prop="businessKey" label="来源 / 规格SKU" min-width="190" />
        <el-table-column prop="changeType" label="差异" width="100" /><el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="原值" min-width="260"><template #default="{row}">{{productDiffSummary(row,fields?.mapping,'before')}}</template></el-table-column>
        <el-table-column label="拟生效值" min-width="260"><template #default="{row}">{{productDiffSummary(row,fields?.mapping,'after')}}</template></el-table-column>
        <el-table-column label="清空字段" min-width="180"><template #default="{row}">{{productFieldDiffs(row).filter(field=>field.cleared).map(field=>fields?.mapping[field.key]||field.key).join('、')||'无'}}</template></el-table-column>
        <el-table-column label="行级错误" min-width="280"><template #default="{row}"><div v-for="item in row.errors" :key="item.field+'-'+item.code" class="row-error">{{fields?.mapping[item.field] || item.field}}：{{item.message}}</div><span v-if="!row.errors.length">—</span></template></el-table-column>
      </el-table>
    </el-card>
    <ImportHistory ref="history" kind="product" @open="openHistory" />
  </div>
</template>
<script setup lang="ts">
import ImportHistory from './components/ImportHistory.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import { getProductImport, getProductFields, getProductTemplate, previewProductImport, publishProductImport, type ProductImportFields } from '@/api/fashion/importBatch'
import { saveBlob, exportImportErrors } from '@/api/fashion/download'
import type { ImportBatchView } from '@/api/fashion/types'
import { importRequiredFields, initialImportSelection, normalizeImportSelection, selectedImportMapping, SELECTION_IDENTITY_FIELDS } from './fieldSelection'
import { productFieldDiffs, productDiffSummary } from './productDiff'
const history=ref<InstanceType<typeof ImportHistory>>(),fields=ref<ProductImportFields>(),selectedFields=ref<string[]>([]),mapping=ref<Record<string,string>>({})
const sources=ref<{label:string;value:string}[]>([]),sourceCode=ref(''),asOf=ref(new Date()),clearFields=ref<string[]>([]),file=ref<File>(),loading=ref(false),initializing=ref(false),downloading=ref(false),error=ref(''),batch=ref<ImportBatchView>()
let inputRevision=0
const requiredFields=computed(()=>fields.value?importRequiredFields(fields.value):[])
const basicFields=computed(()=>fields.value?initialImportSelection(fields.value):[])
const identityFields=computed(()=>SELECTION_IDENTITY_FIELDS.filter(key=>fields.value?.mapping[key]))
const advancedFields=computed(()=>Object.keys(fields.value?.mapping??{}).filter(key=>!basicFields.value.includes(key)&&!identityFields.value.includes(key)))
const clearableFields=computed(()=>['brand','material','jdItemId','jdUrl'].filter(key=>selectedFields.value.includes(key)))
function toggleField(key:string,enabled:boolean){if(!fields.value)return;selectedFields.value=normalizeImportSelection(enabled?[...selectedFields.value,key]:selectedFields.value.filter(item=>item!==key),fields.value);clearFields.value=clearFields.value.filter(item=>selectedFields.value.includes(item))}
function selectFile(event:Event){file.value=(event.target as HTMLInputElement).files?.[0];batch.value=undefined}
function display(value:unknown){return value==null?'未维护':typeof value==='object'?JSON.stringify(value):String(value)}
async function action(fn:()=>Promise<void>){error.value='';loading.value=true;try{await fn()}catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{loading.value=false}}
async function openHistory(id:string){await action(async()=>{batch.value=(await getProductImport(id)).data})}
async function downloadSelectedTemplate(){downloading.value=true;error.value='';try{const blob=await getProductTemplate(selectedFields.value);if(blob.type.includes('json'))throw new Error(JSON.parse(await blob.text()).msg||'模板下载失败');saveBlob(blob,'商品资料-已选字段.xlsx')}catch(e){error.value=e instanceof Error?e.message:'模板下载失败'}finally{downloading.value=false}}
async function preview(){if(!file.value||!asOf.value)return;const revision=inputRevision;await action(async()=>{const response=await previewProductImport({file:file.value!,sourceCode:sourceCode.value,asOf:asOf.value.toISOString(),selectedFields:selectedFields.value,clearFields:clearFields.value,mapping:selectedImportMapping(selectedFields.value,mapping.value)});if(revision!==inputRevision){error.value='输入在预览期间已变化，请重新预览';return}batch.value=response.data;ElMessage.success(response.data.errorCount?'预览完成，请修正行错':'预览通过，可确认生效')})}
async function publish(){if(!batch.value)return;await action(async()=>{batch.value=(await publishProductImport(batch.value!.batchId,batch.value!.rowVersion)).data;ElMessage.success('商品资料已原子发布');await history.value?.load()})}
// 修改输入后旧预览不再允许发布，避免界面配置和冻结批次不一致。
watch([selectedFields,mapping,sourceCode,asOf,clearFields,file],()=>{inputRevision++;batch.value=undefined},{deep:true,flush:'sync'})
onMounted(async()=>{initializing.value=true;try{const [metadata,response]=await Promise.all([getProductFields(),getDicts('fashion_product_source')]);fields.value=metadata.data;mapping.value={...metadata.data.mapping};selectedFields.value=initialImportSelection(metadata.data);sources.value=(response.data??[]).filter((item:any)=>String(item.status)==='0').map((item:any)=>({label:String(item.dictLabel),value:String(item.dictValue)}))}catch(e){error.value=e instanceof Error?e.message:'字段加载失败'}finally{initializing.value=false}})
</script>
<style scoped>.header{display:flex;justify-content:space-between;align-items:center;gap:12px}.mt20{margin-top:20px}.row-error{color:var(--el-color-danger);line-height:1.5}.field-options{display:flex;flex-wrap:wrap;gap:4px 12px}.hint{color:var(--el-text-color-secondary);margin-left:12px}.diff-fields{padding:12px 24px;line-height:1.8}</style>
