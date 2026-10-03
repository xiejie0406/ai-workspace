<template>
  <section class="pc-panel" aria-label="Runner 登记与撤销">
    <h2>工作区与 Runner</h2><p class="pc-muted">独立 Runner 执行本机工具；管理端服务不执行本机 shell。登记目录指纹不符或未验证隔离时拒绝命令。</p>
    <el-table :data="runners"><el-table-column prop="name" label="设备" /><el-table-column label="状态"><template #default="{ row }">{{ stateName(row.status) }}<p class="pc-muted">{{ row.platform }} · {{ row.distributionVersion }}</p></template></el-table-column><el-table-column label="隔离 / 能力"><template #default="{ row }">{{ row.sandboxVerified && /linux|darwin|mac/i.test(row.platform || '') && !/windows|win32/i.test(row.platform || '') ? '已验证隔离' : '命令执行不可用' }}<p class="pc-muted">{{ row.capabilities.join('、') }}</p></template></el-table-column><el-table-column label="最后心跳"><template #default="{ row }">{{ displayTime(row.lastHeartbeatAt) }}</template></el-table-column><el-table-column label="操作"><template #default="{ row }"><el-button v-hasPermi="['paicli:runner:manage']" link :disabled="row.status === 'REVOKED' || busy" @click="selectedRunner = row; sandboxOpen = true">隔离核验</el-button><el-button v-hasPermi="['paicli:runner:manage']" link type="danger" :disabled="row.status === 'REVOKED' || busy" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table>
    <RunnerSandboxDialog v-model="sandboxOpen" :workspace-id="workspaceId" :runner="selectedRunner" @refresh="emit('refresh')" />
    <el-empty v-if="!runners.length" description="当前工作区没有已登记设备" />
    <el-button v-hasPermi="['paicli:runner:manage']" type="primary" :disabled="!workspaceId" @click="enrollOpen = true">登记 Runner</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-dialog v-model="enrollOpen" title="登记当前工作区设备" width="650px" class="pc-dialog" @closed="clearCode">
      <el-form label-width="120px"><el-form-item label="设备名称"><el-input v-model="name" :disabled="!!enrollment" /></el-form-item><el-form-item label="登记根指纹"><el-input v-model="fingerprint" :disabled="!!enrollment" placeholder="在目标 Runner 查询并核对根目录 SHA-256 指纹" /></el-form-item></el-form>
      <el-alert title="绑定码限定当前工作区、单次有效。请只交给所核对的目标 Runner；页面关闭后不保存到浏览器。" type="warning" :closable="false" />
      <template v-if="enrollment"><p>有效期：{{ displayTime(enrollment.expiresAt) }}</p><pre class="pc-pre">{{ safeText(enrollment.command) }}</pre><label class="pc-label" for="pc-enrollment-code">一次性绑定码</label><el-input id="pc-enrollment-code" :model-value="enrollment.code" type="password" show-password readonly autocomplete="off" /></template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" /><template #footer><el-button @click="enrollOpen = false">关闭</el-button><el-button v-if="!enrollment" :loading="busy" :disabled="!name.trim() || !/^[0-9a-f]{64}$/i.test(fingerprint)" type="primary" @click="enroll">生成一次性绑定码</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import RunnerSandboxDialog from './RunnerSandboxDialog.vue'
import { ElMessageBox } from 'element-plus'
import type { PcRunner, PcEnrollment } from '@/types/paicli'
import { enrollRunner, revokeRunner } from '@/api/paicli/workspaces'
import { displayTime, operationKeys, safeText, stateName } from '../model'
import { useOperations } from '../useOperations'
const props = defineProps<{ workspaceId: string; runners: PcRunner[] }>(), emit = defineEmits<{ refresh: [] }>()
const sandboxOpen = ref(false), selectedRunner = ref<PcRunner>()
const enrollOpen = ref(false), name = ref(''), fingerprint = ref(''), enrollment = ref<PcEnrollment>(), { busy, error, run } = useOperations(), keys = operationKeys()
function clearCode() { enrollment.value = undefined; name.value = ''; fingerprint.value = ''; error.value = '' }
watch(() => props.workspaceId, () => { enrollOpen.value = false; sandboxOpen.value = false; selectedRunner.value = undefined; clearCode() })
async function enroll() { const data = { workspaceId: props.workspaceId, name: name.value, rootFingerprint: fingerprint.value }; await run(async () => { const response = await enrollRunner(data, keys.get(data)); keys.done(data); if (data.workspaceId !== props.workspaceId || !enrollOpen.value) return; enrollment.value = response.data; emit('refresh') }) }
async function revoke(runner: PcRunner) { try { await ElMessageBox.confirm(`撤销 ${runner.name} 后停止新执行，已发生操作需继续核对。确认撤销？`, '撤销设备', { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }) } catch { return }; const intent = { action: 'revoke', id: runner.id, version: runner.version }; await run(async () => { await revokeRunner(runner.id, runner.version, keys.get(intent)); keys.done(intent); emit('refresh') }) }
</script>
