<template>
  <section v-if="settings" class="pc-panel" aria-label="代码检索能力">
    <h2>代码检索</h2>
    <p role="status">{{ statusText }}<span v-if="capability?.model"> · {{ capability.model }} · {{ capability.expectedDimensions }} 维</span></p>
    <p class="pc-muted">{{ capability?.message || '语义检索帮助定位相关代码；精确文件搜索仍可使用。' }}</p>
    <el-form label-width="150px" :disabled="busy || !canEditSelection">
      <el-form-item label="使用方式"><el-select v-model="mode" aria-label="代码检索使用方式"><el-option label="自动选择（个人优先）" value="AUTO" /><el-option label="选择内置向量模型" value="PLATFORM" /><el-option v-if="canManage" label="使用个人配置" value="PERSONAL" /><el-option label="停用语义检索" value="DISABLED" /></el-select></el-form-item>
      <el-form-item v-if="mode === 'PLATFORM'" label="内置向量模型"><el-select v-model="releaseId" aria-label="选择内置向量模型"><el-option v-for="item in availableReleases" :key="item.releaseId" :value="item.releaseId" :label="`${item.model} · ${item.expectedDimensions} 维${item.defaultModel ? ' · 平台默认' : ''}`" /></el-select></el-form-item>
    </el-form>
    <p v-if="mode === 'AUTO'" class="pc-muted">个人配置优先；没有个人选择时使用受众内的平台默认。个人配置失败会保留失败状态。</p>
    <p v-if="mode === 'DISABLED'" class="pc-muted">停用后不会自动改用平台默认；已有文件和索引保留。</p>
    <el-button :disabled="busy || !canSelect || (mode === 'PLATFORM' && !releaseId)" type="primary" @click="saveSelection">保存使用方式</el-button>
    <template v-if="canManage && advanced">
      <h3>验证与发布</h3><p class="pc-muted">仅验证已保存的个人向量配置；修改型号、地址或维度后需要重新验证和建立索引。</p>
      <div class="pc-actions"><el-button v-hasPermi="['paicli:connection:manage']" :disabled="busy || confirming || !canVerify" @click="verify">验证个人向量能力</el-button><el-button v-if="canPublish" :disabled="busy || !canPublishCurrent" @click="openPublish">发布内置向量能力</el-button></div>
      <div v-if="canPublish"><h4>本人已发布的向量能力</h4><p v-if="!ownReleases.length">暂无发布记录。</p><div v-for="item in ownReleases" :key="item.releaseId" class="pc-actions"><span>{{ item.model }} · {{ item.expectedDimensions }} 维 · 发布版本 {{ item.releaseVersion }}{{ item.available === false ? ' · 已失效，重新验证后可发布' : '' }}{{ item.defaultModel ? ' · 平台默认' : '' }}</span><el-button link type="danger" :disabled="busy || confirming || item.status === 'REVOKED'" @click="revoke(item)">撤销发布</el-button></div></div>
    </template>
    <el-alert v-if="validationError || error" :title="validationError || error" type="error" :closable="false" />
    <el-dialog v-model="publishOpen" title="发布内置向量能力" width="600px" class="pc-dialog pc-embedding-dialog">
      <p>{{ settings.embeddingConfig?.model }} · {{ settings.embeddingConfig?.expectedDimensions }} 维</p>
      <el-form label-width="130px" :disabled="busy"><el-form-item label="使用范围"><el-select v-model="audience"><el-option label="所有登录用户" value="AUTHENTICATED" /><el-option label="指定用户" value="USERS" /></el-select></el-form-item><el-form-item v-if="audience === 'USERS'" label="若依用户 ID"><el-input v-model="userIds" placeholder="用逗号分隔稳定用户 ID" /></el-form-item><el-form-item label="默认能力"><el-checkbox v-model="defaultModel">设为平台默认向量能力</el-checkbox></el-form-item><el-form-item label="共享确认"><el-checkbox v-model="confirmSharing">允许所选用户使用本人托管能力；用量按实际使用者记账</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="publishOpen=false">取消</el-button><el-button type="primary" :disabled="!confirmSharing || busy" @click="publish">确认发布</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { checkPermi, checkRole } from '@/utils/permission'
import { savePersonalSettings, selectPersonalEmbedding, verifyPersonalEmbedding } from '@/api/paicli/personal'
import { publishPlatformEmbedding, revokePlatformModel } from '@/api/paicli/connections'
import type { PcPersonalSettings, PcEmbeddingStatus, PcEmbeddingRelease, PcEmbeddingMode, PcTask } from '@/types/paicli'
import { operationKeys } from '../model'
import { useOperations } from '../useOperations'
const props=defineProps<{settings?:PcPersonalSettings;ownerUserId:string;capability?:PcEmbeddingStatus;releases:PcEmbeddingRelease[];advanced:boolean}>(),emit=defineEmits<{saved:[];task:[task:PcTask,title:string]}>(),{busy,error,run}=useOperations(),keys=operationKeys()
const mode=ref<PcEmbeddingMode>('AUTO'),releaseId=ref(''),validationError=ref(''),confirming=ref(false)
function mayManage(){return checkPermi(['paicli:connection:manage'])}
function maySelect(){return mode.value==='PERSONAL' ? mayManage() : checkPermi(['paicli:session:write'])}
function mayPublish(){return mayManage() && checkRole(['admin'])}
const canManage=computed(mayManage),canSelect=computed(maySelect),canPublish=computed(mayPublish),canEditSelection=computed(()=>checkPermi(['paicli:session:write']) || mayManage())
const statusText=computed(()=>({READY:'语义检索已就绪',NOT_CONFIGURED:'语义检索尚未准备',DISABLED:'语义检索已停用',UNVERIFIED:'向量能力待验证'}[props.capability?.status || 'NOT_CONFIGURED'])+(props.capability?.source==='PLATFORM'?' · 平台内置':props.capability?.source==='PERSONAL'?' · 个人配置':''))
const availableReleases=computed(()=>props.releases.filter(item=>item.available!==false))
const ownReleases=computed(()=>props.releases.filter(item=>item.sourceOwnerId===props.ownerUserId))
const fingerprint=computed(()=>props.settings?.embeddingConfig && props.capability?.source!=='PLATFORM' && props.capability?.configFingerprint ? props.capability.configFingerprint : props.settings?.embeddingVerification?.configFingerprint)
const canVerify=computed(()=>!!props.settings?.embeddingConfig && !!props.settings.embeddingConfigurationVersion && !!fingerprint.value)
const canPublishCurrent=computed(()=>canVerify.value && props.capability?.source==='PERSONAL' && props.capability.status==='READY')
const publishOpen=ref(false),audience=ref<'AUTHENTICATED'|'USERS'>('AUTHENTICATED'),userIds=ref(''),defaultModel=ref(false),confirmSharing=ref(false)
let generation=0,publishSnapshot=''
function identity(){return JSON.stringify({owner:props.ownerUserId,version:props.settings?.version,configurationVersion:props.settings?.embeddingConfigurationVersion,fingerprint:fingerprint.value})}
watch(()=>[props.ownerUserId,props.settings?.version],()=>{generation++;mode.value=props.settings?.embeddingMode || 'AUTO';releaseId.value=props.settings?.embeddingReleaseId || '';validationError.value='';publishOpen.value=false},{immediate:true})
onBeforeUnmount(()=>{generation++})
async function saveSelection(){if(!props.settings || !maySelect() || busy.value)return;const selected=mode.value;if(selected==='PERSONAL' && !mayManage())return;if(selected==='PLATFORM' && !availableReleases.value.some(item=>item.releaseId===releaseId.value)){validationError.value='请重新选择当前可用的内置向量模型';return};const version=props.settings.version,g=generation,owner=props.ownerUserId,data={embeddingMode:selected,embeddingReleaseId:selected==='PLATFORM'?releaseId.value:null};await run(async()=>{if(selected==='PERSONAL')await savePersonalSettings(data,version,keys.get({version,...data}));else await selectPersonalEmbedding(data as {embeddingMode:'AUTO'|'PLATFORM'|'DISABLED';embeddingReleaseId:string|null},version,keys.get({version,...data}));keys.done({version,...data});if(g===generation && owner===props.ownerUserId)emit('saved')})}
async function verify(){if(!props.settings || !mayManage() || !canVerify.value || busy.value || confirming.value)return;const frozen=identity(),g=generation,version=props.settings.version,configurationVersion=props.settings.embeddingConfigurationVersion!,configFingerprint=fingerprint.value!;confirming.value=true;try{try{await ElMessageBox.confirm(`验证 ${props.settings.embeddingConfig!.model} 的实际向量与用量，将发送一次短文本请求并按中心预算记账。`,'确认向量能力验证',{type:'warning',confirmButtonText:'确认并发起',cancelButtonText:'取消'})}catch{return};if(frozen!==identity() || g!==generation || !mayManage()){validationError.value='本人身份、权限或配置版本已变化，请重新确认验证';return};await run(async()=>{const task=(await verifyPersonalEmbedding(version,configurationVersion,configFingerprint,keys.get({version,configurationVersion,configFingerprint}))).data;keys.done({version,configurationVersion,configFingerprint});if(g!==generation || frozen!==identity())return;emit('task',task,'个人向量能力验证')})}finally{confirming.value=false}}
function publishingIdentity(){const old=ownReleases.value[0];return JSON.stringify({identity:identity(),releaseId:old?.releaseId,releaseVersion:old?.version ?? old?.releaseVersion})}
function openPublish(){if(!mayPublish() || !canPublishCurrent.value)return;publishSnapshot=publishingIdentity();confirmSharing.value=false;publishOpen.value=true}
async function publish(){if(!props.settings || !mayPublish() || !confirmSharing.value || busy.value)return;if(publishSnapshot!==publishingIdentity() || !canPublishCurrent.value){validationError.value='待发布配置或权限已变化，请刷新后重新发布';return};const users=userIds.value.split(/[,，\s]+/).filter(Boolean);if(audience.value==='USERS' && (!users.length || users.some(id=>!/^\d{1,18}$/.test(id)))){validationError.value='请填写稳定的若依用户 ID';return};const old=ownReleases.value[0],g=generation,data={kind:'EMBEDDING' as const,configurationVersion:props.settings.embeddingConfigurationVersion!,configFingerprint:fingerprint.value!,expectedDimensions:props.settings.embeddingConfig!.expectedDimensions!,confirmSharing:true as const,audience:audience.value,userIds:users,defaultModel:defaultModel.value,releaseVersion:old?.version ?? old?.releaseVersion};await run(async()=>{await publishPlatformEmbedding(data,keys.get(data));keys.done(data);if(g===generation){publishOpen.value=false;emit('saved')}})}
async function revoke(item:PcEmbeddingRelease){if(!mayPublish() || item.sourceOwnerId!==props.ownerUserId || busy.value || confirming.value)return;const frozen=identity(),g=generation,version=item.version ?? item.releaseVersion;confirming.value=true;try{try{await ElMessageBox.confirm(`撤销 ${item.model} 后受众用户不能继续调用此向量发布，已有文件和索引保留。`,'撤销内置向量发布',{type:'warning'})}catch{return};const current=ownReleases.value.find(value=>value.releaseId===item.releaseId);if(g!==generation || frozen!==identity() || !mayPublish() || (current?.version ?? current?.releaseVersion)!==version){validationError.value='本人身份、权限或发布版本已变化，请刷新后重试';return};await run(async()=>{const intent={id:item.releaseId,version};await revokePlatformModel(item.releaseId,version,keys.get(intent));keys.done(intent);if(g===generation)emit('saved')})}finally{confirming.value=false}}
</script>
<style>
.pc-embedding-dialog .el-checkbox { max-width:100%; height:auto; align-items:flex-start; white-space:normal; }
.pc-embedding-dialog .el-checkbox__label { white-space:normal; overflow-wrap:anywhere; line-height:1.5; }
.pc-embedding-dialog .el-checkbox__input { margin-top:3px; flex-shrink:0; }
</style>
