<script setup lang="ts">
import {commandErrorStatus} from '@/composables/aippt/commandError'

defineOptions({name:'AipptDetail'})
import { ref, computed, onMounted,onActivated,onDeactivated,onBeforeUnmount,watch } from 'vue'
import { useRouter, useRoute, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { checkPermi } from '@/utils/permission'
import { api,idempotencyKey } from '@/api/aippt'
import type { Project, Source, Outline } from '@/types/aippt'
import { useProjectId } from '@/composables/aippt/useProjectId'
import { ensureCatalog } from '@/composables/aippt/useCatalog'
import { waitTask } from '@/composables/aippt/useTaskResult'
import {taskProgress} from '@/composables/aippt/taskProgress'
import { useTaskEvents } from '@/composables/aippt/useTaskEvents'
import useUserStore from '@/store/modules/user'
import {useAipptEditorStore} from '@/store/modules/aipptEditor'
import { addCloseGuard } from '@/plugins/tab'
import SourceList from '../components/SourceList.vue'
import OutlineEditor from '../components/OutlineEditor.vue'
const id=useProjectId(),route=useRoute(),router=useRouter(),project=ref<Project|null>(null),sources=ref<Source[]>([]),outline=ref<Outline|null>(null),error=ref(''),loading=ref(false),active=ref('sources'),outlineRef=ref<InstanceType<typeof OutlineEditor>|null>(null),sourceRef=ref<InstanceType<typeof SourceList>|null>(null)
const settings=ref<Record<string,any>>({}),settingsSnapshot=ref(''),settingsRevision=ref<number|null>(null),settingsDirty=computed(()=>!!settingsSnapshot.value&&JSON.stringify(settings.value)!==settingsSnapshot.value)
function settingsOf(p:Project){return{title:p.title,audience:p.audience??'',tone:p.tone,content_density:p.content_density,page_count:p.page_count}}
function resetSettings(p:Project){settings.value=settingsOf(p);settingsSnapshot.value=JSON.stringify(settings.value);settingsRevision.value=p.input_revision}
interface SettingsCommand {pid:string;key:string;body:Record<string,unknown>;baseline:Record<string,any>;draft:Record<string,any>}
const settingsCommand=ref<SettingsCommand|null>(null),settingsSaving=ref(false),settingsState=ref<'ready'|'unknown'|'conflict'>('ready'),settingsReading=ref(false),settingsReview=ref<Project|null>(null)
const settingsLocked=computed(()=>settingsSaving.value||!!settingsCommand.value),settingsPending=computed(()=>settingsDirty.value||!!settingsCommand.value)
const settingsKeys=['title','audience','tone','content_density','page_count'] as const
const settingLabels:Record<typeof settingsKeys[number],string>={title:'名称',audience:'受众',tone:'语气',content_density:'文字量',page_count:'目标页数'}
const reviewRows=computed(()=>settingsCommand.value&&settingsReview.value?settingsKeys.map(key=>({key,label:settingLabels[key],baseline:settingsCommand.value!.baseline[key],draft:settingsCommand.value!.draft[key],server:settingsOf(settingsReview.value!)[key],changed:settingsCommand.value!.draft[key]!==settingsCommand.value!.baseline[key]})):[])
const user=useUserStore(),store=useAipptEditorStore();let loadGeneration=0,viewGeneration=0,alive=true,subscribedOutlineJob='';const submittingOutline=ref(false)
function target(){return{pid:id.value,token:user.token,view:viewGeneration}}
function sameTarget(own:ReturnType<typeof target>){return own.pid===id.value&&own.token===user.token&&own.view===viewGeneration}
function clearSettings(){settings.value={};settingsSnapshot.value='';settingsRevision.value=null;settingsCommand.value=null;settingsReview.value=null;settingsState.value='ready';settingsSaving.value=false;settingsReading.value=false}
const parsingTasks=new Set<string>();const parseAbort=new AbortController()
function watchParsing(sources:Source[]){for(const source of sources){if(!source.job_id||source.parse_status==='ready'||source.parse_status==='failed'||parsingTasks.has(source.job_id))continue;parsingTasks.add(source.job_id);void waitTask({job_id:source.job_id,status:'queued'},parseAbort.signal).then(()=>load(false)).catch(e=>{if(e.name!=='AbortError')void load(false)})}}
const generating=computed(()=>submittingOutline.value||outline.value?.status==='generating'||['generating','queued','running'].includes(project.value?.status||''))
const events=useTaskEvents(()=>{void load(false)})
async function load(full=true){const own=++loadGeneration,identity=target(),pid=identity.pid;if(full)loading.value=true;try{await ensureCatalog();if(!sameTarget(identity))return;const [p,s,o]=await Promise.all([api.project(pid),api.sources(pid),api.outline(pid).catch(e=>{if(commandErrorStatus(e)===404)return null;throw e})]);if(own!==loadGeneration||!sameTarget(identity)||!alive)return;if(!settingsPending.value)resetSettings(p);project.value=p;sources.value=s;outline.value=o;if(o?.status==='generating'&&o.job_id&&subscribedOutlineJob!==o.job_id){subscribedOutlineJob=o.job_id;events.watch(o.job_id)}else if(o?.status!=='generating'&&subscribedOutlineJob){events.stop();subscribedOutlineJob='';}watchParsing(s);if(!settingsCommand.value)error.value=''}catch(e:any){if(own===loadGeneration&&sameTarget(identity))error.value=e.message}finally{if(own===loadGeneration&&sameTarget(identity))loading.value=false}}
async function generateOutline(){if(!project.value||generating.value||settingsPending.value)return;const own=target(),editor=outlineRef.value;submittingOutline.value=true;try{await editor?.flush();if(!sameTarget(own)||!alive||!project.value||settingsPending.value)return;const revision=project.value.input_revision;const task=await api.generateOutline(own.pid,{input_revision:revision});if(!sameTarget(own)||!alive)return;active.value='outline';subscribedOutlineJob=task.job_id||task.jobId||task.id||'';events.watch(subscribedOutlineJob);await load(false)}catch(e:any){if(sameTarget(own)&&alive)error.value=e.message}finally{if(sameTarget(own))submittingOutline.value=false}}
function makeSettingsCommand(baseline:Record<string,any>,draft:Record<string,any>,revision:number):SettingsCommand{
 const pageCountChanged=draft.page_count!==baseline.page_count
 if(!Number.isInteger(draft.page_count)||(pageCountChanged&&(draft.page_count<5||draft.page_count>20)))throw new Error('目标页数须为5–20的整数')
 const body:Record<string,unknown>={...draft,input_revision:revision}
 // 页操作后的正式页数可为1–40；未改动时不把它作为新的生成目标提交。
 if(!pageCountChanged)delete body.page_count
 return{pid:id.value,key:idempotencyKey(),body,baseline:{...baseline},draft:{...draft}}
}
async function sendSettings(command:SettingsCommand){if(settingsSaving.value)return;const own=target();if(command.pid!==own.pid)return;settingsSaving.value=true;error.value='';try{
 const saved=await api.update(command.pid,command.body,command.key)
 if(!sameTarget(own)||settingsCommand.value!==command)return
 project.value=saved;resetSettings(saved)
 // 回读完成前仍保留命令和禁用状态，避免用户新输入被回读覆盖。
 await load(false)
 if(!sameTarget(own)||settingsCommand.value!==command)return
 if(project.value)resetSettings(project.value)
 settingsCommand.value=null;settingsState.value='ready';settingsReview.value=null
 }catch(e:any){if(!sameTarget(own)||settingsCommand.value!==command)return;const status=commandErrorStatus(e);if(status>=400&&status<500&&status!==409){settingsCommand.value=null;settingsState.value='ready';error.value=e.message;return}settingsState.value=status===409?'conflict':'unknown';error.value=settingsState.value==='conflict'?'内容参数版本冲突，草稿已保留。请核对最新版本后重新应用，或放弃草稿。':'保存结果尚未确认，原请求和草稿已保留。请重试同一请求。'+(e.message||'')
 }finally{if(sameTarget(own))settingsSaving.value=false}}
async function editSettings(){if(!project.value||settingsLocked.value)return;try{if(!settingsSnapshot.value||settingsRevision.value===null)throw new Error('内容参数尚未读取，请重新读取');settingsCommand.value=makeSettingsCommand(JSON.parse(settingsSnapshot.value),settings.value,settingsRevision.value);await sendSettings(settingsCommand.value)}catch(e:any){error.value=e.message}}
async function retrySettings(){if(settingsState.value==='unknown'&&settingsCommand.value)await sendSettings(settingsCommand.value)}
async function reviewSettings(){if(settingsSaving.value||settingsReading.value||settingsState.value!=='conflict'||!settingsCommand.value)return;const own=target(),command=settingsCommand.value;settingsReading.value=true;try{const server=await api.project(own.pid);if(sameTarget(own)&&settingsCommand.value===command)settingsReview.value=server}catch(e:any){if(sameTarget(own))error.value=e.message}finally{if(sameTarget(own))settingsReading.value=false}}
async function reapplySettings(){if(settingsSaving.value||!settingsCommand.value||!settingsReview.value)return;const command=settingsCommand.value,server=settingsReview.value,baseline=settingsOf(server),draft:Record<string,any>={...baseline};for(const key of settingsKeys)if(command.draft[key]!==command.baseline[key])draft[key]=command.draft[key];try{const next=makeSettingsCommand(baseline,draft,server.input_revision);resetSettings(server);settings.value=draft;settingsReview.value=null;settingsCommand.value=next;settingsState.value='ready';await sendSettings(settingsCommand.value)}catch(e:any){error.value=e.message}}
function discardSettings(){if(settingsSaving.value||!settingsReview.value)return;project.value=settingsReview.value;resetSettings(settingsReview.value);settingsCommand.value=null;settingsReview.value=null;settingsState.value='ready';error.value=''}
async function leave(){try{await outlineRef.value?.flush()}catch{try{await ElMessageBox.confirm('提纲有未保存内容，建议继续保存。是否仍然离开？','未保存提纲',{confirmButtonText:'离开',cancelButtonText:'继续编辑'})}catch{return false}}if(sourceRef.value?.isDirty()||settingsPending.value){try{await ElMessageBox.confirm('还有未提交材料、失败文件或参数调整，离开后请重新补充。','未提交内容',{confirmButtonText:'离开',cancelButtonText:'继续填写'})}catch{return false}}return true}
function unload(e:BeforeUnloadEvent){if(outlineRef.value?.isDirty()||sourceRef.value?.isDirty()||settingsPending.value){e.preventDefault();e.returnValue=''}}
let removeGuard:(()=>void)|undefined
watch(id,()=>{viewGeneration++;clearSettings();submittingOutline.value=false;error.value='';project.value=null;outline.value=null;sources.value=[];events.stop();events.task.value=null;subscribedOutlineJob='';void load()});watch(()=>user.token,(token,previous)=>{if(!token||previous&&token!==previous){store.clear();viewGeneration++;clearSettings();submittingOutline.value=false;project.value=null;outline.value=null;sources.value=[];events.stop();events.task.value=null;subscribedOutlineJob='';loadGeneration++}})
onActivated(()=>{const returning=!alive;alive=true;if(returning)void load(false)});onDeactivated(()=>{alive=false;events.stop();subscribedOutlineJob=''});onBeforeRouteLeave(leave);onBeforeRouteUpdate(leave);onMounted(()=>{removeGuard=addCloseGuard(route.path,leave);window.addEventListener('beforeunload',unload);void load()});onBeforeUnmount(()=>{alive=false;loadGeneration++;viewGeneration++;parseAbort.abort();removeGuard?.();window.removeEventListener('beforeunload',unload)})
</script>
<template><div class="app-container" v-loading="loading"><el-alert v-if="error" type="error" :title="error"><el-button @click="load()">重试读取</el-button></el-alert><template v-if="project"><div class="detail-heading"><div><h2>{{project.title}}</h2><span>{{project.page_count}}页 · {{project.status}}</span></div><el-button @click="router.push({path:'/aippt/editor',query:{projectId:id}})">打开编辑工作台</el-button><el-button v-hasPermi="['aippt:outline:generate']" :disabled="generating||settingsPending||outline?.status==='confirmed'||!sources.length" type="primary" @click="generateOutline">{{outline?'重新生成提纲':'生成提纲'}}</el-button></div><el-alert v-if="events.deliveryMessage?.value" :title="events.deliveryMessage.value" :type="events.delivery?.value?.status==='degraded'?'warning':'info'" :closable="false"/><el-alert v-if="events.disconnected.value" title="进度连接中断，正在恢复；已保存内容仍保留" type="warning" :closable="false"/><el-progress v-if="events.task.value" :percentage="taskProgress(events.task.value).percent"/><p v-if="taskProgress(events.task.value).message" aria-live="polite">{{taskProgress(events.task.value).message}}</p><el-tabs v-model="active"><el-tab-pane label="来源材料" name="sources"><SourceList ref="sourceRef" :project-id="id" :sources="sources" :input-revision="project.input_revision" :locked="outline?.status==='confirmed'||generating" @changed="load(false)"/></el-tab-pane><el-tab-pane label="提纲确认" name="outline"><OutlineEditor ref="outlineRef" :project-id="id" :outline="outline" :sources="sources" :input-revision="project.input_revision" :generating="generating" @changed="load(false)" @task="()=>{events.watch(id,'deck');load(false)}"/></el-tab-pane><el-tab-pane label="内容参数" name="settings"><el-form label-width="100px" :disabled="outline?.status==='confirmed'||generating||settingsLocked||!checkPermi(['aippt:project:edit'])"><el-form-item label="名称"><el-input v-model="settings.title" maxlength="200"/></el-form-item><el-form-item label="受众"><el-input v-model="settings.audience" maxlength="100"/></el-form-item><el-form-item label="语气"><el-select v-model="settings.tone"><el-option label="专业" value="professional"/><el-option label="轻松" value="plain"/><el-option label="鼓舞" value="punchy"/></el-select></el-form-item><el-form-item label="文字量"><el-select v-model="settings.content_density"><el-option label="精简" value="concise"/><el-option label="适中" value="medium"/><el-option label="详尽" value="detailed"/></el-select></el-form-item><el-form-item label="目标页数"><el-input-number v-model="settings.page_count"/><small>新目标须为5–20页；已编辑文稿按正式页数显示。</small></el-form-item><el-button :loading="settingsSaving" @click="editSettings">保存参数</el-button><p>内容参数变更会使旧提纲过期；请先保存或处理参数草稿再生成提纲。主题外观在编辑器单独修改。</p></el-form>
<el-alert v-if="settingsCommand" :closable="false" type="warning" :title="settingsState==='conflict'?'参数草稿待核对最新版本':'参数原请求尚待确认'">
<p>原草稿和请求正文已保留。确认保存结果前，参数字段暂停编辑。</p>
<el-button v-if="settingsState==='unknown'" :disabled="settingsSaving" :loading="settingsSaving" @click="retrySettings">重试同一参数请求</el-button>
<el-button v-if="settingsState==='conflict'" :disabled="settingsReading" :loading="settingsReading" @click="reviewSettings">核对最新参数版本</el-button>
</el-alert></el-tab-pane></el-tabs>
<el-dialog :model-value="!!settingsReview" title="核对参数草稿与最新版本" width="min(760px, 95vw)" @close="settingsReview=null">
<p>仅重新应用您相对原基线改动的字段；其他字段采用下方最新正式值。保存仍会核对最新修订。</p>
<table class="settings-review"><thead><tr><th>字段</th><th>原读取值</th><th>保留草稿</th><th>最新正式值</th></tr></thead><tbody><tr v-for="row in reviewRows" :key="row.key"><th>{{row.label}}{{row.changed?'（已改）':''}}</th><td>{{row.baseline}}</td><td>{{row.draft}}</td><td>{{row.server}}</td></tr></tbody></table>
<template #footer><el-button :disabled="settingsSaving" @click="settingsReview=null">继续保留草稿</el-button><el-button :disabled="settingsSaving" @click="discardSettings">放弃参数草稿</el-button><el-button type="primary" :disabled="settingsSaving" @click="reapplySettings">重新应用并保存</el-button></template>
</el-dialog></template></div></template>
<style scoped>.detail-heading{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:20px}.detail-heading>div:first-child{margin-right:auto}.el-form{max-width:800px}.settings-review{width:100%;border-collapse:collapse;table-layout:fixed}.settings-review th,.settings-review td{border:1px solid #dcdfe6;padding:10px;text-align:left;overflow-wrap:anywhere}</style>
