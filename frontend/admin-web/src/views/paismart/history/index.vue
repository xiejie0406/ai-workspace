<template><div class="app-container ps-page"><h2>问答历史</h2><el-alert v-if="error||pageError" :title="error||pageError" type="error" :closable="false"/><el-form inline><el-form-item label="会话标题"><el-input v-model="query.keyword" clearable @keyup.enter="load(true)"/></el-form-item><el-form-item label="归档状态"><el-select v-model="query.archived"><el-option label="未归档" :value="false"/><el-option label="已归档" :value="true"/></el-select></el-form-item><el-form-item label="日期范围"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD"/></el-form-item><el-button @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form><el-table :data="rows" v-loading="loading"><el-table-column prop="title" label="会话" min-width="260"/><el-table-column prop="updatedAt" label="最近更新" min-width="170"/><el-table-column label="状态" width="110"><template #default="{row}">{{row.archived?'已归档':'未归档'}}</template></el-table-column><el-table-column label="操作" width="260"><template #default="{row}"><el-button link type="primary" @click="view(row)">查看消息</el-button><el-button v-hasPermi="['paismart:chat:send']" link @click="continueChat(row)">继续问答</el-button><el-button v-hasPermi="['paismart:conversation:list']" link @click="toggleArchive(row)">{{row.archived?'取消归档':'归档'}}</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="total,prev,pager,next" @current-change="load()"/><el-drawer v-model="detailOpen" :title="selected?.title" size="min(900px,95vw)"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false"/><ChatMessage v-for="message in messages" :key="message.id" :message="message" @reference="r=>{reference=r;referenceOpen=true}"/><el-pagination v-model:current-page="messagePage" :page-size="50" :total="messageTotal" layout="total,prev,pager,next" @current-change="loadMessages"/></el-drawer><ReferencePreview v-model="referenceOpen" :reference="reference"/></div></template>
<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { conversationAction, conversationMessages, listConversations } from '@/api/paismart/conversations'
import type { Conversation, Message, Reference } from '@/types/paismart'
import ChatMessage from '../chat/components/ChatMessage.vue'
import ReferencePreview from '../chat/components/ReferencePreview.vue'
import { errorText,usePage } from '../composables/usePage'
import '../common.css'
const router=useRouter(),{rows,total,query,loading,error,load}=usePage(listConversations,{archived:false}),selected=shallowRef<Conversation>(),messages=shallowRef<Message[]>([]),detailOpen=ref(false),referenceOpen=ref(false),reference=shallowRef<Reference>(),pageError=ref(''),detailError=ref(''),messagePage=ref(1),messageTotal=ref(0)
const dates=ref<string[]>([])
function search(){query.value.startTime=dates.value?.[0];query.value.endTime=dates.value?.[1];void load(true)}
function reset(){dates.value=[];query.value.keyword=undefined;query.value.archived=false;search()}
async function view(row:Conversation){selected.value=row;messagePage.value=1;detailOpen.value=true;await loadMessages()}
async function loadMessages(){if(!selected.value)return;detailError.value='';try{const response=await conversationMessages(selected.value.id,{pageNum:messagePage.value,pageSize:50});messages.value=response.rows;messageTotal.value=response.total}catch(e){detailError.value=errorText(e)}}
async function toggleArchive(row:Conversation){try{await conversationAction(row,row.archived?'unarchive':'archive');await load()}catch(e){pageError.value=errorText(e)}}
async function continueChat(row:Conversation){try{if(row.archived)await conversationAction(row,'unarchive');await router.push({path:'/paismart/chat',query:{conversationId:String(row.id)}})}catch(e){pageError.value=errorText(e)}}
onMounted(()=>void load())
</script>
