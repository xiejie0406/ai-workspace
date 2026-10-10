<template>
  <article class="pc-panel">
    <h4>{{label}} · {{path}}</h4><p class="pc-muted">产物 {{artifactId}} · 所有者 {{ownerId || '未提供'}}</p>
    <p class="pc-muted">来源 SHA {{sha256 || '未提供'}} · 产物 SHA {{artifact?.sha256 || '未提供'}} · 原文大小 {{artifact?.size === undefined ? '未提供' : `${artifact.size}B`}}</p>
    <p v-if="problem" class="pc-muted">{{problem}}</p><el-button v-else v-hasPermi="['paicli:artifact:read']" link :loading="busy" @click="download">下载完整 UTF-8 原文</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </article>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount,watch} from 'vue'
import {checkPermi} from '@/utils/permission'
import useUserStore from '@/store/modules/user'
import {downloadArtifact} from '@/api/paicli/sessions'
import type {PcPaiSourceMetadata} from '@/types/paicli'
import {saveDownload} from '../model'
import {useOperations} from '../useOperations'
const props=defineProps<{label:string;path:string;artifactId:string;ownerId?:string;directoryContextId?:string;sha256?:string;artifact?:PcPaiSourceMetadata}>(),{busy,error,run}=useOperations()
let generation=0
const problem=computed(()=>{
  if(!checkPermi(['paicli:artifact:read']))return '当前角色没有产物读取权限，不能下载原始文件。'
  const current=String(useUserStore().id ?? ''),artifact=props.artifact
  if(!props.ownerId || !current)return '中心未提供来源所有者，不能确定原文下载范围。'
  if(props.ownerId!==current)return '原始文件仅允许原所有者下载；读取此规则不会扩大产物访问范围。'
  if(!artifact)return '中心尚未返回已核对的原文产物元数据，请刷新来源后再下载。'
  if(!/^\d+$/.test(props.artifactId)||artifact.id!==props.artifactId||artifact.ownerId!==current||artifact.purpose!=='PAI_SOURCE'||typeof artifact.fileName!=='string'||typeof artifact.sha256!=='string'||(props.directoryContextId && artifact.directoryContextId!==props.directoryContextId)||!props.sha256||!/^[a-f0-9]{64}$/i.test(props.sha256)||artifact.sha256.toLowerCase()!==props.sha256.toLowerCase()||!Number.isSafeInteger(artifact.size)||artifact.size<1||artifact.size>50*1024*1024)return '原文产物的归属、用途、摘要或长度不完整，未开放下载。'
  return ''
})
async function download(){if(problem.value||busy.value)return;const artifact=props.artifact!,g=generation;await run(async()=>{try{const blob=await downloadArtifact(artifact.id);if(g!==generation||problem.value)return;if(blob.size!==artifact.size||blob.size>50*1024*1024)throw new Error('原文下载长度与中心来源不同，未保存文件。');const bytes=await blob.arrayBuffer(),hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),value=>value.toString(16).padStart(2,'0')).join('');if(g!==generation||problem.value)return;if(hash!==artifact.sha256.toLowerCase())throw new Error('原文下载摘要与中心来源不同，未保存文件。');try{new TextDecoder('utf-8',{fatal:true}).decode(bytes)}catch{throw new Error('原文下载不是合法 UTF-8，未保存文件。')}const fileName=(artifact.fileName || props.path.split('/').pop() || 'PAI.md').replace(/[\\/:*?"<>|\u0000-\u001f]/g,'_').slice(0,240);await saveDownload(blob,fileName)}catch(cause){if(g===generation)throw cause}})}
watch(()=>[props.artifactId,props.ownerId,props.directoryContextId,props.sha256,props.artifact],()=>{generation++;error.value=''});onBeforeUnmount(()=>{generation++})
</script>
