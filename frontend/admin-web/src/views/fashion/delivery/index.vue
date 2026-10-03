<template>
  <div class="app-container">
    <el-alert title="提案来自已选方案和草稿版本，保留图片、价格口径与文件记录。生成PPT是同步请求，页面只展示实际结果。" type="info" :closable="false" show-icon />
    <div class="toolbar"><el-button type="primary" :loading="loading" @click="load">刷新交付记录</el-button><el-button @click="router.push('/fashion/pool')">进入选品生成提案</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="space" />
    <el-table :data="items" v-loading="loading" :empty-text="error?'交付记录读取失败，请重试':'尚未生成提案文件'">
      <el-table-column prop="title" label="提案名称 / 文件名" min-width="220" />
      <el-table-column label="来源工作单" min-width="180"><template #default="{row}">{{row.poolTitle||row.poolId}}<small v-if="row.poolTitle" class="source-id">{{row.poolId}}</small></template></el-table-column>
      <el-table-column label="客户" min-width="150"><template #default="{row}">{{row.customerName??row.customerId??'未记录客户关联'}}</template></el-table-column>
      <el-table-column prop="draftVersion" label="草稿版本" width="100" /><el-table-column prop="combinationCount" label="方案数量" width="100" />
      <el-table-column label="价格口径" width="100"><template #default="{row}">{{priceLabel(row.priceMode)}}</template></el-table-column><el-table-column label="图片方式" min-width="140"><template #default="{row}">{{row.imageMode==='model'?'已采用试衣图＋原图':'商品原图'}}</template></el-table-column>
      <el-table-column label="生成结果" width="110"><template #default="{row}">{{statusLabel(row.status)}}</template></el-table-column><el-table-column label="页数 / 大小" width="150"><template #default="{row}">{{row.pageCount??'未知'}} 页 / {{row.fileSize==null?'未知':bytes(row.fileSize)}}</template></el-table-column>
      <el-table-column label="生成人" min-width="150"><template #default="{row}">{{row.ownerName||(row.ownerId!=null?'账号 '+row.ownerId:'未记录')}}</template></el-table-column>
      <el-table-column prop="createdAt" label="生成时间" min-width="180" /><el-table-column prop="errorMessage" label="失败原因" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="160" fixed="right"><template #default="{row}"><el-button link type="primary" v-hasPermi="['fashion:quote:export']" :disabled="row.status!=='success'||downloading===row.id" @click="download(row)">下载</el-button><el-button link type="primary" @click="router.push({path:'/fashion/pool',query:{poolId:row.poolId}})">返回方案</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="space" v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total,sizes,prev,pager,next" @current-change="load" @size-change="page=1;load()" />
    <p>“下载次数”是服务端响应记录，不代表客户已收到或接受。重新生成从对应工作单进入，保留旧文件记录。</p>
  </div>
</template>
<script setup lang="ts">
import { onBeforeUnmount,onMounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { listProposalDeliveries,downloadProposalDelivery,type ProposalDelivery } from '@/api/fashion/proposal'
import { saveBlob } from '@/api/fashion/download'
import { ensurePptx } from '../pool/proposalWorkspaceState'
const router=useRouter(),items=ref<ProposalDelivery[]>([]),page=ref(1),pageSize=ref(20),total=ref(0),loading=ref(false),error=ref(''),downloading=ref('')
let controller:AbortController|undefined,sequence=0
async function load(){const seq=++sequence;controller?.abort();controller=new AbortController();items.value=[];total.value=0;loading.value=true;error.value='';try{const response=await listProposalDeliveries(page.value,pageSize.value,controller.signal);if(seq!==sequence)return;items.value=response.data.items;total.value=response.data.total}catch(e){if(seq===sequence)error.value=e instanceof Error?e.message:'交付记录读取失败'}finally{if(seq===sequence)loading.value=false}}
async function download(row:ProposalDelivery){downloading.value=row.id;error.value='';try{const blob=await downloadProposalDelivery(row.id);await ensurePptx(blob);saveBlob(blob,(row.title.replace(/[\\/:*?"<>|]/g,'-')||'套装提案')+'.pptx');await load()}catch(e){error.value=e instanceof Error?e.message:'下载失败'}finally{downloading.value=''}}
function priceLabel(mode:string){return({none:'不展示',group:'团购价',vip:'VIP价'} as Record<string,string>)[mode]||mode}
function statusLabel(status:string){return({success:'生成成功',failed:'生成失败',generating:'同步生成中',unknown:'结果待确认'} as Record<string,string>)[status]||status}
function bytes(value:number){return value<1024?value+' B':value<1048576?(value/1024).toFixed(1)+' KB':(value/1048576).toFixed(1)+' MB'}
onMounted(load);onBeforeUnmount(()=>{sequence++;controller?.abort()})
</script>
<style scoped>.toolbar,.space{margin:16px 0}.toolbar{display:flex;gap:12px}.source-id{display:block;color:var(--el-text-color-secondary)}p{color:var(--el-text-color-secondary)}</style>
