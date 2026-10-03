<template>
  <section class="pc-panel">
    <h2>当前工作区待批准</h2>
    <el-alert title="申请来自当前工作区成员。此入口直接核对批准申请，不借批准权限读取他人的会话或任务。决定仍绑定申请版本、输入哈希、策略和有效期。" type="info" :closable="false" />
    <el-alert v-if="!canDecide" title="当前角色没有人工批准权限。" type="info" :closable="false" />
    <template v-else>
      <div class="pc-actions"><el-button :loading="loading" :disabled="!workspaceId" @click="load">刷新待批准申请</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table v-loading="loading" :data="approvals"><el-table-column prop="toolName" label="完整工具名" min-width="200" /><el-table-column prop="requesterName" label="发起人" width="120" /><el-table-column prop="taskId" label="任务引用" min-width="150" /><el-table-column label="有效期" min-width="180"><template #default="{row}">{{displayTime(row.expiresAt)}}</template></el-table-column><el-table-column label="状态" width="140"><template #default="{row}">{{stateName(row.status)}}</template></el-table-column><el-table-column label="操作" width="160"><template #default="{row}"><el-button v-hasPermi="['paicli:approval:decide']" link type="primary" @click="open(row)">核对完整影响</el-button></template></el-table-column></el-table>
      <el-empty v-if="!loading && !approvals.length && !error" description="当前工作区没有待批准申请" />
      <Pagination v-if="total" v-model:page="page.pageNum" v-model:limit="page.pageSize" :total="total" @pagination="load" />
    </template>
    <ApprovalDrawer v-model="drawerOpen" :approval="selected" @decided="decided" />
  </section>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import Pagination from '@/components/Pagination/index.vue'
import {checkPermi} from '@/utils/permission'
import {listPendingApprovals} from '@/api/paicli/sessions'
import type {PcApproval} from '@/types/paicli'
import ApprovalDrawer from './ApprovalDrawer.vue'
import {displayTime,errorText,stateName} from '../model'
const props=defineProps<{workspaceId:string}>(),emit=defineEmits<{decided:[]}>(),canDecide=computed(()=>checkPermi(['paicli:approval:decide'])),approvals=ref<PcApproval[]>([]),total=ref(0),loading=ref(false),error=ref(''),selected=ref<PcApproval>(),drawerOpen=ref(false),page=reactive({pageNum:1,pageSize:20})
let generation=0
async function load(){const id=props.workspaceId,g=++generation;if(!id||!canDecide.value)return;loading.value=true;error.value='';try{const response=await listPendingApprovals(id,page.pageNum,page.pageSize);if(g===generation&&id===props.workspaceId){approvals.value=response.rows;total.value=response.total}}catch(cause){if(g===generation)error.value=errorText(cause)}finally{if(g===generation)loading.value=false}}
function open(row:PcApproval){if(!canDecide.value||row.workspaceId!==props.workspaceId)return;selected.value=row;drawerOpen.value=true}
async function decided(){await load();emit('decided')}
watch(()=>props.workspaceId,()=>{generation++;approvals.value=[];total.value=0;page.pageNum=1;selected.value=undefined;drawerOpen.value=false;void load()},{immediate:true});onBeforeUnmount(()=>{generation++})
</script>
