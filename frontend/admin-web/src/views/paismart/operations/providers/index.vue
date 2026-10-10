<template>
  <div class="app-container ps-page">
    <h2>模型供应商</h2>
    <el-alert title="密钥只使用平台托管引用。Sub2API 仅接入对话文字；向量、重排及索引保持原配置。" type="info" :closable="false" class="ps-section" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="ps-toolbar">
      <el-button v-hasPermi="['paismart:provider:edit']" type="primary" @click="create">新增供应商</el-button>
      <el-button :loading="loading" @click="load">刷新配置</el-button>
      <el-button v-hasPermi="['paismart:provider:edit']" @click="editScope('LLM')">配置对话模型</el-button>
      <el-button v-hasPermi="['paismart:provider:edit']" @click="editScope('EMBEDDING')">配置向量模型</el-button>
      <el-button v-hasPermi="['paismart:provider:edit']" @click="editScope('RERANK')">配置重排模型</el-button>
    </div>
    <el-table :data="providers" v-loading="loading" class="provider-table" empty-text="暂无供应商配置，请新增供应商或配置所需模型。">
      <el-table-column type="expand"><template #default="{ row }"><el-descriptions :column="1" border class="provider-details">
        <el-descriptions-item label="API 根地址">{{ row.address || '未配置' }}</el-descriptions-item>
        <el-descriptions-item v-if="row.scope === 'EMBEDDING'" label="向量维度">{{ row.dimension || '未配置' }}</el-descriptions-item>
        <el-descriptions-item v-if="isSub2Api(row)" label="输出 Token 上限">{{ row.config?.maxOutputTokens ?? '未配置' }}</el-descriptions-item>
      </el-descriptions></template></el-table-column>
      <el-table-column label="用途 / 供应商" min-width="180"><template #default="{ row }"><div class="ps-muted">{{ scopeLabel(row.scope) }}</div><strong>{{ isSub2Api(row) ? 'Sub2API' : row.provider || '未配置' }}</strong></template></el-table-column>
      <el-table-column label="模型 / 调用 API" min-width="230"><template #default="{ row }"><div>{{ row.model || '未配置' }}</div><div v-if="isSub2Api(row)" class="ps-muted">{{ protocolLabel(row.config?.apiProtocol) }}</div></template></el-table-column>
      <el-table-column label="配置状态" min-width="170"><template #default="{ row }">
        <div class="provider-status"><el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '当前激活' : row.enabled ? '已启用 · 未激活' : '未启用 · 未激活' }}</el-tag><span>{{ row.configured ? '已配置' : '配置缺失' }}</span></div>
        <template v-if="isSub2Api(row)"><el-tag :type="row.compatibilityValid ? 'success' : row.config?.compatibility?.state === 'FAILED' ? 'danger' : 'warning'" size="small">{{ providerCompatibilityLabel(row) }}</el-tag><div v-if="row.activationBlockedReason" class="ps-muted">{{ row.activationBlockedReason }}</div></template>
      </template></el-table-column>
      <el-table-column label="操作" width="220"><template #default="{ row }">
        <el-button v-hasPermi="['paismart:provider:edit']" link @click="edit(row)">编辑</el-button>
        <el-button v-hasPermi="['paismart:provider:test']" link :loading="testing === String(row.id)" :disabled="!!testing || (isSub2Api(row) && !row.enabled)" @click="test(row)">{{ isSub2Api(row) ? '验证文字能力' : '连接测试' }}</el-button>
      </template></el-table-column>
    </el-table>
    <div class="provider-scopes ps-section">
      <section class="scope-note"><h3>文字迁移范围</h3><p>问答、摘要、函数工具回合；已启用的上下文增强、引用核验、冲突辅助和评测文字调用。</p></section>
      <section class="scope-note"><h3>保持当前配置</h3><p>向量模型、维度、索引、重排、OCR及图像能力。未配置的能力仍需单独配置，不由 Sub2API 文字模型代替。</p></section>
    </div>
    <el-alert v-if="testResult" :title="testResult" type="info" :closable="false" class="ps-section" />
    <div v-if="compatibilityResult" class="ps-section" aria-live="polite">
      <h3>文字兼容验证结果</h3>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="整体结果">{{ compatibilityLabel(compatibilityResult.state) }}</el-descriptions-item>
        <el-descriptions-item label="非流式文字">{{ checkLabel(compatibilityResult.checks.text) }}</el-descriptions-item>
        <el-descriptions-item label="流式完成">{{ checkLabel(compatibilityResult.checks.stream) }}</el-descriptions-item>
        <el-descriptions-item label="函数工具回灌">{{ checkLabel(compatibilityResult.checks.tool) }}</el-descriptions-item>
        <el-descriptions-item label="用量来源">{{ usageLabel(compatibilityResult.usageSource) }}</el-descriptions-item>
        <el-descriptions-item label="验证配置版本">{{ compatibilityResult.providerVersion }}</el-descriptions-item>
        <el-descriptions-item label="验证时间">{{ checkedTime(compatibilityResult.checkedAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="compatibilityResult.reason" label="核对说明">{{ compatibilityResult.reason }}</el-descriptions-item>
      </el-descriptions>
      <p class="ps-muted">兼容验证仅核对 API 协议。完整知识问答仍需要原向量模型、活动索引和业务验收。</p>
      <el-button v-if="compatibilityResult.state === 'PASSED' && verifiedProvider && !verifiedProvider.active" v-hasPermi="['paismart:provider:edit']" type="primary" :disabled="loading || !verifiedProvider.enabled || !verifiedProvider.compatibilityValid" @click="edit(verifiedProvider)">配置激活此对话模型</el-button>
      <router-link v-if="compatibilityResult.state === 'PENDING_RECONCILIATION'" v-hasPermi="['paismart:operation:plan']" to="/paismart/jobs">前往系统作业核对模型调用及未知费用</router-link>
    </div>
    <el-dialog v-model="open" :title="`供应商配置 · ${scopeLabel(form.scope)}`" width="720px" class="provider-dialog" :close-on-click-modal="false" :before-close="closeForm">
      <el-form label-width="130px" :disabled="saving">
        <el-form-item label="模型用途" :error="fieldErrors.scope"><el-select v-model="form.scope" :disabled="!!form.id" @change="scopeChanged"><el-option label="对话" value="LLM" /><el-option label="向量" value="EMBEDDING" /><el-option label="重排" value="RERANK" /></el-select></el-form-item>
        <el-form-item label="供应商" required :error="fieldErrors.provider"><el-select v-model="form.provider" allow-create filterable @change="providerChanged"><el-option v-for="p in providerOptions" :key="p" :label="p === 'SUB2API' ? 'Sub2API' : p" :value="p" /></el-select></el-form-item>
        <el-form-item v-if="sub2ApiForm" label="调用 API" required :error="fieldErrors.apiProtocol"><el-select v-model="config.apiProtocol" placeholder="请选择已核实的调用协议"><el-option label="Responses" value="responses" /><el-option label="Chat Completions" value="chat_completions" /></el-select><p class="ps-muted">失败不会自动更换协议、模型或供应商。</p></el-form-item>
        <el-form-item :label="sub2ApiForm ? 'API 根地址' : 'API 地址'" required :error="fieldErrors.address"><el-input v-model="form.address" placeholder="HTTPS 供应商根地址" maxlength="1000" /><p v-if="sub2ApiForm" class="ps-muted">根地址不含 /responses 或 /chat/completions；主机与传输由服务端受控校验。</p></el-form-item>
        <el-form-item v-if="sub2ApiForm" label="请求路径预览"><span class="request-preview">{{ requestPath || '填写根地址并选择协议后显示' }}</span></el-form-item>
        <el-form-item label="模型 ID" required :error="fieldErrors.model"><el-input v-model="form.model" maxlength="200" placeholder="精确填写实例支持的模型 ID" /></el-form-item>
        <el-form-item label="平台供应商编码" :error="fieldErrors.providerCode"><el-input v-model="providerCode" :disabled="sub2ApiForm" placeholder="对应托管秘密元数据 providerCode" /><p class="ps-muted">该编码须与平台托管秘密的供应商和模型能力一致。</p></el-form-item>
        <el-form-item label="秘密引用" :error="fieldErrors.secretRef"><el-input v-model="form.secretRef" autocomplete="off" placeholder="填写 ai.paismart.* 托管引用" maxlength="200" /><p class="ps-muted">只保存托管引用，不回显密钥；编辑时留空保留既有引用。</p></el-form-item>
        <el-form-item v-if="form.scope === 'EMBEDDING'" label="向量维度"><el-input-number v-model="form.dimension" :min="1" :max="8192" :precision="0" /></el-form-item>
        <el-form-item v-if="sub2ApiForm" label="输出 Token 上限" required :error="fieldErrors.maxOutputTokens"><el-input-number v-model="config.maxOutputTokens" :min="1" :max="16000" :precision="0" /><p class="ps-muted">填写经模型核实的预算（1～16000），服务端仍限制项目方法上限。Responses 的预算包含推理消耗。</p></el-form-item>
        <el-form-item v-if="sub2ApiForm && config.apiProtocol === 'responses'" label="推理强度"><el-select v-model="reasoningEffort" clearable aria-label="推理强度" placeholder="供应商默认（不传）"><el-option label="供应商默认（不传）" value="" /><el-option label="低（low）" value="low" /></el-select><p class="ps-muted">当前仅开放低强度；推理消耗计入输出 Token 预算。修改后需重新验证文字能力。</p></el-form-item>
        <el-form-item v-if="sub2ApiForm && config.apiProtocol === 'chat_completions'" label="输出参数" required :error="fieldErrors.outputTokenParameter"><el-select v-model="config.outputTokenParameter" placeholder="请选择实例支持的参数"><el-option label="max_tokens" value="max_tokens" /><el-option label="max_completion_tokens" value="max_completion_tokens" /></el-select><p class="ps-muted">仅发送选中的参数；不会根据报错自动换参数。</p></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="sub2ApiForm && !form.id" /><p v-if="sub2ApiForm" class="ps-muted">{{ form.id ? '启用仅允许候选配置参与受控验证。' : '初次保存为未启用、未激活；保存后再编辑启用候选配置。' }}</p></el-form-item>
        <el-form-item :label="sub2ApiForm ? '激活此对话模型' : '激活此模型'" :error="fieldErrors.active"><el-switch v-model="form.active" :disabled="!!activationReason" /><p v-if="activationReason" class="ps-muted">{{ activationReason }}</p></el-form-item>
      </el-form>
      <el-alert v-if="sub2ApiForm" title="保存仅写入配置，不调用模型。配置或托管密钥变化后，原验证失效，需要重新核对。" type="info" :closable="false" />
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <template #footer><el-button :disabled="saving" @click="open = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存配置</el-button></template>
    </el-dialog>
    <el-dialog v-model="verificationOpen" title="验证文字能力 · 执行前确认" width="620px" class="provider-dialog" :close-on-click-modal="false" :close-on-press-escape="!testing" :show-close="!testing" :before-close="closeVerification">
      <template v-if="verificationRow">
        <p><strong>{{ verificationRow.provider }} / {{ protocolLabel(verificationRow.config?.apiProtocol) }} / {{ verificationRow.model }}</strong></p><p>固定配置版本 {{ verificationRow.rowVersion }}，仅使用合成内容，不读取业务文档。</p>
        <el-alert :title="resumeVerification ? '此次只核对原验证结果，不重送未知请求。费用与原调用需先在系统作业核对。' : '会调用真实 API，最多 3 次请求，每次输出最多 128 Token；同时预占输入估算额度，受账户预算和限流约束。'" type="warning" :closable="false" />
        <el-checkbox v-if="verificationRow.canStartNewVerification" v-model="startNewVerification" :disabled="!!testing" @change="newVerificationChanged">原用量已核对，确认创建新一组验证（最多 3 次收费请求）</el-checkbox>
        <ol><li>非流式短文字与完整终态</li><li>流式函数规划，仅运行固定本地合成函数</li><li>工具结果回灌后的流式回答与用量核对</li></ol>
        <p class="ps-muted">取消确认不发送请求。提交后等待服务端核对，关闭页面不会取消已发送的远端请求；结果未知先核对费用。同一标识在六分钟内只回读状态；到期且没有未知调用时，服务端重放已完成结果、继续尚未发送的子请求，仍限原三次预算。</p>
        <el-alert v-if="testing" title="正在等待服务端核对文字、流式和工具结果，请勿重复提交。" type="info" :closable="false" /><el-alert v-if="verificationError" :title="verificationError" type="error" :closable="false" />
      </template>
      <template #footer><el-button :disabled="!!testing" @click="verificationOpen = false">取消</el-button><el-button v-hasPermi="['paismart:provider:test']" type="primary" :loading="!!testing" @click="confirmVerification">{{ resumeVerification ? '核对原验证结果' : '确认并开始验证' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { getProvider, listProviders, saveProvider, testProvider } from '@/api/paismart/operations'
import { newKey } from '@/api/paismart/client'
import type { Provider, ProviderCompatibility, ProviderConfig, ProviderTestInput } from '@/types/paismart'
import { errorText } from '../../composables/usePage'
import '../../common.css'
const providers = shallowRef<Provider[]>([])
const loading = ref(false), saving = ref(false), open = ref(false), error = ref(''), formError = ref('')
const testing = ref(''), testResult = ref(''), providerCode = ref(''), fieldErrors = ref<Record<string, string>>({})
const compatibilityResult = shallowRef<ProviderCompatibility>()
const verifiedProviderId = ref<Provider['id']>()
const verifiedProvider = computed(() => providers.value.find(row => row.id === verifiedProviderId.value))
const form = ref<Provider>(blankProvider()), originalExternalConfig = ref('')
const config = computed(() => form.value.config ?? (form.value.config = {}))
const reasoningEffort = computed({ get: () => config.value.reasoningEffort ?? '', set: (value: string | undefined) => { if (value === 'low') config.value.reasoningEffort = value; else delete config.value.reasoningEffort } })
const verificationOpen = ref(false), verificationError = ref(''), verificationRow = shallowRef<Provider>()
const startNewVerification = ref(false)
const resumeVerification = computed(() => verificationRow.value?.config?.compatibility?.state === 'PENDING_RECONCILIATION' && !startNewVerification.value)
const pendingVerification = new Map<string, { input: ProviderTestInput; key: string }>()
let loadSerial = 0, formSerial = 0, verificationSerial = 0, active = true
watch(open, value => { if (!value) formSerial++ }, { flush: 'sync' })
watch(verificationOpen, value => { if (!value) verificationSerial++ }, { flush: 'sync' })
// 用途读取期间表单仍可输入；任何草稿变动都使先前的回读/保存结果失效。
watch(form, () => { formSerial++ }, { deep: true, flush: 'sync' })
watch(providerCode, () => { formSerial++ }, { flush: 'sync' })
function blankProvider(): Provider { return { scope: 'LLM', provider: '', model: '', address: '', enabled: false, active: false, rowVersion: 0, config: {} } }
function isSub2Api(row: Provider): boolean { return row.provider.trim().toUpperCase() === 'SUB2API' || row.config?.providerCode?.trim().toLowerCase() === 'sub2api' }
const sub2ApiForm = computed(() => isSub2Api(form.value) || providerCode.value.trim().toLowerCase() === 'sub2api')
const providerOptions = computed(() => form.value.scope === 'EMBEDDING' ? ['ALIYUN', 'ZHIPU'] : form.value.scope === 'LLM' ? ['DEEPSEEK', 'QWEN', 'ZHIPU', 'SUB2API'] : ['DEEPSEEK', 'QWEN', 'ZHIPU'])
function scopeLabel(scope: string) { return ({ LLM: '对话', EMBEDDING: '向量', RERANK: '重排' } as Record<string, string>)[scope] ?? scope }
function protocolLabel(protocol?: string) { return protocol === 'responses' ? 'Responses' : protocol === 'chat_completions' ? 'Chat Completions' : '未选择协议' }
function compatibilityLabel(state?: string) { return ({ PASSED: '兼容验证通过', FAILED: '兼容验证未通过', PENDING_RECONCILIATION: '待核对远端结果及费用' } as Record<string, string>)[state ?? ''] ?? '待验证' }
function providerCompatibilityLabel(row: Provider) { return row.config?.compatibility?.state === 'PASSED' && !row.compatibilityValid ? '验证失效，需重新验证' : compatibilityLabel(row.config?.compatibility?.state) }
function usageLabel(source: string) { return ({ SUPPLIER: '供应商统计', ESTIMATED: '估算（非实际统计）', UNKNOWN: '未知，需核对费用' } as Record<string, string>)[source] ?? source }
function checkLabel(passed: boolean) { return passed ? '通过' : '未通过或未完成（请查看核对说明）' }
function checkedTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) }
const requestPath = computed(() => !form.value.address.trim() || !config.value.apiProtocol ? '' : `${form.value.address.trim().replace(/\/+$/, '')}/${config.value.apiProtocol === 'responses' ? 'responses' : 'chat/completions'}`)
function externalConfigSignature() { const { compatibility, ...parameters } = config.value; return JSON.stringify({ scope: form.value.scope, provider: form.value.provider.trim().toUpperCase(), address: form.value.address.trim().replace(/\/+$/, ''), model: form.value.model.trim(), secretRef: form.value.secretRef?.trim() ?? '', providerCode: providerCode.value.trim().toLowerCase(), parameters }) }
const activationReason = computed(() => {
  if (!form.value.enabled) return '请先启用该配置'
  if (!sub2ApiForm.value) return ''
  if (form.value.scope !== 'LLM') return 'Sub2API 仅允许对话用途'
  if (externalConfigSignature() !== originalExternalConfig.value) return '配置已修改，保存后重新验证文字能力'
  if (!form.value.compatibilityValid) return form.value.activationBlockedReason || '请先完成有效的文字、流式与工具兼容验证'
  return ''
})
watch(() => form.value.enabled, enabled => { if (!enabled) form.value.active = false })
watch(() => config.value.apiProtocol, protocol => { if (!sub2ApiForm.value) return; if (protocol === 'responses') delete config.value.outputTokenParameter; else delete config.value.reasoningEffort })
watch(activationReason, reason => { if (reason && sub2ApiForm.value) form.value.active = false })
async function load() { if (!active) return; const serial = ++loadSerial; loading.value = true; error.value = ''; try { const result = await listProviders(); if (active && serial === loadSerial) providers.value = result.rows } catch (e) { if (active && serial === loadSerial) error.value = errorText(e) } finally { if (active && serial === loadSerial) loading.value = false } }
function edit(row: Provider) { if (!active) return; formSerial++; form.value = { ...row, config: { ...row.config }, secretRef: '' }; providerCode.value = row.config?.providerCode ?? ''; originalExternalConfig.value = externalConfigSignature(); fieldErrors.value = {}; formError.value = ''; open.value = true }
function create() { if (!active) return; formSerial++; form.value = blankProvider(); providerCode.value = ''; originalExternalConfig.value = externalConfigSignature(); fieldErrors.value = {}; formError.value = ''; open.value = true }
async function editScope(scope: string) { if (!active) return; const serial = ++formSerial; error.value = ''; try { const result = await getProvider(scope); if (active && serial === formSerial) edit(result.data) } catch (e) { if (active && serial === formSerial) error.value = errorText(e) } }
function providerChanged() {
  if (form.value.provider.trim().toUpperCase() === 'SUB2API') { form.value.provider = 'SUB2API'; providerCode.value = 'sub2api'; form.value.config = { providerCode: 'sub2api' }; form.value.active = false; if (!form.value.id) form.value.enabled = false }
  else if (providerCode.value === 'sub2api') { providerCode.value = ''; delete config.value.providerCode; delete config.value.apiProtocol; delete config.value.maxOutputTokens; delete config.value.outputTokenParameter; delete config.value.reasoningEffort; delete config.value.compatibility; form.value.compatibilityValid = false; form.value.active = false }
}
function scopeChanged() { if (sub2ApiForm.value && form.value.scope !== 'LLM') { form.value.provider = ''; providerCode.value = ''; delete config.value.providerCode; delete config.value.apiProtocol; delete config.value.maxOutputTokens; delete config.value.outputTokenParameter; delete config.value.reasoningEffort; form.value.active = false } }
function closeForm(done: () => void) { if (!saving.value) done() }
function validate() {
  const errors: Record<string, string> = {}
  if (form.value.enabled || sub2ApiForm.value) { if (!form.value.provider.trim()) errors.provider = '请填写供应商'; if (!form.value.model.trim()) errors.model = '请填写精确模型 ID'; if (!form.value.address.trim()) errors.address = '请填写 API 根地址' }
  if (sub2ApiForm.value) {
    if (form.value.scope !== 'LLM') errors.scope = 'Sub2API 仅允许对话用途，不能替代向量或重排'
    if (form.value.provider.trim().toUpperCase() !== 'SUB2API') errors.provider = 'Sub2API 配置须选择 Sub2API 供应商'
    if (!['responses', 'chat_completions'].includes(config.value.apiProtocol ?? '')) errors.apiProtocol = '请选择调用 API'
    if (!Number.isSafeInteger(config.value.maxOutputTokens) || Number(config.value.maxOutputTokens) < 1 || Number(config.value.maxOutputTokens) > 16000) errors.maxOutputTokens = '请填写 1～16000 的正整数 Token 预算'
    if (config.value.apiProtocol === 'chat_completions' && !['max_tokens', 'max_completion_tokens'].includes(config.value.outputTokenParameter ?? '')) errors.outputTokenParameter = '请选择实例支持的输出参数'
    if (/\/(responses|chat\/completions)\/?$/i.test(form.value.address.trim())) errors.address = '请填写 API 根地址，不含具体操作后缀'
    if (!form.value.id && !form.value.secretRef?.trim()) errors.secretRef = '新配置需要平台托管秘密引用'
    if (form.value.active && activationReason.value) errors.active = activationReason.value
  }
  fieldErrors.value = errors; return Object.keys(errors).length === 0
}
async function save() {
  if (!active || !open.value || saving.value) return
  formError.value = ''; if (!validate()) return; saving.value = true
  const serial = formSerial, parameters: ProviderConfig = { ...config.value }
  if (providerCode.value.trim()) parameters.providerCode = providerCode.value.trim()
  if (sub2ApiForm.value) { parameters.providerCode = 'sub2api'; delete parameters.capabilityCode; if (parameters.apiProtocol === 'responses') delete parameters.outputTokenParameter }
  const target = { ...form.value, config: parameters }
  const sameForm = () => active && open.value && serial === formSerial && String(form.value.id) === String(target.id) && form.value.scope === target.scope && form.value.rowVersion === target.rowVersion
  try { await saveProvider(target); if (sameForm()) { open.value = false; form.value.secretRef = '' }; if (active) await load() }
  catch (e) { if (sameForm()) formError.value = `${errorText(e)}。输入已保留；若配置版本冲突，请刷新后重新编辑，系统不会自动覆盖。` }
  finally { if (active) saving.value = false }
}
async function test(row: Provider) {
  if (!active || testing.value) return
  error.value = ''; testResult.value = ''; compatibilityResult.value = undefined; verifiedProviderId.value = undefined
  if (isSub2Api(row)) { if (row.scope !== 'LLM' || !row.enabled || !row.id) { error.value = 'Sub2API 验证需要已保存并启用的对话配置'; return }; verificationSerial++; verificationRow.value = { ...row, config: { ...row.config } }; startNewVerification.value = false; verificationError.value = ''; verificationOpen.value = true; return }
  const target = { ...row }
  testing.value = String(target.id)
  try { const result = (await testProvider(target.scope, { id: target.id })).data; if (active) testResult.value = `${target.provider} / ${target.model}：${result.state}，耗时 ${result.latencyMs} ms${result.reason ? '，' + result.reason : ''}` } catch (e) { if (active) error.value = errorText(e) } finally { if (active) testing.value = '' }
}
function closeVerification(done: () => void) { if (!testing.value) done() }
function newVerificationChanged() { if (verificationRow.value?.id) pendingVerification.delete(String(verificationRow.value.id)) }
async function confirmVerification() {
  const selectedRow = verificationRow.value; if (!active || !verificationOpen.value || !selectedRow?.id || testing.value) return
  // 固定此次确认的身份及版本，不能引用随后可能变化的确认行。
  const row = { ...selectedRow, id: selectedRow.id, config: { ...selectedRow.config } }
  const serial = verificationSerial
  const sameVerification = () => active && verificationOpen.value && serial === verificationSerial && verificationRow.value?.id === row.id && verificationRow.value?.rowVersion === row.rowVersion
  const id = String(row.id), recorded = row.config?.compatibility
  let operation = pendingVerification.get(id)
  if (!operation || (operation.input.expectedVersion !== row.rowVersion && recorded?.operationKey !== operation.key)) { operation = { input: { id: row.id, mode: 'compatibility', expectedVersion: resumeVerification.value && recorded ? recorded.providerVersion : row.rowVersion }, key: resumeVerification.value && recorded?.operationKey ? recorded.operationKey : newKey() }; pendingVerification.set(id, operation) }
  testing.value = id; verificationError.value = ''
  try { const result = (await testProvider('LLM', operation.input, operation.key)).data; if (sameVerification()) { compatibilityResult.value = result.compatibility; verifiedProviderId.value = row.id; testResult.value = `${row.provider} / ${row.model}：${compatibilityLabel(result.compatibility?.state ?? result.state)}，耗时 ${result.latencyMs} ms${result.reason ? '，' + result.reason : ''}`; if (result.compatibility?.state !== 'PENDING_RECONCILIATION') pendingVerification.delete(id); verificationOpen.value = false }; if (active) await load() }
  catch (e) { if (sameVerification()) verificationError.value = `${errorText(e)}。已保留本次验证标识，再次确认将核对同一次操作；请勿另起收费请求。`; if (active) await load() }
  finally { if (active) testing.value = '' }
}
onBeforeUnmount(() => { active = false; loadSerial++; formSerial++; verificationSerial++ })
onMounted(() => void load())
</script>
<style scoped>
.request-preview { overflow-wrap: anywhere; font-family: Consolas, monospace; }
.provider-table { margin-bottom: 16px; }
.provider-details { padding: 12px 24px; }
.provider-status { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.provider-scopes { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.scope-note { border-left: 3px solid var(--el-color-info); padding: 14px 16px; background: var(--el-fill-color-light); }
.scope-note h3 { margin: 0 0 8px; }
.scope-note p { margin: 0; line-height: 1.6; color: var(--el-text-color-regular); }
@media (max-width: 900px) { .provider-scopes { grid-template-columns: 1fr; } }
:deep(.provider-dialog) { max-width: calc(100vw - 32px); }
:deep(.provider-dialog .el-dialog__body) { max-height: calc(100vh - 220px); overflow-y: auto; }
:deep(.el-form-item__content .ps-muted) { width: 100%; margin: 4px 0 0; line-height: 1.5; }
</style>
