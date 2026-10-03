<template>
  <el-button v-if="checkPermi([permission])" v-hasPermi="[permission]" link :disabled="!artifactId || !sessionId" @click="start">{{approvalId?'按本次批准用途读取完整差异':'分页读取完整文本产物'}} {{artifactId}}</el-button>
  <el-drawer v-model="open" title="获授权完整文本产物（UTF-8 字节分页）" size="850px"><el-alert v-if="error" :title="error" type="error" :closable="false" /><p class="pc-muted">产物 {{artifactId}} · 会话 {{sessionId}} · 摘要 {{page?.sha256 || '待读取'}} · 总计 {{page?.size ?? '待读取'}}B</p><p class="pc-muted">当前字节范围 {{page?.offset ?? 0}} → {{page?.nextOffset ?? 0}}；每次最多 1 MiB，跨页 UTF-8 字符由中心保持边界。</p><pre class="pc-pre">{{safeText(page?.text ?? '')}}</pre><div class="pc-actions"><el-button :loading="busy" :disabled="position===0" @click="previous">上一段原文</el-button><el-button :loading="busy" :disabled="!page?.hasMore" @click="next">下一段原文</el-button><el-button :loading="busy" @click="restart">从头重新读取</el-button></div><p v-if="page && !page.hasMore" class="pc-muted">已到完整产物末尾。</p></el-drawer>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {checkPermi} from '@/utils/permission'
import {getArtifactText,getApprovalPreviewText} from '@/api/paicli/sessions'
import type {PcArtifactTextPage} from '@/types/paicli'
import {safeText} from '../model'
import {useOperations} from '../useOperations'
const props=defineProps<{artifactId:string;sessionId:string;approvalId?:string;expectedSha256?:string;expectedSize?:number}>(),{busy,error,run}=useOperations(),open=ref(false),page=ref<PcArtifactTextPage>(),position=ref(0),permission=computed(()=>props.approvalId?'paicli:approval:decide':'paicli:artifact:read')
let generation=0,offsets=[0],controller:AbortController|undefined
async function load(offset:number,previous?:PcArtifactTextPage){if(!checkPermi([permission.value])||!props.artifactId||!props.sessionId)return false;const approval=props.approvalId,hash=props.expectedSha256,size=props.expectedSize;if(approval&&(!/^\d+$/.test(approval)||!hash||!/^[a-f0-9]{64}$/i.test(hash)||!Number.isSafeInteger(size)||(size ?? -1)<0)){error.value='批准差异缺少冻结的真实产物摘要或长度，未读取。';return false}controller?.abort();controller=new AbortController();const id=props.artifactId,session=props.sessionId,g=++generation;let ok=false;await run(async()=>{try{const value=(await(approval?getApprovalPreviewText(approval,offset,controller!.signal):getArtifactText(id,session,offset,controller!.signal))).data;if(g!==generation)return;if(value.artifactId!==id||value.sessionId!==session||value.offset!==offset||typeof value.text!=='string'||!Number.isSafeInteger(value.nextOffset)||value.nextOffset<offset||(value.hasMore&&value.nextOffset<=offset)||value.nextOffset>value.size||!Number.isSafeInteger(value.size)||!/^[a-f0-9]{64}$/i.test(value.sha256)||(approval&&(value.sha256!==hash||value.size!==size))||(previous&&(value.sha256!==previous.sha256||value.size!==previous.size)))throw new Error('文本产物分页范围或摘要已变化，保留当前页，请重新核对完整产物。');if(id===props.artifactId&&session===props.sessionId){page.value=value;ok=true}}catch(cause){if(g===generation)throw cause}});return ok}
async function start(){if(busy.value||!checkPermi([permission.value]))return;open.value=true;await restart()}
async function restart(){if(busy.value)return;if(await load(0)){position.value=0;offsets=[0]}}
async function next(){const current=page.value;if(!current?.hasMore||busy.value)return;const offset=current.nextOffset;if(await load(offset,current)){offsets[position.value+1]=offset;position.value++}}
async function previous(){const current=page.value;if(!current||position.value===0||busy.value)return;if(await load(offsets[position.value-1]!,current))position.value--}
watch(()=>[props.artifactId,props.sessionId,props.approvalId,props.expectedSha256,props.expectedSize],()=>{generation++;controller?.abort();open.value=false;page.value=undefined;position.value=0;offsets=[0];error.value=''});onBeforeUnmount(()=>{generation++;controller?.abort()})
</script>
