<script setup lang="ts">
import { cloneJson } from '@/views/aippt/render/clone'

import { ref, watch } from 'vue'
import type { ChartBlock } from '../render/types'
const props=defineProps<{block:ChartBlock;disabled?:boolean}>(),emit=defineEmits<{change:[body:Record<string,unknown>]}>();const draft=ref<ChartBlock>(cloneJson(props.block)),error=ref('')
watch(()=>props.block,v=>{draft.value=cloneJson(v)})
function addCategory(){draft.value.categories.push('新分类');draft.value.series.forEach(s=>s.values.push(0))}
function removeCategory(i:number){if(draft.value.categories.length<=1)return;draft.value.categories.splice(i,1);draft.value.series.forEach(s=>s.values.splice(i,1))}
function save(){const b=draft.value;if(!b.categories.length||!b.series.length||b.series.some(s=>s.values.length!==b.categories.length||s.values.some(v=>!Number.isFinite(v)))){error.value='分类与系列数量需一致，数值必须有效';return}error.value='';emit('change',{type:'chart',chart_type:b.chart_type,categories:b.categories,series:b.series,unit:b.unit||null})}
</script>
<template><el-form label-position="top" :disabled="disabled"><el-form-item label="图表类型"><el-select v-model="draft.chart_type"><el-option v-for="(label,value) in {bar:'横向柱图',column:'纵向柱图',line:'折线图',pie:'饼图'}" :key="value" :value="value" :label="label"/></el-select></el-form-item><el-form-item label="单位"><el-input v-model="draft.unit"/></el-form-item><div class="data-scroll"><table><thead><tr><th>分类</th><th v-for="(series,i) in draft.series" :key="i"><el-input v-model="series.name" :aria-label="'系列名称 '+(i+1)"/><el-button :disabled="draft.series.length<=1" link @click="draft.series.splice(i,1)">删除系列</el-button></th><th/></tr></thead><tbody><tr v-for="(_,i) in draft.categories" :key="i"><td><el-input v-model="draft.categories[i]" :aria-label="'分类 '+(i+1)"/></td><td v-for="(series,j) in draft.series" :key="j"><el-input-number v-model="series.values[i]" :controls="false" :aria-label="'系列 '+(j+1)+' 分类 '+(i+1)"/></td><td><el-button link @click="removeCategory(i)">删分类</el-button></td></tr></tbody></table></div><el-button @click="addCategory">增加分类</el-button><el-button @click="draft.series.push({name:'新系列',values:draft.categories.map(()=>0)})">增加系列</el-button><el-alert v-if="error" type="error" :title="error"/><el-button type="primary" @click="save">保存图表数据</el-button><p>图表元素级样式不受支持；数据可编辑，首色跟随主题。</p></el-form></template>
<style scoped>.data-scroll{overflow:auto;margin-bottom:14px}.data-scroll table{border-collapse:collapse}.data-scroll th,.data-scroll td{padding:4px;min-width:110px}.el-input-number{width:110px}</style>
