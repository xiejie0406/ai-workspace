<template>
  <el-drawer :model-value="modelValue" title="人工批准与影响核对" size="650px" @update:model-value="$emit('update:modelValue', $event)">
    <el-empty v-if="!approval" description="未选择批准申请" />
    <template v-else>
      <el-alert v-if="reason" :title="reason" :type="approval.hardDenied ? 'error' : 'warning'" :closable="false" />
      <el-descriptions :column="1" border><el-descriptions-item label="完整工具名">{{ approval.toolName }}</el-descriptions-item><el-descriptions-item label="发起人">{{ approval.requesterName }}</el-descriptions-item><el-descriptions-item label="工作区 / Runner">{{ approval.workspaceId }} / {{ approval.runnerId }}</el-descriptions-item><el-descriptions-item label="根目录指纹">{{ approval.rootFingerprint }}</el-descriptions-item><el-descriptions-item label="输入哈希">{{ approval.inputHash }}</el-descriptions-item><el-descriptions-item label="计划 / 策略 / 申请版本">{{ approval.planVersion ?? '无计划' }} / {{ approval.policyVersion }} / {{ approval.version }}</el-descriptions-item><el-descriptions-item label="有效期">{{ displayTime(approval.expiresAt) }}</el-descriptions-item></el-descriptions>
      <h3>完整参数（控制字符转义显示）</h3><pre class="pc-pre">{{ safeText(approval.arguments) }}</pre>
      <h3>作用路径与影响</h3><pre class="pc-pre">{{ approval.paths.join('\n') || '未声明文件路径' }}
{{ safeText(approval.impact) }}</pre><pre v-if="approval.diff" class="pc-pre">{{ safeText(approval.diff) }}</pre>
      <ApprovalSnapshotPreview v-if="approval.snapshotContext || ['revert_turn','SNAPSHOT_RESTORE'].includes(approval.toolName)" :approval="approval" />
      <label class="pc-label" for="pc-approval-feedback">拒绝反馈 / 修改理由</label><el-input id="pc-approval-feedback" v-model="feedback" type="textarea" :rows="3" :disabled="busy" />
      <el-checkbox v-model="editing" :disabled="!!reason || busy">修改参数后重新审查</el-checkbox><el-input v-if="editing" v-model="revised" type="textarea" :rows="7" aria-label="修改后的 JSON 参数" />
      <el-alert v-if="error || jsonError" :title="error || jsonError" type="error" :closable="false" />
      <div class="pc-actions"><el-button v-if="!approval.hardDenied" v-hasPermi="['paicli:approval:decide']" type="primary" :disabled="!!reason || editing || !!snapshotProblem" :loading="busy" @click="decide('APPROVE')">批准本次</el-button><el-button v-hasPermi="['paicli:approval:decide']" type="danger" :disabled="!!reason || !feedback.trim()" :loading="busy" @click="decide('REJECT')">拒绝并反馈</el-button><el-button v-if="editing" v-hasPermi="['paicli:approval:decide']" :disabled="!!reason || !feedback.trim()" :loading="busy" @click="decide('REVISE')">修改后重新审查</el-button><el-button v-hasPermi="['paicli:approval:decide']" :disabled="!!reason" :loading="busy" @click="decide('SKIP')">跳过本次</el-button></div>
      <p class="pc-muted">决定仅绑定本次参数与版本；硬策略拒绝不能人工放开。过期或文件改变须重新申请。</p>
    </template>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type { PcApproval } from '@/types/paicli'
import { decideApproval } from '@/api/paicli/sessions'
import { checkPermi } from '@/utils/permission'
import { approvalReason, displayTime, operationKeys, safeText } from '../model'
import { useOperations } from '../useOperations'
import ApprovalSnapshotPreview from './ApprovalSnapshotPreview.vue'
import {approvalSnapshotProblem} from '../shared/approvalSnapshot'
const props = defineProps<{ modelValue: boolean; approval?: PcApproval }>(), emit = defineEmits<{ 'update:modelValue': [value: boolean]; decided: [] }>()
const feedback = ref(''), revised = ref(''), editing = ref(false), jsonError = ref(''), now = ref(Date.now()), keys = operationKeys(), { busy, error, run } = useOperations()
const reason = computed(() => props.approval ? approvalReason(props.approval, now.value) : '未选择申请'), snapshotProblem = computed(() => props.approval ? approvalSnapshotProblem(props.approval) : '')
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) }); onBeforeUnmount(() => clearInterval(timer))
watch(() => props.approval?.id, () => { feedback.value = ''; editing.value = false; jsonError.value = ''; error.value = ''; revised.value = JSON.stringify(props.approval?.arguments, null, 2) ?? '' })
async function decide(decision: 'APPROVE' | 'REJECT' | 'REVISE' | 'SKIP') {
  const approval = props.approval; if (!approval || reason.value || !checkPermi(['paicli:approval:decide']) || (['APPROVE','REVISE'].includes(decision) && snapshotProblem.value)) return
  jsonError.value = ''; let revisedArguments: unknown
  if (decision === 'REVISE') { try { revisedArguments = JSON.parse(revised.value) } catch { jsonError.value = '修改后的参数不是有效 JSON，未提交。'; return } }
  const data = { decision, version: approval.version, inputHash: approval.inputHash, feedback: feedback.value, ...(decision === 'REVISE' ? { revisedArguments } : {}) }, intent = { id: approval.id, ...data }
  await run(async () => { await decideApproval(approval.id, data, keys.get(intent)); keys.done(intent); emit('decided'); emit('update:modelValue', false) })
}
</script>
