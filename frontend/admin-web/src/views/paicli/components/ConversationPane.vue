<template>
  <section class="pc-panel" aria-label="会话与工具轨迹" aria-live="polite" :aria-busy="loading">
    <el-empty v-if="!messages.length" description="还没有会话消息。输入任务后查看真实执行与工具证据。" />
    <article v-for="message in messages" :key="message.id ?? message.sequence" class="message">
      <div class="pc-card-title"><strong>{{ roles[message.role] }}<span v-if="message.roleName"> · {{ message.roleName }}</span></strong><span class="pc-muted">{{ displayTime(message.createdAt) }}</span></div>
      <el-alert v-if="message.rawPromptRequired" title="原始系统提示须有独立读取权限，当前只显示记录存在。" type="info" :closable="false" /><pre v-if="message.content != null" class="pc-pre">{{ safeText(message.content) }}</pre><p v-if="message.toolCallId" class="pc-muted">原工具调用引用：{{message.toolCallId}}</p><ArtifactEvidence :result="message.contentParts" />
      <el-alert v-if="message.incomplete" type="warning" :closable="false" title="内容可能未完成，请核对事件位置与任务状态。" />
      <p v-for="image in message.attachments" :key="image.id" class="pc-muted">图片附件：{{ image.fileName }} · {{ image.width }} × {{ image.height }} · {{ image.sha256 }}</p>
      <ArtifactEvidence v-if="message.attachments?.length" :image-ids="message.attachments.map(item=>item.id)" /><el-collapse v-if="message.reasoning || message.reasoningContent"><el-collapse-item title="模型思考（非验证结果）"><pre class="pc-pre">{{ safeText(message.reasoning || message.reasoningContent) }}</pre></el-collapse-item></el-collapse>
      <div v-for="tool in messageTools(message)" :key="tool.id" class="tool">
        <div class="pc-card-title"><strong>{{ tool.toolName }}</strong><el-tag>{{ stateName(tool.status) }}</el-tag></div>
        <p v-if="!tool.status" class="pc-muted">模型提出的工具请求；尚无此消息关联的中心执行裁决，请核对工具消息与后台任务回执。</p><p v-if="tool.role" class="pc-muted">角色：{{ tool.role }}</p>
        <el-collapse><el-collapse-item :title="`参数与结果 · ${tool.id}`"><pre class="pc-pre">{{ safeText(tool.arguments) }}</pre><pre v-if="tool.result" class="pc-pre">{{ safeText(tool.result) }}</pre><el-alert v-if="tool.error" :title="safeText(tool.error)" type="error" :closable="false" /><ArtifactEvidence :result="tool.result" :artifact-id="tool.artifactId" :session-id="sessionId" /></el-collapse-item></el-collapse>
        <el-button v-if="tool.approvalId" v-hasPermi="['paicli:approval:decide']" link type="primary" @click="$emit('approval', tool.approvalId)">查看完整影响与批准</el-button>
      </div>
      <p v-if="message.usage" class="pc-muted">Token：{{ message.usage.totalTokens }} · {{ message.usage.source === 'PROVIDER' ? '供应商回执' : '估算' }}<span v-if="message.usage.cost !== undefined"> · {{ message.usage.cost }} {{ message.usage.currency }}</span></p>
    </article>
    <el-collapse v-if="toolCalls?.length"><el-collapse-item title="中心已确认的工具执行事实"><div v-for="tool in toolCalls" :key="tool.id" class="tool"><strong>{{tool.toolName}}</strong> · {{stateName(tool.status)}}<p class="pc-muted">中心调用 ID {{tool.id}}</p><pre class="pc-pre">{{safeText(tool.arguments)}}</pre><pre v-if="tool.result !== undefined || tool.error" class="pc-pre">{{safeText(tool.error || tool.result)}}</pre><ArtifactEvidence :result="tool.result" :artifact-id="tool.artifactId" :session-id="sessionId" /><el-button v-if="tool.approvalId" v-hasPermi="['paicli:approval:decide']" link @click="$emit('approval',tool.approvalId)">查看批准裁决</el-button></div></el-collapse-item></el-collapse>
    <el-collapse v-if="events.length"><el-collapse-item title="最近增量事件与角色轨迹（最多1000条）"><el-table :data="events" max-height="300"><el-table-column prop="sequence" label="序号" width="90" /><el-table-column prop="type" label="事件" width="180" /><el-table-column label="内容"><template #default="{ row }"><pre class="pc-pre">{{ safeText(row.payload) }}</pre></template></el-table-column></el-table></el-collapse-item></el-collapse>
  </section>
</template>
<script setup lang="ts">
import ArtifactEvidence from './ArtifactEvidence.vue'
import { messageTools } from '../shared/messageTools'
import type { PcMessage, PcEvent, PcToolCall } from '@/types/paicli'
import { displayTime, safeText, stateName } from '../model'
defineProps<{ messages: PcMessage[]; events: PcEvent[]; toolCalls?:PcToolCall[]; sessionId?:string; loading?: boolean }>()
defineEmits<{ approval: [id: string] }>()
const roles = { user: '用户', assistant: '编码助手', system: '系统', tool: '工具结果' }
</script>
<style scoped>.message{padding:14px 0;border-bottom:1px solid var(--el-border-color-light)}.message:first-child{padding-top:0}.tool{padding:12px;border:1px solid var(--el-border-color-light);border-radius:4px;margin:10px 0}</style>

