<template>
  <div class="app-container">
    <el-alert title="每个颜色尺码 SKU 在各仓库独立计数。未导入表示数量未知，0 表示明确缺货。" type="info" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mt20" />
    <el-form inline class="mt20" @submit.prevent="search">
      <el-form-item label="仓库"><el-select v-model="query.warehouseCode" style="width:160px"><el-option v-for="w in warehouses" :key="w.value" :label="w.label" :value="w.value" /></el-select></el-form-item>
      <el-form-item label="来源"><el-select v-model="query.sourceCode" clearable style="width:160px"><el-option v-for="s in sources" :key="s.value" :label="s.label" :value="s.value" /></el-select></el-form-item>
      <el-form-item label="商品"><el-input v-model="query.keyword" placeholder="款号、SKU、名称" clearable /></el-form-item>
      <el-form-item label="库存状态"><el-select v-model="query.mode" style="width:150px"><el-option label="全部" value="all" /><el-option label="有库存" value="positive" /><el-option label="缺货（0）" value="zero" /><el-option label="未导入" value="missing" /><el-option label="超时待核实" value="stale" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" :loading="loading" @click="search">查询</el-button><el-button v-hasPermi="['fashion:stock:import']" @click="importVisible=true">库存 Excel 更新</el-button></el-form-item>
    </el-form>
    <el-radio-group v-model="view"><el-radio-button value="list">SKU 明细</el-radio-button><el-radio-button value="matrix">本页尺码矩阵</el-radio-button></el-radio-group>
    <p v-if="!error">超过 {{freshnessHours}} 小时需核实；当前查询共 {{total}} 个 SKU。</p>
    <el-table v-if="view==='list'" :data="items" v-loading="loading" :empty-text="error?'库存读取失败，请重试':!query.warehouseCode?'请先选择启用的仓库':'当前条件没有商品'">
      <el-table-column prop="sourceCode" label="来源" min-width="120" />
      <el-table-column label="主SKU" min-width="150"><template #default="{row}">{{row.mainSkuCode||'未维护'}}</template></el-table-column>
      <el-table-column prop="name" label="商品" min-width="180" /><el-table-column prop="styleCode" label="款号" min-width="140" /><el-table-column prop="skuCode" label="SKU" min-width="180" />
      <el-table-column prop="colorName" label="颜色" width="100" /><el-table-column prop="sizeCode" label="尺码" width="100" />
      <el-table-column prop="sizeSystem" label="尺码制式" width="110" />
      <el-table-column label="单位" width="80"><template #default="{row}">{{row.unit||'未维护'}}</template></el-table-column>
      <el-table-column label="所属仓库" min-width="170"><template #default="{row}">{{warehouseLabel(row.warehouseCode || query.warehouseCode)}}</template></el-table-column>
      <el-table-column label="可售量" width="120"><template #default="{row}"><el-tag :type="row.availableQty===null?'info':row.availableQty===0?'danger':'success'">{{row.availableQty??'未导入'}}</el-tag></template></el-table-column>
      <el-table-column label="时效" width="160"><template #default="{row}">{{stockFreshness(row)}}</template></el-table-column>
      <el-table-column prop="asOf" label="库存业务时间" min-width="220" /><el-table-column prop="batchId" label="来源批次 ID" min-width="200" />
      <el-table-column label="最近更新人" min-width="130"><template #default="{row}">{{row.updatedBy||'无更新记录'}}</template></el-table-column><el-table-column prop="updatedAt" label="最近更新时间" min-width="200" />
    </el-table>
    <el-table v-else :data="matrix" v-loading="loading" :empty-text="error?'库存读取失败，请重试':'当前页没有库存规格'">
      <el-table-column type="expand"><template #default="{row}"><el-table :data="row.rows"><el-table-column prop="skuCode" label="规格SKU" min-width="180" /><el-table-column prop="sizeCode" label="尺码" width="90" /><el-table-column label="可售量" width="110"><template #default="{row:detail}">{{detail.availableQty??'未导入'}}</template></el-table-column><el-table-column label="时效" min-width="150"><template #default="{row:detail}">{{stockFreshness(detail)}}</template></el-table-column><el-table-column prop="asOf" label="库存业务时间" min-width="200" /><el-table-column prop="batchId" label="来源批次" min-width="180" /></el-table></template></el-table-column>
      <el-table-column prop="sourceCode" label="来源" min-width="120" /><el-table-column label="主SKU" min-width="150"><template #default="{row}">{{row.mainSkuCode||'未维护'}}</template></el-table-column><el-table-column prop="styleCode" label="款号" min-width="140" /><el-table-column prop="colorName" label="颜色" min-width="100" /><el-table-column prop="sizeSystem" label="尺码制式" min-width="100" /><el-table-column label="仓库" min-width="180"><template #default="{row}">{{warehouseLabel(row.warehouseCode)}}</template></el-table-column>
      <el-table-column v-for="size in sizes" :key="size" :label="size" min-width="180"><template #default="{row}"><div v-for="entry in row.sizes[size]??[]" :key="entry.productId">{{entry.availableQty??'未导入'}} {{entry.unit||''}} · {{stockFreshness(entry)}}<small class="sku-hint">{{entry.skuCode}}</small></div><span v-if="!row.sizes[size]">—</span></template></el-table-column>
    </el-table>
    <el-pagination class="mt20" v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" :page-sizes="[50,100,200]" layout="total,sizes,prev,pager,next" @current-change="load" @size-change="search" />
    <el-dialog v-model="importVisible" title="库存 Excel 更新" width="min(1200px, 95vw)" @closed="load"><CatalogValueImportPanel v-if="importVisible" kind="stock" /></el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed,onMounted,reactive,ref,watch } from 'vue'
import { useRoute } from 'vue-router'
import CatalogValueImportPanel from '../import/components/CatalogValueImportPanel.vue'
import { getDicts } from '@/api/system/dict/data'
import { listStocks, type StockRow } from '@/api/fashion/stock'
import { stockMatrix, stockFreshness } from './stockMatrix'
import { listWarehouses, activeWarehouseOptions } from '@/api/fashion/warehouse'
const route=useRoute()
const importVisible=ref(false),items=ref<StockRow[]>([]),total=ref(0),freshnessHours=ref(24),loading=ref(false),view=ref('list'),error=ref('')
const warehouses=ref<{label:string;value:string}[]>([]),sources=ref<{label:string;value:string}[]>([])
const query=reactive({warehouseCode:'',sourceCode:'',keyword:'',mode:'all',page:1,pageSize:50})
let request=0
async function load(){const id=++request,params={...query};items.value=[];total.value=0;error.value='';if(!params.warehouseCode){loading.value=false;error.value='没有可查询仓库，请先在仓库管理中配置启用仓库';return}loading.value=true;try{const r=await listStocks(params);if(id===request){items.value=r.data.items.map(row=>({...row,warehouseCode:row.warehouseCode||params.warehouseCode}));total.value=r.data.total;freshnessHours.value=r.data.freshnessHours}}catch(e){if(id===request)error.value=e instanceof Error?e.message:'库存读取失败，请重试'}finally{if(id===request)loading.value=false}}
function search(){query.page=1;return load()}
function warehouseLabel(code:string){return warehouses.value.find(item=>item.value===code)?.label??code}
const sizes=computed(()=>[...new Set(items.value.map(row=>row.sizeCode))])
const matrix=computed(()=>stockMatrix(items.value))
watch(()=>route.query.warehouseCode,value=>{if(typeof value==='string'){query.warehouseCode=value;void search()}})
onMounted(async()=>{try{const map=(r:any)=>(r.data??[]).filter((x:any)=>String(x.status)==='0').map((x:any)=>({label:String(x.dictLabel),value:String(x.dictValue)}));const [w,s]=await Promise.all([listWarehouses(),getDicts('fashion_product_source')]);const options=activeWarehouseOptions(w.data.items);warehouses.value=options;sources.value=map(s);const requested=typeof route.query.warehouseCode==='string'?route.query.warehouseCode:'';query.warehouseCode=requested||options.find(item=>item.isDefault)?.value||options[0]?.value||'';if(requested&&!options.some(item=>item.value===requested)){const historical=w.data.items.find(item=>item.code===requested);if(historical)warehouses.value.push({label:`${historical.name}（${historical.code}，已停用）`,value:historical.code})}await load()}catch(e){error.value=e instanceof Error?e.message:'仓库或商品来源加载失败'}})
</script>
<style scoped>.mt20{margin-top:20px}p{color:var(--el-text-color-secondary)}.sku-hint{display:block;color:var(--el-text-color-secondary)}</style>
