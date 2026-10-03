<template>
  <section>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="商品名称">{{product.name}}</el-descriptions-item>
      <el-descriptions-item label="主SKU">{{valueText(product.attributes?.mainSkuCode)}}</el-descriptions-item>
      <el-descriptions-item label="品号">{{valueText(product.attributes?.itemNo)}}</el-descriptions-item>
      <el-descriptions-item label="货号">{{valueText(product.attributes?.articleNo)}}</el-descriptions-item>
      <el-descriptions-item label="款号">{{product.styleCode}}</el-descriptions-item>
      <el-descriptions-item label="规格SKU">{{product.skuCode}}</el-descriptions-item>
      <el-descriptions-item label="来源">{{product.sourceCode}}</el-descriptions-item>
      <el-descriptions-item label="颜色">{{product.colorName}}（{{product.colorCode}}）</el-descriptions-item>
      <el-descriptions-item label="尺码">{{product.sizeCode}}</el-descriptions-item>
      <el-descriptions-item label="尺码制式">{{product.sizeSystem}}</el-descriptions-item>
      <el-descriptions-item label="单位">{{product.unit}}</el-descriptions-item>
      <el-descriptions-item label="原销售价">{{valueText(product.salePrice)}}</el-descriptions-item>
      <el-descriptions-item label="团购价">{{loaded?valueText(value.groupPrice):loading?'读取中':'未取得'}}</el-descriptions-item>
      <el-descriptions-item label="VIP价">{{loaded?valueText(value.vipPrice):loading?'读取中':'未取得'}}</el-descriptions-item>
      <el-descriptions-item label="币种">{{valueText(loaded?value.currency:product.currency)}}</el-descriptions-item>
      <el-descriptions-item label="税口径">{{valueText(loaded?value.taxMode:product.taxMode)}}</el-descriptions-item>
      <el-descriptions-item label="价格取得时间">{{valueText(product.priceAsOf)}}</el-descriptions-item>
      <el-descriptions-item label="维护状态">{{loaded?(value.groupPrice==null||value.vipPrice==null?'双价待补齐':'双价已维护'):loading?'读取中':error?'读取失败':'未取得'}}</el-descriptions-item>
      <el-descriptions-item label="更新时间">{{valueText(product.updateTime)}}</el-descriptions-item>
      <el-descriptions-item label="逐价修改记录">当前接口未提供；原销售价变更在导入批次核对</el-descriptions-item>
    </el-descriptions>
    <el-alert title="三个价格口径分别保存。团购价、VIP价留空表示未维护，不复制原销售价。原销售价通过商品内销售价导入工具更新。" type="info" :closable="false" class="space" />
    <el-form v-loading="loading" label-width="100px" @submit.prevent="save">
      <el-form-item label="团购价"><el-input v-model="group" :disabled="!canEdit" inputmode="decimal" clearable placeholder="未维护" /></el-form-item>
      <el-form-item label="VIP价"><el-input v-model="vip" :disabled="!canEdit" inputmode="decimal" clearable placeholder="未维护" /></el-form-item>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form-item><el-button type="primary" v-hasPermi="['fashion:price:import']" :loading="saving" :disabled="loading||!loaded" @click="save">保存团购价 / VIP价</el-button><el-button v-hasPermi="['fashion:price:import']" @click="importVisible=true">原销售价导入</el-button><el-button :loading="loading" @click="load">重新读取</el-button></el-form-item>
    </el-form>
    <el-dialog v-model="importVisible" title="商品原销售价导入" width="min(1200px, 95vw)" append-to-body @closed="$emit('saved')"><CatalogValueImportPanel v-if="importVisible" kind="price" /></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import auth from '@/plugins/auth'
import { fashionRequest } from '@/api/fashion/client'
import type { FashionProduct, RuoYiResult } from '@/api/fashion/types'
import CatalogValueImportPanel from '../import/components/CatalogValueImportPanel.vue'
import { valueText } from './productList'
const props=defineProps<{product:FashionProduct}>(),emit=defineEmits<{saved:[]}>()
interface Prices{productId:string;groupPrice:number|null;vipPrice:number|null;rowVersion:number;currency:string;taxMode:string}
const value=ref<Prices>({productId:'',groupPrice:null,vipPrice:null,rowVersion:0,currency:'',taxMode:''}),group=ref(''),vip=ref(''),loading=ref(false),saving=ref(false),loaded=ref(false),error=ref(''),importVisible=ref(false)
const canEdit=auth.hasPermiOr(['fashion:price:import'])
let sequence=0
async function load(){const seq=++sequence;loading.value=true;loaded.value=false;error.value='';group.value='';vip.value='';try{const response=await fashionRequest<RuoYiResult<Prices>>({path:`pools/products/${props.product.id}/commercial-prices`});if(seq!==sequence)return;value.value=response.data;group.value=response.data.groupPrice==null?'':String(response.data.groupPrice);vip.value=response.data.vipPrice==null?'':String(response.data.vipPrice);loaded.value=true}catch(e){if(seq===sequence)error.value=e instanceof Error?e.message:'价格读取失败'}finally{if(seq===sequence)loading.value=false}}
function amount(text:string){const result=text.trim();if(!result)return null;if(!/^\d{1,14}(\.\d{1,2})?$/.test(result))throw new Error('价格必须为非负金额，最多14位整数和2位小数');return result}
async function save(){if(!loaded.value||saving.value)return;error.value='';try{const body={groupPrice:amount(group.value),vipPrice:amount(vip.value),rowVersion:value.value.rowVersion};saving.value=true;await fashionRequest({path:`pools/products/${value.value.productId}/commercial-prices`,method:'put',body});ElMessage.success('团购价与VIP价已保存');await load();emit('saved')}catch(e){error.value=e instanceof Error?e.message:'保存失败，请重新读取核对最新价格'}finally{saving.value=false}}
watch(()=>props.product.id,load,{immediate:true})
onBeforeUnmount(()=>{sequence++})
</script>
<style scoped>.space{margin:16px 0}.el-input{max-width:320px}</style>
