<template>
  <div class="app-container">
    <el-alert title="每个颜色尺码 SKU 在各仓库独立计数。未导入表示数量未知，0 表示明确缺货。" type="info" :closable="false" show-icon />
    <el-form inline class="mt20" @submit.prevent="search">
      <el-form-item label="仓库"><el-select v-model="query.warehouseCode" style="width:160px"><el-option v-for="w in warehouses" :key="w.value" :label="w.label" :value="w.value" /></el-select></el-form-item>
      <el-form-item label="来源"><el-select v-model="query.sourceCode" clearable style="width:160px"><el-option v-for="s in sources" :key="s.value" :label="s.label" :value="s.value" /></el-select></el-form-item>
      <el-form-item label="商品"><el-input v-model="query.keyword" placeholder="款号、SKU、名称" clearable /></el-form-item>
      <el-form-item label="库存状态"><el-select v-model="query.mode" style="width:150px"><el-option label="全部" value="all" /><el-option label="有库存" value="positive" /><el-option label="缺货（0）" value="zero" /><el-option label="未导入" value="missing" /><el-option label="超时待核实" value="stale" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" :loading="loading" @click="search">查询</el-button><el-button v-hasPermi="['fashion:stock:import']" @click="importVisible=true">库存 Excel 更新</el-button></el-form-item>
    </el-form>
    <el-radio-group v-model="view"><el-radio-button value="list">SKU 明细</el-radio-button><el-radio-button value="matrix">本页尺码矩阵</el-radio-button></el-radio-group>
    <p>超过 {{freshnessHours}} 小时需核实；当前查询共 {{total}} 个 SKU。</p>
    <el-table v-if="view==='list'" :data="items" v-loading="loading" empty-text="当前条件没有商品">
      <el-table-column prop="name" label="商品" min-width="180" /><el-table-column prop="styleCode" label="款号" min-width="140" /><el-table-column prop="skuCode" label="SKU" min-width="180" />
      <el-table-column prop="colorName" label="颜色" width="100" /><el-table-column prop="sizeCode" label="尺码" width="100" />
      <el-table-column label="可售量" width="120"><template #default="{row}"><el-tag :type="row.availableQty===null?'info':row.availableQty===0?'danger':'success'">{{row.availableQty??'未导入'}}</el-tag></template></el-table-column>
      <el-table-column label="时效" width="120"><template #default="{row}">{{row.availableQty===null?'未知':row.stale?'待核实':'有效'}}</template></el-table-column>
      <el-table-column prop="asOf" label="库存业务时间" min-width="220" /><el-table-column prop="batchId" label="来源批次 ID" min-width="200" />
    </el-table>
    <el-table v-else :data="matrix" v-loading="loading"><el-table-column prop="label" label="来源 / 款 / 颜色 / 尺码制式" min-width="270" /><el-table-column v-for="size in sizes" :key="size" :label="size" min-width="95"><template #default="{row}">{{row.quantities[size]===undefined?'—':row.quantities[size]??'未导入'}}</template></el-table-column></el-table>
    <el-pagination class="mt20" v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" :page-sizes="[50,100,200]" layout="total,sizes,prev,pager,next" @current-change="load" @size-change="search" />
    <el-dialog v-model="importVisible" title="库存 Excel 更新" width="min(1200px, 95vw)" @closed="load"><CatalogValueImportPanel v-if="importVisible" kind="stock" /></el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed,onMounted,reactive,ref } from 'vue'
import CatalogValueImportPanel from '../import/components/CatalogValueImportPanel.vue'
import { getDicts } from '@/api/system/dict/data'
import { fashionRequest } from '@/api/fashion/client'
import type { RuoYiResult } from '@/api/fashion/types'
interface Row {productId:string;sourceCode:string;styleCode:string;skuCode:string;name:string;colorCode:string;colorName:string;sizeCode:string;sizeSystem:string;availableQty:number|null;stale:boolean;asOf:string|null;batchId:string|null}
const importVisible=ref(false),items=ref<Row[]>([]),total=ref(0),freshnessHours=ref(24),loading=ref(false),view=ref('list')
const warehouses=ref<{label:string;value:string}[]>([]),sources=ref<{label:string;value:string}[]>([])
const query=reactive({warehouseCode:'',sourceCode:'',keyword:'',mode:'all',page:1,pageSize:50})
let request=0
async function load(){if(!query.warehouseCode)return;const id=++request;loading.value=true;try{const r=await fashionRequest<RuoYiResult<{items:Row[];total:number;freshnessHours:number}>>({path:'stocks',params:{...query}});if(id===request){items.value=r.data.items;total.value=r.data.total;freshnessHours.value=r.data.freshnessHours}}finally{if(id===request)loading.value=false}}
function search(){query.page=1;return load()}
const sizes=computed(()=>[...new Set(items.value.map(row=>row.sizeCode))])
const matrix=computed(()=>{const groups=new Map<string,{label:string;quantities:Record<string,number|null>}>();for(const row of items.value){const key=[row.sourceCode,row.styleCode,row.colorCode,row.sizeSystem].join('/');const group=groups.get(key)??{label:[row.sourceCode,row.styleCode,row.colorName,row.sizeSystem].join(' / '),quantities:{}};group.quantities[row.sizeCode]=row.availableQty;groups.set(key,group)}return [...groups.values()]})
onMounted(async()=>{const map=(r:any)=>(r.data??[]).filter((x:any)=>String(x.status)==='0').map((x:any)=>({label:String(x.dictLabel),value:String(x.dictValue)}));const [w,s]=await Promise.all([getDicts('fashion_warehouse'),getDicts('fashion_product_source')]);warehouses.value=map(w);sources.value=map(s);query.warehouseCode=warehouses.value[0]?.value??'';await load()})
</script>
<style scoped>.mt20{margin-top:20px}p{color:var(--el-text-color-secondary)}</style>
