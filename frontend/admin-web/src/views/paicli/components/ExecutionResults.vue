<template>
  <aside class="pc-panel pc-results" aria-label="执行成果"><h2>此对话的成果与检查</h2><el-tabs v-model="tab"><el-tab-pane label="文件" name="files"><div v-for="artifact in artifacts" :key="artifact.id" class="pc-result-item"><strong>{{artifact.fileName}}</strong><p class="pc-muted">{{artifact.size}} B · {{artifact.purpose}}</p><el-button v-hasPermi="['paicli:artifact:read']" link :loading="busy" @click="download(artifact.id,artifact.fileName)">下载文件</el-button><ArtifactText :artifact-id="artifact.id" :session-id="sessionId" /></div><el-empty v-if="!artifacts.length" description="暂无实际文件产物" :image-size="50" /></el-tab-pane><el-tab-pane label="代码差异" name="diffs"><div v-for="diff in evidence.diffs" :key="diff.id"><strong>{{diff.name}}</strong><pre v-if="diff.diff" class="pc-pre">{{safeText(diff.diff)}}</pre><template v-if="diff.diffArtifact"><p class="pc-muted">完整差异保存在实际产物中，点击后分页读取原文。</p><ArtifactText :artifact-id="diff.diffArtifact.artifactId" :session-id="sessionId" :expected-sha256="diff.diffArtifact.sha256" :expected-size="diff.diffArtifact.size" /></template><el-alert v-if="diff.unavailable" :title="`未取得文本差异：${diff.unavailable}`" type="info" :closable="false" /></div><el-empty v-if="!evidence.diffs.length" description="执行回执尚未提供实际差异" :image-size="50" /></el-tab-pane><el-tab-pane label="实际检查" name="checks"><p class="pc-muted">这里展示命令实际回执。执行结束与业务验收通过分别核对。</p><div v-for="check in evidence.checks" :key="check.id" class="pc-result-item"><strong>{{check.name}}</strong><p>{{stateName(check.status)}}<span v-if="check.exitCode !== undefined"> · 退出码 {{check.exitCode}}</span></p><pre class="pc-pre">{{safeText(check.output)}}</pre><ArtifactEvidence :artifact-id="check.artifactId" :session-id="sessionId" /></div><el-empty v-if="!evidence.checks.length" description="尚未运行或尚无检查回执" :image-size="50" /></el-tab-pane></el-tabs><el-alert v-if="error" :title="error" type="error" :closable="false" /></aside>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PcContext, PcToolCall } from '@/types/paicli'
import { checkPermi } from '@/utils/permission'
import { downloadArtifact } from '@/api/paicli/sessions'
import ArtifactText from './ArtifactText.vue'
import ArtifactEvidence from './ArtifactEvidence.vue'
import { executionEvidence } from '../shared/executionEvidence'
import { stateName, safeText, saveDownload } from '../model'
import { useOperations } from '../useOperations'
const props = defineProps<{sessionId:string; artifacts:PcContext['artifacts']; tools:PcToolCall[]}>(), tab = ref('files'), evidence = computed(() => executionEvidence(props.tools)), {busy,error,run} = useOperations()
async function download(id:string,name:string) { if (!checkPermi(['paicli:artifact:read'])) return; await run(async()=>saveDownload(await downloadArtifact(id),name.replace(/[\\/\u0000-\u001f]/g,'_'))) }
</script>
<style scoped>.pc-results{position:sticky;top:12px;align-self:start;max-height:calc(100vh - 170px);overflow:auto}.pc-result-item{padding:12px 0;border-bottom:1px solid var(--el-border-color-light)}</style>
