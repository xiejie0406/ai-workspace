<template>
  <div class="app-container aps-resources">
    <el-alert title="首版日历维护绝对 UTC 时间窗，不包含循环班制或 HR 实时同步。" type="info" :closable="false" />

    <el-card class="section-card">
      <template #header>
        <div class="section-head">
          <span>车间与工作中心</span>
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

    <el-row v-if="selectedResource" :gutter="16">
      <el-col :xs="24" :lg="10">
        <el-card class="section-card">
          <template #header><div class="section-head"><span>{{ selectedResource.name }} · 多技能</span><el-button v-hasPermi="['aps:resource:edit']" @click="skillDialog=true">添加技能</el-button></div></template>
          <el-table :data="skills">
            <el-table-column prop="code" label="技能" />
            <el-table-column prop="level" label="等级" width="70" />
            <el-table-column prop="validTo" label="有效至" min-width="170" />
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="14">
        <el-card class="section-card">
          <template #header><div class="section-head"><span>绝对可用/不可用时间窗</span><el-button v-hasPermi="['aps:resource:edit']" @click="availabilityDialog=true">添加时间窗</el-button></div></template>
          <el-table :data="availability">
            <el-table-column prop="type" label="类型" width="125" />
            <el-table-column prop="startAt" label="开始 UTC" min-width="180" />
            <el-table-column prop="endAt" label="结束 UTC" min-width="180" />
            <el-table-column prop="sourceType" label="来源" width="100" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="workshopDialog" title="车间" width="520px">
      <el-form label-width="100px"><el-form-item label="编码"><el-input v-model="workshopForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="workshopForm.name" /></el-form-item><el-form-item label="负责人ID"><el-input v-model="workshopForm.managerUserId" /></el-form-item><el-form-item label="状态"><el-select v-model="workshopForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="workshopDialog=false">取消</el-button><el-button type="primary" @click="submitWorkshop">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="centerDialog" title="工作中心" width="520px">
      <el-form label-width="100px"><el-form-item label="编码"><el-input v-model="centerForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="centerForm.name" /></el-form-item><el-form-item label="类型"><el-select v-model="centerForm.centerType"><el-option v-for="item in ['MACHINE','LABOR','MIXED','BATCH']" :key="item" :value="item" /></el-select></el-form-item><el-form-item label="并发容量"><el-input-number v-model="centerForm.concurrentCapacity" :min="0.000001" /></el-form-item><el-form-item label="状态"><el-select v-model="centerForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="centerDialog=false">取消</el-button><el-button type="primary" @click="submitCenter">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="resourceDialog" title="资源" width="560px">
      <el-form label-width="100px"><el-form-item label="编码"><el-input v-model="resourceForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="resourceForm.name" /></el-form-item><el-form-item label="工作中心"><el-select v-model="resourceForm.workCenterId" clearable><el-option v-for="item in centers" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="类型"><el-select v-model="resourceForm.type"><el-option v-for="item in ['PERSON','MACHINE','WORKSTATION','TOOL']" :key="item" :value="item" /></el-select></el-form-item><el-form-item label="班组"><el-input v-model="resourceForm.teamName" /></el-form-item><el-form-item label="状态"><el-select v-model="resourceForm.status"><el-option v-for="item in ['ACTIVE','INACTIVE','MAINTENANCE']" :key="item" :value="item" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="resourceDialog=false">取消</el-button><el-button type="primary" @click="submitResource">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="skillDialog" title="添加技能" width="500px">
      <el-form label-width="90px"><el-form-item label="技能编码"><el-input v-model="skillForm.code" /></el-form-item><el-form-item label="技能名称"><el-input v-model="skillForm.name" /></el-form-item><el-form-item label="等级"><el-input-number v-model="skillForm.level" :min="1" :max="10" /></el-form-item><el-form-item label="证书引用"><el-input v-model="skillForm.certificateRef" /></el-form-item></el-form>
      <template #footer><el-button @click="skillDialog=false">取消</el-button><el-button type="primary" @click="submitSkill">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="availabilityDialog" title="添加绝对时间窗" width="560px">
      <el-form label-width="100px"><el-form-item label="类型"><el-select v-model="availabilityForm.type"><el-option v-for="item in ['AVAILABLE','UNAVAILABLE','OVERTIME','LEAVE','MAINTENANCE']" :key="item" :value="item" /></el-select></el-form-item><el-form-item label="开始"><el-date-picker v-model="availabilityForm.start" type="datetime" /></el-form-item><el-form-item label="结束"><el-date-picker v-model="availabilityForm.end" type="datetime" /></el-form-item><el-form-item label="来源引用"><el-input v-model="availabilityForm.sourceRef" /></el-form-item><el-form-item label="原因"><el-input v-model="availabilityForm.reason" /></el-form-item></el-form>
      <template #footer><el-button @click="availabilityDialog=false">取消</el-button><el-button type="primary" @click="submitAvailability">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listAvailability, listResources, listSkills, listWorkCenters, listWorkshops, saveAvailability, saveResource, saveSkill, saveWorkCenter, saveWorkshop } from '@/api/aps/resource'
import type { ApsAvailability, ApsResource, ApsResourceSkill, ApsWorkCenter, ApsWorkshop } from '@/types/aps/resource'
import { assertUtcHalfOpenWindow, uniqueResources } from './resource-model'

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

async function loadWorkshops() { workshops.value = await listWorkshops(); if (!workshopId.value && workshops.value[0]) workshopId.value = workshops.value[0].id }
async function loadWorkshopData() { if (!workshopId.value) return; loading.value = true; try { [centers.value, resources.value] = await Promise.all([listWorkCenters(workshopId.value), listResources({ workshopId: workshopId.value })]); resources.value = uniqueResources(resources.value); selectedResource.value = undefined } finally { loading.value = false } }
async function selectResource(value?: ApsResource) { selectedResource.value = value; if (value) [skills.value, availability.value] = await Promise.all([listSkills(value.id), listAvailability(value.id)]) }
function reset(target: any, source: any) { for (const key of Object.keys(target)) delete target[key]; Object.assign(target, source) }
function openWorkshop(value?: ApsWorkshop) { reset(workshopForm, value ?? { code: '', name: '', managerUserId: '', status: 'ACTIVE', rowVersion: 0 }); workshopDialog.value = true }
function openCenter(value?: ApsWorkCenter) { reset(centerForm, value ?? { workshopId: workshopId.value, code: '', name: '', centerType: 'MIXED', concurrentCapacity: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 }); centerDialog.value = true }
function openResource(value?: ApsResource) { reset(resourceForm, value ?? { workshopId: workshopId.value, code: '', name: '', workCenterId: '', type: 'PERSON', teamName: '', capacityValue: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 }); resourceDialog.value = true }
async function submitWorkshop() { await saveWorkshop({ ...workshopForm }); workshopDialog.value = false; await loadWorkshops(); ElMessage.success('车间已保存') }
async function submitCenter() { await saveWorkCenter({ ...centerForm, workshopId: workshopId.value }); centerDialog.value = false; await loadWorkshopData(); ElMessage.success('工作中心已保存') }
async function submitResource() { await saveResource({ ...resourceForm, workshopId: workshopId.value, workCenterId: resourceForm.workCenterId || undefined }); resourceDialog.value = false; await loadWorkshopData(); ElMessage.success('资源已保存') }
async function submitSkill() { if (!selectedResource.value) return; await saveSkill(selectedResource.value.id, { ...skillForm }); skillDialog.value = false; skills.value = await listSkills(selectedResource.value.id); ElMessage.success('技能已保存') }
async function submitAvailability() { if (!selectedResource.value || !availabilityForm.start || !availabilityForm.end) return; const startAt = availabilityForm.start.toISOString(), endAt = availabilityForm.end.toISOString(); assertUtcHalfOpenWindow(startAt, endAt); await saveAvailability(selectedResource.value.id, { ...availabilityForm, startAt, endAt }); availabilityDialog.value = false; availability.value = await listAvailability(selectedResource.value.id); ElMessage.success('时间窗已保存') }
watch(workshopId, loadWorkshopData)
onMounted(loadWorkshops)
</script>

<style scoped>
.aps-resources { display: grid; gap: 16px; }
.section-card { margin-top: 16px; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
</style>
