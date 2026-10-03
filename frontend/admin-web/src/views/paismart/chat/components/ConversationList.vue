<template><aside class="ps-panel"><div class="ps-toolbar"><h3 style="margin:0">我的会话</h3><el-button v-hasPermi="['paismart:chat:send']" size="small" @click="$emit('create')">新会话</el-button></div><el-input v-model="query.keyword" placeholder="筛选会话" clearable @keyup.enter="load(true)"/><el-alert v-if="error" :title="error" type="error" :closable="false"/><div v-loading="loading"><button v-for="row in rows" :key="row.id" class="conversation" :class="{selected:String(row.id)===String(selected)}" @click="$emit('select',row)"><span>{{row.title}}</span><small>{{row.updatedAt}}</small></button><el-empty v-if="!loading&&!rows.length" description="还没有会话" :image-size="60"/></div><el-pagination small v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total" layout="prev,pager,next" @current-change="load()"/></aside></template>
<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { listConversations } from '@/api/paismart/conversations'
import type { Conversation, Id } from '@/types/paismart'
import { usePage } from '../../composables/usePage'
const props=defineProps<{selected?:Id;refreshKey?:number}>();defineEmits<{select:[Conversation];create:[]}>()
const {rows,total,query,loading,error,load}=usePage(listConversations,{archived:false,pageSize:15})
onMounted(()=>void load());watch(()=>props.refreshKey,()=>void load())
</script>
<style scoped>.conversation{display:flex;flex-direction:column;width:100%;text-align:left;gap:6px;padding:12px;background:transparent;border:0;border-bottom:1px solid var(--el-border-color-lighter);cursor:pointer;color:var(--el-text-color-primary)}.conversation.selected{background:var(--el-color-primary-light-9)}.conversation:focus-visible{outline:2px solid var(--el-color-primary)}.conversation small{color:var(--el-text-color-secondary)}</style>
