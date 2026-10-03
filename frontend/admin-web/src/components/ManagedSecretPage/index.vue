<template>
  <div class="app-container">
    <el-alert :title="kind === 'AI' ? 'AI 密钥只供服务端 Provider 调用；AI 角色只引用用途标识。代码接入不代表目标环境已验证。' : '平台密钥与 AI 密钥独立授权；MySQL 最低连接凭据和密文根密钥属于自举配置。代码接入不代表目标环境已验证。'" type="info" :closable="false" show-icon class="mb20" />
    <el-form :inline="true" @submit.prevent="load">
      <el-form-item label="项目"><el-input v-model="project" clearable placeholder="按项目筛选" /></el-form-item>
      <el-form-item><el-button type="primary" @click="load">查询</el-button><el-button @click="project='';load()">重置</el-button></el-form-item>
    </el-form>
    <div class="mb8"><el-button type="primary" plain v-hasPermi="[writePerm]" @click="openCreate">新增{{ title }}</el-button></div>
    <el-table v-loading="loading" :data="rows" empty-text="暂无已登记密钥">
      <el-table-column prop="projectCode" label="项目" min-width="110" />
      <el-table-column prop="displayName" label="用途" min-width="160" />
      <el-table-column prop="alias" label="固定别名" min-width="220" />
      <el-table-column v-if="kind === 'AI'" prop="providerCode" label="供应商" min-width="110" />
      <el-table-column prop="authMode" label="鉴权模式" min-width="110" />
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '已启用' : '已停用' }}</el-tag></template></el-table-column>
      <el-table-column label="代码接入" min-width="150"><template #default="{ row }"><el-tag :type="knownConsumer(row) ? 'success' : 'warning'">{{ knownConsumer(row) ? '已替换消费者' : '未登记消费者' }}</el-tag></template></el-table-column>
      <el-table-column prop="activeVersion" label="版本" width="80" />
      <el-table-column prop="updatedAt" label="最近变更" min-width="165" />
      <el-table-column label="操作" min-width="300"><template #default="{ row }">
        <el-button v-if="!isReservedPrevious(row.alias)" link type="primary" v-hasPermi="[writePerm]" @click="openRotate(row)">轮换</el-button>
        <el-button v-if="canStagePrevious(row)" link type="primary" v-hasPermi="[writePerm]" @click="stagePrevious(row)">保留上一版</el-button>
        <el-button link type="danger" :disabled="row.status !== 'ACTIVE' || row.alias === 'platform.ruoyi.jwt'" v-hasPermi="[activatePerm]" @click="disable(row)">停用</el-button>
        <el-button link type="primary" @click="showVersions(row)">版本</el-button>
        <el-button link type="primary" v-hasPermi="[auditPerm]" @click="showAudit(row)">记录</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="editing ? '轮换' + title : '新增' + title" width="620px" destroy-on-close @closed="clearForm">
      <el-form :model="form" label-width="100px" @submit.prevent="save">
        <el-form-item label="固定别名" required><el-input v-model="form.alias" :disabled="editing" autocomplete="off" /></el-form-item>
        <el-form-item label="项目" required><el-input v-model="form.project" :disabled="editing" autocomplete="off" /></el-form-item>
        <el-form-item v-if="kind === 'AI'" label="供应商" required><el-input v-model="form.provider" autocomplete="off" /></el-form-item>
        <el-form-item label="能力"><el-input v-model="form.capability" :placeholder="kind === 'AI' ? '对话填 chat，语音填 speech' : ''" autocomplete="off" /></el-form-item>
        <el-form-item label="鉴权模式"><el-input v-model="form.authMode" placeholder="如 api-key / access-token" autocomplete="off" /></el-form-item>
        <el-form-item label="显示名称" required><el-input v-model="form.name" autocomplete="off" /></el-form-item>
        <template v-if="pairMode">
          <el-form-item label="Key ID" required><el-input v-model="form.keyId" autocomplete="off" /></el-form-item>
          <el-form-item label="Base64 密钥" required><el-input v-model="form.keyBase64" type="password" show-password autocomplete="new-password" /></el-form-item>
        </template>
        <template v-else-if="form.alias === 'ai.interview.volcengine.speech' && form.authMode === 'access-token'">
          <el-form-item label="App ID" required><el-input v-model="form.appId" autocomplete="off" /></el-form-item>
          <el-form-item label="Access Token" required><el-input v-model="form.accessToken" type="password" show-password autocomplete="new-password" /></el-form-item>
        </template>
        <el-form-item v-else label="新密钥" required><el-input v-model="form.value" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="变更原因" required><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存新版本</el-button></template>
    </el-dialog>
    <el-dialog v-model="auditDialog" title="密钥操作记录（不含密钥值）" width="760px"><el-table :data="audits"><el-table-column prop="occurredAt" label="时间" min-width="165" /><el-table-column prop="action" label="动作" width="100" /><el-table-column prop="version" label="版本" width="80" /><el-table-column prop="operator" label="操作人" width="100" /><el-table-column prop="reason" label="原因" min-width="180" /></el-table></el-dialog>
    <el-dialog v-model="versionDialog" title="历史版本（不含密钥值）" width="900px"><el-table :data="versions"><el-table-column prop="version" label="版本" width="70" /><el-table-column prop="provider" label="供应商" width="110" /><el-table-column prop="authMode" label="鉴权模式" width="120" /><el-table-column prop="createdAt" label="创建时间" min-width="160" /><el-table-column prop="createdBy" label="操作人" width="95" /><el-table-column prop="reason" label="原因" min-width="160" /><el-table-column label="操作" width="125"><template #default="{ row }"><el-tag v-if="row.active" type="success">当前生效</el-tag><el-button v-else-if="!isReservedPrevious(versionTarget?.alias)" link type="primary" v-hasPermi="[writePerm]" @click="restore(row)">恢复为新版本</el-button></template></el-table-column></el-table></el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { disableManagedSecret, listManagedSecrets, listManagedSecretVersions, managedSecretAudit, restoreManagedSecretVersion, saveManagedSecret, stagePreviousPlatformSecret } from '@/api/system/managedSecret'

const props = defineProps({ kind: { type: String, required: true } })
// 只表达代码路径已切换；真实环境验证以对应 Feature 的验证记录为准。
const knownAliases = new Set([
  'platform.ruoyi.jwt', 'platform.ruoyi.jwt.previous', 'platform.ruoyi.redis',
  'platform.ruoyi.druid-console', 'platform.ruoyi.mysql.slave',
  'platform.interview.db', 'platform.aps.db',
  'platform.interview.envelope.active', 'platform.interview.envelope.previous',
  'platform.fashion.service.active', 'platform.fashion.service.previous',
  'platform.fashion.contact.active', 'platform.fashion.contact.previous',
  'platform.aden.runner-pepper', 'platform.aden.runner-pepper.previous',
  'ai.interview.deepseek', 'ai.interview.volcengine.speech'
])
function knownConsumer(row) {
  if (knownAliases.has(row.alias)) return true
  if (row.projectCode !== 'paicli') return false
  if (props.kind === 'AI') return /^ai\.paicli\.[1-9][0-9]{0,18}\.[A-Za-z0-9._-]+$/.test(row.alias)
  return row.alias === 'platform.paicli.control-key' || /^platform\.paicli\.wechat\.[1-9][0-9]{0,18}$/.test(row.alias)
}
const reservedPrevious = new Set(['platform.ruoyi.jwt.previous', 'platform.fashion.service.previous', 'platform.fashion.contact.previous', 'platform.interview.envelope.previous', 'platform.aden.runner-pepper.previous'])
function isReservedPrevious(alias) { return reservedPrevious.has(alias) }
const title = computed(() => props.kind === 'AI' ? 'AI 密钥' : '平台密钥')
const pairMode = computed(() => props.kind === 'PLATFORM' && /^(platform\.fashion\.(service|contact)\.|platform\.interview\.envelope\.|platform\.aden\.runner-pepper)/.test(form.alias))
const writePerm = computed(() => props.kind === 'AI' ? 'ai:secret:write' : 'system:platformSecret:write')
const activatePerm = computed(() => props.kind === 'AI' ? 'ai:secret:activate' : 'system:platformSecret:activate')
const auditPerm = computed(() => props.kind === 'AI' ? 'ai:secret:audit' : 'system:platformSecret:audit')
const rows = ref([]), audits = ref([]), project = ref(''), loading = ref(false), saving = ref(false)
const dialog = ref(false), auditDialog = ref(false), editing = ref(false)
const versionDialog = ref(false), versions = ref([]), versionTarget = ref(null)
const blank = () => ({ alias: '', project: '', provider: '', capability: '', authMode: '', name: '', value: '', keyId: '', keyBase64: '', appId: '', accessToken: '', reason: '', expectedRowVersion: null })
const form = reactive(blank())
function clearForm() { Object.assign(form, blank()) }
function openCreate() { clearForm(); editing.value = false; dialog.value = true }
function openRotate(row) { clearForm(); Object.assign(form, { alias: row.alias, project: row.projectCode, provider: row.providerCode, capability: row.capabilityCode, authMode: row.authMode, name: row.displayName, expectedRowVersion: row.rowVersion }); editing.value = true; dialog.value = true }
async function load() { loading.value = true; try { const result = await listManagedSecrets(props.kind, project.value); rows.value = result.data || [] } finally { loading.value = false } }
async function save() {
  if (isReservedPrevious(form.alias)) { ElMessage.warning('上一版用途只能通过“保留上一版”操作写入'); return }
  const tokenPair = form.alias === 'ai.interview.volcengine.speech' && form.authMode === 'access-token'
  const value = pairMode.value ? JSON.stringify({ keyId: form.keyId.trim(), keyBase64: form.keyBase64 })
    : tokenPair ? JSON.stringify({ appId: form.appId.trim(), accessToken: form.accessToken }) : form.value
  if (!form.alias || !form.project || !form.name || !form.reason || (props.kind === 'AI' && !form.provider)
      || (pairMode.value ? (!form.keyId.trim() || !form.keyBase64)
        : tokenPair ? (!form.appId.trim() || !form.accessToken) : !form.value)) { ElMessage.warning('请填写所有必填项'); return }
  saving.value = true
  try { await saveManagedSecret(props.kind, { alias: form.alias, project: form.project, provider: form.provider, capability: form.capability, authMode: form.authMode, name: form.name, value, reason: form.reason, expectedRowVersion: form.expectedRowVersion }); ElMessage.success('已保存新版本'); dialog.value = false; clearForm(); await load() }
  finally { saving.value = false }
}
async function disable(row) {
  const { value } = await ElMessageBox.prompt(`停用 ${row.displayName} 后，服务端读取该用途会失败。请输入原因。`, '确认停用', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空', type: 'warning' })
  await disableManagedSecret(props.kind, row.alias, value, row.rowVersion); ElMessage.success('已停用'); await load()
}
function canStagePrevious(row) { return props.kind === 'PLATFORM' && row.status === 'ACTIVE' && [
  'platform.ruoyi.jwt', 'platform.fashion.service.active', 'platform.fashion.contact.active',
  'platform.interview.envelope.active', 'platform.aden.runner-pepper'
].includes(row.alias) }
async function stagePrevious(row) {
  await ElMessageBox.confirm(`将 ${row.displayName} 当前版本复制到固定 previous 用途，用于轮换后验证旧数据或旧会话。`, '保留上一版', { type: 'warning' })
  await stagePreviousPlatformSecret(row.alias, row.rowVersion)
  ElMessage.success('已保留上一版'); await load()
}
async function showAudit(row) { const result = await managedSecretAudit(props.kind, row.alias); audits.value = result.data || []; auditDialog.value = true }
async function showVersions(row) { versionTarget.value = row; const result = await listManagedSecretVersions(props.kind, row.alias); versions.value = result.data || []; versionDialog.value = true }
async function restore(source) {
  if (!versionTarget.value || source.active) return
  const { value: reason } = await ElMessageBox.prompt(`把 V${source.version} 的值复制为新的生效版本。请输入恢复原因。`, '确认恢复历史版本', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空', type: 'warning' })
  await restoreManagedSecretVersion(props.kind, versionTarget.value.alias, source.version, versionTarget.value.rowVersion, reason)
  ElMessage.success('已创建并启用新版本'); versionDialog.value = false; await load()
}
onMounted(load)
</script>
