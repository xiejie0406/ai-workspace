<template>
  <div class="app-container aps-resources">
    <el-alert title="资源日历按站点时区输入，保存为绝对时间窗；工艺标准时间与资源并发容量分别维护。" type="info" :closable="false" />

    <el-alert v-if="pageError" :title="pageError" type="error" :closable="false" />
    <el-card class="section-card">
      <template #header>
        <div class="section-head">
          <span>车间与工作中心</span><el-button v-if="currentWorkshop" v-hasPermi="['aps:resource:edit']" @click="openWorkshop(currentWorkshop)">编辑当前车间</el-button>
          <div>
            <el-button v-hasPermi="['aps:resource:add']" @click="openWorkshop()">新增车间</el-button>
            <el-button v-hasPermi="['aps:resource:add']" type="primary" :disabled="!workshopId" @click="openCenter()">新增工作中心</el-button>
          </div>
        </div>
      </template>
      <el-select v-model="workshopId" placeholder="选择车间" filterable style="width: 320px">
        <el-option v-for="item in workshops" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
      </el-select>
      <el-table :data="centers" empty-text="当前车间暂无工作中心">
        <el-table-column prop="code" label="中心编码" />
        <el-table-column prop="name" label="中心名称" />
        <el-table-column prop="centerType" label="类型" width="120" />
        <el-table-column prop="concurrentCapacity" label="并发容量" width="120" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="操作" width="90">
          <template #default="scope"><el-button link v-hasPermi="['aps:resource:edit']" @click="openCenter(scope.row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="section-card">
      <template #header>
        <div class="section-head">
          <span>具名资源</span>
          <el-button v-hasPermi="['aps:resource:add']" type="primary" :disabled="!workshopId" @click="openResource()">新增资源</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="resources" highlight-current-row @current-change="selectResource">
        <el-table-column prop="code" label="资源编码" min-width="130" />
        <el-table-column prop="name" label="资源名称" min-width="140" />
        <el-table-column prop="type" label="类型" width="120" />
        <el-table-column prop="teamName" label="班组" min-width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="操作" width="90">
          <template #default="scope"><el-button link v-hasPermi="['aps:resource:edit']" @click.stop="openResource(scope.row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="selectedResource"><template #header><strong>{{ selectedResource.name }} · 资源详情</strong></template><el-descriptions :column="3"><el-descriptions-item label="编码">{{ selectedResource.code }}</el-descriptions-item><el-descriptions-item label="班组">{{ selectedResource.teamName || '—' }}</el-descriptions-item><el-descriptions-item label="占用容量">{{ selectedResource.capacityValue }} {{ selectedResource.capacityUomCode }}</el-descriptions-item></el-descriptions><el-text type="info">专属加工速率仍待定；当前时间由工艺阶段标准计算。</el-text><a :href="`/aps/workbench?resourceId=${selectedResource.id}`">查看计划与占用</a></el-card>
    <el-row v-if="selectedResource" :gutter="16">
      <el-col :xs="24" :lg="10">
        <el-card class="section-card">
          <template #header><div class="section-head"><span>{{ selectedResource.name }} · 多技能</span><el-button v-hasPermi="['aps:resource:edit']" @click="openSkill()">添加技能</el-button></div></template>
          <el-table :data="skills">
            <el-table-column prop="code" label="技能" />
            <el-table-column prop="level" label="等级" width="70" />
            <el-table-column label="有效期" min-width="200"><template #default="scope">{{ timeText(scope.row.validFrom) || '无起始限制' }} → {{ timeText(scope.row.validTo) || '无到期限制' }}</template></el-table-column><el-table-column label="操作" width="75"><template #default="scope"><el-button link v-hasPermi="['aps:resource:edit']" @click="openSkill(scope.row)">编辑</el-button></template></el-table-column>
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="14">
        <el-card class="section-card">
          <template #header><div class="section-head"><span>绝对可用/不可用时间窗</span><el-button v-hasPermi="['aps:resource:edit']" @click="openAvailability()">添加时间窗</el-button></div></template>
          <el-table :data="availability">
            <el-table-column label="类型" width="145"><template #default="scope">{{ availabilityKind(scope.row.type) === 'AVAILABLE' ? '可用' : '不可用' }}<small v-if="!['AVAILABLE','UNAVAILABLE'].includes(scope.row.type)">（旧{{ scope.row.type }}）</small></template></el-table-column>
            <el-table-column label="开始（站点时间）" min-width="180"><template #default="scope">{{ timeText(scope.row.startAt) }}</template></el-table-column>
            <el-table-column label="结束（站点时间）" min-width="180"><template #default="scope">{{ timeText(scope.row.endAt) }}</template></el-table-column>
            <el-table-column prop="sourceType" label="来源" width="100" /><el-table-column prop="reason" label="原因" /><el-table-column label="操作" width="75"><template #default="scope"><el-button link v-hasPermi="['aps:resource:edit']" @click="openAvailability(scope.row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="selectedResource" class="section-card"><template #header><strong>净可用窗口 · {{ site?.zoneId || '站点配置未就绪' }}</strong></template><el-form inline @submit.prevent="loadNet"><el-form-item label="开始"><el-date-picker v-model="netFrom" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item><el-form-item label="结束"><el-date-picker v-model="netTo" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item><el-button :loading="netLoading" @click="loadNet">回查净可用时间</el-button></el-form><el-alert v-if="netError" :title="netError" type="error" :closable="false"/><el-table :data="netWindows" empty-text="请选择范围并回查"><el-table-column label="开始"><template #default="s">{{ timeText(s.row.start) }}</template></el-table-column><el-table-column label="结束"><template #default="s">{{ timeText(s.row.end) }}</template></el-table-column><el-table-column prop="capacityRatio" label="容量比例" /></el-table></el-card>
    <el-dialog v-model="workshopDialog" title="车间" width="520px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" /><el-form label-width="100px"><el-form-item label="编码"><el-input v-model="workshopForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="workshopForm.name" /></el-form-item><el-form-item label="负责人"><el-select v-model="workshopForm.managerUserId" clearable filterable remote :remote-method="fetchUsers" placeholder="按姓名或账号选择"><el-option v-for="u in users" :key="u.userId" :label="`${u.displayName} · ${u.userName}`" :value="u.userId" /></el-select></el-form-item><el-form-item label="状态"><el-select v-model="workshopForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="workshopDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitWorkshop">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="centerDialog" title="工作中心" width="520px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" /><el-form label-width="100px"><el-form-item label="编码"><el-input v-model="centerForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="centerForm.name" /></el-form-item><el-form-item label="类型"><el-select v-model="centerForm.centerType"><el-option v-for="item in ['MACHINE','LABOR','MIXED','BATCH']" :key="item" :value="item" /></el-select></el-form-item><el-form-item label="并发容量"><el-input-number v-model="centerForm.concurrentCapacity" :min="0.000001" /></el-form-item><el-form-item label="状态"><el-select v-model="centerForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="centerDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitCenter">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="resourceDialog" title="资源" width="560px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" /><el-form label-width="100px"><el-form-item label="编码"><el-input v-model="resourceForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="resourceForm.name" /></el-form-item><el-form-item label="工作中心"><el-select v-model="resourceForm.workCenterId" clearable><el-option v-for="item in centers" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="类型"><el-select v-model="resourceForm.type"><el-option v-for="item in ['PERSON','MACHINE','WORKSTATION','TOOL']" :key="item" :value="item" /></el-select></el-form-item><el-form-item v-if="resourceForm.type==='PERSON'" label="关联用户"><el-select v-model="resourceForm.ruoyiUserId" clearable filterable remote :remote-method="fetchUsers" placeholder="可选，选择已授权用户"><el-option v-for="u in users" :key="u.userId" :label="`${u.displayName} · ${u.userName}`" :value="u.userId" /></el-select></el-form-item><el-form-item label="并发容量"><el-input-number v-model="resourceForm.capacityValue" :min="0.000001" /></el-form-item><el-form-item label="容量单位"><el-input v-model="resourceForm.capacityUomCode" /></el-form-item><el-form-item label="班组"><el-input v-model="resourceForm.teamName" /></el-form-item><el-form-item label="状态"><el-select v-model="resourceForm.status"><el-option v-for="item in ['ACTIVE','INACTIVE','MAINTENANCE']" :key="item" :value="item" /></el-select></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="resourceDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitResource">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="skillDialog" title="技能资格" width="500px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" /><el-form label-width="90px"><el-form-item label="技能编码"><el-input v-model="skillForm.code" /></el-form-item><el-form-item label="技能名称"><el-input v-model="skillForm.name" /></el-form-item><el-form-item label="等级"><el-input-number v-model="skillForm.level" :min="1" :max="10" /></el-form-item><el-form-item label="生效时间"><el-date-picker v-model="skillForm.validFromLocal" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" clearable /></el-form-item><el-form-item label="失效时间"><el-date-picker v-model="skillForm.validToLocal" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" clearable /></el-form-item><el-form-item label="状态"><el-select v-model="skillForm.status"><el-option label="有效" value="ACTIVE"/><el-option label="停用" value="INACTIVE"/><el-option label="过期" value="EXPIRED"/></el-select></el-form-item><el-form-item label="证书引用"><el-input v-model="skillForm.certificateRef" /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="skillDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitSkill">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="availabilityDialog" title="日历窗口" width="560px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" /><el-form label-width="100px"><el-form-item label="类型"><el-select v-model="availabilityForm.type"><el-option label="可用" value="AVAILABLE"/><el-option label="不可用" value="UNAVAILABLE"/></el-select></el-form-item><el-form-item label="开始"><el-date-picker v-model="availabilityForm.start" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item><el-form-item label="结束"><el-date-picker v-model="availabilityForm.end" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item><el-form-item label="容量比例"><el-input-number v-model="availabilityForm.capacityRatio" :min="0.000001" :max="1" /></el-form-item><el-form-item label="来源方式"><el-input v-model="availabilityForm.sourceType" maxlength="24" /></el-form-item><el-form-item label="来源引用"><el-input v-model="availabilityForm.sourceRef" /></el-form-item><el-form-item label="原因"><el-input v-model="availabilityForm.reason" /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="availabilityDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="submitAvailability">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { nextTick } from 'vue'
const route=useRoute()
import { ElMessage } from 'element-plus'
import { listNetAvailability, listAvailability, listResources, listSkills, listWorkCenters, listWorkshops, saveAvailability, saveResource, saveSkill, saveWorkCenter, saveWorkshop } from '@/api/aps/resource'
import type { ApsNetWindow, ApsAvailability, ApsResource, ApsResourceSkill, ApsWorkCenter, ApsWorkshop } from '@/types/aps/resource'
import { assertUtcHalfOpenWindow, uniqueResources } from './resource-model'
import { getSiteContext, listUserCandidates, type ApsSiteContext, type ApsUserCandidate } from '@/api/aps/context'
import { siteTime, siteTimeToUtc, operationError } from '@/components/aps/shared/site-time'

const loading = ref(false)
const workshopId = ref('')
const workshops = ref<ApsWorkshop[]>([])
const centers = ref<ApsWorkCenter[]>([])
const resources = ref<ApsResource[]>([])
const selectedResource = ref<ApsResource>()
const skills = ref<ApsResourceSkill[]>([])
const availability = ref<ApsAvailability[]>([])
const workshopDialog = ref(false), centerDialog = ref(false), resourceDialog = ref(false), skillDialog = ref(false), availabilityDialog = ref(false)
const workshopForm = reactive<any>({ code: '', name: '', managerUserId: '', status: 'ACTIVE', rowVersion: 0 })
const centerForm = reactive<any>({ code: '', name: '', centerType: 'MIXED', concurrentCapacity: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 })
const resourceForm = reactive<any>({ code: '', name: '', workCenterId: '', type: 'PERSON', teamName: '', capacityValue: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 })
const skillForm = reactive<any>({ code: '', name: '', level: 1, certificateRef: '', status: 'ACTIVE', rowVersion: 0 })
const availabilityForm = reactive<any>({ type: 'AVAILABLE', start: undefined, end: undefined, sourceType: 'MANUAL', sourceRef: '', reason: '', capacityRatio: 1, rowVersion: 0 })


const site = ref<ApsSiteContext>(), users = ref<ApsUserCandidate[]>([])
const pageError = ref(''), formError = ref(''), saving = ref(false)
const currentWorkshop = computed(() => workshops.value.find(w => w.id === workshopId.value))
const netFrom = ref(''), netTo = ref(''), netWindows = ref<ApsNetWindow[]>([]), netError = ref(''), netLoading = ref(false)
let workshopLoad = 0, resourceLoad = 0, netLoad = 0, usersLoad = 0
const timeText = (value?: string) => value && site.value ? siteTime(value, site.value.zoneId) : value || ''
const utc = (value: string) => { if (!site.value) throw new Error('站点配置未就绪，暂不能提交时间'); return siteTimeToUtc(value, site.value.zoneId) }
const availabilityKind = (type: string) => ['AVAILABLE','OVERTIME'].includes(type) ? 'AVAILABLE' : 'UNAVAILABLE'
async function fetchUsers(keyword = '') { const token=++usersLoad; try { const value=await listUserCandidates(keyword); if(token===usersLoad) users.value=value } catch(e) { if(token===usersLoad) formError.value=operationError(e) } }
async function loadWorkshops() { workshops.value=await listWorkshops(); if(!workshopId.value && workshops.value[0]) workshopId.value=workshops.value[0].id }
async function loadWorkshopData() { const id=workshopId.value, token=++workshopLoad; ++resourceLoad; ++netLoad; netLoading.value=false;selectedResource.value=undefined; skills.value=[]; availability.value=[]; netWindows.value=[]; centers.value=[]; resources.value=[]; if(!id)return; loading.value=true; pageError.value=''; try { const [c,r]=await Promise.all([listWorkCenters(id),listResources({workshopId:id})]); if(token===workshopLoad){centers.value=c;resources.value=uniqueResources(r)} }catch(e){if(token===workshopLoad)pageError.value=operationError(e)}finally{if(token===workshopLoad)loading.value=false} }
async function selectResource(value?: ApsResource) { const token=++resourceLoad; ++netLoad;netLoading.value=false; selectedResource.value=value; skills.value=[]; availability.value=[]; netWindows.value=[]; netError.value=''; if(!value)return; try{const [s,a]=await Promise.all([listSkills(value.id),listAvailability(value.id)]);if(token===resourceLoad){skills.value=s;availability.value=a}}catch(e){if(token===resourceLoad)pageError.value=operationError(e)} }
function reset(target:any, source:any){for(const key of Object.keys(target))delete target[key];Object.assign(target,source);formError.value=''}
function openWorkshop(value?:ApsWorkshop){reset(workshopForm,value??{code:'',name:'',managerUserId:'',status:'ACTIVE',rowVersion:0});workshopDialog.value=true;void fetchUsers()}
function openCenter(value?:ApsWorkCenter){reset(centerForm,value??{workshopId:workshopId.value,code:'',name:'',centerType:'MIXED',concurrentCapacity:1,capacityUomCode:'COUNT',status:'ACTIVE',rowVersion:0});centerDialog.value=true}
function openResource(value?:ApsResource){reset(resourceForm,value??{workshopId:workshopId.value,code:'',name:'',workCenterId:'',type:'PERSON',ruoyiUserId:'',teamName:'',capacityValue:1,capacityUomCode:'COUNT',status:'ACTIVE',rowVersion:0});resourceDialog.value=true;void fetchUsers()}
function openSkill(value?:ApsResourceSkill){reset(skillForm,{...(value??{code:'',name:'',level:1,certificateRef:'',status:'ACTIVE',rowVersion:0}),validFromLocal:timeText(value?.validFrom),validToLocal:timeText(value?.validTo)});skillDialog.value=true}
function openAvailability(value?:ApsAvailability){reset(availabilityForm,{...(value??{type:'AVAILABLE',sourceType:'MANUAL',sourceRef:'',reason:'',capacityRatio:1,rowVersion:0}),originalType:value?.type,type:availabilityKind(value?.type||'AVAILABLE'),start:timeText(value?.startAt),end:timeText(value?.endAt)});availabilityDialog.value=true}
function required(value:string,label:string){if(!value?.trim())throw new Error('请填写'+label)}
async function persist(action:()=>Promise<unknown>, close:()=>void, reload:()=>Promise<unknown>){if(saving.value)return;saving.value=true;formError.value='';try{await action();close();ElMessage.success('已保存');try{await reload()}catch(e){pageError.value='保存成功，但回查失败：'+operationError(e)}}catch(e){formError.value=operationError(e)}finally{saving.value=false}}
async function submitWorkshop(){await persist(()=>{required(workshopForm.code,'车间编码');required(workshopForm.name,'车间名称');return saveWorkshop({...workshopForm})},()=>workshopDialog.value=false,loadWorkshops)}
async function submitCenter(){await persist(()=>{required(centerForm.code,'中心编码');required(centerForm.name,'中心名称');return saveWorkCenter({...centerForm,workshopId:workshopId.value})},()=>centerDialog.value=false,loadWorkshopData)}
async function submitResource(){await persist(()=>{required(resourceForm.code,'资源编码');required(resourceForm.name,'资源名称');return saveResource({...resourceForm,workshopId:workshopId.value,workCenterId:resourceForm.workCenterId||undefined,ruoyiUserId:resourceForm.ruoyiUserId||undefined})},()=>resourceDialog.value=false,loadWorkshopData)}
async function submitSkill(){const resource=selectedResource.value;if(!resource)return;await persist(()=>{required(skillForm.code,'技能编码');required(skillForm.name,'技能名称');const validFrom=skillForm.validFromLocal?utc(skillForm.validFromLocal):undefined,validTo=skillForm.validToLocal?utc(skillForm.validToLocal):undefined;if(validFrom&&validTo)assertUtcHalfOpenWindow(validFrom,validTo);return saveSkill(resource.id,{...skillForm,validFrom,validTo})},()=>skillDialog.value=false,()=>selectResource(resource))}
async function submitAvailability(){const resource=selectedResource.value;if(!resource)return;await persist(()=>{const startAt=utc(availabilityForm.start),endAt=utc(availabilityForm.end);assertUtcHalfOpenWindow(startAt,endAt);const original=availabilityForm.originalType;const type=original&&availabilityKind(original)===availabilityForm.type?original:availabilityForm.type;return saveAvailability(resource.id,{...availabilityForm,type,startAt,endAt})},()=>availabilityDialog.value=false,async()=>{await selectResource(resource);if(netFrom.value&&netTo.value)await loadNet()})}
async function loadNet(){const resource=selectedResource.value;if(!resource||netLoading.value)return;const token=++netLoad;netLoading.value=true;netError.value='';try{const from=utc(netFrom.value),to=utc(netTo.value);assertUtcHalfOpenWindow(from,to);const windows=await listNetAvailability(resource.id,from,to);if(token===netLoad)netWindows.value=windows}catch(e){if(token===netLoad)netError.value=operationError(e)}finally{if(token===netLoad)netLoading.value=false}}
watch(workshopId,loadWorkshopData)
onMounted(async()=>{try{await loadWorkshops()}catch(e){pageError.value=operationError(e)}try{site.value=await getSiteContext();const query=route?.query||{};if(query.resourceId||query.centerId||query.workshopId){const all=await listResources({});const target=all.find(r=>r.id===query.resourceId);const ws=target?.workshopId||query.workshopId|| (query.centerId?all.find(r=>r.workCenterId===query.centerId)?.workshopId:undefined);if(typeof ws==='string'){workshopId.value=ws;await nextTick();await loadWorkshopData();if(target)await selectResource(target)}else pageError.value='当前范围中未找到问题对象，请核对权限'}}catch(e){pageError.value='站点或问题对象读取失败：'+operationError(e)}})
</script>

<style scoped>
.aps-resources { display: grid; gap: 16px; }
.section-card { margin-top: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
</style>
