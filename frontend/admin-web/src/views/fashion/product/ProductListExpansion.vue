<template>
  <section class="product-expansion" aria-label="完整商品资料">
    <h3>基本资料与三种价格</h3>
    <el-descriptions :column="3" border>
      <el-descriptions-item v-for="column in baseColumns" :key="column.key" :label="column.label">{{ productColumnText(product, column) }}</el-descriptions-item>
    </el-descriptions>
    <template v-for="group in groups" :key="group.name">
      <h3>{{ group.name }}</h3>
      <el-descriptions :column="3" border>
        <el-descriptions-item v-for="field in group.fields" :key="field.key" :label="field.label">{{ valueText(product.attributes?.[field.key]) }}</el-descriptions-item>
      </el-descriptions>
    </template>
    <el-alert v-if="!fields.length" title="扩展字段定义尚未取得，请在列表上方重新读取；此处不能判定44项资料是否完整。" type="warning" :closable="false" />
    <h3>审核图片明细</h3>
    <ProductMaterials :product="product" read-only />
    <el-descriptions v-if="canManage" :column="3" border class="technical">
      <el-descriptions-item label="内部商品ID">{{ product.id }}</el-descriptions-item>
      <el-descriptions-item label="记录版本">{{ product.rowVersion }}</el-descriptions-item>
      <el-descriptions-item label="视觉版本">{{ product.visualVersion }}</el-descriptions-item>
    </el-descriptions>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import auth from '@/plugins/auth'
import type { FashionProduct, ProductAttributeField } from '@/api/fashion/types'
import ProductMaterials from '../material/index.vue'
import { createProductColumns, productColumnText, valueText } from './productList'
const props = defineProps<{ product: FashionProduct; fields: ProductAttributeField[] }>()
const canManage = auth.hasPermiOr(['fashion:product:edit','fashion:product:image'])
const baseColumns = computed(() => Object.values(createProductColumns(props.fields,canManage)).filter(column => !column.attribute && column.key !== 'picture'))
const groups = computed(() => [...new Set(props.fields.map(field => field.group))].map(name => ({ name, fields: props.fields.filter(field => field.group === name) })))
</script>
<style scoped>
.product-expansion{padding:16px 24px;max-width:100%;box-sizing:border-box;overflow-wrap:anywhere}
h3{font-size:14px;margin:18px 0 10px}.technical{margin-top:16px}
</style>
