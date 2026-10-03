<template>
  <div class="ledger-reader">
  <el-button v-hasPermi="['paicli:session:export']" :disabled="!sessionId" @click="start">分页读取原始账本</el-button>
  <el-drawer v-model="open" title="原始账本与固定读取范围" size="850px"><p>本次固定末尾序号 {{ page?.endSequence ?? '尚未读取' }} · 当前游标 {{ page?.cursor ?? 0 }}</p><el-alert v-if="page && !page.rawPromptIncluded" title="当前权限只读取获授权账本；system 原始提示及推导正文已由中心移除。" type="info" :closable="false" /><el-alert v-if="error" :title="error" type="error" :closable="false" /><pre v-for="(entry,index) in page?.entries" :key="index" class="pc-pre">{{ safeText(entry) }}</pre><el-empty v-if="page && !page.entries.length" description="当前范围没有账本记录" /><div class="pc-actions"><el-button :loading="busy" :disabled="position===0" @click="previous">上一批</el-button><el-button :loading="busy" :disabled="!page?.hasMore" @click="next">下一批</el-button><el-button :loading="busy" @click="start">从当前末尾重新读取</el-button></div><p class="pc-muted">每批最多 200 条并受中心字节上限控制，翻页保持本次固定末尾序号；不以压缩视图替代原记录。</p></el-drawer>
  </div>
</template>
<script setup lang="ts">
import { onBeforeUnmount,ref,watch } from 'vue'
import { checkPermi } from '@/utils/permission'
import { getSessionLedger } from '@/api/paicli/sessions'
import type { PcLedgerPage } from '@/types/paicli'
import { safeText } from '../model'
import { useOperations } from '../useOperations'
const props=defineProps<{sessionId:string}>(),{busy,error,run}=useOperations(),page=ref<PcLedgerPage>(),open=ref(false),position=ref(0)
let generation=0,offsets=[0]
async function load(after:number,end?:number){if(!checkPermi(['paicli:session:export']))return false;const id=props.sessionId,g=++generation;let loaded=false;await run(async()=>{const response=await getSessionLedger(id,after,end);if(g===generation&&id===props.sessionId){page.value=response.data;loaded=true}});return loaded}
async function start(){if(!props.sessionId||busy.value||!checkPermi(['paicli:session:export']))return;page.value=undefined;position.value=0;offsets=[0];open.value=true;await load(0)}
async function next(){const current=page.value;if(!current?.hasMore||busy.value)return;if(await load(current.cursor,current.endSequence)){offsets[position.value+1]=current.cursor;position.value++}}
async function previous(){const current=page.value;if(position.value===0||!current||busy.value)return;if(await load(offsets[position.value-1]!,current.endSequence))position.value--}
watch(()=>props.sessionId,()=>{generation++;page.value=undefined;open.value=false});onBeforeUnmount(()=>{generation++})
</script>
