<template>
  <div class="pc-actions"><el-button v-for="id in images" :key="id" v-hasPermi="['paicli:artifact:read']" link :loading="busy" @click="preview(id)">读取图片产物 {{id}}</el-button><el-button v-if="artifactId" v-hasPermi="['paicli:artifact:read']" link :loading="busy" @click="download">下载完整工具产物 {{artifactId}}</el-button></div>
  <ArtifactText v-if="artifactId && sessionId && textAvailable !== false" :artifact-id="artifactId" :session-id="sessionId" />
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-dialog v-model="open" title="已获授权的图片证据" width="760px" class="pc-dialog" @closed="clear"><img v-if="url" :src="url" alt="工具实际返回的图片产物" style="max-width:100%;max-height:70vh;object-fit:contain" /></el-dialog>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue'
import { downloadArtifact } from '@/api/paicli/sessions'
import { checkPermi } from '@/utils/permission'
import { imageArtifactIds } from '../shared/artifactReferences'
import { saveDownload } from '../model'
import { useOperations } from '../useOperations'
import ArtifactText from './ArtifactText.vue'
const props=defineProps<{result?:unknown;artifactId?:string;imageIds?:string[];sessionId?:string;textAvailable?:boolean}>(),images=computed(()=>[...new Set([...(props.imageIds||[]),...imageArtifactIds(props.result)])]),{busy,error,run}=useOperations(),open=ref(false),url=ref('')
let generation=0
function clear(){if(url.value)URL.revokeObjectURL(url.value);url.value=''}
async function preview(id:string){if(!checkPermi(['paicli:artifact:read']))return;const g=generation;await run(async()=>{const blob=await downloadArtifact(id);if(g!==generation)return;if(!['image/png','image/jpeg','image/gif','image/webp'].includes(blob.type))throw new Error('中心产物不是支持的图片格式');clear();url.value=URL.createObjectURL(blob);open.value=true})}
async function download(){if(!props.artifactId||!checkPermi(['paicli:artifact:read']))return;const id=props.artifactId;await run(async()=>saveDownload(await downloadArtifact(id),`paicli-tool-${id}.txt`))}
watch(()=>[props.result,props.artifactId,props.imageIds],()=>{generation++;open.value=false;clear()});onBeforeUnmount(()=>{generation++;clear()})
</script>
