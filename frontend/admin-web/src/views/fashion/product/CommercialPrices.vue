<template>
  <el-dialog v-model="visible" title="维护团购价 / VIP价" width="560px" append-to-body :close-on-click-modal="false">
    <el-alert title="维护当前尺码SKU的两类价格；同主SKU的不同颜色尺码可分别维护。留空表示缺价，不会复制销售价。" :closable="false" type="info" />
    <el-form v-loading="loading" label-width="100px" class="price-form" @submit.prevent="save">
      <el-form-item label="商品">{{ name }}</el-form-item>
      <el-form-item label="币种/税口径">{{ value.currency }} / {{ value.taxMode }}</el-form-item>
      <el-form-item label="团购价"><el-input v-model="group" inputmode="decimal" clearable placeholder="缺价" /></el-form-item>
      <el-form-item label="VIP价"><el-input v-model="vip" inputmode="decimal" clearable placeholder="缺价" /></el-form-item>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
    </el-form>
    <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="loading || !loaded" @click="save">保存双价格</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fashionRequest } from '@/api/fashion/client'
import type { FashionProduct, RuoYiResult } from '@/api/fashion/types'
interface Prices { productId: string; groupPrice: number | null; vipPrice: number | null; rowVersion: number; currency: string; taxMode: string }
const emit = defineEmits<{ saved: [] }>()
const visible=ref(false), loading=ref(false), saving=ref(false), loaded=ref(false), name=ref(''), error=ref(''), group=ref(''), vip=ref('')
const value=ref<Prices>({productId:'',groupPrice:null,vipPrice:null,rowVersion:0,currency:'',taxMode:''})
let requestVersion=0
async function open(product: FashionProduct) {
  const version=++requestVersion
  visible.value=true;loading.value=true;loaded.value=false;error.value='';name.value=`${product.name} / ${product.skuCode}`;group.value='';vip.value=''
  try {
    const result=await fashionRequest<RuoYiResult<Prices>>({path:`pools/products/${product.id}/commercial-prices`})
    if(version!==requestVersion)return
    value.value=result.data;group.value=result.data.groupPrice == null?'':String(result.data.groupPrice);vip.value=result.data.vipPrice == null?'':String(result.data.vipPrice);loaded.value=true
  } catch(e) { if(version===requestVersion)error.value=e instanceof Error?e.message:'价格读取失败' }
  finally { if(version===requestVersion)loading.value=false }
}
function amount(text:string): string | null {
  const normalized=text.trim()
  if(!normalized)return null
  if(!/^\d{1,14}(\.\d{1,2})?$/.test(normalized))throw new Error('价格必须为非负金额，最多14位整数和2位小数')
  return normalized
}
async function save(){
  if(saving.value||!loaded.value)return
  error.value=''
  try {
    const body={groupPrice:amount(group.value),vipPrice:amount(vip.value),rowVersion:value.value.rowVersion}
    saving.value=true
    await fashionRequest({path:`pools/products/${value.value.productId}/commercial-prices`,method:'put',body})
    visible.value=false;emit('saved');ElMessage.success('团购价与VIP价已保存')
  }catch(e){error.value=e instanceof Error?e.message:'保存失败，请重新打开核对最新价格'}finally{saving.value=false}
}
defineExpose({open})
</script>
<style scoped>.price-form{margin-top:18px}</style>
