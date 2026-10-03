<template>
  <section class="pc-panel" aria-label="上下文、提示层与诊断">
    <h2>上下文与诊断</h2><el-empty v-if="!context" description="选择会话后查看上下文" />
    <template v-else>
      <p>{{ context.estimatedTokens.toLocaleString() }} / {{ context.contextWindow.toLocaleString() }} Token · {{ context.messages }} 条消息</p><el-progress :percentage="Math.min(100, Math.round(context.estimatedTokens / Math.max(1, context.contextWindow) * 100))" />
      <p class="pc-muted">压缩 {{ context.compressionCount }} 次；供应商 usage 与估算分别保留。</p><p v-if="context.usage">本会话用量 {{ context.usage.totalTokens }} · {{ context.usage.source === 'PROVIDER' ? '供应商回执' : '估算' }}</p>
      <el-alert v-if="context.externalMemoryPaused" title="包含外部内容，自动记忆提取已暂停。只有明确记住并核实的内容才成为正式记忆。" type="warning" :closable="false" />
      <el-alert v-if="context.compressionReason" :title="context.compressionReason" :type="context.lastCompressionStatus === 'FAILED' ? 'error' : 'info'" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <OperationTask :task-id="operationTaskId" :workspace-id="operationWorkspaceId" title="上下文压缩" @settled="emit('refresh')" />
      <div class="pc-actions"><el-button v-hasPermi="['paicli:session:write']" :disabled="active || readonly" :loading="busy" @click="action('COMPRESS')">压缩上下文</el-button><el-button v-hasPermi="['paicli:session:write']" :disabled="active || readonly" :loading="busy" @click="action('CLEAR')">清空模型上下文</el-button><el-button v-hasPermi="['paicli:session:write']" :disabled="active || readonly" :loading="busy" @click="action('CLEAR_INPUT_HISTORY')">清理输入历史</el-button><el-button v-hasPermi="['paicli:session:export']" :loading="busy" @click="exportLedger">收齐并导出会话证据</el-button><LedgerReader :session-id="context.sessionId" /></div>
      <p v-if="exportProgress" class="pc-muted" role="status">{{exportProgress}}</p><p class="pc-muted">压缩失败或 Token 未减少时保留原历史；清空模型上下文不会删除原始审计账本。活跃执行期间不可切换上下文。</p>
      <el-collapse><el-collapse-item title="提示层与规则来源"><el-button v-hasPermi="['paicli:context:raw']" :loading="busy" @click="readRaw">读取原始提示层</el-button><div v-for="layer in promptLayers" :key="layer.name"><strong>{{ layer.name }}</strong><p class="pc-muted">{{ layer.source }}</p><pre v-if="layer.content" v-hasPermi="['paicli:context:raw']" class="pc-pre">{{ safeText(layer.content) }}</pre></div><el-empty v-if="!promptLayers.length" description="暂无已上报提示层" /></el-collapse-item><el-collapse-item title="超大工具结果与产物"><el-alert v-if="context.artifactsHasMore" title="当前仅展示首批产物元数据，请分页查看完整记录；未显示不代表产物不存在。" type="info" :closable="false" /><SessionHistory :session-id="context.sessionId" initial-kind="artifacts" /><ul><li v-for="artifact in context.artifacts" :key="artifact.id">{{ artifact.fileName }} · {{ artifact.purpose }} · {{ artifact.size }}B <el-button v-hasPermi="['paicli:artifact:read']" link @click="download(artifact.id, artifact.fileName)">读回完整结果</el-button></li></ul><el-empty v-if="!context.artifacts.length" description="没有卸载结果" /></el-collapse-item><el-collapse-item title="文件诊断"><el-alert title="JavaParser 仅检查 Java 语法；其他语言需在批准后执行对应构建或检查。" type="info" :closable="false" /><div v-for="(diagnostic,index) in context.diagnostics" :key="index"><p><strong>{{ diagnostic.file }}:{{ diagnostic.line }}:{{ diagnostic.column }}</strong> · {{ diagnostic.engine }}</p><pre class="pc-pre">{{ safeText(diagnostic.message) }}</pre></div><el-empty v-if="!context.diagnostics.length" description="当前没有已上报诊断（不代表全部检查通过）" /></el-collapse-item></el-collapse>
    </template>
  </section>
</template>
<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { PcContext } from '@/types/paicli'
import { contextAction, downloadArtifact, getRawContext } from '@/api/paicli/sessions'
import { completeSessionExport } from '../shared/sessionExport'
import { checkPermi } from '@/utils/permission'
import { operationKeys, safeText, saveDownload } from '../model'
import { useOperations } from '../useOperations'
import OperationTask from './OperationTask.vue'
import LedgerReader from './LedgerReader.vue'
import SessionHistory from './SessionHistory.vue'
const props = defineProps<{ context?: PcContext; active: boolean; readonly?: boolean }>(), emit = defineEmits<{ refresh: [] }>(), { busy, error, run } = useOperations(), keys = operationKeys()
const rawContext = ref<PcContext>(), promptLayers = computed(() => rawContext.value?.promptLayers ?? props.context?.promptLayers ?? [])
const operationTaskId = ref(''), operationWorkspaceId = ref('')
const exportProgress=ref('');let exportController:AbortController|undefined
let rawGeneration = 0
watch(() => props.context?.sessionId, () => { operationTaskId.value = ''; operationWorkspaceId.value = '';exportController?.abort();exportProgress.value='' })
watch(() => `${props.context?.sessionId ?? ''}:${props.context?.version ?? ''}`, () => { rawGeneration++; rawContext.value = undefined })
async function readRaw() { const context = props.context; if (!context) return; await run(async () => { const generation = ++rawGeneration; const response = await getRawContext(context.sessionId); if (generation === rawGeneration && props.context?.sessionId === context.sessionId && props.context?.version === context.version && response.data.sessionId === context.sessionId) rawContext.value = response.data }) }
async function action(value: 'COMPRESS' | 'CLEAR' | 'CLEAR_INPUT_HISTORY') { const context = props.context; if (!context || props.active || props.readonly) return; if (value !== 'COMPRESS') { try { await ElMessageBox.confirm(value === 'CLEAR' ? '清空后模型不再使用当前会话历史，原始账本仍保留。确认继续？' : '仅清理输入历史，不清空模型上下文。确认继续？', '核对上下文操作', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }) } catch { return } }; if (props.active || props.readonly || props.context?.sessionId !== context.sessionId || props.context?.version !== context.version) return; const intent = { id: context.sessionId, version: context.version, value }; await run(async () => { const response = await contextAction(context.sessionId, value, context.version, keys.get(intent)); if('id' in response.data) { operationTaskId.value = response.data.id; operationWorkspaceId.value = response.data.workspaceId }; keys.done(intent); emit('refresh') }) }
async function exportLedger() { const id=props.context?.sessionId;if(!id||!checkPermi(['paicli:session:export']))return;exportController?.abort();const controller=new AbortController();exportController=controller;await run(async()=>{const blob=await completeSessionExport(id,controller.signal,message=>{if(!controller.signal.aborted)exportProgress.value=message});if(!controller.signal.aborted && props.context?.sessionId===id)saveDownload(blob,`paicli-session-${id}.json`)}); }
onBeforeUnmount(()=>exportController?.abort())
async function download(id: string, fileName: string) { await run(async () => saveDownload(await downloadArtifact(id), fileName)) }
</script>


