<template>
  <el-dialog :model-value="modelValue" title="快照与恢复核对" width="900px" class="pc-dialog" @update:model-value="$emit('update:modelValue', $event)">
    <el-alert title="文件恢复不会撤销已执行的 shell、MCP 或外部系统副作用；恢复须按当前哈希重新批准。" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <OperationTask :task-id="operationTaskId" :workspace-id="operationWorkspaceId" :title="operationKind==='PREVIEW'?'读取快照恢复差异':'恢复快照'" @settled="settled" />
    <div class="pc-actions"><el-button :loading="busy" @click="refreshSnapshots">刷新快照列表</el-button><el-button v-if="requestedSnapshotId" v-hasPermi="['paicli:snapshot:read']" :loading="busy" @click="readConfirmedPreview">读取已上报预览</el-button></div>
    <el-table v-loading="busy" :data="snapshots"><el-table-column prop="kind" label="轮次位置" width="110" /><el-table-column label="时间"><template #default="{ row }">{{ displayTime(row.createdAt) }}</template></el-table-column><el-table-column prop="description" label="说明" /><el-table-column prop="files" label="文件数" width="90" /><el-table-column label="操作" width="130"><template #default="{ row }"><el-button v-hasPermi="['paicli:snapshot:read']" link :disabled="readonly" @click="preview(row)">核对恢复差异</el-button></template></el-table-column></el-table>
    <el-empty v-if="!busy && !snapshots.length" description="没有可用快照" />
    <template v-if="restorePreview"><h3>恢复预览</h3><p class="pc-muted">当前工作区指纹 {{restorePreview.currentFingerprint}} · 快照版本 {{restorePreview.version}} · Git {{restorePreview.commitId}}</p><el-alert :title="restorePreview.warning || '请核对每个文件和当前版本。'" type="warning" :closable="false" /><pre v-if="restorePreview.conflicts.length" class="pc-pre">{{safeText(restorePreview.conflicts)}}</pre><el-table :data="restorePreview.files" max-height="260"><el-table-column prop="path" label="文件路径" /><el-table-column label="动作" width="100"><template #default="{row}">{{fileStatuses[row.status as keyof typeof fileStatuses] || row.status}}</template></el-table-column><el-table-column label="当前文件 SHA-256" min-width="220"><template #default="{row}"><span class="pc-pre">{{row.currentHash === null ? '当前不存在' : row.currentHash}}</span></template></el-table-column><el-table-column label="快照文件 SHA-256" min-width="220"><template #default="{row}"><span class="pc-pre">{{row.targetHash === null ? '快照不存在' : row.targetHash}}</span></template></el-table-column><el-table-column label="文件模式" width="110"><template #default="{row}">{{row.currentMode ?? '无'}} → {{row.targetMode ?? '无'}}</template></el-table-column></el-table><el-collapse><el-collapse-item v-for="file in restorePreview.files.filter(row=>row.diff !== undefined)" :key="file.path" :title="`${file.path} · 已上报文本差异`"><pre class="pc-pre">{{safeText(file.diff)}}</pre></el-collapse-item></el-collapse><template v-if="restorePreview.eventArtifactId"><p class="pc-muted">完整预览原文已存产物 {{restorePreview.eventArtifactId}} · {{restorePreview.eventArtifactSize}}B · {{restorePreview.eventArtifactSha256}}</p><ArtifactEvidence :artifact-id="restorePreview.eventArtifactId" :session-id="sessionId" /></template><el-checkbox v-model="confirmed" :disabled="readonly || !restorePreview.canRestore || conflicts">已核对影响，并确认仅恢复所列文件</el-checkbox><el-button v-hasPermi="['paicli:snapshot:restore']" type="danger" :loading="busy" :disabled="readonly || !confirmed || !restorePreview.canRestore || conflicts" @click="restore">提交恢复申请</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { PcSnapshot, PcRestorePreview, PcTaskDetail } from '@/types/paicli'
import { listSnapshots, previewRestore, requestRestorePreview, restoreSnapshot } from '@/api/paicli/sessions'
import { displayTime, operationKeys, safeText } from '../model'
import { useOperations } from '../useOperations'
import {checkPermi} from '@/utils/permission'
import OperationTask from './OperationTask.vue'
import ArtifactEvidence from './ArtifactEvidence.vue'
import {requireRestoreProof} from '../shared/restoreProof'
const props = defineProps<{ modelValue: boolean; sessionId: string; readonly?: boolean }>(), emit = defineEmits<{ 'update:modelValue': [value: boolean]; refresh: [] }>()
const snapshots = ref<PcSnapshot[]>([]), restorePreview = ref<PcRestorePreview>(), confirmed = ref(false), { busy, error, run } = useOperations(), keys = operationKeys()
const operationTaskId=ref(''),operationWorkspaceId=ref(''),requestedSnapshotId=ref(''),operationKind=ref<'PREVIEW'|'RESTORE'>('PREVIEW')
const fileStatuses={ADD:'新增',MODIFY:'替换',DELETE:'删除',UNCHANGED:'内容相同'}
let generation=0
const conflicts = computed(() => !Array.isArray(restorePreview.value?.conflicts) || restorePreview.value.conflicts.length>0)
async function refreshSnapshots(){if(!props.sessionId||!checkPermi(['paicli:snapshot:read']))return;const id=props.sessionId,g=++generation;await run(async()=>{const response=await listSnapshots(id);if(g===generation&&id===props.sessionId)snapshots.value=response.data})}
watch(()=>[props.modelValue,props.sessionId],async()=>{generation++;restorePreview.value=undefined;confirmed.value=false;requestedSnapshotId.value='';operationTaskId.value='';operationWorkspaceId.value='';if(props.modelValue&&props.sessionId)await refreshSnapshots()})
async function preview(snapshot:PcSnapshot){if(props.readonly||!checkPermi(['paicli:snapshot:read']))return;confirmed.value=false;restorePreview.value=undefined;const session=props.sessionId,intent={action:'snapshot-preview',id:snapshot.id,version:snapshot.version};await run(async()=>{const task=(await requestRestorePreview(snapshot.id,snapshot.version,keys.get(intent))).data;keys.done(intent);if(session!==props.sessionId||props.readonly)return;requestedSnapshotId.value=snapshot.id;operationTaskId.value=task.id;operationWorkspaceId.value=task.workspaceId;operationKind.value='PREVIEW'})}
async function readConfirmedPreview(){const id=requestedSnapshotId.value,session=props.sessionId;if(!id||!checkPermi(['paicli:snapshot:read']))return;await run(async()=>{const response=await previewRestore(id),proof=requireRestoreProof(response.data,id);if(session===props.sessionId&&requestedSnapshotId.value===id)restorePreview.value=proof})}
async function settled(task:PcTaskDetail){if(task.status!=='COMPLETED'||task.id!==operationTaskId.value)return;if(operationKind.value==='PREVIEW')await readConfirmedPreview();else{await refreshSnapshots();emit('refresh')}}
async function restore(){const current=restorePreview.value;if(props.readonly||!current||!confirmed.value||conflicts.value||!current.canRestore||!checkPermi(['paicli:snapshot:restore']))return;const proof=requireRestoreProof(current,current.snapshotId),intent={action:'restore',...proof};await run(async()=>{const task=(await restoreSnapshot(proof.snapshotId,proof,keys.get(intent))).data;keys.done(intent);operationKind.value='RESTORE';operationTaskId.value=task.id;operationWorkspaceId.value=task.workspaceId;ElMessage.success('恢复申请已提交，请按中心审批与 Runner 回执核对结果。');restorePreview.value=undefined;confirmed.value=false;emit('refresh')})}
</script>

