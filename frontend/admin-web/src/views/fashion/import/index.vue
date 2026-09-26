<template>
  <div class="app-container fashion-import">
    <el-alert title="普通商品资料导入不会修改当前价格或库存。请先预览差异，确认无行级错误后再发布。" type="warning" :closable="false" show-icon class="mb20" />
    <el-card shadow="never">
      <el-form label-width="110px">
        <el-form-item label="商品模板"><el-button @click="downloadTemplate('product')">下载 Excel 模板</el-button></el-form-item>
        <el-form-item label="来源范围"><el-select v-model="sourceCode" style="width:240px"><el-option v-for="item in sources" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="业务时间"><el-date-picker v-model="asOf" type="datetime" /></el-form-item>
        <el-form-item label="文件"><input type="file" accept=".csv,.xlsx" @change="selectFile"></el-form-item>
        <el-collapse class="mb20"><el-collapse-item title="字段映射（填写 Excel 中实际的列名）"><el-form-item v-for="(label,key) in defaultMapping" :key="key" :label="label"><el-input v-model="mapping[key]" style="max-width:360px" /></el-form-item></el-collapse-item></el-collapse>
        <el-form-item label="显式清空"><el-checkbox-group v-model="clearFields"><el-checkbox value="brand">品牌</el-checkbox><el-checkbox value="material">材质</el-checkbox><el-checkbox value="jdItemId">京东 ID</el-checkbox><el-checkbox value="jdUrl">京东链接</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" :disabled="!file || !sourceCode" v-hasPermi="['fashion:product:import']" @click="preview">校验并预览</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-card v-if="batch" shadow="never" class="mt20">
      <template #header><div class="header"><span>{{ batch.batchNo }} · {{ batch.status }}</span><el-button type="success" :disabled="batch.status !== 'validated'" v-hasPermi="['fashion:product:import']" @click="publish">确认生效</el-button></div></template>
      <el-descriptions :column="3" border><el-descriptions-item label="数据行">{{ batch.actualCount }}</el-descriptions-item><el-descriptions-item label="错误行">{{ batch.errorCount }}</el-descriptions-item><el-descriptions-item label="文件摘要"><code>{{ batch.fileHash.slice(0,16) }}…</code></el-descriptions-item></el-descriptions>
      <el-button class="mt20" @click="exportImportErrors(batch.details)">下载错误明细</el-button>
      <el-table :data="batch.details" class="mt20" empty-text="没有数据行">
        <el-table-column prop="sourceRowNo" label="文件行" width="80" /><el-table-column prop="businessKey" label="来源 / SKU" min-width="190" />
        <el-table-column prop="changeType" label="差异" width="100" /><el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="行级错误" min-width="280"><template #default="{ row }"><div v-for="error in row.errors" :key="`${error.field}-${error.code}`" class="row-error">{{ error.field }}：{{ error.message }}</div><span v-if="!row.errors.length">—</span></template></el-table-column>
      </el-table>
    </el-card>
    <ImportHistory ref="history" kind="product" @open="openHistory" />
  </div>
</template>

<script setup lang="ts">
import ImportHistory from './components/ImportHistory.vue'
import { getProductImport } from '@/api/fashion/importBatch'
import { downloadTemplate, exportImportErrors } from '@/api/fashion/download'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import { previewProductImport, getProductFields, publishProductImport } from '@/api/fashion/importBatch'
import type { ImportBatchView } from '@/api/fashion/types'
const history=ref<InstanceType<typeof ImportHistory>>()
async function openHistory(id:string){batch.value=(await getProductImport(id)).data}
const defaultMapping=ref<Record<string,string>>({}), mapping=ref<Record<string,string>>({})
interface Option { label:string; value:string }
const sources=ref<Option[]>([]), sourceCode=ref(''), asOf=ref(new Date()), clearFields=ref<string[]>([]), file=ref<File>(), loading=ref(false), batch=ref<ImportBatchView>()
function selectFile(event: Event){ file.value=(event.target as HTMLInputElement).files?.[0] }
async function preview(){ if(!file.value)return; loading.value=true; try{ const response=await previewProductImport({file:file.value,sourceCode:sourceCode.value,asOf:asOf.value.toISOString(),clearFields:clearFields.value,mapping:mapping.value}); batch.value=response.data; ElMessage.success(response.data.errorCount?'预览完成，请修正行错':'预览通过，可确认生效') } finally{loading.value=false} }
async function publish(){ if(!batch.value)return; const response=await publishProductImport(batch.value.batchId,batch.value.rowVersion); batch.value=response.data; ElMessage.success('商品资料已原子发布');await history.value?.load() }
onMounted(async()=>{const fields=await getProductFields();defaultMapping.value=fields.data.mapping;mapping.value={...fields.data.mapping};const response=await getDicts('fashion_product_source');sources.value=(response.data??[]).filter((item:any)=>String(item.status)==='0').map((item:any)=>({label:String(item.dictLabel),value:String(item.dictValue)}))})
</script>
<style scoped>.header{display:flex;justify-content:space-between;align-items:center}.mt20{margin-top:20px}.row-error{color:var(--el-color-danger);line-height:1.5}</style>
