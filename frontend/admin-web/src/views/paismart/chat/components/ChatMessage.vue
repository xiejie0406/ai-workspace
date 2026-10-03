<template><article class="message" :class="message.role"><div class="role">{{message.role==='user'?'我的问题':message.role==='tool'?'工具结果':'知识回答'}} <span v-if="message.state" class="ps-muted">{{message.state}}</span><time v-if="message.createdAt" class="ps-muted">{{message.createdAt}}</time></div><div class="markdown-body" v-html="html"/><div v-if="message.references?.length" class="references"><el-button v-for="(reference,i) in message.references" :key="reference.id" link type="primary" @click="$emit('reference',reference)">[{{i+1}}] {{reference.title}} {{reference.page?'· 第 '+reference.page+' 页':''}} <span class="citation-state" :class="{'citation-state--suspicious':referenceVerdict(reference).suspicious}">{{referenceVerdict(reference).label}}</span><small v-if="referenceVerdict(reference).suspicious" class="citation-reason">{{referenceVerdict(reference).reason}}</small></el-button></div><div v-if="message.role==='assistant'&&!streaming" class="ps-actions"><el-button size="small" @click="copyAnswer">复制回答</el-button><el-button v-hasPermi="['paismart:chat:send']" size="small" @click="feedback('good')">有帮助</el-button><el-button v-hasPermi="['paismart:chat:send']" size="small" @click="feedback('bad')">需改进</el-button><span role="status">{{feedbackState}}</span></div></article></template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import {referenceVerdict} from '../../composables/referencePresentation'
import {renderSafeMarkdown} from '../../composables/safeMarkdown'
import 'katex/dist/katex.min.css'
import { ElMessageBox } from 'element-plus'
import { sendFeedback } from '@/api/paismart/chat'
import type { Message, Reference } from '@/types/paismart'
import { errorText } from '../../composables/usePage'
const props=defineProps<{message:Message;streaming?:boolean}>();defineEmits<{reference:[Reference]}>()
// Markdown、数学与表格经过 HTML 禁用、KaTeX trust:false 和 DOM 清洗。
const html=computed(()=>renderSafeMarkdown(props.message.content)),feedbackState=ref('')
async function copyAnswer(){try{await navigator.clipboard.writeText(props.message.content);feedbackState.value='回答已复制'}catch{await ElMessageBox.alert(props.message.content,'回答原文')}}
async function feedback(rating:'good'|'bad'){try{let reason='';if(rating==='bad'){const response=await ElMessageBox.prompt('请说明需要改进的内容', '回答反馈',{inputType:'textarea',inputValidator:v=>!!v?.trim()||'请填写反馈原因'});reason=response.value}await sendFeedback(props.message.id,rating,reason);feedbackState.value='反馈已记录'}catch(e){if(e!=='cancel'&&e!=='close')feedbackState.value=errorText(e)}}
</script>
<style scoped>.message{border:1px solid var(--el-border-color-light);border-radius:8px;padding:16px;margin:12px 0}.message.user{background:var(--el-color-primary-light-9)}.role{font-weight:600}.code-block{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color);padding:12px;border-radius:6px}.references{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.references :deep(.el-button){height:auto;white-space:normal;max-width:100%;text-align:left}.references :deep(.el-button>span){display:block;overflow-wrap:anywhere}.role span{font-weight:normal;margin-left:8px}</style>

<style scoped>.markdown-body{overflow-wrap:anywhere;line-height:1.7}.markdown-body :deep(pre){white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color);padding:12px;border-radius:6px}.markdown-body :deep(table){display:block;overflow:auto;border-collapse:collapse;max-width:100%}.markdown-body :deep(td),.markdown-body :deep(th){border:1px solid var(--el-border-color);padding:8px 12px}.markdown-body :deep(.math-block){overflow:auto}.markdown-body :deep(a){color:var(--el-color-primary)}.markdown-body :deep(blockquote){margin-left:0;padding-left:12px;border-left:3px solid var(--el-border-color)}</style>
