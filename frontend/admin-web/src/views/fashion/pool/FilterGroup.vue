<template>
<section class="filter-group">
  <header><el-input v-model="group.label" aria-label="筛选组名称" maxlength="30" class="group-name"/><div><el-button link @click="$emit('copy')">复制条件</el-button><el-button link @click="$emit('reset')">清空条件</el-button><el-button link type="danger" :disabled="!removable" @click="$emit('remove')">移除此组</el-button></div></header>
  <el-form label-position="top" class="horizontal-fields">
    <el-form-item label="三级分类（可多选）" class="category"><el-select v-model="group.categoryLeafCodes" multiple filterable collapse-tags collapse-tags-tooltip placeholder="直接搜索三级分类"><el-option v-for="c in options.categories" :key="c.code" :value="c.code" :label="c.path"/></el-select></el-form-item>
    <el-form-item v-for="field in basics" :key="field.key" :label="field.label"><el-select v-model="group[field.key]" multiple filterable collapse-tags placeholder="不限"><el-option v-for="v in options[field.key]" :key="v" :label="v" :value="v"/></el-select></el-form-item>
    <el-form-item label="团购价区间"><div class="range"><el-input-number v-model="group.groupPriceMin" :min="0" :controls="false" placeholder="最低"/><span>—</span><el-input-number v-model="group.groupPriceMax" :min="0" :controls="false" placeholder="最高"/></div></el-form-item>
    <el-form-item label="VIP 价区间"><div class="range"><el-input-number v-model="group.vipPriceMin" :min="0" :controls="false" placeholder="最低"/><span>—</span><el-input-number v-model="group.vipPriceMax" :min="0" :controls="false" placeholder="最高"/></div></el-form-item>
    <el-form-item v-for="field in codes" :key="field.key" :label="field.label"><el-select v-model="group[field.key]" multiple filterable allow-create default-first-option collapse-tags placeholder="输入编码后回车"/></el-form-item>
    <el-form-item label="商品名称"><el-input v-model="group.keyword" clearable placeholder="名称关键词"/></el-form-item>
    <el-form-item label="仓库"><el-select v-model="group.warehouseCode" clearable placeholder="选择仓库"><el-option v-for="w in options.warehouses" :key="w" :label="w" :value="w"/></el-select></el-form-item>
    <el-form-item label="库存状态"><el-select v-model="group.stockStatus"><el-option label="不限" value="all"/><el-option label="有货" value="positive"/><el-option label="零库存" value="zero"/><el-option label="未知库存" value="unknown"/></el-select></el-form-item>
    <el-form-item label="可售数量区间"><div class="range"><el-input-number v-model="group.stockMin" :min="0" :precision="0" :controls="false" placeholder="最少"/><span>—</span><el-input-number v-model="group.stockMax" :min="0" :precision="0" :controls="false" placeholder="最多"/></div></el-form-item>
    <el-form-item label="颜色编码"><el-select v-model="group.colors" multiple filterable allow-create default-first-option collapse-tags placeholder="输入颜色编码后回车"/></el-form-item>
    <el-form-item label="尺码"><el-select v-model="group.sizes" multiple filterable allow-create default-first-option collapse-tags placeholder="输入尺码后回车"/></el-form-item>
    <el-form-item label="库存时效"><el-select v-model="group.stockFreshness" placeholder="不限"><el-option label="不限" value="all"/><el-option label="有效库存" value="fresh"/><el-option label="存在过期库存" value="stale"/></el-select></el-form-item>
    <el-form-item label="面料成分"><el-input v-model="group.fabricComposition" clearable placeholder="包含文字，如棉"/></el-form-item>
    <el-form-item v-for="f in extras" :key="f.key" :label="f.label"><el-select v-model="group[f.key]" multiple filterable allow-create default-first-option collapse-tags placeholder="输入后回车"/></el-form-item>
    <el-form-item label="资料与供货要求"><el-checkbox v-model="group.requireAllSizes">指定尺码齐码有货</el-checkbox><el-checkbox :model-value="group.hasMainImage===true" @update:model-value="(v:unknown)=>group.hasMainImage=v?true:undefined">有主图</el-checkbox><el-checkbox :model-value="group.imagesConfirmed===true" @update:model-value="(v:unknown)=>group.imagesConfirmed=v?true:undefined">图片已核对</el-checkbox></el-form-item>
  </el-form>
</section>
</template>
<script setup lang="ts">
import type {PoolFilterGroup,PoolOptions} from '@/api/fashion/poolTypes'
defineProps<{group:PoolFilterGroup;options:PoolOptions;removable:boolean}>()
defineEmits<{copy:[];reset:[];remove:[]}>()
const basics=[{key:'seasons',label:'适用季节'},{key:'genders',label:'性别'},{key:'brands',label:'品牌'}] as const
const extras=[{key:'styles',label:'风格'},{key:'fits',label:'版型'},{key:'scenes',label:'适用场景'}] as const
const codes=[{key:'itemNos',label:'品号'},{key:'articleNos',label:'货号'},{key:'mainSkuCodes',label:'主 SKU 编号'}] as const
</script>
<style scoped>
.filter-group{border:1px solid var(--el-border-color);border-radius:8px;margin:16px 0;padding:16px;background:var(--el-bg-color)}header{display:flex;justify-content:space-between;gap:12px;margin-bottom:12px}.group-name{width:180px}.horizontal-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:0 16px}.category{grid-column:span 2}.el-form-item{margin-bottom:12px}.el-select{width:100%}.range{display:flex;align-items:center;gap:6px;min-width:0}.range .el-input-number{width:100%;min-width:0}@media(max-width:600px){.category{grid-column:auto}header{flex-wrap:wrap}}
</style>
