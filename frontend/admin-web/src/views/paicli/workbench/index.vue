<template>
  <main class="app-container pc-page pc-coding">
    <header class="pc-card-title"><div><h1>智能编码</h1><p class="pc-muted">读懂代码、实现功能、排查错误；在一个对话里查看执行和成果。</p></div><div class="pc-actions"><el-button v-hasPermi="['paicli:terminal:use']" :loading="openingTerminal" :disabled="historical || session.sending.value || operation.busy.value" @click="openTerminal">打开终端</el-button><el-button :loading="personal.loading.value" @click="reload">刷新</el-button></div></header>
    <el-alert v-if="pageError || personal.error.value || operation.error.value || session.error.value" :title="personal.error.value || pageError || operation.error.value || session.error.value" type="error" :closable="false" />
    <el-alert v-if="personal.context.value && !personal.context.value.canChat" type="warning" :closable="false"><template #title>{{readinessMessage}}</template><el-button v-hasPermi="['paicli:session:write']" :loading="personal.loading.value" @click="prepare">重试准备</el-button><el-button v-hasPermi="['paicli:connection:read']" link @click="router.push('/paicli/connections')">模型与设置</el-button></el-alert>
    <div class="pc-coding-grid" :class="{'has-results': showResults}">
      <aside class="pc-panel pc-history"><div class="pc-card-title"><h2>我的对话</h2><el-button v-hasPermi="['paicli:session:write']" link type="primary" :disabled="session.sending.value || operation.busy.value || openingTerminal" @click="newConversation">新对话</el-button></div><el-select v-model="historyFilter" aria-label="筛选对话" class="pc-wide"><el-option label="全部对话" value="ALL" /><el-option label="进行中" value="ACTIVE" /><el-option label="待确认" value="WAITING_APPROVAL" /></el-select><button v-for="item in visibleSessions" :key="item.id" class="pc-session" :aria-current="session.detail.value?.id === item.id" :disabled="session.sending.value || operation.busy.value || openingTerminal" @click="chooseSession(item.id)"><strong>{{item.title}}</strong><p class="pc-muted">{{stateName(item.status)}}</p></button><el-empty v-if="!visibleSessions.length" description="从右侧开始第一次对话" :image-size="50" /><Pagination v-if="sessionTotal > sessionSize" v-model:page="sessionPage" v-model:limit="sessionSize" :total="sessionTotal" layout="prev, pager, next" :auto-scroll="false" @pagination="loadSessions" /></aside>
      <div class="pc-stack pc-conversation">
        <section v-if="!session.detail.value?.messages.length && !session.loading.value" class="pc-panel pc-welcome"><h2>你想完成什么？</h2><p class="pc-muted">直接描述目标，或选择一个起点。文件保存在个人目录；处理已有代码时再更换文件位置。</p><div class="pc-starters"><button v-for="starter in starters" :key="starter.title" @click="text = starter.prompt"><strong>{{starter.title}}</strong><span>{{starter.description}}</span></button></div></section>
        <el-alert v-if="historical" title="这是只读历史。原记录和成果保留，新建对话后继续处理。" type="info" :closable="false" />
        <el-alert v-if="['DISCONNECTED','EXPIRED'].includes(session.streamState.value)" title="连接已中断，当前内容可能未完成。" type="warning" :closable="false"><el-button @click="reconnect">重新获取实际进度</el-button></el-alert>
        <section v-if="hasExecution" class="pc-panel pc-execution" aria-label="本次执行" aria-live="polite"><div class="pc-card-title"><h2>本次执行</h2><el-tag>{{stateName(session.detail.value?.latestTaskStatus || session.detail.value?.status)}}</el-tag></div><p>{{executionSummary}}</p><div v-for="tool in recentTools" :key="tool.id" class="pc-execution-step"><span>{{tool.toolName}}<span v-if="tool.role"> · {{tool.role}}</span></span><el-tag size="small">{{stateName(tool.status)}}</el-tag></div><p v-if="!recentTools.length && session.active.value" class="pc-muted">等待中心返回实际执行步骤。</p><PlanPanel v-if="currentPlan" :readonly="historical" :plan="currentPlan" :active="session.active.value" @refresh="refreshSession" @cancel="cancel" /><div v-for="approval in pendingApprovals" :key="approval.id" class="pc-inline-approval"><h3>需要你确认：{{approval.toolName}}</h3><p>{{approval.impact}}</p><el-button v-hasPermi="['paicli:approval:decide']" type="primary" @click="openApproval(approval.id)">查看影响并确认</el-button></div><div class="pc-actions"><el-button v-hasPermi="['paicli:task:cancel']" :disabled="!session.active.value" :loading="operation.busy.value" @click="cancel">停止本次执行</el-button><el-button v-if="hasResults" link @click="resultsVisible = !resultsVisible">{{resultsVisible ? '收起成果' : '查看成果'}}</el-button></div></section>
        <ConversationPane v-if="session.detail.value?.messages.length || session.loading.value" :messages="session.detail.value?.messages ?? []" :session-id="session.detail.value?.id" :tool-calls="session.detail.value?.toolCalls" :events="session.events.value" :loading="session.loading.value" @approval="openApproval" />
        <el-alert v-if="personal.context.value?.canChat && currentDirectory?.state !== 'VERIFIED'" title="现在可以进行文字问答。文件读写和终端需要等待个人目录准备完成。" type="info" :closable="false" /><section class="pc-panel pc-composer" aria-label="输入要求"><label class="pc-label" for="pc-turn-text">{{session.active.value ? '补充要求（本次执行结束后发送）' : '描述你的要求'}}</label><el-input id="pc-turn-text" v-model="text" type="textarea" :rows="4" :disabled="historical || session.sending.value" placeholder="例如：分析项目启动方式；实现登录页面；定位并修复报错…" @paste="imageAttachments?.paste($event)" /><ImageAttachments ref="imageAttachments" v-model="attachments" :directory-context-id="personal.directoryContextId.value" :supports-images="!!selectedModel?.supportsImages" :disabled="historical || session.sending.value || !personal.directoryContextId.value" /><div v-if="selectedFiles.length" class="pc-actions"><el-tag v-for="file in selectedFiles" :key="file.id" closable :disable-transitions="true" @close="selectedFiles = selectedFiles.filter(item => item.id !== file.id)">资料：{{file.name}}（发送后读取实际正文）</el-tag></div><div class="pc-composer-options"><el-select v-model="modelId" aria-label="选择模型" :disabled="historical || session.active.value || session.sending.value" placeholder="请选择模型" @change="changeModel"><el-option v-for="model in models" :key="model.id" :value="model.id" :label="model.displayName || model.modelId" :disabled="!model.enabled" /></el-select><el-select v-model="handling" aria-label="处理方式" :disabled="historical || session.active.value"><el-option label="只分析" value="ANALYSE" /><el-option label="直接处理" value="REACT" /><el-option label="先出方案" value="PLAN" /><el-option label="AI 协作" value="TEAM" /></el-select><el-button :disabled="session.active.value || session.sending.value || operation.busy.value || openingTerminal || terminalHasInstance || historical" link @click="directoryOpen = true">文件位置：{{currentDirectory?.label || '个人默认目录'}}</el-button></div><div class="pc-card-title"><p class="pc-muted">{{session.active.value ? '草稿会保留，不会自动发送。' : currentDirectory?.path || '未指定目录时使用个人默认目录。'}}</p><el-button v-hasPermi="['paicli:session:write']" type="primary" :loading="session.sending.value || operation.busy.value" :disabled="!canSend" @click="send">发送</el-button></div></section>
        <WebTerminalPane v-if="terminalEnabled && session.detail.value" :key="session.detail.value.id" v-show="terminalVisible" ref="terminalPane" :session-id="session.detail.value.id" :directory-context-id="personal.directoryContextId.value" :supports-images="!!selectedModel?.supportsImages" :disabled="historical || !personal.context.value?.canOpenTerminal" :disabled-reason="terminalMessage" :active-task="session.active.value" :visible="terminalVisible" :initial-terminal-id="initialTerminalId" :cancel-task="cancelTerminalTask" @opened="terminalOpened" @refresh="refreshSession" /><el-button v-if="terminalEnabled" link @click="terminalVisible = !terminalVisible">{{terminalVisible ? '收起终端' : '展开终端'}}</el-button>
        <el-collapse v-if="session.detail.value"><el-collapse-item title="对话记录、上下文与恢复" name="details"><SessionHistory :session-id="session.detail.value.id" /><LedgerReader :session-id="session.detail.value.id" /><ContextPanel :context="session.detail.value.context" :active="session.active.value" :readonly="historical" @refresh="refreshSession" /><el-button v-hasPermi="['paicli:snapshot:read']" :disabled="historical" @click="snapshotsOpen = true">快照与恢复</el-button></el-collapse-item></el-collapse>
      </div>
      <ExecutionResults v-if="showResults && session.detail.value" :session-id="session.detail.value.id" :artifacts="session.detail.value.context?.artifacts ?? []" :tools="session.detail.value.toolCalls ?? []" />
    </div>
    <PersonalDirectoryPicker v-model="directoryOpen" :directories="personal.directories.value" :current-id="personal.directoryContextId.value" :active="session.active.value || session.sending.value || operation.busy.value || openingTerminal || terminalHasInstance" @refresh="personal.load" @select="chooseDirectory" /><ApprovalDrawer v-model="approvalOpen" :approval="selectedApproval" @decided="refreshSession" /><SnapshotDialog v-model="snapshotsOpen" :readonly="historical" :session-id="session.detail.value?.id ?? ''" @refresh="refreshSession" />
  </main>
</template>
<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import Pagination from '@/components/Pagination/index.vue'
import { checkPermi } from '@/utils/permission'
import { createSession, listSessions } from '@/api/paicli/sessions'
import { switchModel } from '@/api/paicli/connections'
import { cancelTask, getTask } from '@/api/paicli/tasks'
import type { PcApprovalMode, PcAttachment, PcMode, PcSession } from '@/types/paicli'
import ApprovalDrawer from '../components/ApprovalDrawer.vue'
import ContextPanel from '../components/ContextPanel.vue'
import ConversationPane from '../components/ConversationPane.vue'
import SessionHistory from '../components/SessionHistory.vue'
import LedgerReader from '../components/LedgerReader.vue'
import ImageAttachments from '../components/ImageAttachments.vue'
import PlanPanel from '../components/PlanPanel.vue'
import SnapshotDialog from '../components/SnapshotDialog.vue'
import PersonalDirectoryPicker from '../components/PersonalDirectoryPicker.vue'
import ExecutionResults from '../components/ExecutionResults.vue'
import { executionEvidence } from '../shared/executionEvidence'
import { usePersonalContext } from '../usePersonalContext'
import { useSession } from '../useSession'
import { useOperations } from '../useOperations'
import { errorText, isHistoricalSession, operationKeys, stateName, terminalTaskStates } from '../model'
import '../page.css'
const WebTerminalPane = defineAsyncComponent(() => import('../components/WebTerminalPane.vue'))
defineOptions({ name: 'Workbench' })
const route = useRoute(), router = useRouter(), personal = usePersonalContext(), session = useSession(), operation = useOperations(), keys = operationKeys()
const sessions = ref<PcSession[]>([]), modelId = ref(''), handling = ref<PcMode | 'ANALYSE'>('REACT'), approvalMode = ref<PcApprovalMode>('ASK'), text = ref(''), attachments = ref<PcAttachment[]>([]), selectedFiles = ref<{id:string;name:string}[]>([]), pageError = ref('')
const sessionPage = ref(1), sessionSize = ref(20), sessionTotal = ref(0), historyFilter = ref('ALL'), approvalOpen = ref(false), approvalId = ref(''), directoryOpen = ref(false), snapshotsOpen = ref(false), resultsVisible = ref(true), imageAttachments = ref<InstanceType<typeof ImageAttachments>>()
const terminalEnabled = ref(false), terminalVisible = ref(false), terminalHasInstance = ref(false), openingTerminal = ref(false), terminalPane = ref<InstanceType<typeof WebTerminalPane>>(), initialTerminalId = ref(typeof route.query.terminalId === 'string' ? route.query.terminalId : '')
let alive = true, terminalGeneration = 0, directoryExplicit = false, awaitTerminalOpen = false, firstLoad = true, lastSelectedId = '', generation = 0
let viewActive = true, fileHandoffGeneration = 0, fileHandoffPending = '', selectionGeneration = 0, displayedDraftId = 'new'
const drafts = new Map<string, {text: string; attachments: PcAttachment[]; files: {id:string;name:string}[]}>()
const models = computed(() => personal.context.value?.models ?? []), selectedModel = computed(() => models.value.find(item => item.id === modelId.value))
const mode = computed<PcMode>(()=>handling.value === 'ANALYSE' ? 'REACT' : handling.value), readOnly = computed(()=>handling.value === 'ANALYSE')
const currentDirectory = computed(()=>session.detail.value ? session.detail.value.directoryContext : personal.directory.value)
const historical = computed(() => isHistoricalSession(session.detail.value))
const terminalMessage=computed(()=>personal.context.value?.terminal?.message || personal.context.value?.terminalMessage || '终端尚未准备。请等待文件位置核对并确认终端能力已启用。')
const readinessMessage = computed(() => personal.context.value?.runtime.message || (!models.value.some(item => item.enabled) ? '尚未配置可用模型，请在模型与设置中添加模型。' : '个人环境正在准备。'))
const visibleSessions = computed(() => sessions.value.filter(item => historyFilter.value === 'ALL' || (historyFilter.value === 'ACTIVE' ? !!item.activeTaskId && !terminalTaskStates.has(item.status) : item.status === historyFilter.value)))
const pendingApprovals = computed(() => session.detail.value?.approvals.filter(item => ['PENDING','WAITING_APPROVAL'].includes(item.status)) ?? []), selectedApproval = computed(() => session.detail.value?.approvals.find(item => item.id === approvalId.value))
const executionId = computed(()=>session.detail.value?.latestTaskId || session.detail.value?.activeTaskId), currentPlan = computed(()=> {const plan=session.detail.value?.plan; return plan && executionId.value && (plan.taskId || plan.parentId) === executionId.value ? plan : undefined}), recentTools = computed(() => (session.detail.value?.toolCalls ?? []).filter(tool=>!!executionId.value && (tool.taskId || tool.parentId) === executionId.value).slice(-4))
const hasExecution = computed(() => !!session.detail.value?.activeTaskId || !!currentPlan.value || !!recentTools.value.length || !!pendingApprovals.value.length)
const evidence = computed(() => executionEvidence(session.detail.value?.toolCalls ?? [])), hasResults = computed(() => !!session.detail.value?.context?.artifacts.length || !!evidence.value.diffs.length || !!evidence.value.checks.length), showResults = computed(() => hasResults.value && resultsVisible.value)
const executionSummary = computed(() => pendingApprovals.value.length ? '执行暂停，等待你确认具体影响。' : session.active.value ? '正在按当前要求处理，步骤和状态来自实际执行记录。' : (session.detail.value?.latestTaskStatus || session.detail.value?.status) === 'COMPLETED' ? '本次执行已结束。请查看文件和实际检查结果。' : '本次执行记录已保留，未完成事项请核对实际回执。')
const canSend = computed(() => !historical.value && !!text.value.trim() && !!selectedModel.value?.enabled && !!personal.context.value?.canChat && !session.active.value && !session.sending.value && !operation.busy.value && !openingTerminal.value && !personal.loading.value && !imageAttachments.value?.uploading && (attachments.value.length === 0 || !!selectedModel.value?.supportsImages))
function storeDraft() { drafts.set(displayedDraftId, {text: text.value, attachments: [...attachments.value],files:[...selectedFiles.value]}) }
async function loadSessions() { const g = ++generation; try { const result = await listSessions(undefined, sessionPage.value, sessionSize.value); if (g === generation) { sessions.value = result.rows; sessionTotal.value = result.total } } catch(cause) { if (g === generation) pageError.value = errorText(cause) } }
async function reload() { await personal.load(); if (!models.value.some(item => item.id === modelId.value)) modelId.value = personal.context.value?.modelId ?? models.value.find(item => item.enabled)?.id ?? ''; await loadSessions() }
async function prepare() { await personal.prepare(); if (!modelId.value) modelId.value = personal.context.value?.modelId ?? ''; }
async function chooseSession(id: string, isCurrent = () => alive && viewActive, syncRoute = true) { if (openingTerminal.value || session.sending.value || operation.busy.value) return false; storeDraft(); const attempt = ++selectionGeneration; await session.select(id); const detail = session.detail.value; if (attempt !== selectionGeneration || !isCurrent() || !detail || detail.id !== id) return false; lastSelectedId = id; displayedDraftId = id; modelId.value = detail.modelId; handling.value = detail.readOnly ? 'ANALYSE' : detail.mode; approvalMode.value = detail.approvalMode; personal.directoryContextId.value = detail.directoryContextId ?? ''; selectedFiles.value = drafts.get(id)?.files ?? []; text.value = drafts.get(id)?.text ?? ''; attachments.value = drafts.get(id)?.attachments ?? []; terminalHasInstance.value = false; initialTerminalId.value = ''; terminalEnabled.value = false; if (syncRoute) await router.replace({query:{...route.query,sessionId:id,terminalId:undefined,workspaceId:undefined}}); return true }
function newConversation() { if (session.sending.value || operation.busy.value || openingTerminal.value) return; storeDraft(); selectionGeneration++; directoryExplicit = false; session.dispose(); lastSelectedId = ''; displayedDraftId = 'new'; selectedFiles.value = drafts.get('new')?.files ?? []; text.value = drafts.get('new')?.text ?? ''; attachments.value = drafts.get('new')?.attachments ?? []; personal.directoryContextId.value = personal.context.value?.directoryContext?.id ?? ''; modelId.value = personal.context.value?.modelId ?? models.value.find(item => item.enabled)?.id ?? ''; terminalEnabled.value = false; terminalHasInstance.value = false; initialTerminalId.value = ''; void router.replace({query:{...route.query,sessionId:undefined,terminalId:undefined,workspaceId:undefined}}) }
function chooseDirectory(id: string) { if (session.active.value || session.sending.value || operation.busy.value || openingTerminal.value || terminalHasInstance.value) return; if (session.detail.value) { storeDraft(); selectionGeneration++; session.dispose(); lastSelectedId = ''; displayedDraftId = 'new'; void router.replace({query:{...route.query,sessionId:undefined,terminalId:undefined}}) }; personal.directoryContextId.value = id; directoryExplicit = true; attachments.value = []; }
async function ensureSession(title: string, terminal = false) {
  if (session.detail.value) return true
  if(lastSelectedId){await session.select(lastSelectedId);if(!session.detail.value)throw new Error(session.error.value || '对话已建立，但未取得完整内容，请重试加载。');displayedDraftId=lastSelectedId;return true}
  const context = await personal.prepare(); if (!context) return false
  if (!(terminal ? context.runtime.ready : context.canChat)) { pageError.value = readinessMessage.value; return false }
  const data = {title:title.trim().slice(0,60) || '终端对话',directoryContextId:directoryExplicit ? personal.directoryContextId.value || undefined : undefined,modelId:modelId.value || context.modelId || undefined,mode:mode.value,readOnly:readOnly.value,approvalMode:approvalMode.value}
  const result = await createSession(data,keys.get(data)); keys.done(data); lastSelectedId = result.data.id; await session.select(result.data.id); if (session.detail.value) displayedDraftId = result.data.id; if (result.data.directoryContextId) personal.directoryContextId.value = result.data.directoryContextId; await loadSessions(); await router.replace({query:{...route.query,sessionId:result.data.id,workspaceId:undefined}}); return !!session.detail.value
}
async function send() {
  if (!canSend.value || !checkPermi(['paicli:session:write'])) return
  if (new TextEncoder().encode(text.value).length > 65536) { pageError.value = '输入超过 64KB，请缩减正文并使用文件引用。'; return }
  const input = text.value, images = [...attachments.value], files = selectedFiles.value.map(item=>item.id); pageError.value = ''
  await operation.run(async () => { if (!await ensureSession(input)) return; const detail = session.detail.value!; const ok = await session.send({text:input,mode:mode.value,readOnly:readOnly.value,approvalMode:approvalMode.value,modelId:modelId.value || detail.modelId,attachments:images.map(item=>item.id),files,version:detail.version}); if (ok) { text.value = ''; attachments.value = []; selectedFiles.value = []; drafts.delete('new'); drafts.delete(detail.id); await loadSessions() } })
}
async function cancelTerminalTask() { const id = session.detail.value?.activeTaskId; if (!id || !checkPermi(['paicli:task:cancel'])) return !id; let requested = false; await operation.run(async () => { const task = (await getTask(id)).data, intent = {action:'cancel',taskId:id,version:task.version}; await cancelTask(id,task.version,keys.get(intent)); keys.done(intent); requested = true; ElMessage.info('已请求停止，请等待实际停止回执。'); await refreshSession() }); return requested }
async function cancel() { await cancelTerminalTask() }
async function openTerminal() {
  if (historical.value || openingTerminal.value || session.sending.value || operation.busy.value || !checkPermi(['paicli:terminal:use'])) return
  const terminalAttempt = ++terminalGeneration; openingTerminal.value = true; pageError.value = ''
  try {
    if (!session.detail.value && !await ensureSession('终端对话',true)) return
    const targetId = session.detail.value?.id
    const isCurrent = () => alive && terminalAttempt === terminalGeneration && !!targetId && session.detail.value?.id === targetId
    for (let attempt = 0; attempt < 20 && currentDirectory.value?.state !== 'VERIFIED'; attempt++) {
      await new Promise(resolve => setTimeout(resolve,1000)); if (!isCurrent()) return; await session.refresh()
    }
    if (!isCurrent()) return
    await personal.load(); if (!isCurrent()) return
    if (!personal.context.value?.canOpenTerminal || currentDirectory.value?.state !== 'VERIFIED') {
      pageError.value = currentDirectory.value?.state !== 'VERIFIED' ? '文件位置尚未完成核对，终端暂不可用；已有对话和草稿保留。' : terminalMessage.value; return
    }
    awaitTerminalOpen = true; terminalEnabled.value = true; terminalVisible.value = true; await nextTick()
    if (isCurrent() && terminalPane.value) { awaitTerminalOpen = false; await terminalPane.value.open() }
  } catch(cause) { if (alive && terminalAttempt === terminalGeneration) pageError.value = errorText(cause) }
  finally { if (terminalAttempt === terminalGeneration) openingTerminal.value = false }
}
function terminalOpened(id: string) { terminalHasInstance.value = true; initialTerminalId.value = id; void router.replace({query:{...route.query,sessionId:session.detail.value?.id,terminalId:id,view:'terminal'}}) }
async function changeModel(value: string) { const detail = session.detail.value; if (!detail || historical.value || session.active.value || !checkPermi(['paicli:session:write']) || detail.modelId === value) return; const previous = detail.modelId, intent = {action:'model',sessionId:detail.id,version:detail.version,modelId:value}; await operation.run(async () => { try { await switchModel(detail.id,value,detail.version,keys.get(intent)); keys.done(intent); await refreshSession() } catch(cause) { modelId.value = previous; throw cause } }) }
async function refreshSession() { try { await session.refresh(); await loadSessions(); void session.subscribe() } catch(cause) { pageError.value = errorText(cause) } }
async function reconnect() { if (session.streamState.value === 'EXPIRED' && session.detail.value) await session.select(session.detail.value.id); else void session.subscribe() }
function openApproval(id: string) { approvalId.value = id; approvalOpen.value = true }
async function consumeFileHandoff() {
  const fileId = route.query.fileId
  if (typeof fileId !== 'string' || !/^\d+$/.test(fileId)) {
    if (fileHandoffPending) {
      const entryPath = route.fullPath, restoreId = typeof route.query.sessionId === 'string' ? route.query.sessionId : lastSelectedId
      fileHandoffGeneration++; selectionGeneration++; fileHandoffPending = ''
      // 同页取消也必须使底层选择失效，不能留下旧详情与原草稿混用。
      session.dispose()
      if (restoreId) await chooseSession(restoreId,()=>alive && viewActive && route.fullPath === entryPath)
    }
    return false
  }
  const entryPath = route.fullPath
  if (fileHandoffPending === entryPath) return true
  const attempt = ++fileHandoffGeneration
  fileHandoffPending = ''
  if (session.sending.value || operation.busy.value || openingTerminal.value) { pageError.value = '当前操作尚未结束，请完成后重新从我的文件选择资料。'; return true }
  const fileName = typeof route.query.fileName === 'string' ? route.query.fileName : '已选资料'
  const targetId = typeof route.query.sessionId === 'string' ? route.query.sessionId : lastSelectedId
  const isCurrent = () => alive && viewActive && attempt === fileHandoffGeneration && route.fullPath === entryPath
  fileHandoffPending = entryPath
  try {
    // 文件页未指定会话时回到原对话；没有原对话则使用当前个人新草稿。
    // 新路由意图立即替代等待中的旧选择，沿用useSession的取消与代际保护；旧选择不写路由。
    if (targetId && (targetId !== session.detail.value?.id || !firstLoad) && !await chooseSession(targetId,isCurrent,false)) return true
    if (!isCurrent() || (targetId && session.detail.value?.id !== targetId)) return true
    if (!checkPermi(['paicli:session:write']) || historical.value || session.active.value) {
      pageError.value = historical.value ? '只读历史不能加入资料，请新建对话后重新选择资料。' : session.active.value ? '当前对话正在执行，请等待结束后重新选择资料。' : '当前账号没有发送要求的权限，不能加入资料。'
    } else {
      pageError.value = ''
      if (!selectedFiles.value.some(file => file.id === fileId)) selectedFiles.value = [...selectedFiles.value, {id:fileId,name:fileName}]
      if (!text.value.trim()) text.value = '分析这份资料，说明关键内容和可以继续处理的事项。'
      storeDraft()
    }
    fileHandoffPending = ''
    await router.replace({query:{...route.query,sessionId:targetId || undefined,fileId:undefined,fileName:undefined,terminalId:undefined,workspaceId:undefined}})
    return true
  } finally { if (attempt === fileHandoffGeneration) fileHandoffPending = '' }
}
watch(() => session.detail.value?.directoryContextId, id => { if (session.detail.value) personal.directoryContextId.value = id ?? '' })
watch(terminalPane, pane=>{if(pane && awaitTerminalOpen){awaitTerminalOpen = false; void pane.open()}})
watch(() => session.detail.value?.status, () => { void loadSessions() })
watch(() => [route.query.fileId, route.query.fileName, route.query.sessionId], async () => { if ((!firstLoad || fileHandoffPending) && viewActive && !await consumeFileHandoff() && typeof route.query.sessionId === 'string' && route.query.sessionId !== lastSelectedId) { const entryPath = route.fullPath; await chooseSession(route.query.sessionId,()=>alive && viewActive && route.fullPath === entryPath) } })
onMounted(async () => { await personal.prepare(); await reload(); firstLoad = false; const entryPath = route.fullPath; if (await consumeFileHandoff()) return; if (typeof route.query.sessionId === 'string') { const terminalId = initialTerminalId.value; if (!await chooseSession(route.query.sessionId,()=>alive && viewActive && route.fullPath === entryPath)) return; initialTerminalId.value = terminalId }; if (alive && viewActive && route.query.view === 'terminal') await openTerminal() })
onActivated(async () => { viewActive = true; if (!firstLoad && !await consumeFileHandoff()) { const id = typeof route.query.sessionId === 'string' ? route.query.sessionId : lastSelectedId, entryPath = route.fullPath; if (id) await chooseSession(id,()=>alive && viewActive && route.fullPath === entryPath) } }); onDeactivated(() => { viewActive = false; fileHandoffGeneration++; selectionGeneration++; fileHandoffPending = ''; storeDraft(); terminalGeneration++; openingTerminal.value = false; awaitTerminalOpen = false }); onBeforeUnmount(() => { alive = false; viewActive = false; fileHandoffGeneration++; selectionGeneration++; terminalGeneration++; awaitTerminalOpen = false })
const starters = [{title:'读懂代码',description:'了解结构、入口与业务逻辑',prompt:'分析当前目录的项目结构，说明启动入口和主要业务流程。'},{title:'实现功能',description:'从目标到文件和代码改动',prompt:'我想实现一个功能：\n预期结果：\n请先说明处理范围，再完成实现和检查。'},{title:'排查错误',description:'定位原因，修复并核对结果',prompt:'请帮助定位并修复这个问题：\n复现步骤：\n错误信息：'},{title:'审查改动',description:'检查差异、风险和测试证据',prompt:'审查当前目录的代码改动，指出有依据的问题和需要补充的检查。'}]
</script>

