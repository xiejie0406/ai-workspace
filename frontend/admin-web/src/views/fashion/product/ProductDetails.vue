<template>
  <el-drawer v-model="visible" :title="editing?'编辑商品资料':'商品详情'" size="min(960px, 95vw)" :close-on-click-modal="!editing">
    <div v-loading="loading">
      <template v-if="product">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="来源 / 款号">{{product.sourceCode}} / {{product.styleCode}}</el-descriptions-item>
          <el-descriptions-item label="SKU">{{product.skuCode}}</el-descriptions-item>
          <el-descriptions-item label="当前销售价">{{product.salePrice ?? '未导入'}} {{product.currency}}</el-descriptions-item>
          <el-descriptions-item label="状态">{{product.status}}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="135px" class="mt20" :disabled="!editing">
          <el-row :gutter="16"><el-col v-for="field in baseFields" :key="field.key" :xs="24" :md="12">
            <el-form-item :label="field.label"><el-input v-model="base[field.key]" /></el-form-item>
          </el-col></el-row>
          <el-form-item label="属性已经核实"><el-switch v-model="confirmed" /></el-form-item>
          <ProductAttributes v-model="attributes" :fields="fields" />
        </el-form>
        <p>电商观察价仅记录采集时点，不会修改销售价。来源图片须经素材导入审核后用于选品。</p>
        <div class="images"><el-image v-for="url in sourceImages" :key="url" :src="url" :preview-src-list="sourceImages" fit="contain" loading="lazy"><template #error><span>来源图片不可用</span></template></el-image></div>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible=false">关闭</el-button>
      <el-button v-if="!editing && product" v-hasPermi="['fashion:product:edit']" type="primary" @click="editing=true">编辑资料</el-button>
      <el-button v-if="editing" type="primary" :loading="saving" @click="save">保存修改</el-button>
    </template>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getProduct, updateProducts } from '@/api/fashion/product'
import { getProductFields } from '@/api/fashion/importBatch'
import type { FashionProduct, ProductAttributeField } from '@/api/fashion/types'
import ProductAttributes from './ProductAttributes.vue'
const emit=defineEmits<{(event:'saved'):void}>()
const visible=ref(false),loading=ref(false),saving=ref(false),editing=ref(false),confirmed=ref(false)
const product=ref<FashionProduct>(),attributes=ref<Record<string,string>>({}),base=ref<Record<string,string>>({}),fields=ref<ProductAttributeField[]>([])
const baseFields=[{key:'name',label:'商品名称'},{key:'categoryCode',label:'品类编码'},{key:'colorCode',label:'颜色编码'},{key:'colorName',label:'颜色名称'},{key:'sizeCode',label:'尺码'},{key:'sizeSystem',label:'尺码制式'},{key:'unit',label:'销售单位'},{key:'brand',label:'品牌'},{key:'material',label:'材质'},{key:'season',label:'季节编码'},{key:'jdItemId',label:'京东商品 ID'},{key:'jdUrl',label:'京东链接'}]
const sourceImages=computed(()=>[attributes.value.sourceMainImageUrl,attributes.value.sourceColorImageUrl,attributes.value.sourceSizeImageUrl,...(attributes.value.sourceDetailImageUrls||'').split(/\r?\n/)].filter((url):url is string=>Boolean(url?.startsWith('https://'))))
async function open(id:string){visible.value=true;editing.value=false;loading.value=true;product.value=undefined;try{const [p,f]=await Promise.all([getProduct(id),getProductFields()]);product.value=p.data;fields.value=f.data.attributes;attributes.value={...p.data.attributes};confirmed.value=p.data.attributesConfirmed;const raw=p.data as unknown as Record<string,unknown>;base.value=Object.fromEntries(baseFields.map(field=>[field.key,String(raw[field.key]??'')]))}finally{loading.value=false}}
async function save(){if(!product.value)return;saving.value=true;try{const response=await updateProducts([{id:product.value.id,rowVersion:product.value.rowVersion,changes:{...base.value,attributes:attributes.value,attributesConfirmed:confirmed.value}}]);product.value=response.data[0];editing.value=false;emit('saved');ElMessage.success('商品资料已保存')}finally{saving.value=false}}
defineExpose({open})
</script>
<style scoped>.mt20{margin-top:20px}.images{display:flex;gap:12px;flex-wrap:wrap}.images .el-image{width:150px;height:180px;border:1px solid var(--el-border-color-light)}p{color:var(--el-text-color-secondary)}</style>
