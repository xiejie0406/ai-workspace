<template>
  <div ref="container" class="thumbnail">
    <el-image v-if="url" :src="url" :preview-src-list="[url]" :preview-teleported="true" fit="contain" class="content" />
    <div v-else class="content placeholder">{{message}}</div>
  </div>
</template>
<script setup lang="ts">
import { computed,ref,watch,onMounted,onBeforeUnmount } from 'vue'
import { fashionRequest } from '@/api/fashion/client'
const props=withDefaults(defineProps<{productId:string;imageKey?:string;canView?:boolean}>(),{canView:true})
const container=ref<HTMLElement>(),url=ref(''),error=ref(''),visible=ref(false),loading=ref(false)
const message=computed(()=>!props.canView?'无内部查看许可':error.value|| (loading.value?'图片加载中':'图片待加载'))
let controller:AbortController|undefined,sequence=0,observer:IntersectionObserver|undefined
async function load(){
  const seq=++sequence;controller?.abort();controller=new AbortController();error.value='';loading.value=false
  if(url.value)URL.revokeObjectURL(url.value)
  url.value='';if(!props.productId||!props.canView||!visible.value)return
  loading.value=true
  try{
    const blob=await fashionRequest<Blob>({path:`products/${props.productId}/main-image`,responseType:'blob',signal:controller.signal})
    if(seq!==sequence)return
    if(!blob.type.startsWith('image/')){error.value='图片内容不可用';return}
    url.value=URL.createObjectURL(blob)
  }catch(e){if(seq===sequence)error.value=(e as {status?:number})?.status===403?'无查看权限':(e as {status?:number})?.status===404?'未维护主图':'图片暂不可用'}
  finally{if(seq===sequence)loading.value=false}
}
watch(()=>[props.productId,props.imageKey,props.canView],load)
onMounted(()=>{
  if(typeof IntersectionObserver==='undefined'){visible.value=true;void load();return}
  observer=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting)){visible.value=true;observer?.disconnect();void load()}},{rootMargin:'160px'})
  if(container.value)observer.observe(container.value)
})
onBeforeUnmount(()=>{sequence++;observer?.disconnect();controller?.abort();if(url.value)URL.revokeObjectURL(url.value)})
</script>
<style scoped>.thumbnail{width:100%;height:160px;background:var(--el-fill-color-light)}.content{width:100%;height:100%}.placeholder{display:flex;align-items:center;justify-content:center;color:var(--el-text-color-secondary);font-size:12px;text-align:center}</style>
