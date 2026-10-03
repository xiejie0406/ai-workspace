<script setup lang="ts">
import { ref } from 'vue'
import type { Slide,Theme } from '@/types/aippt'
import { useSlideVisibility } from '@/composables/aippt/useSlideVisibility'
import SlideStage from './SlideStage.vue'
defineProps<{slide:Slide;theme:Theme;index:number}>();const root=ref<HTMLElement|null>(null),visible=useSlideVisibility(root)
</script>
<template><div ref="root" class="thumbnail"><SlideStage v-if="visible&&slide.blocks.length&&slide.status==='ready'" :slide="slide" :theme="theme" :index="index"/><div v-else class="status">{{slide.status==='failed'?'生成失败':slide.status==='ready'?'正在显示':'等待生成'}}</div></div></template>
<style scoped>.thumbnail{width:100%;aspect-ratio:16/9;background:#f5f7fa;overflow:hidden}.status{height:100%;display:grid;place-items:center;color:#909399;font-size:12px}</style>
