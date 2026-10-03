<template>
  <section class="pc-panel">
    <h3>当前通道的消息预算与允许名单</h3>
    <el-descriptions :column="1" border><el-descriptions-item label="实际模型 ID">{{channel.modelId || '按工作区已登记默认供应商与默认模型解析，未设则拒绝启动'}}</el-descriptions-item><el-descriptions-item label="今日轮次">{{channel.dailyTurnUsed ?? '未上报'}} / {{channel.policy?.dailyTurnLimit ?? '未上报'}}</el-descriptions-item><el-descriptions-item label="单轮 Token">{{channel.policy?.singleTurnTokenBudget ?? '未上报'}}</el-descriptions-item><el-descriptions-item label="命令允许名单"><pre class="pc-pre">{{safeText(channel.policy?.commandAllowlist ?? '未上报')}}</pre></el-descriptions-item><el-descriptions-item label="MCP 允许名单">{{channel.policy?.mcpAllowlist?.join('、') || '未允许任何 MCP'}}</el-descriptions-item></el-descriptions>
    <el-button v-hasPermi="['paicli:connection:manage']" @click="edit">修改当前通道策略</el-button>
    <el-dialog v-model="open" title="微信文本通道策略" width="750px" class="pc-dialog"><el-form label-width="150px"><el-form-item label="每日轮次上限"><el-input-number v-model="form.dailyTurnLimit" :min="1" :max="10000" /></el-form-item><el-form-item label="单轮 Token 预算"><el-input-number v-model="form.singleTurnTokenBudget" :min="1" :max="1000000" /></el-form-item><el-form-item label="命令允许名单"><el-input v-model="commands" type="textarea" :rows="6" placeholder="每行一条明确允许的命令；留空默认不允许" /></el-form-item><el-form-item label="MCP 服务器名单"><el-select v-model="form.mcpAllowlist" multiple class="pc-wide"><el-option v-for="name in serverNames" :key="name" :label="name" :value="name" /></el-select></el-form-item></el-form><el-alert title="这里只登记通道限制，不能绕过全局硬策略、工作区成员权限或人工批准。活跃任务结束后中心才允许改变配置版本。" type="warning" :closable="false" /><el-alert v-if="error || localError" :title="error || localError" type="error" :closable="false" /><template #footer><el-button @click="open=false">关闭</el-button><el-button v-hasPermi="['paicli:connection:manage']" type="primary" :loading="busy" @click="save">按当前版本保存策略</el-button></template></el-dialog>
  </section>
</template>
<script setup lang="ts">
import {computed,reactive,ref,watch} from 'vue'
import {checkPermi} from '@/utils/permission'
import {saveWechatPolicy} from '@/api/paicli/connections'
import type {PcWechat,PcWechatPolicy} from '@/types/paicli'
import {errorText,operationKeys,safeText} from '../model'
import {wechatCommandLines} from '../shared/registrationInputs'
import {useOperations} from '../useOperations'
const props=defineProps<{channel:PcWechat;mcpNames:string[]}>(),emit=defineEmits<{saved:[]}>(),{busy,error,run}=useOperations(),keys=operationKeys(),open=ref(false),localError=ref(''),commands=ref(''),form=reactive<PcWechatPolicy>({dailyTurnLimit:50,singleTurnTokenBudget:64000,commandAllowlist:[],mcpAllowlist:[]})
const serverNames=computed(()=>[...new Set([...props.mcpNames,...form.mcpAllowlist])])
function edit(){if(!checkPermi(['paicli:connection:manage']))return;Object.assign(form,props.channel.policy || {dailyTurnLimit:50,singleTurnTokenBudget:64000,commandAllowlist:[],mcpAllowlist:[]});form.mcpAllowlist=[...form.mcpAllowlist];commands.value=form.commandAllowlist.join('\n');localError.value='';error.value='';open.value=true}
async function save(){if(!checkPermi(['paicli:connection:manage']))return;try{const data={dailyTurnLimit:form.dailyTurnLimit,singleTurnTokenBudget:form.singleTurnTokenBudget,commandAllowlist:wechatCommandLines(commands.value),mcpAllowlist:[...form.mcpAllowlist]},channel=props.channel,intent={id:channel.id,version:channel.version,...data};localError.value='';await run(async()=>{await saveWechatPolicy(channel.id,channel.version,data,keys.get(intent));keys.done(intent);open.value=false;emit('saved')})}catch(cause){localError.value=errorText(cause)}}
watch(()=>props.channel.id,()=>{open.value=false})
</script>
