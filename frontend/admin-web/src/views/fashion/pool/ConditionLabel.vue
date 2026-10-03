<template>
  <span class="condition-label"><span>{{label}}</span><el-button v-if="mode" link size="small" :type="mode==='follow'?'primary':'warning'" :aria-label="`${label}：${mode==='follow'?'设为独立':'恢复跟随'}`" @click="emit('change',mode==='follow'?'independent':'follow')">{{mode==='follow'?'跟随总条件':'独立 · 恢复跟随'}}</el-button></span>
</template>
<script setup lang="ts">
defineProps<{label:string;mode?:'follow'|'independent'}>()
const emit=defineEmits<{change:[mode:'follow'|'independent']}>()
</script>
<style scoped>.condition-label{display:flex;gap:8px;align-items:center;flex-wrap:wrap;line-height:20px}.condition-label .el-button{font-size:11px;min-height:20px}</style>
