<template>
  <div class="app-container aps-routing">
    <el-alert title="路线以版本发布；已发布工序和已展开任务不会被后续 V2 静默改写。SAME_START 可保存在草稿，但 P0 阻止发布。" type="info" :closable="false" />
    <el-row :gutter="16">
      <el-col :xs="24" :lg="8">
        <el-card class="section-card">
          <template #header><div class="head"><span>产品与物料</span><el-button v-hasPermi="['aps:routing:add']" @click="itemDialog=true">新增</el-button></div></template>
          <el-table :data="items" highlight-current-row @current-change="selectItem">
            <el-table-column prop="code" label="编码" /><el-table-column prop="name" label="名称" />
            <el-table-column prop="type" label="类型" width="120" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="16">
        <el-card class="section-card">
          <template #header><div class="head"><span>可复用工序</span><el-button v-hasPermi="['aps:routing:add']" @click="operationDialog=true">新增工序</el-button></div></template>
          <el-table :data="operations">
            <el-table-column prop="code" label="编码" /><el-table-column prop="name" label="名称" />
            <el-table-column prop="mode" label="模式" width="130" /><el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="阶段" width="80"><template #default="s">{{ s.row.phases.length }}</template></el-table-column>
            <el-table-column label="操作" width="90"><template #default="s"><el-button v-if="s.row.status==='DRAFT'" link v-hasPermi="['aps:routing:publish']" @click="activate(s.row)">生效</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="section-card">
      <template #header><div class="head"><span>路线版本 {{ selectedItem ? `· ${selectedItem.name}` : '' }}</span><el-button type="primary" :disabled="!selectedItem" v-hasPermi="['aps:routing:add']" @click="routeDialog=true">新建路线</el-button></div></template>
      <el-table :data="routes" highlight-current-row @current-change="selectRoute">
        <el-table-column prop="routeCode" label="路线" /><el-table-column prop="versionNo" label="版本" width="100" />
        <el-table-column prop="status" label="状态" width="100" /><el-table-column prop="changeNote" label="变更说明" />
        <el-table-column label="操作" width="180"><template #default="s"><el-button link @click.stop="selectRoute(s.row)">编辑图</el-button><el-button link v-hasPermi="['aps:routing:add']" @click.stop="copyVersion(s.row)">复制版本</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="graph" class="section-card">
      <template #header><div class="head"><span>{{ graph.route.routeCode }} / {{ graph.route.versionNo }} 路线 DAG</span><div><el-button :disabled="graph.route.status!=='DRAFT'" @click="openNode">加节点</el-button><el-button :disabled="graph.route.status!=='DRAFT'||graph.nodes.length<2" @click="edgeDialog=true">加依赖</el-button><el-button :disabled="graph.route.status!=='DRAFT'" v-hasPermi="['aps:routing:edit']" @click="saveGraph">保存图</el-button><el-button type="primary" :disabled="graph.route.status!=='DRAFT'" v-hasPermi="['aps:routing:publish']" @click="publish">校验并发布</el-button></div></div></template>
      <el-row :gutter="16"><el-col :span="12"><h4>节点</h4><el-table :data="graph.nodes"><el-table-column prop="nodeCode" label="节点" /><el-table-column prop="nodeName" label="名称" /><el-table-column label="工序"><template #default="s">{{ operationName(s.row.operationSpecId) }}</template></el-table-column></el-table></el-col><el-col :span="12"><h4>显式依赖</h4><el-table :data="editableEdges"><el-table-column prop="predecessorNodeCode" label="来源" /><el-table-column prop="successorNodeCode" label="目标" /><el-table-column prop="dependencyType" label="类型" /></el-table></el-col></el-row>
      <el-alert v-if="validation && !validation.publishable" class="issues" type="error" :closable="false" :title="`存在 ${validation.issues.length} 个发布问题`"><template #default><div v-for="item in validation.issues" :key="item.objectId+item.code">{{ item.code }} · {{ item.message }}</div></template></el-alert>
    </el-card>

    <el-dialog v-model="itemDialog" title="新增产品/物料" width="520px"><el-form label-width="100px"><el-form-item label="编码"><el-input v-model="itemForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="itemForm.name" /></el-form-item><el-form-item label="类型"><el-select v-model="itemForm.type"><el-option v-for="v in ['PRODUCT','SEMI_FINISHED','MATERIAL']" :key="v" :value="v" /></el-select></el-form-item><el-form-item label="基础单位"><el-input v-model="itemForm.baseUomCode" /></el-form-item></el-form><template #footer><el-button @click="itemDialog=false">取消</el-button><el-button type="primary" @click="submitItem">保存</el-button></template></el-dialog>
    <el-dialog v-model="operationDialog" title="新增基础工序" width="620px"><el-form label-width="110px"><el-form-item label="工序编码"><el-input v-model="operationForm.code" /></el-form-item><el-form-item label="工序名称"><el-input v-model="operationForm.name" /></el-form-item><el-form-item label="模式"><el-select v-model="operationForm.mode"><el-option v-for="v in ['MANUAL','MAN_MACHINE','AUTO','WAIT','TRANSPORT']" :key="v" :value="v" /></el-select></el-form-item><el-form-item label="阶段类型"><el-select v-model="operationForm.phaseType"><el-option v-for="v in ['SETUP','RUN','UNLOAD','WAIT','TRANSPORT']" :key="v" :value="v" /></el-select></el-form-item><el-form-item label="固定秒数"><el-input-number v-model="operationForm.fixedSeconds" :min="0" /></el-form-item><el-form-item label="每件秒数"><el-input-number v-model="operationForm.secondsPerUnit" :min="0" /></el-form-item><el-form-item label="资源类型"><el-select v-model="operationForm.resourceType"><el-option v-for="v in ['PERSON','MACHINE','WORKSTATION','TOOL']" :key="v" :value="v" /></el-select></el-form-item><el-form-item label="工作中心ID"><el-input v-model="operationForm.workCenterId" placeholder="来自资源与日历页" /></el-form-item></el-form><template #footer><el-button @click="operationDialog=false">取消</el-button><el-button type="primary" @click="submitOperation">保存草稿</el-button></template></el-dialog>
    <el-dialog v-model="routeDialog" title="新建路线版本" width="500px"><el-form label-width="90px"><el-form-item label="路线编码"><el-input v-model="routeForm.routeCode" /></el-form-item><el-form-item label="版本号"><el-input v-model="routeForm.versionNo" /></el-form-item><el-form-item label="变更说明"><el-input v-model="routeForm.changeNote" /></el-form-item></el-form><template #footer><el-button @click="routeDialog=false">取消</el-button><el-button type="primary" @click="submitRoute">创建</el-button></template></el-dialog>
    <el-dialog v-model="nodeDialog" title="添加路线节点" width="500px"><el-form label-width="90px"><el-form-item label="节点编码"><el-input v-model="nodeForm.nodeCode" /></el-form-item><el-form-item label="节点名称"><el-input v-model="nodeForm.nodeName" /></el-form-item><el-form-item label="工序"><el-select v-model="nodeForm.operationSpecId"><el-option v-for="v in activeOperations" :key="v.id" :label="`${v.code} · ${v.name}`" :value="v.id" /></el-select></el-form-item></el-form><template #footer><el-button @click="nodeDialog=false">取消</el-button><el-button type="primary" @click="addNode">添加</el-button></template></el-dialog>
    <el-dialog v-model="edgeDialog" title="添加显式依赖" width="520px"><el-form label-width="100px"><el-form-item label="来源节点"><el-select v-model="edgeForm.predecessorNodeCode"><el-option v-for="v in graph?.nodes" :key="v.id" :value="v.nodeCode" /></el-select></el-form-item><el-form-item label="目标节点"><el-select v-model="edgeForm.successorNodeCode"><el-option v-for="v in graph?.nodes" :key="v.id" :value="v.nodeCode" /></el-select></el-form-item><el-form-item label="关系类型"><el-select v-model="edgeForm.dependencyType"><el-option v-for="v in ['FINISH','QUANTITY','SAME_START']" :key="v" :value="v" /></el-select></el-form-item><el-form-item v-if="edgeForm.dependencyType==='QUANTITY'" label="数量门槛"><el-input-number v-model="edgeForm.thresholdQty" :min="0.000001" /></el-form-item></el-form><template #footer><el-button @click="edgeDialog=false">取消</el-button><el-button type="primary" @click="addEdge">添加</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { activateOperation, copyRoute, createRoute, getRoute, listItems, listOperations, listRoutes, publishRoute, saveItem, saveOperation, saveRouteGraph, validateRoute } from '@/api/aps/manufacturing'
import type { ApsItem, ApsOperation, ApsRouteGraph, ApsRouteVersion, ApsRouteValidation } from '@/types/aps/manufacturing'
import { routeGraphInput, validateRouteGraph, type EditableEdge } from './route-model'

const items = ref<ApsItem[]>([]), operations = ref<ApsOperation[]>([]), routes = ref<ApsRouteVersion[]>([])
const selectedItem = ref<ApsItem>(), graph = ref<ApsRouteGraph>(), validation = ref<ApsRouteValidation>()
const itemDialog = ref(false), operationDialog = ref(false), routeDialog = ref(false), nodeDialog = ref(false), edgeDialog = ref(false)
const itemForm = reactive<any>({ code: '', name: '', type: 'PRODUCT', baseUomCode: 'PCS', status: 'ACTIVE', rowVersion: 0 })
const operationForm = reactive<any>({ code: '', name: '', mode: 'MAN_MACHINE', phaseType: 'RUN', fixedSeconds: 0, secondsPerUnit: 10, resourceType: 'MACHINE', workCenterId: '' })
const routeForm = reactive<any>({ routeCode: '', versionNo: 'V1', changeNote: '' })
const nodeForm = reactive<any>({ nodeCode: '', nodeName: '', operationSpecId: '' })
const edgeForm = reactive<any>({ predecessorNodeCode: '', successorNodeCode: '', dependencyType: 'FINISH', thresholdQty: undefined })
const activeOperations = computed(() => operations.value.filter(value => value.status === 'ACTIVE'))
const editableEdges = computed<EditableEdge[]>(() => graph.value ? routeGraphInput(graph.value.nodes, graph.value.edges).edges : [])

async function refresh() { [items.value, operations.value] = await Promise.all([listItems(), listOperations()]) }
async function selectItem(value?: ApsItem) { selectedItem.value = value; graph.value = undefined; routes.value = value ? await listRoutes(value.id) : [] }
async function selectRoute(value?: ApsRouteVersion) { if (value) { graph.value = await getRoute(value.id); validation.value = undefined } }
function operationName(id: string) { return operations.value.find(value => value.id === id)?.name ?? id }
async function submitItem() { await saveItem({ ...itemForm }); itemDialog.value = false; await refresh(); ElMessage.success('产品/物料已保存') }
async function submitOperation() { const perUnit = Number(operationForm.secondsPerUnit); const fixed = Number(operationForm.fixedSeconds); const durationModel = fixed > 0 && perUnit > 0 ? 'FIXED_PLUS_UNIT' : fixed > 0 ? 'FIXED' : 'PER_UNIT'; const requirements = operationForm.phaseType === 'WAIT' ? [] : [{ requirementNo: 1, resourceType: operationForm.resourceType, workCenterId: operationForm.workCenterId || undefined, seatCount: 1, optional: false }]; await saveOperation({ code: operationForm.code, name: operationForm.name, mode: operationForm.mode, interruptible: false, qualityGateRequired: false, phases: [{ phaseNo: 1, phaseType: operationForm.phaseType, name: operationForm.name, durationModel, fixedSeconds: fixed, secondsPerUnit: perUnit, resourceHoldPolicy: 'PHASE_ONLY', requirements }], rowVersion: 0 }); operationDialog.value = false; await refresh(); ElMessage.success('工序草稿已保存') }
async function activate(value: ApsOperation) { await activateOperation(value.id, value.rowVersion); await refresh(); ElMessage.success('工序已生效，后续不可原位修改') }
async function submitRoute() { if (!selectedItem.value) return; const value = await createRoute({ itemId: selectedItem.value.id, ...routeForm }); routeDialog.value = false; routes.value = await listRoutes(selectedItem.value.id); await selectRoute(value); }
function openNode() { Object.assign(nodeForm, { nodeCode: '', nodeName: '', operationSpecId: activeOperations.value[0]?.id ?? '' }); nodeDialog.value = true }
function addNode() { if (!graph.value) return; graph.value.nodes.push({ id: crypto.randomUUID(), routeVersionId: graph.value.route.id, operationSpecId: nodeForm.operationSpecId, nodeCode: nodeForm.nodeCode.toUpperCase(), nodeName: nodeForm.nodeName, displayOrder: (graph.value.nodes.length + 1) * 10, quantityMultiplier: 1, terminal: true, rowVersion: 0 }); nodeDialog.value = false }
function addEdge() { if (!graph.value) return; const ids = new Map(graph.value.nodes.map(value => [value.nodeCode, value.id])); const predecessor = graph.value.nodes.find(value => value.nodeCode === edgeForm.predecessorNodeCode); if (predecessor) predecessor.terminal = false; const successor = graph.value.nodes.find(value => value.nodeCode === edgeForm.successorNodeCode); if (successor && !graph.value.edges.some(value => value.predecessorNodeId === successor.id)) successor.terminal = true; graph.value.edges.push({ id: crypto.randomUUID(), routeVersionId: graph.value.route.id, predecessorNodeId: ids.get(edgeForm.predecessorNodeCode) ?? '', successorNodeId: ids.get(edgeForm.successorNodeCode) ?? '', dependencyType: edgeForm.dependencyType, thresholdQty: edgeForm.dependencyType === 'QUANTITY' ? edgeForm.thresholdQty : undefined, lagSeconds: 0, consumesOutput: false, rowVersion: 0 }); edgeDialog.value = false }
async function saveGraph(): Promise<boolean> { if (!graph.value) return false; const input = routeGraphInput(graph.value.nodes, graph.value.edges); const issues = validateRouteGraph(graph.value.nodes, input.edges, false); if (issues.length) { ElMessage.error(issues[0].message); return false } graph.value = await saveRouteGraph(graph.value.route.id, input); ElMessage.success('路线图已原子保存'); return true }
async function publish() { if (!graph.value || !(await saveGraph())) return; validation.value = await validateRoute(graph.value.route.id); if (!validation.value.publishable) return ElMessage.error('路线存在发布阻断问题'); await publishRoute(graph.value.route.id, graph.value.route.rowVersion); await selectItem(selectedItem.value); ElMessage.success('路线版本已发布') }
async function copyVersion(value: ApsRouteVersion) { const { value: version } = await ElMessageBox.prompt('请输入新版本号', '复制路线版本'); await copyRoute(value.id, version, `复制自 ${value.versionNo}`); if (selectedItem.value) routes.value = await listRoutes(selectedItem.value.id); ElMessage.success('已复制为独立草稿') }
onMounted(refresh)
</script>

<style scoped>.aps-routing{display:grid;gap:16px}.section-card{margin-top:16px}.head{display:flex;align-items:center;justify-content:space-between;gap:12px}.issues{margin-top:16px}</style>
