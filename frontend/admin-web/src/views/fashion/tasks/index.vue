<template>
  <div class="app-container">
    <el-alert title="任务列表汇总当前用户有权查看的商品属性建议、需求分析和模特试衣。执行成功后仍需人工复核，未知费用不显示为零。" type="info" :closable="false" show-icon />
    <div class="toolbar"><el-button type="primary" :loading="loading" @click="load">刷新任务</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="space" />
    <el-table :data="items" v-loading="loading" :empty-text="error?'任务读取失败，请重试':'暂无有权查看的任务'">
      <el-table-column prop="id" label="任务编号" min-width="180" /><el-table-column label="类型" width="140"><template #default="{row}">{{typeLabel(row)}}</template></el-table-column>
      <el-table-column prop="relatedName" label="关联商品 / 工作单" min-width="180" />
      <el-table-column label="来源编号" min-width="180"><template #default="{row}">{{row.poolId||row.productId||row.sourceId||'未记录'}}</template></el-table-column>
      <el-table-column label="发起人" min-width="150"><template #default="{row}">{{row.ownerName||(row.ownerId!=null?'账号 '+row.ownerId:'未记录')}}</template></el-table-column>
      <el-table-column label="执行状态" width="130"><template #default="{row}">{{statusLabel(row.status)}}</template></el-table-column>
      <el-table-column label="复核状态" width="140"><template #default="{row}">{{statusLabel(row.reviewStatus)||'尚未复核'}}</template></el-table-column>
      <el-table-column label="采用状态" width="130"><template #default="{row}">{{adoptionLabel(row)}}</template></el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="180" /><el-table-column label="完成时间" min-width="180"><template #default="{row}">{{row.finishedAt||'尚未完成'}}</template></el-table-column>
      <el-table-column label="费用" min-width="160"><template #default="{row}">{{row.actualCost==null?'实际费用未知':row.actualCost+' '+(row.currency||'')}} · {{billingLabel(row.billingStatus)}}</template></el-table-column>
      <el-table-column label="服务 / 模型" min-width="180"><template #default="{row}">{{row.providerCode||'未记录'}} / {{row.model||'未记录'}}</template></el-table-column>
      <el-table-column prop="errorMessage" label="失败说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right"><template #default="{row}"><el-button link type="primary" @click="showDetail(row)">详情</el-button><el-button v-if="row.poolId" link type="primary" @click="router.push({path:'/fashion/pool',query:{poolId:row.poolId}})">返回选品</el-button><el-button v-else-if="row.productId" link type="primary" @click="router.push({path:'/fashion/product',query:{productId:row.productId}})">返回商品</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="space" v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total,sizes,prev,pager,next" @current-change="load" @size-change="page=1;load()" />
    <el-dialog v-model="detailVisible" title="任务详情" width="680px"><el-descriptions :column="1" border><el-descriptions-item v-for="row in detailRows" :key="row.label" :label="row.label">{{row.value}}</el-descriptions-item></el-descriptions><p>应用、复核、取消和恢复在任务来源页面进行，避免绕过其版本与许可检查。</p></el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onBeforeUnmount,onMounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { listBusinessTasks,getBusinessTaskDetail,type BusinessTask } from '@/api/fashion/proposal'
import {businessTaskDetailRows,taskTypeLabel as typeLabel,taskStatusLabel as statusLabel,taskBillingLabel as billingLabel,taskAdoptionLabel as adoptionLabel} from './taskDetail'
const router=useRouter(),items=ref<BusinessTask[]>([]),page=ref(1),pageSize=ref(20),total=ref(0),loading=ref(false),error=ref(''),detailVisible=ref(false),detailRows=ref<{label:string;value:string}[]>([])
let controller:AbortController|undefined,detailController:AbortController|undefined,sequence=0,detailSequence=0
async function load(){const seq=++sequence;controller?.abort();controller=new AbortController();items.value=[];total.value=0;loading.value=true;error.value='';try{const response=await listBusinessTasks(page.value,pageSize.value,controller.signal);if(seq!==sequence)return;items.value=response.data.items;total.value=response.data.total}catch(e){if(seq===sequence)error.value=e instanceof Error?e.message:'任务读取失败'}finally{if(seq===sequence)loading.value=false}}
async function showDetail(task:BusinessTask){const seq=++detailSequence;detailController?.abort();detailController=new AbortController();detailVisible.value=false;detailRows.value=[];error.value='';try{const response=await getBusinessTaskDetail(task.detailPath,detailController.signal);if(seq!==detailSequence)return;detailRows.value=businessTaskDetailRows(task,response.data);detailVisible.value=true}catch(e){if(seq===detailSequence)error.value=e instanceof Error?e.message:'详情读取失败'}}
onMounted(load);onBeforeUnmount(()=>{sequence++;detailSequence++;controller?.abort();detailController?.abort()})
</script>
<style scoped>.toolbar,.space{margin:16px 0}p{color:var(--el-text-color-secondary)}</style>
