<template><article class="message" :class="message.role"><div class="role">{{message.role==='user'?'我的问题':message.role==='tool'?'工具结果':'知识回答'}} <span v-if="message.state" class="ps-muted">{{message.state}}</span><time v-if="message.createdAt" class="ps-muted">{{message.createdAt}}</time></div><div class="markdown-body" v-html="html"/><div v-if="message.references?.length" class="references"><el-button v-for="(reference,i) in message.references" :key="reference.id" link type="primary" @click="$emit('reference',reference)">[{{i+1}}] {{reference.title}} {{reference.page?'· 第 '+reference.page+' 页':''}} <span class="citation-state" :class="{'citation-state--suspicious':referenceVerdict(reference).suspicious}">{{referenceVerdict(reference).label}}</span><small v-if="referenceVerdict(reference).suspicious" class="citation-reason">{{referenceVerdict(reference).reason}}</small></el-button></div><div v-if="message.role==='assistant'&&!streaming" class="ps-actions" :aria-busy="feedbackBusy">
<el-button size="small" @click="copyAnswer">复制回答</el-button>
<el-button v-hasPermi="['paismart:chat:send']" size="small" :type="currentRating==='GOOD'?'primary':undefined" :aria-pressed="currentRating==='GOOD'" :disabled="!feedbackAllowed||feedbackBusy" :loading="feedbackBusy&&pendingRating==='good'" @click="feedback('good')">有帮助</el-button>
<el-button v-hasPermi="['paismart:chat:send']" size="small" :type="currentRating==='BAD'?'primary':undefined" :aria-pressed="currentRating==='BAD'" :disabled="!feedbackAllowed||feedbackBusy" :loading="feedbackBusy&&pendingRating==='bad'" @click="feedback('bad')">需改进</el-button>
<span v-if="currentRating" class="ps-muted">当前评价：{{currentRating==='GOOD'?'有帮助':'需改进'}}</span>
<span v-if="feedbackUnavailable" class="ps-muted">{{feedbackUnavailable}}</span><span role="status" aria-live="polite">{{feedbackState}}</span></div></article></template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {referenceVerdict} from '../../composables/referencePresentation'
import {renderSafeMarkdown} from '../../composables/safeMarkdown'
import 'katex/dist/katex.min.css'
import { ElMessageBox } from 'element-plus'
import { sendFeedback } from '@/api/paismart/chat'
import type { Message, Reference } from '@/types/paismart'
import { errorText, terminal } from '../../composables/usePage'
const props=defineProps<{message:Message;streaming?:boolean}>();defineEmits<{reference:[Reference]}>()
// Markdown、数学与表格经过 HTML 禁用、KaTeX trust:false 和 DOM 清洗。
const html=computed(()=>renderSafeMarkdown(props.message.content)),feedbackState=ref('')
const currentRating=ref<Message['rating']>(null),currentVersion=ref(0),feedbackBusy=ref(false),pendingRating=ref<'good'|'bad'>()
let identityVersion=0,disposed=false
// 消息切换（包括A→B→A）或重新读取评价后，旧弹窗和响应都失效。
watch(()=>[props.message.id,props.message.generationId,props.message.rating,props.message.feedbackVersion,props.message.feedbackEligible,props.streaming],(value,previous)=>{
  if(previous&&value.every((field,index)=>Object.is(field,previous[index])))return
  // 同一身份的旧读取不能倒退刚收到的当前值；资格或stream状态改变仍使旧操作失效。
  if(previous&&value[0]===previous[0]&&value[1]===previous[1]&&value[4]===previous[4]&&value[5]===previous[5]&&Number.isSafeInteger(props.message.feedbackVersion)&&props.message.feedbackVersion!>=0&&props.message.feedbackVersion!<currentVersion.value)return
  identityVersion++;feedbackBusy.value=false;pendingRating.value=undefined;feedbackState.value=''
  currentRating.value=props.message.rating==='GOOD'||props.message.rating==='BAD'?props.message.rating:null
  currentVersion.value=Number.isSafeInteger(props.message.feedbackVersion)&&props.message.feedbackVersion!>=0?props.message.feedbackVersion!:0
},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{disposed=true;identityVersion++})
const feedbackVersionValid=computed(()=>props.message.feedbackVersion===undefined||Number.isSafeInteger(props.message.feedbackVersion)&&props.message.feedbackVersion>=0)
const feedbackAllowed=computed(()=>feedbackVersionValid.value&&props.message.feedbackEligible!==false&&props.message.generationId!=null&&String(props.message.generationId).length>0&&!props.streaming&&(props.message.feedbackEligible===true||terminal(props.message.state??'')&&!!props.message.content.trim()))
const feedbackUnavailable=computed(()=>{
  if(feedbackAllowed.value)return ''
  if(!feedbackVersionValid.value)return '评价状态无效，请重新读取回答后再试。'
  if(!terminal(props.message.state??''))return '此回答尚未生成完成，暂不能评价。'
  if(!props.message.content.trim())return '此回答没有可评价的正文。'
  if(props.message.generationId==null)return '此历史回答的生成关联尚未确认，暂不能评价。'
  return props.message.feedbackEligible===false?'此回答尚不满足评价条件，暂不能评价。':'评价状态尚未加载，请重新读取回答后再试。'
})
function snapshot(){return {version:identityVersion,messageId:props.message.id,generationId:props.message.generationId,feedbackVersion:currentVersion.value}}
function belongsToCurrent(target:ReturnType<typeof snapshot>){return !disposed&&identityVersion===target.version&&String(props.message.id)===String(target.messageId)&&props.message.generationId===target.generationId}
function cancelled(error:unknown){return error==='cancel'||error==='close'}
async function copyAnswer(){
  const target=snapshot(),content=props.message.content
  try{await navigator.clipboard.writeText(content);if(belongsToCurrent(target))feedbackState.value='回答已复制'}
  catch{if(!belongsToCurrent(target))return;try{await ElMessageBox.alert(content,'回答原文')}catch(error){if(belongsToCurrent(target)&&!cancelled(error))feedbackState.value=errorText(error)}}
}
async function feedback(rating:'good'|'bad'){
  if(!feedbackAllowed.value||feedbackBusy.value)return
  const target=snapshot();feedbackBusy.value=true;pendingRating.value=rating;feedbackState.value=''
  try{
    let reason=''
    if(rating==='bad'){
      const response=await ElMessageBox.prompt('请说明需要改进的内容','回答反馈',{inputType:'textarea',inputValidator:value=>!value?.trim()?'请填写反馈原因':value.length>1000?'反馈原因不得超过1000字':true})
      if(!belongsToCurrent(target))return
      reason=response.value
    }
    if(!belongsToCurrent(target))return
    feedbackState.value='正在保存评价…'
    const response=await sendFeedback(target.messageId,rating,reason,target.feedbackVersion)
    if(!belongsToCurrent(target))return
    const result=response.data
    if(!result||String(result.messageId)!==String(target.messageId)||String(result.generationId)!==String(target.generationId)||!result.conversationId||result.rating!==rating.toUpperCase()||!Number.isSafeInteger(result.rowVersion)||result.rowVersion<1||typeof result.changed!=='boolean'||result.rowVersion!==target.feedbackVersion+(result.changed?1:0))throw new Error('反馈回执与当前回答不一致，请重新读取回答后确认')
    currentRating.value=result.rating;currentVersion.value=result.rowVersion
    feedbackState.value=result.changed?'反馈已记录':'反馈已记录，当前评价未变化'
  }catch(error){if(belongsToCurrent(target)&&!cancelled(error))feedbackState.value=errorText(error)}
  finally{if(belongsToCurrent(target)){feedbackBusy.value=false;pendingRating.value=undefined}}
}
</script>
<style scoped>.message{border:1px solid var(--el-border-color-light);border-radius:8px;padding:16px;margin:12px 0}.message.user{background:var(--primary-bg,var(--el-color-primary-light-9));color:var(--el-text-color-primary)}.role{font-weight:600}.code-block{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color);padding:12px;border-radius:6px}.references{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.references :deep(.el-button){height:auto;white-space:normal;max-width:100%;text-align:left}.references :deep(.el-button>span){display:block;overflow-wrap:anywhere}.role span{font-weight:normal;margin-left:8px}</style>

<style scoped>.markdown-body{overflow-wrap:anywhere;line-height:1.7}.markdown-body :deep(pre){white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color);padding:12px;border-radius:6px}.markdown-body :deep(table){display:block;overflow:auto;border-collapse:collapse;max-width:100%}.markdown-body :deep(td),.markdown-body :deep(th){border:1px solid var(--el-border-color);padding:8px 12px}.markdown-body :deep(.math-block){overflow:auto}.markdown-body :deep(a){color:var(--el-color-primary)}.markdown-body :deep(blockquote){margin-left:0;padding-left:12px;border-left:3px solid var(--el-border-color)}</style>
