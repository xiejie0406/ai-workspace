<template>
  <el-button v-if="file.purpose === 'USER_FILE' && checkPermi(['paicli:artifact:read'])" v-hasPermi="['paicli:artifact:read']" link @click="start">预览</el-button>
  <el-drawer v-model="open" title="文件预览" size="min(850px, 100vw)">
    <p>{{ file.fileName }} · 总大小 {{ file.size }} B</p>
    <p class="pc-muted pc-preview-meta">摘要：{{ file.sha256 }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="busy" role="status">正在读取原文…</p>
    <template v-if="page">
      <p class="pc-muted">当前字节范围 {{ page.offset }} → {{ page.nextOffset }}；较长文件分页读取。</p>
      <pre class="pc-pre" aria-label="文件原文">{{ safeText(page.text) }}</pre>
    </template>
    <div class="pc-actions">
      <el-button :disabled="busy || position === 0" @click="previous">上一段原文</el-button>
      <el-button :disabled="busy || !page?.hasMore" @click="next">下一段原文</el-button>
      <el-button :loading="busy" @click="restart">从头重新读取</el-button>
    </div>
    <p v-if="page && !page.hasMore" class="pc-muted">已到文件末尾。</p>
    <p class="pc-muted">只读取本人文件。无法预览的格式仍可返回列表下载。</p>
  </el-drawer>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { checkPermi } from '@/utils/permission'
import { getPersonalFilePreview } from '@/api/paicli/files'
import type { PcPersonalFile, PcPersonalFilePreview } from '@/types/paicli'
import { errorText, safeText } from '../model'
const props=defineProps<{file:PcPersonalFile}>()
const open=ref(false),busy=ref(false),error=ref(''),page=ref<PcPersonalFilePreview>(),position=ref(0)
let generation=0,offsets=[0],controller:AbortController|undefined
function clear(){generation++;controller?.abort();controller=undefined;page.value=undefined;position.value=0;offsets=[0];busy.value=false;error.value=''}
async function load(offset:number){
  const file=props.file
  if(!open.value||file.purpose!=='USER_FILE'||!checkPermi(['paicli:artifact:read']))return false
  controller?.abort();const requestController=new AbortController();controller=requestController;const g=++generation
  busy.value=true;error.value=''
  try{
    const value=(await getPersonalFilePreview(file.id,offset,requestController.signal)).data
    if(g!==generation)return false
    if(!value||value.artifactId!==file.id||value.purpose!=='USER_FILE'||value.fileName!==file.fileName||value.offset!==offset||typeof value.text!=='string'||typeof value.hasMore!=='boolean'||!Number.isSafeInteger(value.size)||value.size!==file.size||value.size<0||typeof value.sha256!=='string'||!/^[a-f0-9]{64}$/i.test(value.sha256)||value.sha256.toLowerCase()!==file.sha256.toLowerCase()||!Number.isSafeInteger(value.nextOffset)||value.nextOffset<offset||value.nextOffset>value.size||value.hasMore!==(value.nextOffset<value.size)||(value.hasMore&&value.nextOffset<=offset)||new TextEncoder().encode(value.text).length!==value.nextOffset-offset)throw new Error('文件摘要、长度或预览范围与列表不一致，保留当前页，请刷新文件列表后核对。')
    page.value=value;return true
  }catch(cause){if(g===generation)error.value=errorText(cause);return false}
  finally{if(g===generation)busy.value=false}
}
async function start(){if(busy.value||props.file.purpose!=='USER_FILE'||!checkPermi(['paicli:artifact:read']))return;open.value=true;await restart()}
async function restart(){if(busy.value)return;if(await load(0)){position.value=0;offsets=[0]}}
async function next(){if(busy.value||!page.value?.hasMore)return;const offset=page.value.nextOffset;if(await load(offset)){offsets[position.value+1]=offset;position.value++}}
async function previous(){if(busy.value||!page.value||position.value===0)return;if(await load(offsets[position.value-1]!))position.value--}
watch(open,value=>{if(!value)clear()})
watch(()=>[props.file.id,props.file.purpose,props.file.fileName,props.file.sha256,props.file.size],()=>{open.value=false;clear()})
onBeforeUnmount(clear)
</script>
<style scoped>.pc-preview-meta{overflow-wrap:anywhere}</style>
