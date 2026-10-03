<template>
<el-card class="total-conditions" shadow="never"><template #header><strong>总查询条件</strong><span class="hint"> · 自动作用于仍在跟随的子组；修改子组时可单独设置</span></template>
  <el-form label-position="top" class="total-grid">
    <el-form-item label="团购套数"><el-input-number v-model="conditions.quantity" :min="1" :precision="0" :controls="false" placeholder="不限"/></el-form-item>
    <el-form-item label="默认仓库"><el-select v-model="conditions.warehouseCode" clearable placeholder="未指定（库存未知）"><el-option v-for="w in warehouses" :key="w.code" :label="`${w.name}（${w.code}）`" :value="w.code"/></el-select></el-form-item>
    <el-form-item v-for="f in lists" :key="f.key" :label="f.label"><el-select v-model="conditions[f.key]" multiple filterable collapse-tags placeholder="不限"><el-option v-for="v in options[f.key]" :key="v" :label="v" :value="v"/></el-select></el-form-item>
    <el-form-item v-for="f in prices" :key="f.key" :label="f.label" :error="f.key.startsWith('group')?filterRangeError(conditions.groupPriceMin,conditions.groupPriceMax,'团购价'):filterRangeError(conditions.vipPriceMin,conditions.vipPriceMax,'VIP价')"><el-input-number v-model="conditions[f.key]" :min="0" :precision="2" :controls="false" placeholder="不限"/></el-form-item>
    <el-form-item label="默认库存时效"><el-select v-model="conditions.stockFreshness" clearable placeholder="不限"><el-option label="不限" value="all"/><el-option label="有效库存" value="fresh"/><el-option label="存在过期库存" value="stale"/></el-select></el-form-item>
    <el-form-item v-for="f in checks" :key="f.key" :label="f.label"><el-select :model-value="conditions[f.key]??null" clearable placeholder="不限" @update:model-value="(v:unknown)=>conditions[f.key]=v==null||v===''?undefined:Boolean(v)"><el-option label="要求满足" :value="true"/><el-option label="不要求" :value="false"/></el-select></el-form-item>
  </el-form>
  <p class="hint">团购套数100时，每个跟随组需求100；每套用量默认1。整套价格在下方单独填写，单品价格不会套用整套预算。需指定仓库才能核对需求数量，清空仓库表示库存未知。</p>
</el-card>
</template>
<script setup lang="ts">
import {computed} from 'vue'
import type {PoolFilterGroup,PoolOptions} from '@/api/fashion/poolTypes'
import {filterRangeError} from '@/api/fashion/poolTypes'
const props=defineProps<{conditions:PoolFilterGroup;options:PoolOptions}>()
const warehouses=computed(()=>props.options.warehouseOptions??props.options.warehouses.map(code=>({code,name:code})))
const lists=[{key:'seasons',label:'默认季节'},{key:'genders',label:'默认性别'},{key:'brands',label:'默认品牌'}] as const
const prices=[{key:'groupPriceMin',label:'默认单品团购价下限'},{key:'groupPriceMax',label:'默认单品团购价上限'},{key:'vipPriceMin',label:'默认单品 VIP 价下限'},{key:'vipPriceMax',label:'默认单品 VIP 价上限'}] as const
const checks=[{key:'requireAllSizes',label:'默认齐码要求'},{key:'hasMainImage',label:'默认有可用主图'},{key:'imagesConfirmed',label:'默认图片已核对'}] as const
</script>
<style scoped>.total-conditions{margin-top:18px}.total-grid{display:grid;grid-template-columns:repeat(4,minmax(160px,1fr));gap:0 18px}.el-select,.el-input-number{width:100%}.hint{color:var(--el-text-color-secondary);font-size:12px;line-height:1.6}@media(max-width:1000px){.total-grid{grid-template-columns:repeat(2,minmax(150px,1fr))}}@media(max-width:600px){.total-grid{grid-template-columns:1fr}}</style>
