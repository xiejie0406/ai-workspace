<template>
  <el-card class="mt20" shadow="never">
    <template #header><strong>人工需求与选品</strong></template>
    <el-form label-width="100px" :disabled="quote.status!=='draft'">
      <el-row :gutter="12"><el-col v-for="field in requirementFields" :key="field.key" :xs="24" :sm="12"><el-form-item :label="field.label"><el-input v-model="requirements[field.key]" :maxlength="200" /></el-form-item></el-col></el-row>
      <el-form-item label="偏好颜色"><el-input v-model="colors" placeholder="多个颜色用逗号分隔" /></el-form-item>
      <el-form-item label="排除项"><el-input v-model="exclusions" placeholder="多个排除项用逗号分隔" /></el-form-item>
      <el-form-item><el-button type="primary" :loading="saving" v-hasPermi="['fashion:quote:edit']" @click="confirm">保存并确认需求</el-button></el-form-item>
    </el-form>
    <el-divider />
    <el-form inline>
      <el-form-item label="方案档位"><el-select v-model="tierCount" style="width:150px" @change="clear"><el-option v-for="tier in selectableTiers" :key="tier.category_count" :label="`${tier.category_count} 品类`" :value="tier.category_count" /></el-select></el-form-item>
      <el-form-item label="筛选商品"><el-input v-model="keyword" placeholder="名称、颜色、款号、标签" clearable /></el-form-item>
      <el-form-item label="单品最高价"><el-input-number v-model="maxPrice" :min="0" :precision="2" /></el-form-item>
    </el-form>
    <p v-if="quote.progressive">递进方案从最高档选择商品，保存后生成各档组合。</p>
    <div v-for="slot in tier?.slots||[]" :key="slot.slot_index" class="slot">
      <h3>{{slot.slot_index}}. {{slot.category}}</h3>
      <el-select v-model="chosen[slot.slot_index]" filterable clearable placeholder="选择此品类商品" style="width:100%" @change="allocate(slot.slot_index)">
        <el-option v-for="item in candidates(slot.category)" :key="item.candidate_ref" :label="`${item.product_name} / ${item.color_name} / ¥${(item.conservative_unit_price_minor/100).toFixed(2)} / 库存${item.total_available_qty}`" :value="item.candidate_ref" />
      </el-select>
      <template v-if="selected(slot.slot_index)">
        <div class="selected-product"><ProductThumbnail :product-id="selected(slot.slot_index)!.variants[0].product_ref" /><div><strong>{{selected(slot.slot_index)!.product_name}}</strong><p>{{selected(slot.slot_index)!.color_name}} · {{selected(slot.slot_index)!.style_ref}}</p><el-button link @click="delete chosen[slot.slot_index]">移除商品</el-button></div></div>
        <el-table :data="selected(slot.slot_index)!.variants"><el-table-column prop="sku_ref" label="SKU" /><el-table-column prop="size_code" label="尺码" width="100" /><el-table-column prop="available_qty" label="当前可售量" width="120" /><el-table-column label="选购数量" width="190"><template #default="{row}"><el-input-number v-model="quantities[row.product_ref]" :min="0" :max="row.available_qty" :precision="0" /></template></el-table-column></el-table>
        <p>已分配 {{allocated(slot.slot_index)}} / {{quote.requestedQty}} 件</p>
      </template>
    </div>
    <el-form label-width="100px" class="mt20"><el-form-item label="组合名称"><el-input v-model="name" maxlength="100" /></el-form-item><el-form-item><el-button type="success" :disabled="!canSave" :loading="saving" v-hasPermi="['fashion:quote:edit']" @click="save">保存人工组合</el-button></el-form-item></el-form>
    <p>每个品类合计数量须等于采购套数；商品价格和库存变化后需要刷新候选再保存。</p>
  </el-card>
</template>
<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fashionRequest } from '@/api/fashion/client'
import type { FashionQuote,SelectionWorkspace,RuoYiResult } from '@/api/fashion/types'
import ProductThumbnail from '../components/ProductThumbnail.vue'
const props=defineProps<{quote:FashionQuote;workspace:SelectionWorkspace}>(),emit=defineEmits<{(event:'saved'):void}>()
const requirementFields=[{key:'audience',label:'适用人群'},{key:'scene',label:'场景'},{key:'season',label:'季节'},{key:'style',label:'风格'}]
const requirements=ref<Record<string,string>>({}),colors=ref(''),exclusions=ref(''),saving=ref(false),name=ref('人工选品组合'),chosen=ref<Record<number,string>>({}),quantities=ref<Record<string,number>>({}),keyword=ref(''),maxPrice=ref<number>(),tierCount=ref<number>()
const selectableTiers=computed(()=>props.quote.progressive?[...props.workspace.preview.tiers].sort((a,b)=>b.category_count-a.category_count).slice(0,1):props.workspace.preview.tiers)
const tier=computed(()=>selectableTiers.value.find(t=>t.category_count===tierCount.value))
function clear(){chosen.value={};quantities.value={}}
watch(()=>props.quote.id,()=>{
  const r=(props.quote.requirement??{}) as Record<string,unknown>
  requirements.value=Object.fromEntries(requirementFields.map(f=>[f.key,String(r[f.key]??'')]))
  colors.value=Array.isArray(r.preferred_colors)?r.preferred_colors.join(','):''
  exclusions.value=Array.isArray(r.exclusions)?r.exclusions.join(','):''
},{immediate:true})
watch(()=>props.workspace.preview.candidate_set_hash,()=>{clear();tierCount.value=selectableTiers.value[0]?.category_count},{immediate:true})
function candidates(category:string){const text=keyword.value.trim().toLowerCase();return props.workspace.preview.frozen_candidates.filter(c=>c.category_code===category&&(!maxPrice.value||c.conservative_unit_price_minor<=maxPrice.value*100)&&(!text||[c.product_name,c.color_name,c.style_ref,...c.tags].join(' ').toLowerCase().includes(text)))}
function selected(slot:number){return props.workspace.preview.frozen_candidates.find(c=>c.candidate_ref===chosen.value[slot])}
function allocate(slot:number){let remaining=props.quote.requestedQty;for(const sku of selected(slot)?.variants??[]){const qty=Math.min(remaining,sku.available_qty);quantities.value[sku.product_ref]=qty;remaining-=qty}}
function allocated(slot:number){return (selected(slot)?.variants??[]).reduce((total,sku)=>total+(quantities.value[sku.product_ref]??0),0)}
const canSave=computed(()=>props.quote.status==='draft'&&props.quote.requirementConfirmed&&Boolean(tier.value)&&tier.value!.slots.every(slot=>selected(slot.slot_index)&&allocated(slot.slot_index)===props.quote.requestedQty)&&Boolean(name.value.trim()))
const split=(value:string)=>value.split(/[,，]/).map(x=>x.trim()).filter(Boolean)
async function confirm(){saving.value=true;try{await fashionRequest({path:`quotes/${props.quote.id}/requirements/confirm`,method:'put',body:{rowVersion:props.quote.rowVersion,requirements:{...requirements.value,preferredColors:split(colors.value),exclusions:split(exclusions.value)}}});ElMessage.success('需求已人工确认');emit('saved')}finally{saving.value=false}}
async function save(){
  if(!canSave.value||!tier.value)return;saving.value=true
  try{
    const refs=tier.value.slots.map(slot=>chosen.value[slot.slot_index]);const values:Record<string,number>={}
    for(const slot of tier.value.slots)for(const sku of selected(slot.slot_index)!.variants)values[sku.product_ref]=quantities.value[sku.product_ref]??0
    await fashionRequest<RuoYiResult<SelectionWorkspace>>({path:`quotes/${props.quote.id}/selection/manual`,method:'post',body:{quoteRowVersion:props.quote.rowVersion,candidateSetHash:props.workspace.preview.candidate_set_hash,name:name.value,candidateRefs:refs,quantities:values}})
    ElMessage.success('人工组合已保存');emit('saved')
  }finally{saving.value=false}
}
</script>
<style scoped>.mt20{margin-top:20px}.slot{padding:12px 0;border-top:1px solid var(--el-border-color-light)}.selected-product{display:grid;grid-template-columns:140px 1fr;gap:20px;margin:16px 0}p{color:var(--el-text-color-secondary)}</style>
