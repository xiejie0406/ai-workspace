<template>
  <section class="pc-panel pc-web-terminal" aria-label="原 PaiCLI 网页终端">
    <div class="pc-toolbar">
      <label for="pc-terminal-renderer">原终端界面</label>
      <el-select id="pc-terminal-renderer" v-model="renderer" :disabled="activeInstance" style="width:140px"><el-option label="inline" value="inline" /><el-option label="Lanterna 全屏" value="lanterna" /><el-option label="plain" value="plain" /></el-select>
      <el-button v-hasPermi="['paicli:terminal:use']" type="primary" :loading="stream.busy.value || initializing" :disabled="disabled || !sessionId" @click="open">打开终端</el-button>
      <el-button v-hasPermi="['paicli:terminal:read']" :disabled="!activeInstance || stream.connected.value" @click="stream.connect">重新连接</el-button>
      <el-button v-hasPermi="['paicli:terminal:use']" :disabled="!activeInstance || stream.canWrite.value || stream.busy.value" @click="claim">接管输入</el-button>
      <el-button :disabled="!terminal" @click="copy">复制选中文本</el-button>
      <el-button :disabled="!stream.writable.value" @click="stream.input('\u0003')">中断 Ctrl+C</el-button>
      <el-button v-hasPermi="['paicli:session:export']" :loading="exporting" :disabled="!sessionId" @click="downloadEvidence">下载完整会话证据</el-button>
      <el-dropdown :disabled="!inputAvailable" @command="sendShortcut"><el-button :disabled="!inputAvailable">发送快捷键</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="key in shortcuts" :key="key.name" :command="key.data">{{ key.name }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
      <el-button v-hasPermi="['paicli:artifact:write']" :disabled="!stream.writable.value || attaching || images?.uploading || !supportsImages" @click="pasteClipboard">粘贴图片</el-button>
      <el-button v-hasPermi="['paicli:artifact:write']" :disabled="!stream.writable.value || attaching || images?.uploading || !supportsImages" @click="images?.pick()">上传图片</el-button>
      <el-button v-hasPermi="['paicli:terminal:use']" type="danger" :disabled="!activeInstance || stream.busy.value" @click="stopOpen = true">结束终端</el-button>
    </div>
    <el-alert v-if="stream.error.value || imageError" :title="stream.error.value || imageError" type="error" :closable="false" />
    <el-alert v-if="stream.terminal.value?.reason" :title="stream.terminal.value.reason" type="warning" :closable="false" />
    <el-alert v-if="stream.connected.value && !stream.canWrite.value && !stream.replaying.value" title="当前窗口只读。需要输入时请明确接管；其他窗口的旧控制权将失效。" type="warning" :closable="false" />
    <el-alert v-if="stream.attachmentRequest.value" title="原 CLI 正在等待本次图片。请主动粘贴/上传并确认图片，或取消；不会读取 执行机器的系统剪贴板。" type="info" :closable="false" />
    <section v-if="stream.authorizationRequest.value" :key="stream.authorizationRequest.value.requestId" class="pc-panel" aria-label="原终端命令授权确认"><h3>原命令需要网页确认</h3><p>{{ stream.authorizationRequest.value.reason }}</p><pre class="pc-pre">/{{ stream.authorizationRequest.value.command }} {{ stream.authorizationRequest.value.arguments }}</pre><template v-if="stream.authorizationRequest.value.operation === 'APPROVAL_DECISION'"><p>目录 {{ stream.authorizationRequest.value.directoryContextId }} · 批准申请 {{ stream.authorizationRequest.value.approvalId }} · 版本 {{ stream.authorizationRequest.value.version }} · 原决定 {{ stream.authorizationRequest.value.decision }}</p><pre class="pc-pre">{{ safeText(stream.authorizationRequest.value.preview) }}</pre><p v-if="stream.authorizationRequest.value.feedback">原反馈：{{ stream.authorizationRequest.value.feedback }}</p><pre v-if="stream.authorizationRequest.value.revisedArguments" class="pc-pre">拟修改参数：{{ safeText(stream.authorizationRequest.value.revisedArguments) }}</pre></template><template v-if="stream.authorizationRequest.value.frozenTargets"><p>本次冻结的目录与设备目标：</p><pre class="pc-pre">{{ safeText(stream.authorizationRequest.value.frozenTargets) }}</pre></template><template v-if="stream.authorizationRequest.value.operation === 'TASK_LOG' || stream.authorizationRequest.value.operation === 'TASK_CANCEL'"><p>任务 {{ stream.authorizationRequest.value.taskId }} · 会话 {{ stream.authorizationRequest.value.taskSessionId }} · 冻结版本 {{ stream.authorizationRequest.value.taskVersion }}</p><pre class="pc-pre">{{ safeText(stream.authorizationRequest.value.preview) }}</pre></template><p class="pc-muted">批准仅执行上述冻结的命令；终端凭据不会获得其他目录的通用权限。实际范围继续由若依身份与业务权限核对。</p><el-button v-hasPermi="[authorizationPermission]" type="danger" :loading="stream.busy.value" :disabled="!stream.writable.value" @click="authorizeCommand">批准本次命令</el-button><el-button :loading="stream.busy.value" :disabled="!stream.writable.value" @click="stream.authorize('REJECT')">拒绝</el-button></section>
    <section v-if="stream.exportArtifacts.value.length" class="pc-panel" aria-label="原 CLI 导出文件"><h3>原终端导出文件已就绪</h3><p class="pc-muted">下列文件来自原 /export 的实际产物。点击保存至当前浏览器，不自动下载。</p><div class="pc-actions"><el-button v-for="artifact in stream.exportArtifacts.value" :key="artifact.id" v-hasPermi="['paicli:artifact:read']" :loading="downloadingId === artifact.id" :disabled="!!downloadingId" @click="downloadExport(artifact.id, artifact.fileName)">保存 {{ artifact.fileName }}</el-button></div></section>
    <el-empty v-if="!stream.terminal.value" :description="disabledReason || '点击打开终端，在当前执行环境 上运行原 PaiCLI；切换网页视图不会重复启动。'" :image-size="70" />
    <div ref="canvas" v-show="!!stream.terminal.value" class="pc-terminal-canvas" @paste.capture="onPaste" @click="terminal?.focus()" />
    <div v-show="attachments.length || stream.attachmentRequest.value || images?.uploadError || images?.uploading" class="pc-terminal-images">
      <ImageAttachments ref="images" v-model="attachments" :directory-context-id="directoryContextId" :supports-images="supportsImages" :disabled="!stream.writable.value || stream.busy.value" />
      <el-button v-hasPermi="['paicli:artifact:write']" type="primary" :loading="images?.uploading || attaching" :disabled="!attachments.length || !supportsImages || images?.uploading || !stream.writable.value || !stream.attachmentRequest.value" @click="attachImages">确认本次图片并交给原 CLI</el-button>
      <el-button v-if="stream.attachmentRequest.value" :disabled="attaching" @click="cancelImages">取消本次图片请求</el-button>
      <el-button v-if="images?.uploadError" :disabled="images?.uploading || attaching" @click="discardFailedImage">确认放弃失败图片并继续</el-button>
      <p class="pc-muted">图片上传后只在当前原输入追加 @clipboard，不追加 Enter。请在原终端提交当前输入，再在此确认同一批图片；此处不另发任务。移除图片后请同时清理原输入中的引用，失败时保留图片。</p>
    </div>
    <p class="pc-muted" role="status">{{ statusLabel }}<template v-if="stream.terminal.value"> · {{ stream.terminal.value.renderer }} · {{ dimensions.cols }} × {{ dimensions.rows }} · 已确认输出 {{ stream.outputSeq.value }} · 未确认输入 {{ stream.unconfirmedInputs.value }}</template></p>
    <p v-if="exportProgress" class="pc-muted" role="status">{{ exportProgress }}</p>
    <p class="pc-muted">Ctrl+C 始终交原 CLI。复制请使用“复制选中文本”或 Ctrl+Shift+C；Ctrl+O/P 等浏览器冲突键可通过“发送快捷键”送入。刷新/离开只分离窗口，后台任务继续按中心状态运行。</p>
    <el-dialog v-model="pasteOpen" title="确认多行终端粘贴" width="650px"><p>保留原换行并按原终端粘贴模式发送，不追加 Enter。若 CLI 当前处于确认状态，请先核对内容。</p><el-input :model-value="pasteText" type="textarea" :rows="8" readonly /><template #footer><el-button @click="pasteOpen=false">取消</el-button><el-button type="primary" :disabled="!inputAvailable" @click="confirmPaste">发送原粘贴内容</el-button></template></el-dialog>
    <el-dialog v-model="stopOpen" title="结束原 CLI 终端" width="600px"><p>将停止此终端的 CLI 进程，保留编码会话、记录及后台任务；不停止 Runner 或共享服务。</p><el-checkbox v-if="activeTask" v-model="cancelOnStop" :disabled="!checkPermi(['paicli:task:cancel'])">另外请求取消当前编码任务（已发生的副作用须核对）</el-checkbox><template #footer><el-button @click="stopOpen=false">返回终端</el-button><el-button type="danger" :loading="stream.busy.value" @click="stop">确认结束终端</el-button></template></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Terminal, IDisposable } from '@xterm/xterm'
import type { FitAddon } from '@xterm/addon-fit'
import type { PcAttachment } from '@/types/paicli'
import type { PcTerminalRenderer } from '@/types/paicli-terminal'
import { checkPermi } from '@/utils/permission'
import ImageAttachments from './ImageAttachments.vue'
import { useTerminal } from '../useTerminal'
import { errorText, safeText, saveDownload } from '../model'
import { completeSessionExport } from '../shared/sessionExport'
import { downloadArtifact } from '@/api/paicli/sessions'

const props = defineProps<{ sessionId: string; directoryContextId: string; supportsImages: boolean; disabled?: boolean; disabledReason?: string; activeTask?: boolean; visible?: boolean; initialTerminalId?: string; cancelTask: () => Promise<boolean> }>()
const emit = defineEmits<{ opened: [id: string]; refresh: [] }>()
const canvas = ref<HTMLElement>(), terminal = shallowRef<Terminal>(), images = ref<InstanceType<typeof ImageAttachments>>(), renderer = ref<PcTerminalRenderer>('inline'), initializing = ref(false), dimensions = ref({ cols: 100, rows: 30 }), attachments = ref<PcAttachment[]>([]), imageError = ref(''), attaching = ref(false), pasteOpen = ref(false), pasteText = ref(''), stopOpen = ref(false), cancelOnStop = ref(false), exporting = ref(false), exportProgress = ref('')
let exportController: AbortController | undefined
const downloadingId = ref('')
let fit: FitAddon | undefined, observer: ResizeObserver | undefined, listeners: IDisposable[] = [], resizeTimer: ReturnType<typeof setTimeout> | undefined, initPromise: Promise<void> | undefined, alive = true, imagePlaceholderAdded = false
const stream = useTerminal({ reset: () => terminal.value?.reset(), write: data => new Promise<void>(resolve => { if (terminal.value) terminal.value.write(data, resolve); else resolve() }) })
const activeInstance = computed(() => !!stream.terminal.value && !['EXITED','FAILED','LOST'].includes(stream.terminal.value.state)), inputAvailable = computed(() => stream.writable.value && !attaching.value && !images.value?.uploading && !images.value?.uploadError && !stream.attachmentRequest.value && !stream.authorizationRequest.value)
const authorizationPermission = computed(() => ({ APPROVAL_DECISION:'paicli:approval:decide', SNAPSHOT_CLEAN_ALL:'paicli:snapshot:restore', TASK_LOG:'paicli:task:read', TASK_CANCEL:'paicli:task:cancel' } as Record<string,string>)[stream.authorizationRequest.value?.operation || ''] || 'paicli:terminal:unsupported')
const statusLabel = computed(() => stream.replaying.value ? '正在恢复完整终端画面，暂不接受输入' : !stream.terminal.value ? '尚未打开' : stream.connected.value ? stream.terminal.value.state === 'RUNNING' ? stream.canWrite.value ? '已连接，可输入' : '已连接，只读' : ({ STARTING:'正在启动原 CLI', STOPPING:'正在结束', EXITED:'原 CLI 已退出', FAILED:'终端启动/执行失败', LOST:'原终端进程已丢失' } as Record<string,string>)[stream.terminal.value.state] || stream.terminal.value.state : '窗口已分离')
const shortcuts = [
  { name:'Enter',data:'\r' }, { name:'ESC',data:'\u001b' }, { name:'Tab',data:'\t' }, { name:'Shift+Tab',data:'\u001b[Z' },
  { name:'Ctrl+C · 中断',data:'\u0003' }, { name:'Ctrl+D · 退出',data:'\u0004' }, { name:'Ctrl+O · 展开/折叠',data:'\u000f' },
  { name:'Ctrl+P · 历史',data:'\u0010' }, { name:'Ctrl+\\ · 文件树',data:'\u001c' }, { name:'Ctrl+K · 清理输入',data:'\u000b' },
  { name:'Ctrl+L · 重绘',data:'\u000c' }, { name:'Ctrl+R · 搜索历史',data:'\u0012' }, { name:'Ctrl+V · 原图片请求',data:'\u0016' },
  { name:'I · 计划补充修订',data:'I' }, { name:'↑',data:'\u001b[A' }, { name:'↓',data:'\u001b[B' }, { name:'←',data:'\u001b[D' }, { name:'→',data:'\u001b[C' }
]
async function initialize() {
  if (terminal.value) return
  if (initPromise) return initPromise
  initializing.value = true
  initPromise = (async () => {
    const [{ Terminal: Xterm }, { FitAddon: Fit }, _css] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit'), import('@xterm/xterm/css/xterm.css')])
    await nextTick(); if (!alive || !canvas.value) return
    const value = new Xterm({ cols:100, rows:30, cursorBlink:true, fontSize:14, fontFamily:'Consolas, "Microsoft YaHei", monospace', scrollback:5000, screenReaderMode:true, disableStdin:true, theme:{background:'#101820',foreground:'#e7edf4'}, linkHandler:{ allowNonHttpProtocols:false, activate: (_event, uri) => { try { const target = new URL(uri); if (['http:','https:'].includes(target.protocol)) window.open(target.href,'_blank','noopener,noreferrer') } catch { imageError.value = '终端链接格式无效，未打开。' } } } })
    terminal.value = value; fit = new Fit(); value.loadAddon(fit); value.open(canvas.value)
    // 原 CLI Ctrl+C 不能被“存在选区时复制”改写；仅显式组合键复制。
    value.attachCustomKeyEventHandler(event => {
      if (event.isComposing) return true
      if (event.type === 'keydown' && (event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'c') { event.preventDefault(); void copy(); return false }
      if (event.type === 'keydown' && event.ctrlKey && !event.shiftKey && event.key.toLowerCase() === 'c') { event.preventDefault(); if (stream.writable.value) stream.input('\u0003'); return false }
      if (event.type === 'keydown' && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') return false // 由原生 paste 事件处理文本/图片，不抢宿主剪贴板。
      return inputAvailable.value
    })
    listeners = [value.onData(data => { if (inputAvailable.value) stream.input(data) }), value.onBinary(data => { if (inputAvailable.value && Array.from(data).every(char => char.charCodeAt(0) <= 255)) stream.input(data, true) }), value.onResize(size => { dimensions.value = size; stream.resize(size.cols, size.rows) }), value.parser.registerOscHandler(52, () => true)]
    observer = new ResizeObserver(scheduleFit); observer.observe(canvas.value); scheduleFit()
  })().finally(() => { initializing.value = false; initPromise = undefined })
  return initPromise
}
function scheduleFit() { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => { if (!canvas.value?.offsetWidth || !canvas.value?.offsetHeight) return; const size = fit?.proposeDimensions(); if (size && terminal.value) { terminal.value.resize(Math.max(20, Math.min(400, size.cols)), Math.max(5, Math.min(120, size.rows))); stream.resize(terminal.value.cols, terminal.value.rows) } }, 100) }
async function open() {
  if (!props.sessionId || props.disabled || !checkPermi(['paicli:terminal:use'])) return
  try { await initialize(); await stream.open(props.sessionId, renderer.value, dimensions.value.cols, dimensions.value.rows, props.initialTerminalId); if (stream.terminal.value) { emit('opened', stream.terminal.value.terminalId); await nextTick(); scheduleFit(); terminal.value?.focus() } } catch(cause) { stream.error.value = errorText(cause) }
}
async function copy() { const selected = terminal.value?.getSelection(); if (!selected) { ElMessage.info('请先在终端中选择需要复制的文本。'); return }; try { await navigator.clipboard.writeText(selected); ElMessage.success('已复制选中文本。') } catch(cause) { imageError.value = `复制被浏览器拒绝，请核对站点剪贴板权限：${errorText(cause)}` } }
async function downloadEvidence() { if (exporting.value || !checkPermi(['paicli:session:export'])) return; const id = props.sessionId, controller = new AbortController(); exportController = controller; exporting.value = true; imageError.value = ''; try { const blob = await completeSessionExport(id, controller.signal, message => { if (!controller.signal.aborted) exportProgress.value = message }); if (!controller.signal.aborted && id === props.sessionId) await saveDownload(blob, `paicli-session-${id}.json`) } catch(cause) { if (!controller.signal.aborted) imageError.value = errorText(cause) } finally { exporting.value = false } }
async function downloadExport(id: string, fileName: string) { if (downloadingId.value || !checkPermi(['paicli:artifact:read'])) return; const sessionId = props.sessionId; downloadingId.value = id; imageError.value = ''; try { const blob = await downloadArtifact(id); if (sessionId === props.sessionId) await saveDownload(blob, fileName.replace(/[\\/\u0000-\u001f]/g,'_')) } catch(cause) { if (sessionId === props.sessionId) imageError.value = errorText(cause) } finally { downloadingId.value = '' } }
function sendShortcut(data: string) { if (inputAvailable.value && stream.input(data)) terminal.value?.focus() }
function onPaste(event: ClipboardEvent) {
  if (!stream.writable.value || stream.replaying.value) { event.preventDefault(); event.stopPropagation(); return }
  const files = Array.from(event.clipboardData?.files ?? []).filter(file => file.type.startsWith('image/'))
  if (files.length) { event.preventDefault(); event.stopPropagation(); void uploadFiles(files); return }
  const text = event.clipboardData?.getData('text/plain') || ''; if (!text) return
  event.preventDefault(); event.stopPropagation(); if (!inputAvailable.value) { imageError.value = '请先处理当前图片请求，再粘贴文字。'; return }
  if (new TextEncoder().encode(text).length > 16000) { imageError.value = '粘贴内容超过 16KB，请分段粘贴或使用授权路径引用。'; return }
  if (/[\r\n]/.test(text)) { pasteText.value = text; pasteOpen.value = true } else terminal.value?.paste(text)
}
function confirmPaste() { if (!inputAvailable.value) return; terminal.value?.paste(pasteText.value); pasteOpen.value = false; pasteText.value = ''; terminal.value?.focus() }
async function uploadFiles(files: File[]) { imageError.value = ''; if (!props.supportsImages) { imageError.value = '当前模型不支持图片，请先切换图片模型，未发送任何内容。'; return }; if (!checkPermi(['paicli:artifact:write'])) { imageError.value = '没有图片上传权限。'; return }; await images.value?.addFiles(files) }
async function pasteClipboard() {
  imageError.value = ''
  try { if (!navigator.clipboard?.read) throw new Error('此浏览器或站点不支持读取图片，请在终端按 Ctrl+V 或上传图片。'); const entries = await navigator.clipboard.read(), files: File[] = []; for (const entry of entries) for (const type of entry.types) if (['image/png','image/jpeg'].includes(type)) { files.push(new File([await entry.getType(type)],`剪贴板-${files.length + 1}.${type === 'image/png' ? 'png' : 'jpg'}`,{type})); break }; if (!files.length) throw new Error('剪贴板中没有 PNG/JPEG 图片。'); await uploadFiles(files) } catch(cause) { imageError.value = errorText(cause) }
}
async function attachImages() {
  if (!attachments.value.length || images.value?.uploading || !stream.writable.value || !props.supportsImages) return
  imageError.value = ''
  if (!stream.attachmentRequest.value) { imageError.value = '请在原终端提交当前含 @clipboard 的输入，再确认图片。'; return }
  attaching.value = true
  try { if (await stream.attachments(attachments.value.map(item => item.id))) { attachments.value = []; imagePlaceholderAdded = false; terminal.value?.focus() } } finally { attaching.value = false }
}
async function cancelImages() { if (await stream.attachments([], 'CANCELLED')) { imagePlaceholderAdded = false; attachments.value = []; terminal.value?.focus() } }
async function discardFailedImage() { try { await ElMessageBox.confirm('失败的图片不会进入原 CLI。已成功上传的图片及原输入保留；需要失败图片时请重新粘贴/上传。是否继续？','确认放弃失败图片',{confirmButtonText:'确认继续',cancelButtonText:'返回重试',type:'warning'}); images.value?.clearUploadError(); terminal.value?.focus() } catch { /* 失败图片仍阻止继续输入。 */ } }
async function claim() { try { await ElMessageBox.confirm('接管后其他窗口无法继续输入，是否接管此原 CLI？','接管终端输入',{confirmButtonText:'接管输入',cancelButtonText:'保持只读',type:'warning'}); await stream.claim() } catch { /* 用户保留只读。 */ } }
async function authorizeCommand() { const request = stream.authorizationRequest.value; if (!request) return; try { await ElMessageBox.confirm(request.reason,`确认原命令 /${request.command} ${request.arguments}`,{confirmButtonText:'批准本次命令',cancelButtonText:'返回核对',type:'warning'}); await stream.authorize('APPROVE') } catch { /* 原请求保持待处理。 */ } }
async function stop() { if (cancelOnStop.value && props.activeTask && !await props.cancelTask()) return; await stream.stop(); if (!stream.error.value) { stopOpen.value = false; cancelOnStop.value = false; emit('refresh') } }
watch(() => attachments.value.length, count => { if (count && !imagePlaceholderAdded && !stream.attachmentRequest.value) { if (stream.input(' @clipboard ')) { imagePlaceholderAdded = true; terminal.value?.focus() } else imageError.value = '图片已保留，但尚未写入原终端引用；请核对连接/控制权，在原输入中自行添加 @clipboard。' } })
watch(() => inputAvailable.value, value => { if (terminal.value) terminal.value.options.disableStdin = !value })
watch(() => stream.terminal.value?.renderer, value => { if (value) renderer.value = value })
watch(() => props.sessionId, () => { stream.dispose(); exportController?.abort(); exportProgress.value = ''; attachments.value = []; imagePlaceholderAdded = false; imageError.value = ''; stopOpen.value = false; pasteOpen.value = false; cancelOnStop.value = false })
watch(() => props.visible, value => { if (value) { void nextTick(scheduleFit) } })
onMounted(initialize)
onActivated(() => { if (stream.terminal.value && !stream.connected.value && activeInstance.value) void stream.connect(); scheduleFit() })
onDeactivated(stream.detach)
onBeforeUnmount(() => { alive = false; exportController?.abort(); stream.detach(); observer?.disconnect(); clearTimeout(resizeTimer); listeners.forEach(item => item.dispose()); terminal.value?.dispose() })
defineExpose({ open })
</script>
<style scoped>
.pc-web-terminal{min-width:0}.pc-terminal-canvas{background:#101820;padding:12px;height:540px;min-height:260px;overflow:hidden}.pc-terminal-images{padding:12px 0}.pc-web-terminal :deep(.xterm){height:100%}.pc-web-terminal :deep(.xterm-viewport){overflow-y:auto}.pc-web-terminal :deep(.xterm-screen){margin:0}.pc-web-terminal .pc-toolbar{flex-wrap:wrap}@media(max-width:1100px){.pc-terminal-canvas{height:480px}}@media(prefers-reduced-motion:reduce){.pc-web-terminal :deep(.xterm-cursor-blink){animation:none}}
</style>
