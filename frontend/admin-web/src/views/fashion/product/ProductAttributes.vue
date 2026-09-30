<template>
  <div v-for="group in groups" :key="group" class="attribute-group">
    <h3>{{ group }}</h3>
    <el-row :gutter="16">
      <el-col v-for="field in fields.filter(item=>item.group===group)" :key="field.key" :xs="24" :md="12">
        <el-form-item :label="field.label">
          <el-input :model-value="modelValue[field.key] || ''" :maxlength="field.maxLength" :type="field.maxLength>255?'textarea':'text'" :rows="2" placeholder="未知可留空" @update:model-value="(value: string)=>emit('update:modelValue',{...modelValue,[field.key]:value})" />
        </el-form-item>
      </el-col>
    </el-row>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { ProductAttributeField } from '@/api/fashion/types'
const props=defineProps<{modelValue:Record<string,string>;fields:ProductAttributeField[]}>()
const emit=defineEmits<{(event:'update:modelValue',value:Record<string,string>):void}>()
const groups=computed(()=>[...new Set(props.fields.map(field=>field.group))])
</script>
<style scoped>h3{font-size:15px;margin:20px 0 14px}.attribute-group{border-top:1px solid var(--el-border-color-light)}</style>
