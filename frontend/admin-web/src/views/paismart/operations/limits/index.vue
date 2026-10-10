<template>
  <div class="app-container ps-page">
    <h2>用量与限流政策</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="ps-section"/>
    <el-card v-loading="loading" shadow="never">
      <el-form v-if="form" label-width="190px">
        <h3>个人请求与文件保护</h3>

        <el-form-item label="每分钟上传请求"><el-input-number v-model="form.uploadPerMinute" :min="1" :max="10000" :precision="0"/></el-form-item>
        <el-form-item label="每日个人对话 Token"><el-input-number v-model="form.dailyChatTokens" :min="1" :max="1000000000000" :precision="0"/></el-form-item>
        <el-form-item label="单文件上限（字节）"><el-input-number v-model="form.maxFileBytes" :min="1" :max="2147483648" :precision="0"/></el-form-item>
        <template v-if="form.rules">
          <h3>问答消息窗口</h3>
          <el-form-item label="窗口最大请求数"><el-input-number v-model="form.rules.chatMessage.max" :min="1" :max="1000000" :precision="0"/></el-form-item>
          <el-form-item label="窗口秒数"><el-input-number v-model="form.rules.chatMessage.windowSeconds" :min="1" :max="86400" :precision="0"/></el-form-item>
          <section v-for="rule in budgetRules" :key="rule.key" class="budget-rule">
            <h3>{{rule.label}}</h3>
            <el-form-item :label="'短窗口最大'+rule.unit"><el-input-number v-model="form.rules[rule.key].minuteMax" :min="1" :max="1000000000000" :precision="0"/></el-form-item>
            <el-form-item label="短窗口秒数"><el-input-number v-model="form.rules[rule.key].minuteWindowSeconds" :min="1" :max="86400" :precision="0"/></el-form-item>
            <el-form-item :label="'长窗口最大'+rule.unit"><el-input-number v-model="form.rules[rule.key].dayMax" :min="1" :max="1000000000000" :precision="0"/></el-form-item>
            <el-form-item label="长窗口秒数"><el-input-number v-model="form.rules[rule.key].dayWindowSeconds" :min="1" :max="604800" :precision="0"/></el-form-item>
          </section>
        </template>
        <el-alert v-else title="后端没有返回原有五组窗口政策，当前不允许用缺失规则覆盖配置。请核对接口版本。" type="warning" :closable="false"/>
        <el-form-item label="当前政策版本">{{form.rowVersion}}</el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['paismart:usage:adjust']" type="primary" :loading="saving" :disabled="!form.rules" @click="save">保存全部政策</el-button>
          <el-button @click="load">重新读取</el-button><span role="status">{{result}}</span>
        </el-form-item>
      </el-form>
      <el-empty v-else-if="!loading" description="未读取到政策，不能以默认值覆盖现有配置"/>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { getLimits,saveLimits } from '@/api/paismart/operations'
import type { RateLimits } from '@/types/paismart'
import { errorText } from '../../composables/usePage'
import '../../common.css'
const form=ref<RateLimits>(),loading=ref(false),saving=ref(false),error=ref(''),result=ref('')
const budgetRules=[{key:'llmGlobalToken',label:'全局对话 Token 预算',unit:'Token'},{key:'embeddingUploadToken',label:'上传向量 Token 预算',unit:'Token'},{key:'embeddingQueryRequest',label:'检索向量请求窗口',unit:'请求数'},{key:'embeddingQueryGlobalToken',label:'全局检索向量 Token 预算',unit:'Token'}] as const
let requestSerial=0,formSerial=0,active=true
watch(form,()=>{formSerial++;result.value=''},{deep:true,flush:'sync'})
async function load(){
  if(!active)return
  const current=++requestSerial,revision=formSerial,sameForm=()=>active&&current===requestSerial&&revision===formSerial
  loading.value=true;error.value='';result.value=''
  try{const response=await getLimits();if(sameForm())form.value=response.data}
  catch(e){if(sameForm())error.value=errorText(e)}
  finally{if(active&&current===requestSerial)loading.value=false}
}
async function save(){
  if(saving.value||loading.value||!active||!form.value?.rules)return
  const current=++requestSerial,revision=formSerial
  const input={...form.value,rules:Object.fromEntries(Object.entries(form.value.rules).map(([key,value])=>[key,{...value}])) as RateLimits['rules']}
  const sameForm=()=>active&&current===requestSerial&&revision===formSerial
  saving.value=true;error.value='';result.value=''
  try{const response=await saveLimits(input);if(sameForm()){form.value=response.data;result.value='全部政策已保存'}}
  catch(e){if(sameForm())error.value=errorText(e)}
  finally{if(active)saving.value=false}
}
onBeforeUnmount(()=>{active=false;requestSerial++})
onMounted(()=>void load())
</script>
<style scoped>.budget-rule{padding:8px 0;border-top:1px solid var(--el-border-color-lighter)}</style>
