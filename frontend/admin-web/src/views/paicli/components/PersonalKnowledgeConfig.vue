<template>
  <section v-if="settings" class="pc-panel">
    <h2>检索与向量模型 · 配置版本 {{ settings.version }}</h2>
    <el-alert title="配置仅保存已批准服务地址和中心密钥引用；实际搜索、索引仍通过当前执行环境 执行。" type="info" :closable="false" />
    <el-form label-width="150px" :disabled="!canManage || busy">
      <h3>网页搜索</h3>
      <el-form-item label="启用搜索配置"><el-switch v-model="searchEnabled" /></el-form-item>
      <template v-if="searchEnabled">
        <el-form-item label="供应商"><el-select v-model="search.provider"><el-option v-for="provider in ['zhipu','deepseek','serpapi','searxng']" :key="provider" :label="provider" :value="provider" /></el-select></el-form-item>
        <el-form-item label="服务地址"><el-input v-model="search.baseUrl" placeholder="可选；留空由原供应商适配使用默认值" /></el-form-item>
        <el-form-item label="搜索模型"><el-input v-model="search.model" /></el-form-item>
        <el-form-item label="搜索引擎"><el-input v-model="search.searchEngine" /></el-form-item>
        <el-form-item label="中心密钥引用"><el-input v-model="search.secretRef" autocomplete="off" :placeholder="`ai.paicli.user.${ownerUserId}.*`" /></el-form-item>
      </template>
      <h3>代码语义索引</h3>
      <el-form-item label="启用向量配置"><el-switch v-model="embeddingEnabled" /></el-form-item>
      <template v-if="embeddingEnabled">
        <el-form-item label="供应商"><el-select v-model="embedding.provider"><el-option v-for="provider in ['ollama','openai','zhipu','glm']" :key="provider" :label="provider === 'openai' ? '阿里云百炼 / OpenAI兼容' : provider" :value="provider" /></el-select></el-form-item>
        <el-form-item label="完整向量模型 ID"><el-input v-model="embedding.model" /></el-form-item>
        <el-form-item label="服务地址"><el-input v-model="embedding.baseUrl" /></el-form-item><el-form-item label="预期向量维度"><el-input-number v-model="embedding.expectedDimensions" :min="1" :max="8192" /><span class="pc-muted"> text-embedding-v4 本次接入为 1024 维</span></el-form-item>
        <el-form-item label="中心密钥引用"><el-input v-model="embedding.secretRef" autocomplete="off" :placeholder="`ai.paicli.user.${ownerUserId}.*`" /></el-form-item>
      </template>
    </el-form>
    <el-alert v-if="validationError || error" :title="validationError || error" type="error" :closable="false" />
    <p class="pc-muted">停用会清除此个人对应配置，保留已有索引及审计。修改向量模型后须重新建立索引；保存不代表检索已通过。</p>
    <el-button v-hasPermi="['paicli:connection:manage']" type="primary" :loading="busy" @click="save">保存个人配置</el-button>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { checkPermi } from '@/utils/permission'
import { savePersonalSettings } from '@/api/paicli/personal'
import type { PcPersonalSettings, PcSearchConfig, PcEmbeddingConfig } from '@/types/paicli'
import { operationKeys, errorText } from '../model'
import { useOperations } from '../useOperations'
import { personalKnowledgePayload } from '../shared/personalSettings'
const props=defineProps<{ settings?:PcPersonalSettings;ownerUserId:string }>(),emit=defineEmits<{ saved:[] }>(),{ busy,error,run }=useOperations(),keys=operationKeys()
const searchEnabled=ref(false),embeddingEnabled=ref(false),validationError=ref(''),canManage=computed(()=>checkPermi(['paicli:connection:manage']))
const search=reactive<PcSearchConfig>({provider:'zhipu'}),embedding=reactive<PcEmbeddingConfig>({provider:'openai',model:'text-embedding-v4',baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',expectedDimensions:1024})
let generation=0
onBeforeUnmount(()=>{generation++})
watch(()=>[props.ownerUserId,props.settings?.version],()=>{generation++;const settings=props.settings;searchEnabled.value=!!settings?.searchConfig;embeddingEnabled.value=!!settings?.embeddingConfig;for(const key of Object.keys(search))delete(search as unknown as Record<string,unknown>)[key];for(const key of Object.keys(embedding))delete(embedding as unknown as Record<string,unknown>)[key];Object.assign(search,settings?.searchConfig || {provider:'zhipu'});Object.assign(embedding,settings?.embeddingConfig || {provider:'openai',model:'text-embedding-v4',baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',expectedDimensions:1024});validationError.value=''}, {immediate:true})
async function save(){if(!props.settings || !checkPermi(['paicli:connection:manage']) || busy.value)return;const owner=props.ownerUserId,version=props.settings.version,g=generation;let data:Pick<PcPersonalSettings,'modelId'|'searchConfig'|'embeddingConfig'|'embeddingMode'|'embeddingReleaseId'>;try{data={...personalKnowledgePayload(props.ownerUserId,searchEnabled.value?search:undefined,embeddingEnabled.value?embedding:undefined),embeddingMode:embeddingEnabled.value?'PERSONAL':'DISABLED',embeddingReleaseId:null}}catch(cause){validationError.value=errorText(cause);return};validationError.value='';await run(async()=>{const intent={version,...data};await savePersonalSettings(data,version,keys.get(intent));keys.done(intent);if(g===generation && owner===props.ownerUserId && version===props.settings?.version && checkPermi(['paicli:connection:manage']))emit('saved')})}
</script>
