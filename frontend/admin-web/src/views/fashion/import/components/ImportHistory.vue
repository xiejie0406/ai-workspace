<template>
  <el-card shadow="never" class="mt20">
    <template #header><div class="header"><strong>导入历史</strong><el-button @click="load">刷新历史</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="items" v-loading="loading" :empty-text="error?'导入历史读取失败，请重试':'暂无导入批次'">
      <el-table-column type="expand"><template #default="{row}"><el-descriptions :column="2" border><el-descriptions-item label="范围">{{scopeSummary(row.scope)}}</el-descriptions-item><el-descriptions-item label="恢复来源批次">{{row.sourceBatchId||'普通导入'}}</el-descriptions-item><el-descriptions-item label="验证时间">{{row.validatedAt||'尚未验证'}}</el-descriptions-item><el-descriptions-item label="失败说明">{{row.errorMessage||'无'}}</el-descriptions-item></el-descriptions></template></el-table-column>
      <el-table-column prop="batchNo" label="批次" min-width="220" /><el-table-column prop="fileName" label="文件" min-width="170" />
      <el-table-column label="类型" width="110"><template #default>{{kindLabel}}</template></el-table-column>
      <el-table-column label="操作类型" width="100"><template #default="{row}">{{row.operationType==='restore'?'历史恢复':'导入'}}</template></el-table-column>
      <el-table-column prop="sourceCode" label="来源" width="130" /><el-table-column v-if="kind==='stock'" prop="warehouseCode" label="仓库" width="110" />
      <el-table-column label="状态" width="120"><template #default="{row}">{{statusLabel(String(row.status))}}</template></el-table-column><el-table-column prop="actualCount" label="行数" width="80" /><el-table-column prop="errorCount" label="错误" width="80" />
      <el-table-column prop="asOf" label="业务时间" min-width="200" />
      <el-table-column prop="expectedCount" label="应覆盖行数" width="110" /><el-table-column label="新增行" width="90"><template #default="{row}">{{row.changeCounts?.added??'—'}}</template></el-table-column><el-table-column label="修改行" width="90"><template #default="{row}">{{row.changeCounts?.changed??'—'}}</template></el-table-column><el-table-column label="不变行" width="90"><template #default="{row}">{{row.changeCounts?.unchanged??'—'}}</template></el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" />
      <el-table-column prop="createdBy" label="创建人" min-width="110" /><el-table-column label="发布人" min-width="110"><template #default="{row}">{{row.publishedBy||'尚未发布'}}</template></el-table-column><el-table-column label="发布时间" min-width="200"><template #default="{row}">{{row.publishedAt||'尚未发布'}}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button link type="primary" @click="emit('open',row.batchId)">查看批次</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="load" />
  </el-card>
</template>
<script setup lang="ts">
import { computed,onMounted,ref,watch } from 'vue'
import { listImportHistory, type ImportHistoryRow } from '@/api/fashion/importHistory'
const props=defineProps<{kind:'product'|'price'|'stock'}>()
const kindLabel=computed(()=>({product:'商品资料',price:'销售价格',stock:'库存'}[props.kind]))
const emit=defineEmits<{(event:'open',id:string):void}>()
const items=ref<ImportHistoryRow[]>([]),total=ref(0),page=ref(1),loading=ref(false),error=ref('')
let request=0
function statusLabel(status:string){return ({validated:'待发布',invalid:'校验未通过',publishing:'发布中',success:'已成功',conflict:'数据冲突',failed:'失败'} as Record<string,string>)[status]??status}
function scopeSummary(value:string|null){if(!value)return '未记录范围';try{const scope=JSON.parse(value);const labels:Record<string,string>={sourceCode:'来源',warehouseCode:'仓库',categoryCode:'品类',clearFields:'清空字段'};return Object.entries(scope).map(([key,value])=>`${labels[key]||key}：${Array.isArray(value)?value.join('、')||'无':value==null?'未指定':String(value)}`).join('；')||'未记录范围'}catch{return '范围记录无法读取'}}
async function load(){const id=++request;loading.value=true;error.value='';items.value=[];total.value=0;try{const r=await listImportHistory(props.kind,page.value);if(id===request){items.value=r.data.items;total.value=r.data.total}}catch(e){if(id===request)error.value=e instanceof Error?e.message:'导入历史读取失败，请重试'}finally{if(id===request)loading.value=false}}
watch(()=>props.kind,()=>{page.value=1;void load()})
onMounted(load);defineExpose({load})
</script>
<style scoped>.mt20{margin-top:20px}.header{display:flex;justify-content:space-between;align-items:center}.el-pagination{margin-top:16px}</style>
