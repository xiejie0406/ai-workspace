<template>
  <el-image v-if="url" :src="url" :preview-src-list="[url]" fit="contain" class="thumbnail" />
  <div v-else class="thumbnail placeholder">{{failed?'图片暂不可用':'图片加载中'}}</div>
</template>
<script setup lang="ts">
import { ref,watch,onBeforeUnmount } from 'vue'
import { fashionRequest } from '@/api/fashion/client'
const props=defineProps<{productId:string}>(),url=ref(''),failed=ref(false)
let controller:AbortController|undefined,sequence=0
watch(()=>props.productId,async id=>{
  const seq=++sequence;controller?.abort();controller=new AbortController()
  if(url.value)URL.revokeObjectURL(url.value)
  url.value='';failed.value=false;if(!id)return
  try{
    const blob=await fashionRequest<Blob>({path:`products/${id}/main-image`,responseType:'blob',signal:controller.signal})
    if(seq!==sequence)return
    if(!blob.type.startsWith('image/')){failed.value=true;return}
    url.value=URL.createObjectURL(blob)
  }catch{if(seq===sequence)failed.value=true}
},{immediate:true})
onBeforeUnmount(()=>{sequence++;controller?.abort();if(url.value)URL.revokeObjectURL(url.value)})
</script>
<style scoped>.thumbnail{width:100%;height:160px;background:var(--el-fill-color-light)}.placeholder{display:flex;align-items:center;justify-content:center;color:var(--el-text-color-secondary)}</style>
