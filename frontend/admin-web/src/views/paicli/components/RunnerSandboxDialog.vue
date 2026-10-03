<template>
  <el-dialog :model-value="modelValue" title="登记 Runner 隔离核验" width="650px" class="pc-dialog" @update:model-value="emit('update:modelValue',$event)">
    <template v-if="runner">
      <p>{{ runner.name }} · {{ runner.platform || '平台未上报' }} · 当前版本 {{ runner.version }}</p>
      <el-alert :title="supported ? (form.verified ? '登记真实核验回执，不能代替目标 Runner 本机 Required 隔离检测。证明须在 5 分钟内，活跃执行期间不可修改。' : '停用命令只需填写原因；活跃执行期间不可修改隔离状态。') : '此设备平台没有可用的 seatbelt / bubblewrap 隔离核验；命令执行保持不可用，不能登记为已验证。'" type="warning" :closable="false" />
      <p v-if="runner.sandboxAttestation" class="pc-muted">已登记：{{ runner.sandboxAttestation.provider }} · {{ displayTime(runner.sandboxAttestation.checkedAt) }} · {{ runner.sandboxAttestation.sha256 }}</p>
      <el-form v-if="supported" label-width="130px">
        <el-form-item label="核验结果"><el-select v-model="form.verified"><el-option :value="false" label="未验证 / 停用命令" /><el-option :value="true" label="真实核验通过" /></el-select></el-form-item>
        <el-form-item v-if="form.verified" label="实际隔离引擎"><el-select v-model="form.provider"><el-option value="bubblewrap" label="bubblewrap（Linux）" /><el-option value="seatbelt" label="seatbelt（macOS）" /></el-select></el-form-item>
        <el-form-item v-if="form.verified" label="回执 SHA-256"><el-input v-model="form.sha256" placeholder="填写目标设备实际核验报告摘要" /></el-form-item>
        <el-form-item v-if="form.verified" label="实际核验时间"><el-input v-model="form.checkedAt" placeholder="报告中的 ISO UTC 时间，例如 2026-10-03T00:00:00Z" /></el-form-item>
        <el-form-item label="核对依据 / 原因"><el-input v-model="form.reason" type="textarea" :rows="4" placeholder="填写真实核验依据与批准原因；不输入凭证" /></el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
    </template>
    <template #footer><el-button @click="emit('update:modelValue',false)">关闭</el-button><el-button v-if="supported" v-hasPermi="['paicli:runner:manage']" type="primary" :loading="busy" :disabled="!valid || runner?.status === 'REVOKED'" @click="save">按当前设备版本登记</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { PcRunner } from '@/types/paicli'
import { recordRunnerSandbox, type PcSandboxInput } from '@/api/paicli/workspaces'
import { displayTime, operationKeys } from '../model'
import { useOperations } from '../useOperations'
const props = defineProps<{ modelValue: boolean; workspaceId: string; runner?: PcRunner }>(), emit = defineEmits<{ 'update:modelValue': [value: boolean]; refresh: [] }>()
const { busy,error,run } = useOperations(), keys = operationKeys(), form = reactive({ verified:false,provider:'bubblewrap' as 'seatbelt'|'bubblewrap',sha256:'',checkedAt:'',reason:'' })
const supported = computed(() => !!props.runner && /linux|darwin|mac/i.test(props.runner.platform ?? '') && !/windows|win32/i.test(props.runner.platform ?? ''))
const valid = computed(() => supported.value && !!form.reason.trim() && (!form.verified || (/^[a-f0-9]{64}$/.test(form.sha256) && Number.isFinite(Date.parse(form.checkedAt)) && form.checkedAt.endsWith('Z'))))
watch(() => `${props.runner?.id ?? ''}:${props.runner?.version ?? ''}:${props.modelValue}`, () => { form.verified = false; form.provider = /darwin|mac/i.test(props.runner?.platform ?? '') ? 'seatbelt':'bubblewrap'; form.sha256 = ''; form.checkedAt = ''; form.reason = ''; error.value = '' },{ immediate:true })
async function save() { const runner = props.runner; if (!runner || !supported.value || !valid.value || runner.status === 'REVOKED' || runner.workspaceId !== props.workspaceId) return; const data: PcSandboxInput = form.verified ? { verified:true,attestation:{ provider:form.provider,sha256:form.sha256,checkedAt:form.checkedAt },reason:form.reason } : { verified:false,reason:form.reason },intent = { id:runner.id,version:runner.version,...data }; await run(async () => { await recordRunnerSandbox(runner.id,runner.version,data,keys.get(intent)); keys.done(intent); if (props.runner?.id === runner.id && props.workspaceId === runner.workspaceId) { emit('update:modelValue',false); emit('refresh') } }) }
</script>
