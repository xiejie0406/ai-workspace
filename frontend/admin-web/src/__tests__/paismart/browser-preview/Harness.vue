<template><main style="max-width:1050px;margin:20px auto;font-family:sans-serif"><h1>PaiSmart 组件技术检查</h1><p>使用本地脱敏夹具，仅验证组件，未联机业务系统。</p><ChatMessage :message="message" streaming/><h2>PDF 缩放与引用</h2><el-button @click="zoom=100">100%</el-button><el-button @click="zoom=150">150%</el-button><span role="status">缩放 {{zoom}}%</span><PdfPage v-if="blob" :blob="blob" :zoom="zoom" anchor="PaiSmart citation anchor"/></main></template>
<script setup lang="ts">
import {ref} from 'vue'
import ChatMessage from '@/views/paismart/chat/components/ChatMessage.vue'
import PdfPage from '@/views/paismart/documents/components/PdfPage.vue'
import {message} from './fixture'
const blob=ref<Blob>(),zoom=ref(100)
fetch('./sample.pdf').then(r=>{if(!r.ok)throw new Error('PDF夹具缺失');return r.blob()}).then(b=>blob.value=b)
</script>
