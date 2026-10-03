<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiRequest, apiRequestWithMeta, getRuoYiToken, jsonBody, newOperationKey } from '@/shared/api/client'

type Turn = {
  turnId: string
  sequence: number
  state: string
  kind?: 'PRIMARY' | 'FOLLOW_UP' | 'CLARIFICATION'
  questionText?: string | null
  answerVersionId?: string | null
}
type Snapshot = {
  id: string
  state: string
  mode: 'TEXT' | 'CASCADE_VOICE'
  turns: Turn[]
  allowedCommands: string[]
  version: number
  failureCode?: string | null
  voiceSummary?: { state: string; transcriptId?: string | null } | null
  startedAt?: string | null
  completedAt?: string | null
  serverNow?: string | null
}
type Policy = { purpose: string; versionId: string; requiredForRegistration: boolean }
type Preflight = { enabled: boolean; consentRequired: boolean; supportedCodecs: string[]; maxDurationSeconds: number; maxBytes: number; unavailableReasonCode?: string | null }
type VoiceCapabilities = { configured: boolean; checks: Array<{ id: string; ready: boolean }> }
type VoiceHandle = { voiceSessionId: string; websocketPath: string; socketTicket: string; expiresAt: string; codec: string; socketGeneration: number; initialServerSequence: number; maxInFlightChunks: number; maxBufferedDurationMs: number; maxChunkBytes: number; maxDurationSeconds: number; maxBytes: number }
type TranscriptFinal = { transcriptId: string; transcriptVersionId: string; text: string; transcriptVersion: number; lowConfidenceSpans: Array<{ startInclusive: number; endExclusive: number; confidence: number }> }
type StoredTranscript = { id: string; state: string; turnId: string; version: number; latestVersion?: { id: string; text: string; lowConfidenceSpans: TranscriptFinal['lowConfidenceSpans'] } | null }
type ServerEnvelope = { type: string; sequence: number; data: Record<string, any> }
type LocalAnswer = { text: string; source: 'TEXT' | 'VOICE'; durationMs?: number }
type OperationAccepted = { operationId: string; statusUrl: string }

const route = useRoute()
const router = useRouter()
const snapshot = ref<Snapshot | null>(null)
const etag = ref('')
const answer = ref('')
const error = ref('')
const loading = ref(false)
const busy = ref(false)
const waitingForInterviewer = ref(false)
const voiceNoticeAccepted = ref(false)
const voiceReadiness = ref<'CHECKING' | 'READY' | 'UNAVAILABLE'>('CHECKING')
const voiceUnavailableReason = ref('')
const messagesElement = ref<HTMLElement | null>(null)
const localAnswers = ref<Record<string, LocalAnswer>>({})
const showTextComposer = ref(false)
const partialTranscript = ref('')
const elapsedSeconds = ref(0)
const voiceState = ref<'IDLE'|'PREPARING'|'LISTENING'|'TRANSCRIBING'|'REVIEWING'|'UNAVAILABLE'>('IDLE')
const voicePreparationMessage = ref('')
const microphoneError = ref('')
const recordedBytes = ref(0)
const transcript = ref<TranscriptFinal | null>(null)
const ttsState = ref<'IDLE'|'BUFFERING'|'PLAYING'|'FAILED'>('IDLE')
const ttsError = ref('')

let mediaStream: MediaStream | null = null
let recorder: MediaRecorder | null = null
let socket: WebSocket | null = null
let voiceHandle: VoiceHandle | null = null
let clientSequence = 0
let lastServerSequence = 0
let totalBytes = 0
let totalDuration = 0
let chunkQueue: Promise<void> = Promise.resolve()
let recordedChunks: Blob[] = []
let ttsChunks: Uint8Array[] = []
let ttsMimeType = 'audio/mpeg'
let pendingChunks = new Map<number, number>()
let serverPaused = false
let ttsAudio: HTMLAudioElement | null = null
let ttsObjectUrl: string | null = null
let questionSpeechRequest: AbortController | null = null
let deliveredTtsForNextTurn = false
const questionAudioCache = new Map<string, Blob>()
let disposed = false
let elapsedTimer: number | undefined
let voiceTimer: number | undefined
let captureGeneration = 0
let composerTurnKey = ''
let serverClockOffsetMs = 0

const interviewId = computed(() => String(route.params.interviewId ?? ''))
const orderedTurns = computed(() => [...(snapshot.value?.turns ?? [])].sort((left, right) => left.sequence - right.sequence))
const currentTurn = computed(() => snapshot.value?.state === 'IN_PROGRESS'
  ? [...orderedTurns.value].reverse().find(turn => turn.state === 'QUESTION_COMMITTED')
  : undefined)
const terminal = computed(() => ['COMPLETED', 'CANCELLED', 'FAILED_FINAL'].includes(snapshot.value?.state ?? ''))
const completedCount = computed(() => orderedTurns.value.filter(turn => ['ANSWER_CONFIRMED', 'CLOSED', 'SKIPPED'].includes(turn.state)).length)
const currentSequence = computed(() => currentTurn.value?.sequence ?? orderedTurns.value.at(-1)?.sequence ?? 0)
const lowConfidence = computed(() => transcript.value?.lowConfidenceSpans?.length ?? 0)
const voiceMode = computed(() => snapshot.value?.mode === 'CASCADE_VOICE')
const voiceReady = computed(() => !voiceMode.value || voiceReadiness.value === 'READY')
const aiStateLabel = computed(() => {
  if (ttsState.value === 'PLAYING') return 'AI 正在朗读'
  if (ttsState.value === 'BUFFERING') return 'AI 正在准备语音'
  if (waitingForInterviewer.value) return 'AI 正在生成追问'
  if (voiceState.value === 'PREPARING') return '正在准备语音回答'
  if (voiceState.value === 'LISTENING') return '正在聆听你的回答'
  if (voiceState.value === 'TRANSCRIBING') return '正在整理语音转写'
  if (voiceState.value === 'REVIEWING') return '请确认语音转写'
  if (snapshot.value?.state === 'PAUSED') return '面试已暂停'
  return '等待你的回答'
})
const outlineModules = computed(() => {
  const current = Math.max(1, currentSequence.value || 1)
  const labels = ['项目经历', 'Java 基础', '集合与并发', 'JVM', 'Spring / MySQL', '系统设计']
  return labels.map((label, index) => ({
    label,
    index: index + 1,
    done: index + 1 < current && index < completedCount.value,
    active: index + 1 === Math.min(labels.length, current),
    detail: index + 1 === Math.min(labels.length, current) ? '当前考察方向' : '按计划依次推进',
  }))
})

async function scrollToBottom() {
  await nextTick()
  if (messagesElement.value) messagesElement.value.scrollTop = messagesElement.value.scrollHeight
}

function applySnapshot(response: { data: Snapshot; etag?: string }) {
  const sampledServerTime = Date.parse(response.data.serverNow ?? '')
  if (Number.isFinite(sampledServerTime)) serverClockOffsetMs = sampledServerTime - Date.now()
  const activeTurn = response.data.state === 'IN_PROGRESS'
    ? response.data.turns.findLast(turn => turn.state === 'QUESTION_COMMITTED')
    : undefined
  const nextComposerTurnKey = activeTurn ? `${response.data.id}:${activeTurn.turnId}` : ''
  if (nextComposerTurnKey && composerTurnKey && nextComposerTurnKey !== composerTurnKey) {
    answer.value = ''
    transcript.value = null
    partialTranscript.value = ''
  }
  if (nextComposerTurnKey) composerTurnKey = nextComposerTurnKey
  snapshot.value = response.data
  etag.value = response.etag ?? `"v${response.data.version}"`
  updateElapsed()
}

function updateElapsed() {
  const startedAt = Date.parse(snapshot.value?.startedAt ?? '')
  if (!Number.isFinite(startedAt)) return
  const completedAt = Date.parse(snapshot.value?.completedAt ?? '')
  const endAt = Number.isFinite(completedAt) ? completedAt : Date.now() + serverClockOffsetMs
  elapsedSeconds.value = Math.max(0, Math.floor((endAt - startedAt) / 1000))
}

async function loadVoiceReadiness() {
  if (!voiceMode.value) return
  voiceReadiness.value = 'CHECKING'
  try {
    const capabilities = await apiRequest<VoiceCapabilities>('/voice-capabilities', { cache: 'no-store' })
    if (capabilities.configured) {
      voiceReadiness.value = 'READY'
      voiceUnavailableReason.value = ''
      return
    }
    const labels: Record<string, string> = {
      DATABASE: '语音数据表', STORAGE: '音频存储', ENCRYPTION: '敏感字段加密',
      TICKET: '连接票据', ASR: '语音识别', TTS: '语音播报',
    }
    const missing = capabilities.checks.filter(check => !check.ready)
      .map(check => labels[check.id] ?? check.id)
    voiceUnavailableReason.value = `${missing.join('、') || '语音服务'}未就绪，可用文字回答继续本场。`
  } catch {
    voiceUnavailableReason.value = '无法确认语音服务状态，可用文字回答继续本场。'
  }
  voiceReadiness.value = 'UNAVAILABLE'
  showTextComposer.value = true
}

async function load(showLoading = true) {
  if (!interviewId.value) {
    error.value = '缺少面试 ID'
    return
  }
  if (showLoading) loading.value = true
  error.value = ''
  try {
    applySnapshot(await apiRequestWithMeta<Snapshot>(`/interviews/${encodeURIComponent(interviewId.value)}`, { cache: 'no-store' }))
    await loadVoiceReadiness()
    await recoverPendingTranscript()
    await scrollToBottom()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '面试会话恢复失败'
  } finally {
    if (showLoading) loading.value = false
  }
}

async function recoverPendingTranscript() {
  const id = snapshot.value?.voiceSummary?.transcriptId
  if (!voiceMode.value || !id || !currentTurn.value || transcript.value || answer.value.trim()) return
  try {
    const saved = await apiRequest<StoredTranscript>(`/transcripts/${encodeURIComponent(id)}`, { cache: 'no-store' })
    if (saved.state !== 'ASR_FINAL' || saved.turnId !== currentTurn.value?.turnId || !saved.latestVersion) return
    transcript.value = {
      transcriptId: saved.id,
      transcriptVersionId: saved.latestVersion.id,
      text: saved.latestVersion.text,
      transcriptVersion: saved.version,
      lowConfidenceSpans: saved.latestVersion.lowConfidenceSpans ?? [],
    }
    answer.value = saved.latestVersion.text
    voiceState.value = 'REVIEWING'
    showTextComposer.value = true
  } catch { /* 转写恢复失败时保留当前题和手动回答入口。 */ }
}

async function startInterview() {
  if (!snapshot.value) return
  busy.value = true
  error.value = ''
  try {
    applySnapshot(await apiRequestWithMeta<Snapshot>(`/interviews/${snapshot.value.id}/commands/start`, {
      method: 'POST', headers: { 'If-Match': etag.value, 'Idempotency-Key': newOperationKey() },
    }))
    await scrollToBottom()
    if (voiceMode.value && voiceReady.value && voiceNoticeAccepted.value && currentTurn.value) {
      void speakCurrentQuestion()
    }
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '开始面试失败'
    // 开始状态可能已提交而首题失败；以服务端快照更新可恢复操作。
    try {
      applySnapshot(await apiRequestWithMeta<Snapshot>(`/interviews/${encodeURIComponent(interviewId.value)}`, { cache: 'no-store' }))
    } catch { /* 保留原错误，用户仍可重新加载 */ }
    error.value = message
  } finally {
    busy.value = false
  }
}

async function waitForNextTurn(previousTurnId: string) {
  waitingForInterviewer.value = true
  try {
    for (let attempt = 0; attempt < 15 && !disposed; attempt += 1) {
      await new Promise(resolve => window.setTimeout(resolve, attempt === 0 ? 500 : 900))
      const response = await apiRequestWithMeta<Snapshot>(`/interviews/${encodeURIComponent(interviewId.value)}`, { cache: 'no-store' })
      applySnapshot(response)
      const active = response.data.turns.find(turn => turn.state === 'QUESTION_COMMITTED')
      if ((active && active.turnId !== previousTurnId)
        || ['COMPLETED', 'CANCELLED', 'FAILED_FINAL'].includes(response.data.state)) {
        await scrollToBottom()
        if (active && voiceMode.value && voiceReady.value && voiceNoticeAccepted.value
          && !deliveredTtsForNextTurn) void speakCurrentQuestion()
        return
      }
    }
    error.value = 'AI 面试官仍在生成下一步。当前回答已经提交，可稍后刷新或重新进入会话恢复。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '等待 AI 面试官回复失败'
  } finally {
    waitingForInterviewer.value = false
  }
}

async function submitAnswer() {
  const activeTurn = currentTurn.value
  const value = answer.value.trim()
  if (!snapshot.value || !activeTurn || !value) return

  busy.value = true
  error.value = ''
  deliveredTtsForNextTurn = false
  const submittedAnswer: LocalAnswer = {
    text: value,
    source: transcript.value ? 'VOICE' : 'TEXT',
    durationMs: transcript.value ? totalDuration : undefined,
  }
  try {
    if (transcript.value) {
      await apiRequest(`/transcripts/${encodeURIComponent(transcript.value.transcriptId)}/commands/confirm`, {
        method: 'POST',
        headers: { 'If-Match': `"v${transcript.value.transcriptVersion}"`, 'Idempotency-Key': newOperationKey() },
        body: jsonBody({ transcriptVersionId: transcript.value.transcriptVersionId, correctedText: value, lowConfidenceAcknowledged: true }),
      })
    } else {
      await apiRequest(`/interviews/${snapshot.value.id}/answers`, {
        method: 'POST',
        headers: { 'If-Match': etag.value, 'Idempotency-Key': newOperationKey() },
        body: jsonBody({ turnId: activeTurn.turnId, turnSequence: activeTurn.sequence, text: value }),
      })
    }
    localAnswers.value = { ...localAnswers.value, [activeTurn.turnId]: submittedAnswer }
    answer.value = ''
    transcript.value = null
    partialTranscript.value = ''
    if (submittedAnswer.source === 'VOICE') showTextComposer.value = false
    // 保留当前语音 socket，让服务端把下一题 TTS 推送到同一连接。
    cleanupVoice(false)
    voiceState.value = 'IDLE'
    await scrollToBottom()
    await waitForNextTurn(activeTurn.turnId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '回答提交失败'
  } finally {
    busy.value = false
  }
}

async function command(name: string): Promise<boolean> {
  if (!snapshot.value) return false
  cancelVoice('SESSION_COMMAND')
  busy.value = true
  error.value = ''
  try {
    const response = await apiRequestWithMeta<Snapshot | OperationAccepted>(`/interviews/${snapshot.value.id}/commands/${name}`, {
      method: 'POST', headers: { 'If-Match': etag.value, 'Idempotency-Key': newOperationKey() }, body: jsonBody({}),
    })
    if ('turns' in response.data) applySnapshot({ data: response.data, etag: response.etag })
    else await load(false)
    await scrollToBottom()
    return true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '面试操作失败'
    return false
  } finally {
    busy.value = false
  }
}

async function completeAndViewFeedback() {
  if (await command('complete')) {
    await router.push(`/interviews/${encodeURIComponent(interviewId.value)}/feedback`)
  }
}

async function speakCurrentQuestion() {
  const turn = currentTurn.value
  if (!turn || !snapshot.value || !voiceMode.value || !voiceReady.value) return
  if (!voiceNoticeAccepted.value) {
    ttsError.value = '请先勾选语音使用说明，再点击朗读题目。'
    return
  }
  if (voiceState.value === 'LISTENING' || ttsState.value === 'BUFFERING') return
  ttsError.value = ''
  const request = new AbortController()
  questionSpeechRequest = request
  try {
    const cached = questionAudioCache.get(turn.turnId)
    if (cached) {
      playAudioBlob(cached, turn.turnId)
      return
    }
    ttsState.value = 'BUFFERING'
    await grantVoicePolicies()
    const token = getRuoYiToken()
    if (!token) throw new Error('登录已过期，请重新登录')
    const response = await fetch(`/api/v1/interviews/${encodeURIComponent(snapshot.value.id)}/turns/${encodeURIComponent(turn.turnId)}/question-speech`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, Accept: 'audio/ogg, audio/mpeg, audio/wav, application/json', 'X-Correlation-ID': newOperationKey() },
      cache: 'no-store',
      credentials: 'omit',
      signal: request.signal,
    })
    const contentType = (response.headers.get('Content-Type') ?? '').split(';')[0].trim().toLowerCase()
    if (contentType === 'application/json') {
      const failure = await response.json().catch(() => null) as { code?: number; msg?: string } | null
      throw new Error(failure?.code === 401 ? '朗读请求认证失败，请检查登录状态' : failure?.msg || '面试官朗读暂不可用，请阅读题目后作答')
    }
    if (!response.ok) throw new Error(response.status >= 500 ? '面试官朗读暂不可用，请阅读题目后作答' : '朗读请求未通过，请检查语音授权')
    if (!['audio/ogg', 'audio/mpeg', 'audio/wav'].includes(contentType)) throw new Error('朗读服务未返回有效音频，请重试')
    const blob = await response.blob()
    if (!blob.size || blob.size > 20 * 1024 * 1024) throw new Error('朗读音频无效')
    if (currentTurn.value?.turnId !== turn.turnId || disposed || request.signal.aborted) {
      ttsState.value = 'IDLE'
      return
    }
    questionAudioCache.clear()
    questionAudioCache.set(turn.turnId, blob)
    playAudioBlob(blob, turn.turnId)
  } catch (cause) {
    if (request.signal.aborted) { ttsState.value = 'IDLE'; return }
    ttsState.value = 'FAILED'
    ttsError.value = cause instanceof Error ? cause.message : '朗读失败，请阅读题目后作答'
  } finally {
    if (questionSpeechRequest === request) questionSpeechRequest = null
  }
}

function clearAnswer() {
  answer.value = ''
  transcript.value = null
  voiceState.value = 'IDLE'
  cleanupVoice(true)
}

async function grantVoicePolicies() {
  const policies = (await apiRequest<{ policies: Policy[] }>('/policies/current')).policies
  for (const purpose of ['VOICE_CAPTURE', 'MODEL_PROCESSING']) {
    const policy = policies.find(item => item.purpose === purpose)
    if (!policy) throw new Error(`缺少 ${purpose} 服务端政策`)
    await apiRequest(`/consents/${purpose}/grants`, {
      method: 'POST', headers: { 'Idempotency-Key': newOperationKey() },
      body: jsonBody({ policyVersionId: policy.versionId, acknowledgement: true }),
    })
  }
}

function supportedCodecs() {
  const audioContextAvailable = typeof window.AudioContext === 'function'
  const recordable = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus']
    .some(type => MediaRecorder.isTypeSupported(type))
  return audioContextAvailable && recordable ? ['audio/wav'] : []
}

function recordingCodec() {
  return ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus']
    .find(type => MediaRecorder.isTypeSupported(type))
}

async function startVoice() {
  if (!voiceReady.value) {
    error.value = voiceUnavailableReason.value
    return
  }
  if (!voiceNoticeAccepted.value || !snapshot.value || !currentTurn.value) {
    error.value = '请先确认本轮语音采集和模型转写说明。'
    return
  }
  error.value = ''
  microphoneError.value = ''
  cleanupVoice(true)
  releaseTtsAudio()
  const attempt = captureGeneration
  voiceState.value = 'PREPARING'
  voicePreparationMessage.value = '正在请求麦克风权限，请在浏览器提示中选择允许…'
  transcript.value = null
  try {
    const codecs = supportedCodecs()
    if (!codecs.length) throw new Error('当前浏览器没有可用录音格式')
    mediaStream = await acquireMicrophone(attempt)
    if (attempt !== captureGeneration || disposed) return
    voicePreparationMessage.value = '麦克风已打开，正在连接语音服务…'
    await grantVoicePolicies()
    if (attempt !== captureGeneration || disposed) return
    const preflight = await apiRequest<Preflight>(`/interviews/${snapshot.value.id}/voice-preflight`, {
      method: 'POST', body: jsonBody({ turnId: currentTurn.value.turnId, codecCandidates: codecs }),
    })
    if (attempt !== captureGeneration || disposed) return
    if (!preflight.enabled || preflight.consentRequired) throw new Error(preflight.unavailableReasonCode || '服务端语音能力不可用')
    const codec = codecs.find(item => preflight.supportedCodecs.includes(item))
    if (!codec) throw new Error('服务端不支持当前录音格式')
    voiceHandle = await apiRequest<VoiceHandle>(`/interviews/${snapshot.value.id}/voice-sessions`, {
      method: 'POST', headers: { 'Idempotency-Key': newOperationKey() },
      body: jsonBody({ turnId: currentTurn.value.turnId, codec, expectedSessionVersion: snapshot.value.version }),
    })
    if (attempt !== captureGeneration || disposed) return
    connectVoiceSocket(voiceHandle)
  } catch (cause) {
    if (attempt !== captureGeneration || disposed) return
    const message = cause instanceof Error ? cause.message : '语音准备失败'
    if (!mediaStream) {
      microphoneError.value = `麦克风未就绪：${message}。请检查浏览器权限后重试，或切换文字回答。`
      voiceState.value = 'IDLE'
      cleanupVoice(true)
      return
    }
    voiceState.value = 'UNAVAILABLE'
    error.value = `${message}，可直接输入文字回答。`
    showTextComposer.value = true
    cleanupVoice(true)
  }
}

async function acquireMicrophone(attempt: number): Promise<MediaStream> {
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持麦克风采集')
  let timedOut = false
  let timeoutId: number | undefined
  const request = navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true }, video: false,
  }).then(stream => {
    if (timedOut || attempt !== captureGeneration || disposed) stream.getTracks().forEach(track => track.stop())
    return stream
  })
  const timeout = new Promise<MediaStream>((_, reject) => {
    timeoutId = window.setTimeout(() => {
      timedOut = true
      reject(new Error('等待浏览器麦克风授权超时'))
    }, 15000)
  })
  try {
    const stream = await Promise.race([request, timeout])
    if (attempt !== captureGeneration || disposed) {
      stream.getTracks().forEach(track => track.stop())
      throw new Error('语音准备已取消')
    }
    if (!stream.getAudioTracks().some(track => track.readyState === 'live')) {
      stream.getTracks().forEach(track => track.stop())
      throw new Error('未检测到可用麦克风')
    }
    return stream
  } finally {
    window.clearTimeout(timeoutId)
  }
}

function connectVoiceSocket(handle: VoiceHandle) {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = getRuoYiToken()
  if (!token) {
    voiceState.value = 'UNAVAILABLE'
    error.value = '登录状态已失效，请重新登录。'
    cleanupVoice(true)
    return
  }
  socket = new WebSocket(`${protocol}//${location.host}${handle.websocketPath}`, ['aic.voice.v1', `ruoyi-bearer.${token}`])
  clientSequence = 0
  lastServerSequence = handle.initialServerSequence
  totalBytes = 0
  totalDuration = 0
  recordedBytes.value = 0
  pendingChunks.clear()
  serverPaused = false
  socket.onopen = () => sendVoice('client.hello', { socketTicket: handle.socketTicket, protocolVersion: 1, resumeFromServerSequence: handle.initialServerSequence, capabilities: ['BASE64_AUDIO', 'TRANSCRIPT_REVIEW'] })
  const activeSocket = socket
  socket.onmessage = event => {
    if (socket !== activeSocket) return
    try { void handleServerMessage(JSON.parse(String(event.data))).catch(() => voiceFailure('语音消息处理失败')) }
    catch { voiceFailure('语音消息格式错误') }
  }
  socket.onerror = () => { if (socket === activeSocket) voiceFailure('语音连接失败') }
  socket.onclose = event => {
    if (socket !== activeSocket || ['REVIEWING', 'UNAVAILABLE', 'IDLE'].includes(voiceState.value)) return
    const reason = /^[A-Za-z0-9_.:-]{1,96}$/.test(event.reason) ? `:${event.reason}` : ''
    voiceFailure(`语音连接已断开（${event.code}${reason}）`)
  }
  armVoiceTimeout(10000, '语音连接超时')
}

function armVoiceTimeout(milliseconds: number, message: string) {
  window.clearTimeout(voiceTimer)
  voiceTimer = window.setTimeout(() => voiceFailure(message), milliseconds)
}
function voiceFailure(message: string) {
  error.value = `${message}，可直接输入文字回答。`
  voiceState.value = 'UNAVAILABLE'
  showTextComposer.value = true
  cleanupVoice(true)
}

async function handleServerMessage(message: ServerEnvelope) {
  // resync-required 可能携带的是旧 cursor 的下一条合法消息；先处理事件语义，
  // 再做连续序列校验，避免客户端因 cursor 不可回放而误触发新的录音。
  if (message.type === 'server.resync-required') {
    error.value = '语音连接需要重新同步，请改用文字或重新录音。'
    cancelVoice('SERVER_RESYNC_REQUIRED')
    return
  }
  if (message.sequence !== lastServerSequence + 1) {
    error.value = '语音消息序号异常，请改用文字回答。'
    cancelVoice('SERVER_SEQUENCE_GAP')
    return
  }
  lastServerSequence = message.sequence
  if (message.type === 'server.hello') {
    window.clearTimeout(voiceTimer)
    if (message.data.resumeAccepted !== true) {
      error.value = '语音连接无法恢复，请改用文字或重新录音。'
      cancelVoice('SERVER_RESUME_REJECTED')
      return
    }
    try {
      await beginCapture()
    } catch (cause) {
      error.value = `${cause instanceof Error ? cause.message : '无法打开麦克风'}，可直接输入文字回答。`
      voiceState.value = 'UNAVAILABLE'
      showTextComposer.value = true
      cleanupVoice(true)
    }
  }
  else if (message.type === 'voice.turn.state' && message.data.state === 'TRANSCRIBING') voiceState.value = 'TRANSCRIBING'
  else if (message.type === 'asr.final') {
    transcript.value = message.data as TranscriptFinal
    answer.value = transcript.value.text
    partialTranscript.value = ''
    showTextComposer.value = true
    voiceState.value = 'REVIEWING'
    cleanupVoice(false)
  } else if (message.type === 'asr.partial' || message.type === 'transcript.partial') {
    partialTranscript.value = String(message.data.text ?? '')
  } else if (message.type === 'speech.failed' || message.type === 'voice.turn.degraded') {
    error.value = `语音识别失败（${message.data.reasonCode || 'UNKNOWN'}），可直接输入文字回答。`
    voiceState.value = 'UNAVAILABLE'
    showTextComposer.value = true
    cleanupVoice(false)
  } else if (message.type === 'server.nack') {
    error.value = '语音连接需要重新同步，请改用文字或重新录音。'
    cancelVoice('SERVER_RESYNC_REQUIRED')
  } else if (message.type === 'server.ack') {
    const accepted = Number(message.data.acceptedClientSequence)
    if (Number.isSafeInteger(accepted)) for (const sequence of pendingChunks.keys()) if (sequence <= accepted) pendingChunks.delete(sequence)
    if (serverPaused && pendingChunks.size < (voiceHandle?.maxInFlightChunks ?? 8)) {
      serverPaused = false
      if (recorder?.state === 'paused') recorder.resume()
    }
  } else if (message.type === 'server.flow-control') {
    serverPaused = message.data.paused === true
    if (serverPaused && recorder?.state === 'recording') recorder.pause()
    if (!serverPaused && recorder?.state === 'paused') recorder.resume()
  } else if (message.type === 'tts.state') {
    if (message.data.state === 'STARTED') {
      deliveredTtsForNextTurn = true
      ttsChunks = []
      ttsMimeType = mimeTypeForAudioCodec(String(message.data.codec ?? ''))
      ttsState.value = 'BUFFERING'
    }
    else if (message.data.state === 'COMPLETED') { if (!ttsChunks.length) ttsState.value = 'FAILED' }
    else if (message.data.state === 'FAILED') { releaseTtsAudio(); ttsState.value = 'FAILED' }
    else if (message.data.state === 'CANCELLED') { releaseTtsAudio(); ttsState.value = 'IDLE' }
  } else if (message.type === 'tts.chunk') {
    ttsChunks.push(base64ToBytes(String(message.data.bytesBase64 ?? '')))
    if (message.data.endOfOutput === true) playTts()
  }
}

async function beginCapture() {
  if (!voiceHandle) return
  if (!mediaStream?.getAudioTracks().some(track => track.readyState === 'live')) throw new Error('麦克风连接已断开，请重新开始录音')
  const generation = ++captureGeneration
  const sourceCodec = recordingCodec()
  if (!sourceCodec) throw new Error('当前浏览器没有可用录音格式')
  recordedChunks = []
  recorder = new MediaRecorder(mediaStream, { mimeType: sourceCodec })
  recorder.ondataavailable = event => {
    if (event.data.size > 0) recordedChunks.push(event.data)
  }
  recorder.onstop = () => {
    mediaStream?.getTracks().forEach(track => track.stop())
    voiceState.value = 'TRANSCRIBING'
    chunkQueue = uploadRecordedAudio(generation, sourceCodec)
      .catch(cause => voiceFailure(cause instanceof Error ? cause.message : '音频上传失败'))
  }
  recorder.onerror = () => cancelVoice('RECORDER_ERROR')
  recorder.start(250)
  voiceState.value = 'LISTENING'
}

async function uploadRecordedAudio(generation: number, sourceCodec: string) {
  if (generation !== captureGeneration || !voiceHandle) return
  if (!recordedChunks.length) throw new Error('没有采集到可用的录音')
  const encoded = await encodeMonoWav(new Blob(recordedChunks, { type: sourceCodec }))
  if (generation !== captureGeneration || !voiceHandle) return
  totalBytes = encoded.bytes.byteLength
  totalDuration = encoded.durationMs
  if (totalBytes > voiceHandle.maxBytes || totalDuration > voiceHandle.maxDurationSeconds * 1000) {
    throw new Error('录音超过服务端时长或大小限制')
  }
  recordedBytes.value = totalBytes
  sendVoice('client.audio.start', { codec: voiceHandle.codec, sampleRate: encoded.sampleRate, channelCount: 1 })
  const durationBoundBytes = Math.max(4 * 1024, Math.floor(
    totalBytes * Math.max(1, voiceHandle.maxBufferedDurationMs - 100) / totalDuration,
  ))
  const maximumChunk = Math.min(160 * 1024, voiceHandle.maxChunkBytes, durationBoundBytes)
  let sentBytes = 0
  let sentDuration = 0
  while (sentBytes < totalBytes) {
    if (generation !== captureGeneration || disposed) return
    const end = Math.min(totalBytes, sentBytes + maximumChunk)
    const remainingDuration = totalDuration - sentDuration
    const durationMs = end === totalBytes
      ? remainingDuration
      : Math.max(1, Math.min(voiceHandle.maxBufferedDurationMs,
          Math.floor(totalDuration * (end - sentBytes) / totalBytes)))
    const bytes = encoded.bytes.slice(sentBytes, end)
    const sequence = sendVoice('client.audio.chunk', { bytesBase64: uint8ArrayToBase64(bytes), durationMs })
    if (!sequence) throw new Error('语音连接已断开')
    pendingChunks.set(sequence, durationMs)
    await waitForChunkAck(sequence)
    sentBytes = end
    sentDuration += durationMs
  }
  sendVoice('client.audio.stop', { totalBytes, totalDurationMs: totalDuration })
  voiceState.value = 'TRANSCRIBING'
  armVoiceTimeout(50000, '语音识别超时')
}

async function waitForChunkAck(sequence: number) {
  for (let attempt = 0; attempt < 250; attempt += 1) {
    if (!pendingChunks.has(sequence)) return
    if (!socket || socket.readyState !== WebSocket.OPEN) throw new Error('语音连接已断开')
    await new Promise(resolve => window.setTimeout(resolve, 20))
  }
  throw new Error('音频分片确认超时')
}

async function encodeMonoWav(blob: Blob) {
  const context = new AudioContext()
  try {
    const decoded = await context.decodeAudioData(await blob.arrayBuffer())
    const frameCount = decoded.length
    const output = new ArrayBuffer(44 + frameCount * 2)
    const view = new DataView(output)
    writeAscii(view, 0, 'RIFF')
    view.setUint32(4, 36 + frameCount * 2, true)
    writeAscii(view, 8, 'WAVE')
    writeAscii(view, 12, 'fmt ')
    view.setUint32(16, 16, true)
    view.setUint16(20, 1, true)
    view.setUint16(22, 1, true)
    view.setUint32(24, decoded.sampleRate, true)
    view.setUint32(28, decoded.sampleRate * 2, true)
    view.setUint16(32, 2, true)
    view.setUint16(34, 16, true)
    writeAscii(view, 36, 'data')
    view.setUint32(40, frameCount * 2, true)
    const channels = Array.from({ length: decoded.numberOfChannels }, (_, index) => decoded.getChannelData(index))
    for (let frame = 0; frame < frameCount; frame += 1) {
      let sample = 0
      for (const channel of channels) sample += channel[frame] ?? 0
      sample = Math.max(-1, Math.min(1, sample / channels.length))
      view.setInt16(44 + frame * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
    }
    return {
      bytes: new Uint8Array(output),
      sampleRate: decoded.sampleRate,
      durationMs: Math.max(1, Math.round(decoded.duration * 1000)),
    }
  } finally {
    await context.close()
  }
}

function writeAscii(view: DataView, offset: number, value: string) {
  for (let index = 0; index < value.length; index += 1) view.setUint8(offset + index, value.charCodeAt(index))
}

function finishVoice() { if (recorder && recorder.state !== 'inactive') recorder.stop() }
function cancelVoice(reason = 'USER_CANCELLED_RECORDING') {
  if (socket?.readyState === WebSocket.OPEN && voiceState.value === 'LISTENING') sendVoice('client.audio.cancel', { reasonCode: reason })
  cleanupVoice(true)
  partialTranscript.value = ''
  voicePreparationMessage.value = ''
  if (voiceState.value !== 'REVIEWING') voiceState.value = 'IDLE'
}
function cancelTts() {
  questionSpeechRequest?.abort()
  questionSpeechRequest = null
  if (socket?.readyState === WebSocket.OPEN) {
    sendVoice('client.tts.cancel', { reasonCode: 'USER_CANCELLED_PLAYBACK' })
  }
  releaseTtsAudio()
  ttsState.value = 'IDLE'
}
function playTts() {
  const blob = new Blob(ttsChunks, { type: ttsMimeType })
  playAudioBlob(blob)
}
function playAudioBlob(blob: Blob, turnId?: string) {
  releaseTtsAudio()
  if (!['audio/ogg', 'audio/mpeg', 'audio/wav'].includes(blob.type.split(';')[0].trim().toLowerCase())) {
    if (turnId) questionAudioCache.delete(turnId)
    ttsState.value = 'FAILED'
    ttsError.value = '朗读音频格式无效，请重试'
    return
  }
  ttsObjectUrl = URL.createObjectURL(blob)
  const audio = new Audio(ttsObjectUrl)
  ttsAudio = audio
  const fail = (message: string, invalidAudio: boolean) => {
    if (ttsAudio !== audio) return
    if (invalidAudio && turnId) questionAudioCache.delete(turnId)
    releaseTtsAudio()
    ttsState.value = 'FAILED'
    ttsError.value = message
  }
  audio.onended = () => {
    if (ttsAudio !== audio) return
    releaseTtsAudio()
    ttsState.value = 'IDLE'
  }
  audio.onerror = () => fail('朗读音频无法播放，请重新点击“朗读题目”获取音频', true)
  ttsState.value = 'BUFFERING'
  void audio.play().then(() => {
    if (ttsAudio === audio) ttsState.value = 'PLAYING'
  }).catch((cause: unknown) => {
    const name = cause instanceof DOMException ? cause.name : ''
    if (name === 'NotAllowedError') {
      fail('浏览器拦截了自动播放，请点击“朗读题目”重试', false)
    } else if (name === 'AbortError') {
      if (ttsAudio === audio) { releaseTtsAudio(); ttsState.value = 'IDLE' }
    } else {
      fail('朗读音频无法播放，请重新点击“朗读题目”获取音频', true)
    }
  })
}
function mimeTypeForAudioCodec(codec: string) {
  const normalized = codec.trim().toLowerCase()
  if (normalized === 'mp3' || normalized === 'mpeg') return 'audio/mpeg'
  if (normalized.includes('ogg') || normalized.includes('opus')) return 'audio/ogg;codecs=opus'
  if (normalized.includes('wav')) return 'audio/wav'
  return 'application/octet-stream'
}
function releaseTtsAudio() {
  ttsAudio?.pause()
  ttsAudio = null
  if (ttsObjectUrl) URL.revokeObjectURL(ttsObjectUrl)
  ttsObjectUrl = null
  ttsChunks = []
}
function pendingBufferedDuration() {
  let total = 0
  pendingChunks.forEach(duration => { total += duration })
  return total
}
function cleanupVoice(closeSocket: boolean) {
  window.clearTimeout(voiceTimer)
  captureGeneration += 1
  if (recorder) {
    recorder.ondataavailable = null
    recorder.onstop = null
    if (recorder.state !== 'inactive') recorder.stop()
  }
  mediaStream?.getTracks().forEach(track => track.stop())
  recorder = null
  mediaStream = null
  const currentSocket = socket
  if (closeSocket && currentSocket && currentSocket.readyState <= WebSocket.OPEN) currentSocket.close(1000, 'client_cleanup')
  if (closeSocket) socket = null
  pendingChunks.clear()
  recordedChunks = []
  chunkQueue = Promise.resolve()
}
function sendVoice(type: string, data: Record<string, unknown>) {
  if (!socket || socket.readyState !== WebSocket.OPEN || !voiceHandle) return 0
  clientSequence += 1
  const sequence = clientSequence
  socket.send(JSON.stringify({ messageId: crypto.randomUUID(), type, voiceSessionId: voiceHandle.voiceSessionId, sessionId: snapshot.value?.id, turnId: currentTurn.value?.turnId, generation: voiceHandle.socketGeneration, sequence, ackSequence: lastServerSequence, occurredAt: new Date().toISOString(), schemaVersion: 1, data }))
  return sequence
}
function arrayBufferToBase64(buffer: ArrayBuffer) {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) binary += String.fromCharCode(...bytes.subarray(index, Math.min(index + 0x8000, bytes.length)))
  return btoa(binary)
}
function uint8ArrayToBase64(bytes: Uint8Array) {
  return arrayBufferToBase64(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer)
}
function base64ToBytes(value: string) {
  const binary = atob(value)
  return Uint8Array.from(binary, character => character.charCodeAt(0))
}
function formatElapsed() {
  return `${String(Math.floor(elapsedSeconds.value / 60)).padStart(2, '0')}:${String(elapsedSeconds.value % 60).padStart(2, '0')}`
}
function formatDuration(durationMs?: number) {
  if (!durationMs) return ''
  const totalSeconds = Math.max(1, Math.round(durationMs / 1000))
  return `${String(Math.floor(totalSeconds / 60)).padStart(2, '0')}:${String(totalSeconds % 60).padStart(2, '0')}`
}
function toggleVoiceDock() {
  if (voiceState.value === 'LISTENING') finishVoice()
  else if (!['PREPARING', 'TRANSCRIBING', 'REVIEWING'].includes(voiceState.value)) void startVoice()
}
function toggleTextComposer() {
  showTextComposer.value = !showTextComposer.value
  if (showTextComposer.value) cancelVoice('SWITCH_TO_TEXT')
}

onMounted(() => {
  elapsedTimer = window.setInterval(() => {
    if (snapshot.value?.startedAt) updateElapsed()
    else if (snapshot.value?.state === 'IN_PROGRESS') elapsedSeconds.value += 1
  }, 1000)
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  window.clearInterval(elapsedTimer)
  cancelVoice('COMPONENT_UNMOUNTED')
  questionSpeechRequest?.abort()
  releaseTtsAudio()
  questionAudioCache.clear()
})
</script>

<template>
  <section class="voice-workspace">
    <aside class="voice-panel outline-panel" aria-label="面试大纲">
      <div class="panel-section">
        <span class="eyebrow">INTERVIEW BRIEF</span>
        <h2>本次面试大纲</h2>
        <p class="panel-desc">本场从已发布题库按计划选题；服务可用时会根据回答补充追问。</p>
        <div class="goal-card"><b>面试主题</b><p>Java 后端工程师 · 中级<br />重点考察：集合、并发、JVM、Spring、MySQL、项目设计</p></div>
      </div>
      <div class="panel-section outline-scroll">
        <div class="label">考察大纲</div>
        <div class="outline-list">
          <div v-for="item in outlineModules" :key="item.index" class="outline-item" :class="{ active: item.active, done: item.done }">
            <div class="outline-num">{{ item.done ? '✓' : item.index }}</div>
            <div><b>{{ String(item.index).padStart(2, '0') }} · {{ item.label }}</b><span>{{ item.detail }}</span></div>
          </div>
        </div>
      </div>
      <div class="outline-tip">大纲展示本场考察方向，具体问题以会话中显示的题目为准。</div>
    </aside>

    <main class="voice-panel conversation-panel">
      <header class="conversation-header">
        <div class="topic-heading"><span class="topic-icon">◉</span><div><strong>Java 后端 · {{ voiceMode ? '语音' : '文字' }}面试</strong><small>{{ voiceMode && !voiceReady ? '语音服务未就绪 · 可用文字回答' : voiceMode ? '按计划提问 · 录音后转写' : '按计划提问 · 输入文字回答' }}</small></div></div>
        <div class="timer">{{ snapshot?.startedAt ? '距开始' : '本页计时' }} <strong>{{ formatElapsed() }}</strong></div>
      </header>
      <div ref="messagesElement" class="conversation-scroll" aria-live="polite">
        <div class="conversation-inner">
          <div v-if="loading" class="stream-state">正在恢复面试会话…</div>
          <div v-else-if="error" class="stream-error" role="alert">{{ error }}<button type="button" @click="load()">重新加载</button></div>
          <div v-if="voiceMode && voiceReadiness === 'UNAVAILABLE'" class="stream-state" role="status">{{ voiceUnavailableReason }}</div>
          <section v-if="voiceMode && currentTurn" class="interviewer-stage" aria-label="语音面试官">
            <div class="interviewer-portrait" :class="{ speaking: ttsState === 'PLAYING' }" aria-hidden="true">
              <svg viewBox="0 0 112 112" xmlns="http://www.w3.org/2000/svg"><circle cx="56" cy="56" r="54" fill="#e8efff"/><path d="M18 108c2-24 17-38 38-38s36 14 38 38" fill="#304e87"/><path d="M44 72l12 20 12-20" fill="#f5f8ff"/><path d="M52 79l4 5 4-5-4-5z" fill="#4c6fe7"/><path d="M55 83l-5 23h12l-5-23" fill="#4c6fe7"/><rect x="49" y="63" width="14" height="15" rx="6" fill="#e8ab82"/><ellipse cx="56" cy="45" rx="23" ry="27" fill="#f3bd96"/><path d="M33 47c-4-20 8-34 27-33 18 1 26 13 23 30-4-4-7-13-10-17-9 9-22 9-39 8z" fill="#243656"/><circle cx="48" cy="48" r="2" fill="#243656"/><circle cx="64" cy="48" r="2" fill="#243656"/><path d="M49 59c5 4 10 4 15 0" stroke="#a65d58" stroke-width="2" fill="none" stroke-linecap="round"/></svg>
            </div>
            <div class="interviewer-stage-copy"><strong>AI 面试官</strong><span role="status">{{ aiStateLabel }}</span><small>{{ ttsState === 'PLAYING' ? '请听完题目，再点击麦克风回答' : '题目显示在下方；点击“朗读题目”可以听语音提问' }}</small></div>
            <span v-if="ttsState === 'PLAYING'" class="stage-wave" aria-hidden="true">▂ ▆ ▃ ▇ ▄</span>
          </section>
          <article v-else class="turn ai-turn intro-turn"><span class="speaker-avatar ai-avatar">AI</span><div class="turn-body"><div class="speaker">AI 面试官</div><div class="bubble intro-bubble">{{ snapshot?.state === 'READY' ? '面试计划已经准备好。开始后会显示第一道问题。' : voiceMode && !voiceReady ? '语音服务当前未就绪，请根据显示的题目用文字回答。' : voiceMode ? '请根据题目回答。录音结束后，请检查转写内容再发送。' : '请根据题目输入并发送文字回答。' }}</div></div></article>
          <template v-for="turn in orderedTurns" :key="turn.turnId">
            <article :id="`turn-${turn.turnId}`" class="turn ai-turn"><span class="speaker-avatar ai-avatar">AI</span><div class="turn-body"><div class="speaker">AI 面试官 · {{ turn.kind === 'FOLLOW_UP' ? '根据回答追问' : voiceMode && voiceReady ? '语音提问' : '文字提问' }}</div><div class="bubble question-bubble"><div class="question-tag">{{ turn.kind === 'FOLLOW_UP' ? '↳ 根据回答追问' : '● 面试问题' }}</div><p>{{ turn.questionText ?? '问题正在生成中…' }}</p><div v-if="voiceMode && voiceReady && turn.turnId === currentTurn?.turnId" class="voice-row"><button class="mini-play" type="button" :disabled="ttsState === 'BUFFERING'" :aria-label="ttsState === 'PLAYING' ? '停止朗读题目' : '朗读题目'" @click="ttsState === 'PLAYING' ? cancelTts() : speakCurrentQuestion()">{{ ttsState === 'PLAYING' ? '■' : '▶' }}</button><span class="mini-wave" aria-hidden="true"><i v-for="bar in 7" :key="bar"></i></span><span>{{ ttsState === 'BUFFERING' ? '正在准备朗读…' : ttsState === 'PLAYING' ? '正在朗读题目' : '朗读题目' }}</span></div></div></div></article>
            <article v-if="localAnswers[turn.turnId]" class="turn user-turn"><div class="turn-body"><div class="speaker user-speaker">我 · {{ localAnswers[turn.turnId].source === 'VOICE' ? '语音回答' : '文字回答' }}</div><div class="bubble answer-bubble"><p>{{ localAnswers[turn.turnId].text }}</p><div class="voice-row answer-voice-row"><span>{{ localAnswers[turn.turnId].source === 'VOICE' ? formatDuration(localAnswers[turn.turnId].durationMs) : '已发送' }}</span><span v-if="localAnswers[turn.turnId].source === 'VOICE'" class="mini-wave" aria-hidden="true"><i v-for="bar in 7" :key="bar"></i></span></div></div></div><span class="speaker-avatar me-avatar">我</span></article>
            <div v-else-if="['ANSWER_CONFIRMED', 'CLOSED'].includes(turn.state)" class="answer-recovery-note">回答已由服务端确认；历史正文暂不在房间回显。</div>
            <div v-else-if="turn.state === 'SKIPPED'" class="answer-recovery-note">该题已跳过。</div>
          </template>
          <div v-if="voiceMode && (voiceState === 'LISTENING' || partialTranscript)" class="live-card"><div class="live-top"><span class="listen"><i class="pulse"></i>正在录音</span><span>语音模式</span></div><div class="live-text">{{ partialTranscript || '请直接回答，完成后点击“结束回答”进行转写。' }}<i class="caret"></i></div></div>
          <div v-if="waitingForInterviewer" class="thinking-state"><i></i><i></i><i></i><span>AI 面试官正在分析回答并生成下一步</span></div>
          <div v-if="snapshot?.state === 'IN_PROGRESS' && !orderedTurns.length && !error" class="stream-state">第一道题尚未生成。可在下方重试，已有面试计划会保留。</div>
          <div v-if="snapshot?.state === 'PAUSED'" class="stream-state">面试已暂停，恢复后可继续回答当前问题。</div>
          <div v-if="snapshot?.state === 'COMPLETED'" class="completion-state"><strong>面试已完成</strong><RouterLink :to="`/interviews/${interviewId}/feedback`">查看或生成面试反馈报告</RouterLink></div>
        </div>
      </div>
      <footer class="voice-dock">
        <div v-if="snapshot?.state === 'READY'" class="start-panel"><label v-if="voiceMode && voiceReady" class="voice-consent"><input v-model="voiceNoticeAccepted" type="checkbox" />同意本轮语音播报、采集和模型转写；开始后会朗读首题，麦克风仍需手动打开</label><button class="primary-action" type="button" :disabled="busy" @click="startInterview">{{ busy ? '正在开始…' : '开始面试' }}</button></div>
        <div v-else-if="snapshot?.state === 'IN_PROGRESS' && !orderedTurns.length" class="start-panel"><button class="primary-action" type="button" :disabled="busy" @click="startInterview">{{ busy ? '正在生成…' : '重试生成第一题' }}</button></div>
        <div v-else-if="currentTurn && !terminal" class="dock-content">
          <label v-if="voiceMode && voiceReady" class="voice-consent"><input v-model="voiceNoticeAccepted" type="checkbox" />我同意本轮语音播报、采集和模型转写；朗读题目不会打开麦克风</label>
          <p v-if="ttsError" class="microphone-error" role="alert">{{ ttsError }}</p>
          <div v-if="voiceMode && voiceReady && !showTextComposer && !transcript" class="voice-surface">
            <div class="dock-status"><b>{{ aiStateLabel }}</b><span>{{ voiceState === 'PREPARING' ? voicePreparationMessage : voiceState === 'LISTENING' ? '录音暂存在当前页面，结束后通过受控连接上传' : recordedBytes ? `最近录音 ${recordedBytes} bytes` : '点击麦克风开始回答' }}</span></div>
            <button class="voice-orb" type="button" :disabled="busy || waitingForInterviewer || ttsState === 'PLAYING' || ttsState === 'BUFFERING' || voiceState === 'PREPARING'" :aria-label="voiceState === 'PREPARING' ? '正在准备语音回答' : voiceState === 'LISTENING' ? '结束语音回答' : microphoneError ? '重试语音回答' : '开始语音回答'" @click="toggleVoiceDock">{{ voiceState === 'LISTENING' ? '■' : voiceState === 'PREPARING' ? '…' : ttsState === 'PLAYING' ? '🔊' : '🎙' }}</button>
            <div class="dock-actions"><button class="icon-btn" type="button" aria-label="朗读当前问题" :disabled="ttsState === 'BUFFERING'" @click="speakCurrentQuestion">▶</button><button v-if="ttsState === 'PLAYING'" class="icon-btn" type="button" aria-label="停止播放" @click="cancelTts">■</button><button v-if="voiceState === 'LISTENING'" class="end-btn" type="button" @click="finishVoice">结束回答</button><button class="icon-btn" type="button" aria-label="切换文字回答" @click="toggleTextComposer">⌨</button></div>
          </div>
          <p v-if="microphoneError && !showTextComposer" class="microphone-error" role="alert">{{ microphoneError }}</p>
          <div v-else class="composer-shell"><div v-if="transcript" class="transcript-notice"><strong>语音已转写，请检查后发送</strong><span v-if="lowConfidence">{{ lowConfidence }} 处低置信术语需要确认</span></div><textarea v-model="answer" rows="3" maxlength="30000" aria-label="面试回答" :placeholder="voiceMode && voiceReady ? '输入回答，或切回语音模式……' : '输入文字回答……'" @keydown.ctrl.enter.prevent="submitAnswer" /><div class="composer-toolbar"><div class="composer-tools"><button v-if="voiceMode && voiceReady" class="quiet-action" type="button" @click="toggleTextComposer">切回语音</button><button class="quiet-action" type="button" :disabled="busy || !answer" @click="clearAnswer">清空</button><span class="draft-label">草稿仅在当前页面，发送后保存</span></div><button class="primary-action" type="button" :disabled="busy || waitingForInterviewer || !answer.trim()" @click="submitAnswer">{{ busy ? '正在发送…' : transcript ? '确认转写并发送' : '发送' }}</button></div></div>
          <div class="compact-session-actions">
            <button v-if="snapshot?.allowedCommands?.includes('SKIP')" class="secondary-action" type="button" :disabled="busy" @click="command('skip')">跳过当前题</button>
            <button v-if="snapshot?.allowedCommands?.includes('COMPLETE')" class="danger-outline" type="button" :disabled="busy" @click="completeAndViewFeedback">结束并查看反馈</button>
          </div>
          <p class="dock-caption">{{ voiceMode && voiceReady && !showTextComposer && !transcript ? '语音优先 · 结束录音后转写，失败时可改用文字' : '发送后将成为正式面试回答。Ctrl + Enter 快速发送。' }}</p>
        </div>
      </footer>
    </main>

    <aside class="voice-panel status-panel" aria-label="面试状态">
      <header class="status-header"><strong>面试状态</strong><p>这里展示当前轮次与已经确认的回答进度。</p></header>
      <div class="status-scroll">
        <section class="state-card"><div class="label">当前考察点</div><h3>{{ outlineModules.find(item => item.active)?.label ?? '面试' }}</h3><div class="chips"><span class="chip active">第 {{ currentSequence || 1 }} 题</span><span class="chip active">{{ voiceMode ? '语音回答' : '文字回答' }}</span></div></section>
        <section class="state-card"><div class="label">本轮交互状态</div><h3>{{ aiStateLabel }}</h3><p>{{ waitingForInterviewer ? '回答已提交，正在等待服务端返回下一题。' : voiceState === 'LISTENING' ? '正在采集语音；结束录音后才会生成最终转写。' : transcript ? '请检查并确认转写；确认前它不是正式回答。' : '题目已经显示，可朗读后手动开始回答。' }}</p></section>
        <section class="state-card"><div class="label">回答进度</div><h3>已确认 {{ completedCount }} 轮</h3><p>只有服务端确认后的回答计入进度；面试评价请在结束后的报告查看。</p></section>
        <section class="next-box"><div class="label">下一步</div><b>{{ transcript ? '确认当前转写' : waitingForInterviewer ? '等待下一题' : '回答当前题目' }}</b><p>{{ transcript ? '可编辑识别结果，确认后将作为正式面试回答。' : waitingForInterviewer ? '如等待较久，可刷新页面从服务端恢复会话。' : '朗读和录音由你分别控制，也可以切换文字回答。' }}</p></section>
        <div class="note">面试过程中不展示明确评分，避免干扰回答。最终评价将在面试报告页呈现。</div>
        <section class="status-actions"><button v-if="snapshot?.allowedCommands?.includes('PAUSE')" class="secondary-action" type="button" :disabled="busy" @click="command('pause')">暂停面试</button><button v-if="snapshot?.allowedCommands?.includes('RESUME')" class="secondary-action" type="button" :disabled="busy" @click="command('resume')">继续面试</button><button v-if="snapshot?.allowedCommands?.includes('SKIP')" class="secondary-action" type="button" :disabled="busy" @click="command('skip')">跳过当前题</button><button v-if="snapshot?.allowedCommands?.includes('COMPLETE')" class="danger-outline" type="button" :disabled="busy" @click="completeAndViewFeedback">结束并查看反馈</button></section>
      </div>
      <footer class="status-footer"><span>已完成 {{ completedCount }} 个问答回合</span><span>{{ voiceMode ? '语音面试' : '文字模式' }}</span></footer>
    </aside>
  </section>
</template>

<style scoped>
.voice-workspace{display:grid;grid-template-columns:280px minmax(560px,1fr) 320px;gap:14px;height:calc(100dvh - 68px);min-height:650px;padding:14px;background:#f5f7fb;overflow:hidden}.voice-panel{min-width:0;min-height:0;border:1px solid #e6eaf1;border-radius:18px;background:#fff;box-shadow:0 16px 42px rgba(37,55,96,.08)}.outline-panel,.status-panel,.conversation-panel{display:flex;flex-direction:column;overflow:hidden}.panel-section{padding:18px}.panel-section + .panel-section{border-top:1px solid #edf0f5}.eyebrow{font-size:11px;letter-spacing:1.2px;color:#3457d5;font-weight:850}.panel-section h2{margin:7px 0 6px;font-size:18px}.panel-desc{margin:0;color:#72809a;font-size:12px;line-height:1.65}.goal-card{margin-top:14px;padding:13px;border:1px solid #e7ebf7;border-radius:14px;background:#f8faff}.goal-card b{font-size:13px}.goal-card p{margin:6px 0 0;color:#68758e;font-size:12px;line-height:1.6}.outline-scroll{flex:1;overflow:auto}.label{color:#8490a5;font-size:10.5px;letter-spacing:.7px;text-transform:uppercase}.outline-list{padding-top:4px}.outline-item{display:grid;grid-template-columns:26px 1fr;gap:9px;padding:10px 0;position:relative}.outline-item:not(:last-child):before{content:"";position:absolute;left:12px;top:34px;bottom:-7px;width:1px;background:#e3e8f0}.outline-num{width:25px;height:25px;display:grid;place-items:center;border:1px solid #e0e6ef;border-radius:8px;background:#f1f4fa;color:#71809a;font-size:11px;z-index:1}.outline-item.active .outline-num{border-color:#3457d5;color:#fff;background:#3457d5}.outline-item.done .outline-num{border-color:#c9eddf;color:#17a879;background:#eaf9f3}.outline-item b{font-size:12.5px}.outline-item span{display:block;margin-top:4px;color:#72809a;font-size:11.5px;line-height:1.45}.outline-tip{padding:15px 18px;border-top:1px solid #e7ecf6;background:#f7f9ff;color:#62708b;font-size:11.5px;line-height:1.65}.conversation-header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 18px;border-bottom:1px solid #edf0f5}.topic-heading{display:flex;align-items:center;gap:10px}.topic-icon{width:36px;height:36px;display:grid;place-items:center;border-radius:11px;color:#3457d5;background:#eef3ff}.topic-heading strong,.topic-heading small{display:block}.topic-heading strong{font-size:13px}.topic-heading small{margin-top:3px;color:#72809a;font-size:11.5px}.timer{color:#53617b;font-size:13px;font-variant-numeric:tabular-nums}.timer strong{margin-left:5px;color:#152037;font-size:16px}.conversation-scroll{flex:1;min-height:0;overflow:auto;padding:18px 22px 10px;scroll-behavior:smooth}.conversation-inner{width:min(820px,100%);margin:0 auto}.turn{display:flex;gap:10px;margin-bottom:18px}.user-turn{justify-content:flex-end}.speaker-avatar{width:34px;height:34px;display:grid;place-items:center;flex:0 0 auto;border-radius:11px;font-size:12px;font-weight:800}.ai-avatar{color:#3457d5;background:#eef3ff}.me-avatar{color:#58657d;background:#f0f2f6}.turn-body{max-width:78%;min-width:0}.speaker{margin:0 0 5px 2px;color:#8390a6;font-size:11px}.user-speaker{text-align:right;margin-right:2px}.bubble{padding:12px 14px;border:1px solid #e8ecf3;border-radius:14px;color:#263551;font-size:14px;line-height:1.78}.intro-bubble,.question-bubble{background:#fbfcff}.answer-bubble{border-color:#dfe7ff;color:#273f8d;background:#eef3ff}.bubble p{margin:0;white-space:pre-wrap}.question-tag{margin-bottom:7px;color:#3457d5;font-size:11px;font-weight:800}.voice-row{display:flex;align-items:center;gap:9px;margin-top:10px;color:#75829a;font-size:11.5px}.answer-voice-row{justify-content:flex-end}.mini-play{width:28px;height:28px;border:0;border-radius:50%;color:#3457d5;background:#edf2ff;cursor:pointer}.mini-wave{display:flex;align-items:center;gap:2px;height:20px}.mini-wave i{width:2px;border-radius:2px;background:#8ea1e9}.mini-wave i:nth-child(1){height:6px}.mini-wave i:nth-child(2){height:14px}.mini-wave i:nth-child(3){height:9px}.mini-wave i:nth-child(4){height:17px}.mini-wave i:nth-child(5){height:10px}.mini-wave i:nth-child(6){height:13px}.mini-wave i:nth-child(7){height:7px}.live-card{margin:8px 0 20px 44px;border:1px solid #dfe6f6;border-radius:16px;background:#f7f9ff;overflow:hidden}.live-top{display:flex;align-items:center;justify-content:space-between;padding:10px 13px;border-bottom:1px solid #e8ecf5;color:#6e7b93;font-size:11.5px}.listen{display:flex;align-items:center;gap:7px;color:#17a879;font-weight:700}.pulse{width:7px;height:7px;border-radius:50%;background:#17a879;animation:blink 1s infinite}.live-text{min-height:76px;padding:13px 14px 15px;color:#33415d;font-size:14px;line-height:1.85}.caret{display:inline-block;width:2px;height:16px;margin-left:2px;background:#3457d5;vertical-align:-3px;animation:blink .8s infinite}@keyframes blink{50%{opacity:.25}}.answer-recovery-note{margin:-8px 0 20px 44px;color:#8a93a4;font-size:12px}.thinking-state{display:flex;align-items:center;gap:4px;margin:0 0 24px 44px;color:#697386}.thinking-state i{width:6px;height:6px;border-radius:50%;background:#98a2b3;animation:thinking 1.1s infinite}.thinking-state i:nth-child(2){animation-delay:.12s}.thinking-state i:nth-child(3){animation-delay:.24s}.thinking-state span{margin-left:5px;font-size:13px}@keyframes thinking{50%{transform:translateY(-3px);opacity:.55}}.stream-state,.stream-error,.completion-state{margin:0 0 22px 44px;padding:13px;border-radius:10px}.stream-state{color:#53617a;background:#f0f2f6}.stream-error{color:#a72d27;background:#fff1f0}.stream-error button{margin-left:8px;border:0;color:inherit;background:transparent;text-decoration:underline}.completion-state{display:grid;gap:3px;color:#166534;background:#ecfdf3}.voice-dock{padding:12px 18px 16px;border-top:1px solid #edf0f5;background:#fff}.dock-content,.start-panel{width:min(860px,100%);margin:0 auto}.voice-consent{display:flex;align-items:flex-start;gap:8px;padding:0 3px 9px;color:#526078;font-size:12px}.voice-consent input{width:auto;margin-top:3px}.voice-surface{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;height:94px;padding:0 18px;border:1px solid #e3e9fb;border-radius:18px;background:#f3f6ff}.dock-status{color:#62708a;font-size:12px}.dock-status b{display:block;margin-bottom:5px;color:#18233a;font-size:13px}.voice-orb{position:relative;width:64px;height:64px;border:0;border-radius:50%;color:#fff;background:linear-gradient(135deg,#3157d9,#7e91f4);box-shadow:0 0 0 10px rgba(69,94,218,.08),0 13px 28px rgba(49,87,217,.24);font-size:22px;cursor:pointer}.voice-orb:after{content:"";position:absolute;inset:-9px;border:1px solid rgba(49,87,217,.2);border-radius:50%;animation:ring 2s infinite}@keyframes ring{0%{transform:scale(.9);opacity:.9}70%{transform:scale(1.16);opacity:0}100%{opacity:0}}.voice-orb:disabled{cursor:not-allowed;opacity:.55}.dock-actions{display:flex;justify-content:flex-end;align-items:center;gap:8px}.icon-btn{width:38px;height:38px;border:1px solid #e0e6ef;border-radius:11px;color:#65728b;background:#fff;cursor:pointer}.end-btn{padding:10px 13px;border:0;border-radius:11px;color:#ee6670;background:#fff1f2;font-size:12px;font-weight:700;cursor:pointer}.primary-action{min-height:42px;padding:10px 18px;border:0;border-radius:11px;color:#fff;background:#3457d5;font-weight:750;cursor:pointer}.primary-action:disabled{cursor:not-allowed;opacity:.55}.composer-shell{padding:10px;border:1px solid #d8dde7;border-radius:18px;background:#fff;box-shadow:0 10px 30px rgba(23,32,51,.09)}.composer-shell textarea{min-height:64px;max-height:160px;padding:8px 10px;border:0;background:transparent;resize:none}.composer-shell textarea:focus{outline:0}.composer-toolbar{display:flex;align-items:flex-end;justify-content:space-between;gap:10px}.composer-tools{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.quiet-action{min-height:38px;padding:8px 11px;border:1px solid transparent;border-radius:9px;color:#697386;background:transparent;cursor:pointer}.quiet-action:hover{color:#172033;background:#f5f6f8}.draft-label{padding:4px 9px;border-radius:999px;color:#697386;background:#eff1f5;font-size:12px}.transcript-notice{display:flex;justify-content:space-between;gap:8px;padding:3px 9px 0;font-size:12px}.transcript-notice span{color:#a46016}.dock-caption{margin:8px 0 0;color:#8a96aa;text-align:center;font-size:11px}.status-header{padding:17px 18px;border-bottom:1px solid #edf0f5}.status-header strong{font-size:15px}.status-header p{margin:5px 0 0;color:#72809a;font-size:11.5px;line-height:1.5}.status-scroll{min-height:0;overflow:auto;padding:14px 16px 18px}.state-card{padding:13px;margin-bottom:12px;border:1px solid #e8ecf6;border-radius:14px;background:#f8faff}.state-card h3{margin:6px 0;font-size:13px}.state-card p{margin:0;color:#6f7c93;font-size:11.5px;line-height:1.55}.chips{display:flex;flex-wrap:wrap;gap:7px;margin-top:9px}.chip{padding:5px 8px;border:1px solid #e2e7ef;border-radius:999px;color:#65728a;background:#fff;font-size:10.5px}.chip.active{border-color:#dbe4ff;color:#3457d5;background:#eef3ff}.meter{margin-top:8px}.meter-row{display:flex;justify-content:space-between;margin-bottom:6px;color:#77849a;font-size:11px}.meter-bar{height:6px;border-radius:8px;background:#e9edf3;overflow:hidden}.meter-bar i{display:block;height:100%;border-radius:8px;background:linear-gradient(90deg,#4968dd,#8194ef)}.next-box{padding:13px;margin-top:12px;border:1px dashed #d6deef;border-radius:14px;background:#fcfdff}.next-box b{font-size:12.5px}.next-box p{margin:6px 0 0;color:#6f7c93;font-size:11.5px;line-height:1.55}.note{padding:12px;margin-top:12px;border:1px solid #f6e4c2;border-radius:13px;color:#7f6847;background:#fffaf1;font-size:11.5px;line-height:1.6}.status-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}.secondary-action,.danger-outline{padding:9px 12px;border-radius:10px;background:#fff;cursor:pointer}.secondary-action{border:1px solid #cad3ea;color:#25365f}.danger-outline{border:1px solid #e8b8be;color:#ad303b}.status-footer{display:flex;justify-content:space-between;padding:13px 16px;border-top:1px solid #edf0f5;color:#8b96aa;font-size:11px}@media(max-width:1100px){.voice-workspace{grid-template-columns:240px 1fr}.status-panel{display:none}}@media(max-width:760px){.voice-workspace{display:block;height:auto;min-height:calc(100dvh - 68px);padding:8px;overflow:visible}.outline-panel{display:none}.conversation-panel{min-height:calc(100dvh - 84px)}.conversation-header{padding:12px 14px}.timer{font-size:11px}.conversation-scroll{padding:16px 12px}.turn-body{max-width:88%}.live-card{margin-left:0}.voice-dock{position:sticky;bottom:0;padding:9px 10px 12px}.voice-surface{grid-template-columns:1fr auto;padding:0 12px}.dock-actions{display:none}.composer-toolbar{align-items:flex-end}}@media(prefers-reduced-motion:reduce){*{animation:none!important;scroll-behavior:auto!important}}
.compact-session-actions{display:none;justify-content:flex-end;gap:8px;margin-top:9px}
.microphone-error{margin:9px 2px 0;color:#a72d27;font-size:12px;line-height:1.5}
.interviewer-stage{display:flex;align-items:center;gap:14px;margin:2px 0 12px;padding:12px 16px;border:1px solid #dce5fb;border-radius:20px;background:linear-gradient(115deg,#f4f7ff,#fff);box-shadow:0 12px 30px rgba(49,87,217,.07)}
.interviewer-portrait{width:88px;height:88px;flex:0 0 88px;border:4px solid #fff;border-radius:50%;box-shadow:0 0 0 1px #cad8fb,0 8px 22px rgba(44,78,149,.16);overflow:hidden}
.interviewer-portrait.speaking{box-shadow:0 0 0 6px rgba(56,99,220,.15),0 0 0 13px rgba(56,99,220,.07),0 8px 22px rgba(44,78,149,.16)}
.interviewer-portrait svg{display:block;width:100%;height:100%}.interviewer-stage-copy{display:grid;gap:5px;min-width:0}.interviewer-stage-copy strong{font-size:17px;color:#1d3155}.interviewer-stage-copy span{font-weight:700;color:#3457d5}.interviewer-stage-copy small{line-height:1.5;color:#65748e}.stage-wave{margin-left:auto;white-space:nowrap;color:#4a6ce3;font-size:21px;letter-spacing:2px;animation:blink 1s infinite}
@media(max-width:760px){.interviewer-stage{padding:12px;gap:12px}.interviewer-portrait{width:76px;height:76px;flex-basis:76px}.interviewer-stage-copy strong{font-size:16px}.stage-wave{display:none}}
@media(max-width:1100px){.compact-session-actions{display:flex}}
@media(max-width:760px){.compact-session-actions{justify-content:space-between}.compact-session-actions button{flex:1}}
</style>
