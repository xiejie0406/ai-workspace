<template><main class="fixture-root"><h1>PaiSmart 迁移补齐组件技术夹具</h1><el-alert title="本页仅使用明确标注的本地测试响应，无真实后台代理、登录、模型或支付服务。技术检查不计业务验收通过。" type="warning" :closable="false"/><el-button @click="router.push('/paismart/evaluations')">评测来源技术夹具</el-button><el-button @click="router.push('/paismart/documents')">本人筛选技术夹具</el-button><el-button @click="router.push('/paismart/recharge?tradeNo=fixture-revoked')">充值状态技术夹具</el-button><el-button @click="router.push('/');active='summary'">额度、上传与评测夹具</el-button><el-button @click="active='chat'">会话切换夹具</el-button><el-button @click="active='index'">索引校验夹具</el-button><template v-if="route.path===`/`&&active==='summary'"><Usage/><el-button @click="uploadOpen=true">打开上传技术夹具</el-button><UploadDialog v-model="uploadOpen" :spaces="spaces"/><Review/></template><Chat v-if="route.path===`/`&&active==='chat'"/><Indexes v-if="route.path===`/`&&active==='index'"/><RouterView v-if="route.path!==`/`"/><h2>测试调用记录</h2><pre id="fixture-calls">{{fixtureCalls.join('\n')}}</pre></main></template>
<script setup lang="ts">
import {ref} from 'vue'
import {RouterView,useRoute,useRouter} from 'vue-router'
import Usage from '@/views/paismart/usage/index.vue'
import UploadDialog from '@/views/paismart/documents/components/UploadDialog.vue'
import Review from '@/views/paismart/governance/evaluations/review.vue'
import Chat from '@/views/paismart/chat/index.vue'
import Indexes from '@/views/paismart/governance/indexes/index.vue'
import {fixtureCalls,spaces} from './mock-api'
const uploadOpen=ref(false),active=ref('summary'),route=useRoute(),router=useRouter()
</script>
<style>body{margin:0;font-family:sans-serif;background:#f7f8fa}.fixture-root{box-sizing:border-box;max-width:1300px;margin:20px auto;padding:20px;background:white}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>


