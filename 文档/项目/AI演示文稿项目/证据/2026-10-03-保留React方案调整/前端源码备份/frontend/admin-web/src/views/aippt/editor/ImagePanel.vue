<script setup lang="ts">
import { ref } from 'vue'
import type { ImageBlock } from '../render/types'
const props=defineProps<{block:ImageBlock;disabled?:boolean}>(),emit=defineEmits<{upload:[file:File]}>();const error=ref('')
function select(e:Event){const input=e.target as HTMLInputElement,file=input.files?.[0];input.value='';if(!file)return;if(file.size>8*1024*1024){error.value='图片超过8 MiB';return}if(!['image/png','image/jpeg','image/webp'].includes(file.type)){error.value='仅支持PNG、JPEG、WebP';return}error.value='';emit('upload',file)}
</script>
<template><section><h4>替换图片</h4><p>{{block.alt}}</p><el-alert v-if="error" type="error" :title="error"/><label>选择图片（≤8 MiB）<input :disabled="disabled" type="file" accept="image/png,image/jpeg,image/webp" @change="select"/></label><p>换图将保护该图片，AI改稿不会覆盖。预览与PPTX按cover裁剪。</p><p v-if="(block as any).credit">来源署名：{{(block as any).credit}}</p><p v-if="(block as any).source">图片来源：{{(block as any).source}}</p></section></template>
<style scoped>label{display:flex;flex-direction:column;gap:12px}p{line-height:1.6;color:#606266}</style>
