<script setup lang="ts">
import { ref,watch,onBeforeUnmount } from 'vue'
import { api } from '@/api/aippt'
import { layouts } from '../render/design'
import type { Slide } from '@/types/aippt'
const props=defineProps<{projectId:string;slide:Slide;disabled?:boolean}>(),emit=defineEmits<{switch:[id:string];unlock:[]}>();const candidates=ref<{layout_id:string;compatible:boolean;reason?:string}[]>([]),error=ref('');let generation=0
watch(()=>[props.projectId,props.slide.id,props.slide.revision],async()=>{const own=++generation,pid=props.projectId,sid=props.slide.id;candidates.value=[];error.value='';try{const result=await api.layouts(pid,sid);if(own===generation)candidates.value=result}catch(e:any){if(own===generation)error.value=e.message}},{immediate:true});onBeforeUnmount(()=>generation++)
</script>
<template><section><el-alert v-if="error" type="error" :title="error"/><p>当前：{{slide.layout_mode==='flex'?'灵活布局':'固定版式'}} · {{layouts.get(slide.layout_id)?.name||slide.layout_id}}</p><el-button v-if="slide.layout_mode==='fixed'" :disabled="disabled" @click="emit('unlock')">解锁为灵活布局</el-button><p>切换保持内容与锁定；不兼容版式不能应用。</p><article v-for="c in candidates" :key="c.layout_id" class="candidate"><div><strong>{{layouts.get(c.layout_id)?.name||c.layout_id}}</strong><small v-if="!c.compatible">{{c.reason||'内容块或容量不兼容'}}</small></div><el-button :disabled="disabled||!c.compatible||slide.layout_id===c.layout_id" @click="emit('switch',c.layout_id)">应用</el-button></article></section></template>
<style scoped>.candidate{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 0;border-bottom:1px solid #ebeef5}.candidate small{display:block;color:#e6a23c;margin-top:5px}</style>
