<template>
  <div class="app-container catalog-import">
    <el-alert :title="notice" type="warning" :closable="false" show-icon class="mb20" />
    <el-card shadow="never">
      <template #header><strong>{{ title }}</strong></template>
      <el-form label-width="120px">
        <el-form-item label="固定模板"><el-button @click="downloadTemplate(kind)">下载 Excel 模板</el-button></el-form-item>
        <el-form-item label="来源范围">
          <el-select v-model="sourceCode" style="width:240px"><el-option v-for="item in sources" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        </el-form-item>
        <el-form-item label="品类子范围">
          <el-select v-model="categoryCode" clearable placeholder="全部在售 SKU" style="width:240px"><el-option v-for="item in categories" :key="item.value" :label="item.label" :value="item.value" /></el-select>
          <span class="scope-tip">这是范围全量，不是全库更新。</span>
        </el-form-item>
        <el-form-item v-if="kind==='stock'" label="库存仓库">
          <el-select v-model="warehouseCode" style="width:240px"><el-option v-for="item in warehouses" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        </el-form-item>
        <el-form-item label="业务时间"><el-date-picker v-model="asOf" type="datetime" /></el-form-item>
        <el-form-item label="全量文件"><input type="file" accept=".csv,.xlsx" @change="selectFile"></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!canPreview" v-hasPermi="[permission]" @click="preview">严格校验并预览</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="batch" shadow="never" class="mt20">
      <template #header>
        <div class="header"><span>{{ batch.batchNo }} · {{ statusLabel(batch.status) }}</span>
          <div>
            <el-button v-if="batch.status==='conflict'" :loading="loading" @click="preview">按当前数据重新校验</el-button>
            <el-button v-if="batch.status==='success'" v-hasPermi="['fashion:import:restore']" @click="createRestore">生成恢复预览</el-button>
            <el-button type="success" :disabled="batch.status!=='validated'" :loading="loading" v-hasPermi="[permission]" @click="publish">确认原子发布</el-button>
          </div>
        </div>
      </template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="范围应覆盖">{{ batch.expectedCount }}</el-descriptions-item>
        <el-descriptions-item label="文件实际行">{{ batch.actualCount }}</el-descriptions-item>
        <el-descriptions-item label="阻断项">{{ batch.errorCount }}</el-descriptions-item>
        <el-descriptions-item label="操作">{{ batch.operationType==='restore'?'历史恢复':'全量导入' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="batch.errorMessage" :title="batch.errorMessage" type="error" :closable="false" class="mt20" />
      <el-button class="mt20" @click="exportImportErrors(batch.details)">下载错误明细</el-button>
      <el-table :data="batch.details" class="mt20" empty-text="没有导入明细">
        <el-table-column prop="sourceRowNo" label="文件行" width="80"><template #default="{row}">{{ row.rowType==='missing'?'缺失':row.sourceRowNo }}</template></el-table-column>
        <el-table-column prop="businessKey" label="来源 / SKU" min-width="180" />
        <el-table-column prop="changeType" label="差异" width="100" />
        <el-table-column label="原值" min-width="180"><template #default="{row}"><code>{{ compact(row.beforeData) }}</code></template></el-table-column>
        <el-table-column label="拟生效值" min-width="180"><template #default="{row}"><code>{{ compact(row.normalizedData) }}</code></template></el-table-column>
        <el-table-column label="行级错误" min-width="260"><template #default="{row}"><div v-for="error in row.errors" :key="`${error.field}-${error.code}`" class="row-error">{{ error.field }}：{{ error.message }}</div><span v-if="!row.errors.length">—</span></template></el-table-column>
      </el-table>
    </el-card>
    <ImportHistory ref="history" :kind="kind" @open="openHistory" />
  </div>
</template>

<script setup lang="ts">
import ImportHistory from './ImportHistory.vue'
import { getCatalogImport } from '@/api/fashion/catalogImport'
import { downloadTemplate, exportImportErrors } from '@/api/fashion/download'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import { catalogTemplateUrl, previewCatalogImport, publishCatalogImport, restoreCatalogImport } from '@/api/fashion/catalogImport'
import type { CatalogImportBatchView, CatalogImportKind } from '@/api/fashion/types'

interface Option { label:string; value:string }
const props=defineProps<{kind:CatalogImportKind}>()
const kind=props.kind
const title=kind==='price'?'当前销售价严格全量更新':'当前库存严格全量更新'
const notice=kind==='price'
  ?'范围在预览时冻结，缺行、重复键、空值、币种/口径冲突或旧时间都会阻断整批；价格与库存互不修改。'
  :'范围在预览时冻结，缺行、空值、未知 SKU 或旧时间都会阻断整批；明确的 0 表示缺货，不会把缺行当作 0。'
const permission=kind==='price'?'fashion:price:import':'fashion:stock:import'
const sources=ref<Option[]>([]),categories=ref<Option[]>([]),warehouses=ref<Option[]>([])
const sourceCode=ref(''),categoryCode=ref(''),warehouseCode=ref(''),asOf=ref(new Date()),file=ref<File>(),loading=ref(false),batch=ref<CatalogImportBatchView>()
const canPreview=computed(()=>!!file.value&&!!sourceCode.value&&(kind==='price'||!!warehouseCode.value))

function selectFile(event:Event){file.value=(event.target as HTMLInputElement).files?.[0]}
function mapOptions(response:any):Option[]{return (response.data??[]).filter((item:any)=>String(item.status)==='0').map((item:any)=>({label:String(item.dictLabel),value:String(item.dictValue)}))}
async function preview(){if(!file.value)return;loading.value=true;try{const response=await previewCatalogImport({kind,file:file.value,sourceCode:sourceCode.value,categoryCode:categoryCode.value||undefined,warehouseCode:kind==='stock'?warehouseCode.value:undefined,asOf:asOf.value.toISOString()});batch.value=response.data;ElMessage.success(response.data.errorCount?'校验完成，整批尚不可发布':'严格全量校验通过')}finally{loading.value=false}}
async function publish(){if(!batch.value)return;await ElMessageBox.confirm(`确认原子发布 ${batch.value.expectedCount} 个范围 SKU？`,'发布确认',{type:'warning'});loading.value=true;try{const response=await publishCatalogImport(kind,batch.value.batchId,batch.value.rowVersion);batch.value=response.data;ElMessage.success(kind==='price'?'当前价格已全部生效':'当前库存已全部生效');await history.value?.load()}finally{loading.value=false}}
async function createRestore(){if(!batch.value)return;const source=batch.value;const response=await restoreCatalogImport(kind,source.batchId,globalThis.crypto?.randomUUID?.()??`${Date.now()}-${source.batchId}`,`从批次 ${source.batchNo} 恢复`);batch.value=response.data;ElMessage.warning('已生成新的恢复预览，请核对前后值后再发布')}
const history=ref<InstanceType<typeof ImportHistory>>()
async function openHistory(id:string){batch.value=(await getCatalogImport(kind,id)).data}
function compact(value?:Record<string,unknown>){if(!value)return '—';const keys=kind==='price'?['salePrice','currency','taxMode','asOf']:['availableQty','asOf'];return keys.filter(key=>value[key]!==undefined).map(key=>`${key}=${String(value[key])}`).join(' · ')||'无当前值'}
function statusLabel(status:string){return ({validated:'待发布',invalid:'校验未通过',publishing:'发布中',success:'已成功',conflict:'数据冲突'} as Record<string,string>)[status]??status}
onMounted(async()=>{const [source,category,warehouse]=await Promise.all([getDicts('fashion_product_source'),getDicts('fashion_product_category'),getDicts('fashion_warehouse')]);sources.value=mapOptions(source);categories.value=mapOptions(category);warehouses.value=mapOptions(warehouse);sourceCode.value=sources.value[0]?.value??'';warehouseCode.value=warehouses.value[0]?.value??''})
</script>

<style scoped>.header{display:flex;justify-content:space-between;align-items:center;gap:16px}.mt20{margin-top:20px}.mb20{margin-bottom:20px}.scope-tip{margin-left:12px;color:var(--el-text-color-secondary)}.row-error{color:var(--el-color-danger);line-height:1.5}code{white-space:normal;word-break:break-word}</style>
