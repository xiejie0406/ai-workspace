<template>
  <el-card shadow="never" class="mt20">
    <template #header><div class="header"><strong>导入历史</strong><el-button @click="load">刷新历史</el-button></div></template>
    <el-table :data="items" v-loading="loading" empty-text="暂无导入批次">
      <el-table-column prop="batchNo" label="批次" min-width="220" /><el-table-column prop="fileName" label="文件" min-width="170" />
      <el-table-column prop="sourceCode" label="来源" width="130" /><el-table-column prop="warehouseCode" label="仓库" width="110" />
      <el-table-column prop="status" label="状态" width="100" /><el-table-column prop="actualCount" label="行数" width="80" /><el-table-column prop="errorCount" label="错误" width="80" />
      <el-table-column prop="createTime" label="创建时间" min-width="200" />
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button link type="primary" @click="emit('open',row.batchId)">查看批次</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="load" />
  </el-card>
</template>
<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { fashionRequest } from '@/api/fashion/client'
import type { RuoYiResult } from '@/api/fashion/types'
const props=defineProps<{kind:'product'|'price'|'stock'}>()
const emit=defineEmits<{(event:'open',id:string):void}>()
const items=ref<Record<string,unknown>[]>([]),total=ref(0),page=ref(1),loading=ref(false)
async function load(){loading.value=true;try{const segment={product:'products',price:'prices',stock:'stocks'}[props.kind];const r=await fashionRequest<RuoYiResult<{items:Record<string,unknown>[];total:number}>>({path:`imports/${segment}/history`,params:{page:page.value,pageSize:20}});items.value=r.data.items;total.value=r.data.total}finally{loading.value=false}}
onMounted(load);defineExpose({load})
</script>
<style scoped>.mt20{margin-top:20px}.header{display:flex;justify-content:space-between;align-items:center}.el-pagination{margin-top:16px}</style>
