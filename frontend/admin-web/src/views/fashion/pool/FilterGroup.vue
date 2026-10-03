<template>
<section class="filter-group">
  <header><el-input v-model="group.label" aria-label="筛选组名称" maxlength="30" class="group-name"/><div><el-button link @click="$emit('copy')">复制到其他组</el-button><el-button link @click="$emit('reset')">清空本组</el-button></div></header>
  <el-form label-position="top" class="horizontal-fields">
    <el-form-item label="三级分类（可多选，必填）" class="category"><el-select v-model="q.categoryLeafCodes" multiple filterable collapse-tags collapse-tags-tooltip placeholder="直接搜索三级分类"><el-option v-for="c in options.categories" :key="c.code" :value="c.code" :label="c.path"/></el-select></el-form-item>
    <el-form-item><template #label><ConditionLabel label="仓库" :mode="mode('warehouseCode')" @change="changeMode('warehouseCode',$event)"/></template><el-select v-model="q.warehouseCode" clearable placeholder="未指定（库存未知）"><el-option v-for="w in warehouses" :key="w.code" :label="`${w.name}（${w.code}）`" :value="w.code"/></el-select></el-form-item>
    <el-form-item v-for="field in basics" :key="field.key"><template #label><ConditionLabel :label="field.label" :mode="mode(field.key)" @change="changeMode(field.key,$event)"/></template><el-select v-model="q[field.key]" multiple filterable collapse-tags placeholder="不限"><el-option v-for="v in options[field.key]" :key="v" :label="v" :value="v"/></el-select></el-form-item>
    <el-form-item v-for="field in codes" :key="field.key" :label="field.label"><el-select v-model="q[field.key]" multiple filterable allow-create default-first-option collapse-tags placeholder="输入后回车；多个值取并集"/></el-form-item>
    <el-form-item label="商品名称"><el-input v-model="q.keyword" clearable placeholder="名称包含文字"/></el-form-item>
    <el-form-item v-for="field in numbers" :key="field.key" :error="field.error?rangeError(field.error):undefined"><template #label><ConditionLabel :label="field.label" :mode="mode(field.key)" @change="changeMode(field.key,$event)"/></template><el-input-number v-model="q[field.key]" :min="field.positive?1:0" :precision="field.integer?0:2" :controls="false" placeholder="不限"/></el-form-item>
    <el-form-item label="库存状态"><el-select v-model="q.stockStatus"><el-option label="不限" value="all"/><el-option label="有货" value="positive"/><el-option label="零库存" value="zero"/><el-option label="未知库存" value="unknown"/></el-select></el-form-item>
    <el-form-item><template #label><ConditionLabel label="库存时效" :mode="mode('stockFreshness')" @change="changeMode('stockFreshness',$event)"/></template><el-select v-model="q.stockFreshness" clearable placeholder="不限"><el-option label="不限" value="all"/><el-option label="有效库存" value="fresh"/><el-option label="存在过期库存" value="stale"/></el-select></el-form-item>
    <el-form-item v-for="f in stockDates" :key="f.key" :label="f.label"><el-date-picker v-model="q[f.key]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="不限"/></el-form-item>
    <el-form-item v-for="f in texts" :key="f.key" :label="f.label"><el-input v-model="q[f.key]" clearable placeholder="包含文字"/></el-form-item>
    <el-form-item v-for="f in extras" :key="f.key" :label="f.label"><el-select v-model="q[f.key]" multiple filterable allow-create default-first-option collapse-tags placeholder="输入后回车"/></el-form-item>
    <el-form-item v-for="f in checks" :key="f.key"><template #label><ConditionLabel :label="f.label" :mode="mode(f.key)" @change="changeMode(f.key,$event)"/></template><el-select :model-value="q[f.key]===undefined?null:q[f.key]" clearable placeholder="不限" @update:model-value="(v:unknown)=>write(f.key,v==null||v===''?undefined:Boolean(v))"><el-option label="要求满足" :value="true"/><el-option label="不要求" :value="false"/></el-select></el-form-item>
  </el-form>
  <el-collapse class="advanced"><el-collapse-item name="attributes" :title="`全部商品扩展条件（${attributeFields.length}项白名单；已在上方列出的条件不重复）`">
    <el-alert v-if="!attributeFields.length" title="商品字段定义未加载，请重新进入本页；不接受任意属性字段。" type="warning" :closable="false"/>
    <p class="hint">编码精确匹配，普通文本包含匹配；观察价格和上市年份按数值区间，采集／观察时间使用带时区起止时间。硬条件未知的商品不会猜填。</p>
    <el-form label-position="top" class="horizontal-fields"><el-form-item v-for="f in advancedFields" :key="f.key" :label="`${f.label} · ${f.group}`">
      <div v-if="dateKeys.includes(f.key)" class="range"><el-date-picker :model-value="q.attributeRanges?.[f.key]?.from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="开始" @update:model-value="(v:unknown)=>writeRange(f.key,'from',v)"/><el-date-picker :model-value="q.attributeRanges?.[f.key]?.to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="结束" @update:model-value="(v:unknown)=>writeRange(f.key,'to',v)"/></div>
      <div v-else-if="numberKeys.includes(f.key)" class="range"><el-input-number :model-value="rangeNumber(f.key,'from')" :min="0" :precision="f.key==='listingYear'?0:2" :controls="false" placeholder="最低" @update:model-value="(v:unknown)=>writeRange(f.key,'from',v)"/><el-input-number :model-value="rangeNumber(f.key,'to')" :min="0" :precision="f.key==='listingYear'?0:2" :controls="false" placeholder="最高" @update:model-value="(v:unknown)=>writeRange(f.key,'to',v)"/></div>
      <el-input v-else :model-value="q.attributeFilters?.[f.key]||''" clearable :maxlength="f.maxLength" :placeholder="codeKeys.includes(f.key)?'编码精确匹配':'包含文字'" @update:model-value="(v:string)=>writeAttribute(f.key,v)"/>
    </el-form-item></el-form>
  </el-collapse-item></el-collapse>
</section>
</template>
<script setup lang="ts">
import {computed} from 'vue'
import type {PoolFilterGroup,PoolOptions,PoolInheritance} from '@/api/fashion/poolTypes'
import type {ProductAttributeField} from '@/api/fashion/types'
import {filterRangeError} from '@/api/fashion/poolTypes'
import {effectiveGroup,sharedConditionKeys} from './conditionState'
import ConditionLabel from './ConditionLabel.vue'
const props=defineProps<{group:PoolFilterGroup;options:PoolOptions;totalConditions:PoolFilterGroup;inheritance:PoolInheritance;attributeFields:ProductAttributeField[]}>()
const emit=defineEmits<{copy:[];reset:[];mode:[key:keyof PoolFilterGroup,mode:'follow'|'independent']}>()
const effective=computed(()=>effectiveGroup(props.group,props.totalConditions,props.inheritance))
const q=computed(()=>new Proxy(props.group,{get:(_target,key)=>Reflect.get(effective.value,key),set:(_target,key,value)=>{write(key as keyof PoolFilterGroup,value);return true}}))
function write(key:keyof PoolFilterGroup,value:unknown){Object.assign(props.group,{[key]:value});if(sharedConditionKeys.includes(key))props.inheritance[key]='independent'}
function mode(key:keyof PoolFilterGroup){return sharedConditionKeys.includes(key)?props.inheritance[key]??'independent':undefined}
function changeMode(key:keyof PoolFilterGroup,value:'follow'|'independent'){emit('mode',key,value)}
const warehouses=computed(()=>props.options.warehouseOptions??props.options.warehouses.map(code=>({code,name:code})))
const basics=[{key:'seasons',label:'适用季节'},{key:'genders',label:'性别'},{key:'brands',label:'品牌'}] as const
const extras=[{key:'styles',label:'风格'},{key:'fits',label:'版型'},{key:'scenes',label:'适用场景'},{key:'tags',label:'标签'}] as const
const codes=[{key:'mainSkuCodes',label:'主 SKU 编号'},{key:'skuCodes',label:'规格 SKU 编号'},{key:'styleCodes',label:'款号'},{key:'itemNos',label:'品号'},{key:'articleNos',label:'货号'},{key:'sourceCodes',label:'来源编码'},{key:'colors',label:'颜色编码'},{key:'sizes',label:'尺码'},{key:'sizeSystems',label:'尺码制式'}] as const
const texts=[{key:'fabricComposition',label:'面料成分'},{key:'material',label:'材质'}] as const
const stockDates=[{key:'stockAsOfFrom',label:'库存取得时间开始'},{key:'stockAsOfTo',label:'库存取得时间结束'}] as const
const numbers=[{key:'groupPriceMin',label:'单品团购价下限',error:'group'},{key:'groupPriceMax',label:'单品团购价上限',error:'group'},{key:'vipPriceMin',label:'单品 VIP 价下限',error:'vip'},{key:'vipPriceMax',label:'单品 VIP 价上限',error:'vip'},{key:'quantity',label:'需求套数（每套1件／双）',integer:true,positive:true},{key:'stockMin',label:'库存数量下限',integer:true,error:'stock'},{key:'stockMax',label:'库存数量上限',integer:true,error:'stock'}].map(x=>({integer:false,positive:false,error:'',...x})) as {key:'groupPriceMin'|'groupPriceMax'|'vipPriceMin'|'vipPriceMax'|'quantity'|'stockMin'|'stockMax';label:string;error:string;integer:boolean;positive:boolean}[]
const checks=[{key:'requireAllSizes',label:'指定尺码齐码有货'},{key:'hasMainImage',label:'有可用主图'},{key:'imagesConfirmed',label:'图片已核对'},{key:'attributesConfirmed',label:'属性已确认'}] as const
const existingKeys=['mainSkuCode','itemNo','articleNo','categoryLeafCode','gender','style','scene','fabricComposition','fit']
const dateKeys=['collectedAt','observedAt'],numberKeys=['observedPrice','listingYear'],codeKeys=['platformProductId','platformSkuId','barcode','observedCurrency']
const advancedFields=computed(()=>props.attributeFields.filter(f=>!existingKeys.includes(f.key)))
function rangeError(type:string){const g=effective.value;return type==='group'?filterRangeError(g.groupPriceMin,g.groupPriceMax,'团购价'):type==='vip'?filterRangeError(g.vipPriceMin,g.vipPriceMax,'VIP价'):filterRangeError(g.stockMin,g.stockMax,'可售数量')}
function writeAttribute(key:string,value:string){const next={...props.group.attributeFilters};if(value)next[key]=value;else delete next[key];write('attributeFilters',next)}
function writeRange(key:string,bound:'from'|'to',value:unknown){const next={...props.group.attributeRanges},range={...next[key]};if(value==null||value==='')delete range[bound];else range[bound]=String(value);if(Object.keys(range).length)next[key]=range;else delete next[key];write('attributeRanges',next)}
function rangeNumber(key:string,bound:'from'|'to'){const value=effective.value.attributeRanges?.[key]?.[bound];return value===undefined?undefined:Number(value)}
</script>
<style scoped>
.filter-group{border:1px solid var(--el-border-color);border-radius:8px;margin:16px 0;padding:18px;background:var(--el-bg-color)}header{display:flex;justify-content:space-between;gap:12px;margin-bottom:16px}.group-name{width:180px}.horizontal-fields{display:grid;grid-template-columns:repeat(4,minmax(170px,1fr));gap:0 18px}.category{grid-column:span 2}.el-form-item{margin-bottom:16px}.el-select,.el-input-number,.el-date-editor{width:100%;min-width:0}.range{display:flex;align-items:center;gap:6px;min-width:0}.range .el-input-number,.range :deep(.el-date-editor){width:100%;min-width:0}.advanced{border-top:1px solid var(--el-border-color-light);margin-top:8px}.hint{font-size:12px;line-height:1.6;color:var(--el-text-color-secondary)}@media(max-width:1200px){.horizontal-fields{grid-template-columns:repeat(3,minmax(160px,1fr))}}@media(max-width:850px){.horizontal-fields{grid-template-columns:repeat(2,minmax(140px,1fr))}}@media(max-width:600px){.horizontal-fields{grid-template-columns:1fr}.category{grid-column:auto}header{flex-wrap:wrap}.range{flex-wrap:wrap}}
</style>
