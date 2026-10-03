<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiRequest, apiRequestWithMeta, jsonBody, newOperationKey } from '@/shared/api/client'

type Plan = { id: string; planVersionNo: number; state: string; questionCount: number; estimatedUsage: { quantity: number; unit: string; estimateVersion: string }; version: number }
type Snapshot = { id: string }
type VoiceCapabilities = { configured: boolean; observedAt: string; checks: { label: string; ready: boolean }[] }

const router = useRouter()
const route = useRoute()
const routeMode = () => route.query.mode === 'voice' ? 'CASCADE_VOICE' : 'TEXT'
const form = reactive({
  targetRole: 'JAVA_BACKEND',
  targetLevel: 'MID',
  durationMinutes: 30,
  mode: routeMode(),
  topics: ['Spring Boot'],
})
const plan = ref<Plan | null>(null)
const planEtag = ref('')
const busy = ref(false)
const error = ref('')
const micChecking = ref(false)
const micStatus = ref('尚未检查麦克风。检查时只申请设备权限，不会上传或保存音频。')
const voiceMode = computed(() => form.mode === 'CASCADE_VOICE')
const voiceCapabilities = ref<VoiceCapabilities | null>(null)
const voiceChecking = ref(false)
const voiceCheckError = ref('')
let voiceRequestVersion = 0
let voiceRequestController: AbortController | undefined
const voiceServiceMessage = computed(() => {
  if (voiceChecking.value) return '正在检查语音服务准备情况…'
  if (voiceCheckError.value) return `无法确认语音服务状态：${voiceCheckError.value}`
  const status = voiceCapabilities.value
  if (!status) return '语音服务状态尚未确认，请重新检查。'
  if (status.configured) return '语音配置已就绪，实际语音链路仍需在本场验证。'
  const missing = status.checks.filter(item => !item.ready).map(item => item.label).join('、')
  return `语音服务暂不可用。${missing ? `尚未就绪：${missing}。` : ''}请联系管理员准备相关能力，或改用文字面试。`
})

async function checkVoiceCapabilities() {
  if (!voiceMode.value || voiceChecking.value) return
  const requestVersion = ++voiceRequestVersion
  const controller = new AbortController()
  voiceRequestController = controller
  const timer = window.setTimeout(() => controller.abort(), 10000)
  voiceChecking.value = true
  voiceCheckError.value = ''
  voiceCapabilities.value = null
  try {
    const status = await apiRequest<VoiceCapabilities>('/voice-capabilities', { signal: controller.signal })
    if (requestVersion === voiceRequestVersion && voiceMode.value) voiceCapabilities.value = status
  } catch (cause) {
    if (requestVersion === voiceRequestVersion && voiceMode.value) {
      voiceCheckError.value = cause instanceof Error ? cause.message : '请检查连接后重新检查'
    }
  } finally {
    window.clearTimeout(timer)
    if (requestVersion === voiceRequestVersion) voiceChecking.value = false
  }
}

watch(voiceMode, isVoice => {
  voiceRequestController?.abort()
  ++voiceRequestVersion
  voiceChecking.value = false
  voiceCapabilities.value = null
  voiceCheckError.value = ''
  if (isVoice) void checkVoiceCapabilities()
}, { immediate: true })

onBeforeUnmount(() => {
  ++voiceRequestVersion
  voiceRequestController?.abort()
})

async function checkMicrophone() {
  micChecking.value = true
  micStatus.value = '正在打开麦克风…'
  let timedOut = false
  let timeoutId: number | undefined
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持麦克风采集')
    const request = navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true }, video: false,
    }).then(stream => {
      if (timedOut) stream.getTracks().forEach(track => track.stop())
      return stream
    })
    const timeout = new Promise<MediaStream>((_, reject) => {
      timeoutId = window.setTimeout(() => {
        timedOut = true
        reject(new Error('等待浏览器麦克风授权超时，请允许后重试'))
      }, 15000)
    })
    const stream = await Promise.race([request, timeout])
    const opened = stream.getAudioTracks().some(track => track.readyState === 'live')
    stream.getTracks().forEach(track => track.stop())
    if (!opened) throw new Error('未检测到可用麦克风')
    micStatus.value = '麦克风权限已授予，设备可以打开；实际收音将在面试中检查。'
  } catch (cause) {
    micStatus.value = cause instanceof Error ? `麦克风检查失败：${cause.message}` : '麦克风检查失败，请检查浏览器权限和设备。'
  } finally {
    window.clearTimeout(timeoutId)
    micChecking.value = false
  }
}

function resetPlan() {
  plan.value = null
  planEtag.value = ''
  error.value = ''
}

watch(() => route.query.mode, () => {
  if (form.mode === routeMode()) return
  form.mode = routeMode()
  resetPlan()
})

function changeMode() {
  resetPlan()
  void router.replace({ path: '/interviews/new', query: voiceMode.value ? { mode: 'voice' } : {} })
}

async function createPlan() {
  busy.value = true
  error.value = ''
  const requestedMode = form.mode
  try {
    const response = await apiRequestWithMeta<Plan>('/interview-plans', {
      method: 'POST', body: jsonBody(form), headers: { 'Idempotency-Key': newOperationKey() },
    })
    if (form.mode === requestedMode) {
      plan.value = response.data
      planEtag.value = response.etag ?? `"v${response.data.version}"`
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '面试计划创建失败'
  } finally {
    busy.value = false
  }
}

async function confirmAndStart() {
  if (!plan.value) return
  busy.value = true
  try {
    const confirmed = await apiRequest<Plan>(`/interview-plans/${plan.value.id}/commands/confirm`, {
      method: 'POST',
      body: jsonBody({ acknowledgedEstimateVersion: plan.value.estimatedUsage.estimateVersion }),
      headers: { 'If-Match': planEtag.value || `"v${plan.value.version}"`, 'Idempotency-Key': newOperationKey() },
    })
    const snapshot = await apiRequest<Snapshot>('/interviews', {
      method: 'POST', body: jsonBody({ confirmedPlanId: confirmed.id, planVersionNo: confirmed.planVersionNo }), headers: { 'Idempotency-Key': newOperationKey() },
    })
    await router.push(`/interviews/${encodeURIComponent(snapshot.id)}`)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '面试启动失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="page-width section-page setup-layout">
    <form class="content-card setup-form" @submit.prevent="createPlan">
      <span class="eyebrow">{{ voiceMode ? 'VOICE INTERVIEW' : 'TEXT INTERVIEW' }}</span><h1>配置{{ voiceMode ? '语音' : '文字' }}面试</h1>
      <p class="muted">{{ voiceMode ? 'AI 以语音提问，你可录音回答；语音不可用时可改用文字。' : 'AI 以文字提问，你直接输入文字回答，无需麦克风。' }}</p>
      <label>目标岗位<select v-model="form.targetRole"><option value="JAVA_BACKEND">Java 后端</option><option value="AI_APPLICATION">AI 应用</option><option value="AGENT_ENGINEER">Agent 工程</option></select></label>
      <label>目标级别<select v-model="form.targetLevel"><option value="JUNIOR">初级</option><option value="MID">中级</option><option value="SENIOR">高级</option></select></label>
      <label>面试时长<input v-model.number="form.durationMinutes" type="number" min="5" max="60" /></label>
      <label>面试模式<select v-model="form.mode" :disabled="busy" @change="changeMode"><option value="TEXT">文字面试</option><option value="CASCADE_VOICE">语音面试</option></select></label>
      <section v-if="voiceMode" class="voice-service-note" aria-labelledby="voice-service-heading">
        <h2 id="voice-service-heading">语音服务准备情况</h2>
        <p role="status" aria-live="polite">{{ voiceServiceMessage }}</p>
        <p v-if="voiceCapabilities?.observedAt" class="muted">检查时间：{{ new Date(voiceCapabilities.observedAt).toLocaleTimeString('zh-CN') }}</p>
        <div class="voice-service-actions"><button type="button" class="secondary-action" :disabled="voiceChecking" @click="checkVoiceCapabilities">重新检查服务</button><RouterLink to="/interviews/new">改用文字面试</RouterLink></div>
        <p class="muted">服务检查不会请求麦克风权限或采集音频。</p>
      </section>
      <div v-if="voiceMode" class="mic-check"><button type="button" class="secondary-action" :disabled="micChecking" @click="checkMicrophone">{{ micChecking ? '正在检查…' : '检查麦克风' }}</button><p role="status" aria-live="polite">{{ micStatus }}</p></div>
      <button class="primary-button" :disabled="busy">生成面试计划</button>
      <p v-if="error" class="error-message">{{ error }}</p>
    </form>
    <article class="content-card plan-preview">
      <h2>{{ voiceMode ? '语音' : '文字' }}面试计划预览</h2>
      <div v-if="plan"><p>{{ plan.questionCount }} 道题 · {{ plan.state }}</p><p>预计用量：{{ plan.estimatedUsage.quantity }} {{ plan.estimatedUsage.unit }}</p><button class="primary-button" :disabled="busy" @click="confirmAndStart">确认并进入面试</button></div>
      <p v-else class="muted">生成后显示服务端返回的题量、用量和计划状态。</p>
    </article>
  </section>
</template>

<style scoped>
.voice-service-note { padding: 16px; border: 1px solid #e5c0aa; border-radius: 10px; background: #fff7f0; }
.voice-service-note h2 { margin: 0 0 10px; font-size: 18px; }
.voice-service-note p { margin: 8px 0; line-height: 1.6; }
.voice-service-actions { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.voice-service-actions a { color: #3156d3; text-decoration: underline; }
</style>
