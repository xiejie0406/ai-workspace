<script setup lang="ts">
import type { SlideSaveQueue } from '@/composables/aippt/useSlideSaveQueue'
defineProps<{queue:SlideSaveQueue}>();defineEmits<{retry:[];rebase:[];discard:[];copy:[]}>()
</script>
<template><el-alert v-if="queue.state.status==='failed'||queue.state.status==='conflict'" :closable="false" :type="queue.state.status==='conflict'?'warning':'error'" :title="queue.state.status==='conflict'?'页面版本冲突，保存队列已暂停':'保存失败，草稿已保留'"><p>{{queue.state.error}}</p><el-button @click="$emit('copy')">复制草稿</el-button><el-button v-if="queue.state.status==='failed'" @click="$emit('retry')">重试同一操作</el-button><el-button @click="$emit('rebase')">读取最新版本并重新应用</el-button><el-button @click="$emit('discard')">放弃本地未保存操作</el-button></el-alert></template>
