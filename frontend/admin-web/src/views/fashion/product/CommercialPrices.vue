<template>
  <el-dialog v-model="visible" title="维护团购价 / VIP价" width="640px" append-to-body :close-on-click-modal="false">
    <ProductPrices v-if="product && visible" :product="product" @saved="saved" />
    <template #footer><el-button @click="visible=false">关闭</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import ProductPrices from './ProductPrices.vue'
import type { FashionProduct } from '@/api/fashion/types'
const emit=defineEmits<{saved:[]}>(),visible=ref(false),product=ref<FashionProduct>()
function open(value:FashionProduct){product.value=value;visible.value=true}
function saved(){emit('saved')}
defineExpose({open})
</script>
