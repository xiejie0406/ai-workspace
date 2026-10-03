<template>
  <div class="app-container aps-orders">
    <el-alert title="一个订单可包含多个独立产品行；跨产品关系只来自显式依赖/组件输入，不按页面顺序自动串行。任务展开使用稳定 ID，重复执行只回读原网络。" type="info" :closable="false" />
    <el-card class="section-card">
      <template #header><div class="head"><span>生产订单</span><div><el-button v-hasPermi="['aps:order:add']" @click="openOrder(false)">新增订单</el-button><el-button type="primary" v-hasPermi="['aps:order:import']" @click="openOrder(true)">幂等导入</el-button></div></div></template>
      <el-table :data="orders" highlight-current-row @current-change="selectOrder">
        <el-table-column prop="orderNo" label="订单号" min-width="150" /><el-table-column prop="sourceSystem" label="来源" width="100" /><el-table-column prop="externalId" label="外部 ID" min-width="130" /><el-table-column prop="priority" label="优先级" width="90" /><el-table-column label="产品行" width="80"><template #default="s">{{ s.row.lines.length }}</template></el-table-column><el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="操作" width="210"><template #default="s"><el-button link @click.stop="expand(s.row)">展开任务</el-button><el-button link v-hasPermi="['aps:order:release']" @click.stop="release(s.row)">结构释放</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="selected" class="section-card">
      <template #header><div class="head"><span>{{ selected.orderNo }} · 冻结产品行</span><el-button :disabled="!!expansion?.tasks.length" @click="openMigration">路线迁移差异</el-button></div></template>
      <el-table :data="selected.lines"><el-table-column prop="lineNo" label="行" width="60" /><el-table-column label="产品"><template #default="s">{{ itemName(s.row.itemId) }}</template></el-table-column><el-table-column label="路线版本"><template #default="s">{{ routeName(s.row.routeVersionId) }}</template></el-table-column><el-table-column prop="demandQty" label="数量" width="100" /><el-table-column prop="uomCode" label="单位" width="80" /><el-table-column label="显式跨产品依赖" width="150"><template #default="s">{{ s.row.dependencies.length }}</template></el-table-column></el-table>
    </el-card>

    <el-card v-if="expansion" class="section-card">
      <template #header><div class="head"><span>冻结任务网络</span><el-tag :type="expansion.reused?'info':'success'">{{ expansion.reused ? '复用原展开' : '首次展开' }}</el-tag></div></template>
      <el-descriptions :column="4" border><el-descriptions-item label="生产批">{{ counts.lots }}</el-descriptions-item><el-descriptions-item label="任务">{{ counts.tasks }}</el-descriptions-item><el-descriptions-item label="依赖">{{ counts.dependencies }}</el-descriptions-item><el-descriptions-item label="物料需求">{{ counts.materialDemands }}</el-descriptions-item></el-descriptions>
      <el-table :data="expansion.tasks"><el-table-column prop="taskCode" label="稳定任务编码" min-width="220" /><el-table-column prop="taskName" label="任务" /><el-table-column prop="taskQty" label="数量" width="90" /><el-table-column prop="runSeconds" label="运行秒" width="100" /><el-table-column prop="status" label="状态" width="110" /></el-table>
      <h4>显式依赖</h4><el-table :data="expansion.dependencies"><el-table-column prop="predecessorTaskId" label="来源任务 ID" /><el-table-column prop="successorTaskId" label="目标任务 ID" /><el-table-column prop="dependencyType" label="语义" width="120" /><el-table-column prop="thresholdQty" label="门槛" width="90" /></el-table>
    </el-card>

    <el-dialog v-model="orderDialog" :title="importMode ? '幂等导入订单' : '新增多产品订单'" width="900px">
      <el-form label-width="90px"><el-row :gutter="12"><el-col :span="8"><el-form-item label="订单号"><el-input v-model="orderForm.orderNo" /></el-form-item></el-col><el-col :span="6"><el-form-item label="来源"><el-input v-model="orderForm.sourceSystem" /></el-form-item></el-col><el-col :span="7"><el-form-item label="外部 ID"><el-input v-model="orderForm.externalId" /></el-form-item></el-col><el-col :span="3"><el-form-item label="优先级"><el-input-number v-model="orderForm.priority" :min="1" :max="100" /></el-form-item></el-col></el-row></el-form>
      <div class="head"><h4>产品行</h4><el-button @click="addLine">添加产品行</el-button></div>
      <el-table :data="orderForm.lines"><el-table-column prop="lineNo" label="行号" width="70" /><el-table-column label="产品" min-width="160"><template #default="s"><el-select v-model="s.row.itemId" @change="lineItemChanged(s.row)"><el-option v-for="v in products" :key="v.id" :label="`${v.code} · ${v.name}`" :value="v.id" /></el-select></template></el-table-column><el-table-column label="路线" min-width="160"><template #default="s"><el-select v-model="s.row.routeVersionId"><el-option v-for="v in activeRoutes(s.row.itemId)" :key="v.id" :label="`${v.routeCode}/${v.versionNo}`" :value="v.id" /></el-select></template></el-table-column><el-table-column label="数量" width="130"><template #default="s"><el-input-number v-model="s.row.demandQty" :min="0.000001" /></template></el-table-column><el-table-column prop="uomCode" label="单位" width="75" /><el-table-column label="关系" width="150"><template #default="s"><el-button link @click="openDependency(s.row)">依赖({{ s.row.dependencies.length }})</el-button><el-button link @click="openComponent(s.row)">物料({{ s.row.components.length }})</el-button></template></el-table-column></el-table>
      <template #footer><el-button @click="orderDialog=false">取消</el-button><el-button type="primary" @click="submitOrder">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="dependencyDialog" title="添加显式跨产品依赖" width="560px"><el-form label-width="120px"><el-form-item label="来源产品行"><el-select v-model="dependencyForm.predecessorLineNo"><el-option v-for="v in orderForm.lines" :key="v.lineNo" :value="v.lineNo" /></el-select></el-form-item><el-form-item label="来源节点编码"><el-input v-model="dependencyForm.predecessorNodeCode" /></el-form-item><el-form-item label="目标节点编码"><el-input v-model="dependencyForm.successorNodeCode" /></el-form-item><el-form-item label="依赖类型"><el-select v-model="dependencyForm.dependencyType"><el-option v-for="v in ['FINISH','QUANTITY','SAME_START']" :key="v" :value="v" /></el-select></el-form-item><el-form-item v-if="dependencyForm.dependencyType==='QUANTITY'" label="数量门槛"><el-input-number v-model="dependencyForm.thresholdQty" :min="0.000001" /></el-form-item></el-form><template #footer><el-button @click="dependencyDialog=false">取消</el-button><el-button type="primary" @click="addDependency">添加</el-button></template></el-dialog>
    <el-dialog v-model="componentDialog" title="添加组件/转移需求" width="600px"><el-form label-width="120px"><el-form-item label="目标节点编码"><el-input v-model="componentForm.targetNodeCode" /></el-form-item><el-form-item label="物料"><el-select v-model="componentForm.itemId" @change="componentItemChanged"><el-option v-for="v in items" :key="v.id" :label="`${v.code} · ${v.name}`" :value="v.id" /></el-select></el-form-item><el-form-item label="需求类型"><el-select v-model="componentForm.demandType"><el-option v-for="v in ['COMPONENT','TRANSFER','EXTERNAL']" :key="v" :value="v" /></el-select></el-form-item><el-form-item v-if="componentForm.demandType==='TRANSFER'" label="来源产品行"><el-select v-model="componentForm.sourceLineNo"><el-option v-for="v in orderForm.lines" :key="v.lineNo" :value="v.lineNo" /></el-select></el-form-item><el-form-item v-if="componentForm.demandType==='TRANSFER'" label="来源节点编码"><el-input v-model="componentForm.sourceNodeCode" /></el-form-item><el-form-item label="单位用量"><el-input-number v-model="componentForm.requiredQtyPerUnit" :min="0.000001" /></el-form-item></el-form><template #footer><el-button @click="componentDialog=false">取消</el-button><el-button type="primary" @click="addComponent">添加</el-button></template></el-dialog>
    <el-dialog v-model="migrationDialog" title="未展开订单行路线迁移" width="620px"><el-form label-width="120px"><el-form-item label="订单行"><el-select v-model="migrationForm.lineId"><el-option v-for="v in selected?.lines" :key="v.id" :label="`第 ${v.lineNo} 行 · ${itemName(v.itemId)}`" :value="v.id" /></el-select></el-form-item><el-form-item label="目标路线"><el-select v-model="migrationForm.targetRouteVersionId"><el-option v-for="v in migrationRoutes" :key="v.id" :label="`${v.routeCode}/${v.versionNo}`" :value="v.id" /></el-select></el-form-item></el-form><template #footer><el-button @click="migrationDialog=false">取消</el-button><el-button type="primary" @click="previewMigration">查看差异并确认</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmRouteMigration, expandOrder, getExpansion, listItems, listOrders, listRoutes, previewRouteMigration, releaseOrder, saveOrder } from '@/api/aps/manufacturing'
import type { ApsExpansion, ApsItem, ApsOrder, ApsOrderLine, ApsRouteVersion } from '@/types/aps/manufacturing'
import { addExplicitDependency, expansionCounts, normalizeOrderLines } from './order-model'

const items = ref<ApsItem[]>([]), routes = ref<ApsRouteVersion[]>([]), orders = ref<ApsOrder[]>([])
const selected = ref<ApsOrder>(), expansion = ref<ApsExpansion>()
const orderDialog = ref(false), dependencyDialog = ref(false), componentDialog = ref(false), migrationDialog = ref(false), importMode = ref(false)
const orderForm = reactive<any>({ orderNo: '', sourceSystem: 'LOCAL', externalId: '', priority: 50, lines: [] })
const dependencyForm = reactive<any>({ targetLine: undefined, predecessorLineNo: 1, predecessorNodeCode: '', successorNodeCode: '', dependencyType: 'FINISH', thresholdQty: undefined })
const componentForm = reactive<any>({ targetLine: undefined, targetNodeCode: '', itemId: '', demandType: 'COMPONENT', sourceLineNo: undefined, sourceNodeCode: '', requiredQtyPerUnit: 1, uomCode: 'PCS' })
const migrationForm = reactive<any>({ lineId: '', targetRouteVersionId: '' })
const products = computed(() => items.value.filter(value => value.type !== 'MATERIAL' && value.status === 'ACTIVE'))
const counts = computed(() => expansion.value ? expansionCounts(expansion.value) : { lots: 0, tasks: 0, dependencies: 0, materialDemands: 0 })
const migrationRoutes = computed(() => { const line = selected.value?.lines.find(value => value.id === migrationForm.lineId); return line ? activeRoutes(line.itemId).filter(value => value.id !== line.routeVersionId) : [] })

async function refresh() { [items.value, routes.value, orders.value] = await Promise.all([listItems(), listRoutes(), listOrders()]) }
async function selectOrder(value?: ApsOrder) { selected.value = value; expansion.value = value ? await getExpansion(value.id) : undefined }
function itemName(id: string) { return items.value.find(value => value.id === id)?.name ?? id }
function routeName(id: string) { const value = routes.value.find(route => route.id === id); return value ? `${value.routeCode}/${value.versionNo}` : id }
function activeRoutes(itemId: string) { return routes.value.filter(value => value.itemId === itemId && value.status === 'ACTIVE') }
function openOrder(imported: boolean) { importMode.value = imported; Object.assign(orderForm, { orderNo: '', sourceSystem: imported ? 'ERP' : 'LOCAL', externalId: '', priority: 50, lines: [] }); addLine(); orderDialog.value = true }
function addLine() { orderForm.lines.push({ lineNo: orderForm.lines.length + 1, itemId: '', routeVersionId: '', demandQty: 1, uomCode: 'PCS', components: [], dependencies: [] }) }
function lineItemChanged(line: any) { const item = items.value.find(value => value.id === line.itemId); line.uomCode = item?.baseUomCode ?? 'PCS'; line.routeVersionId = activeRoutes(line.itemId)[0]?.id ?? '' }
function openDependency(line: any) { Object.assign(dependencyForm, { targetLine: line, predecessorLineNo: orderForm.lines[0]?.lineNo ?? 1, predecessorNodeCode: '', successorNodeCode: '', dependencyType: 'FINISH', thresholdQty: undefined }); dependencyDialog.value = true }
function addDependency() { addExplicitDependency(dependencyForm.targetLine, { predecessorLineNo: dependencyForm.predecessorLineNo, predecessorNodeCode: dependencyForm.predecessorNodeCode, successorNodeCode: dependencyForm.successorNodeCode, dependencyType: dependencyForm.dependencyType, thresholdQty: dependencyForm.dependencyType === 'QUANTITY' ? dependencyForm.thresholdQty : undefined, lagSeconds: 0, consumesOutput: false }); dependencyDialog.value = false }
function openComponent(line: any) { Object.assign(componentForm, { targetLine: line, targetNodeCode: '', itemId: '', demandType: 'COMPONENT', sourceLineNo: undefined, sourceNodeCode: '', requiredQtyPerUnit: 1, uomCode: 'PCS' }); componentDialog.value = true }
function componentItemChanged() { componentForm.uomCode = items.value.find(value => value.id === componentForm.itemId)?.baseUomCode ?? 'PCS' }
function addComponent() { componentForm.targetLine.components.push({ demandNo: componentForm.targetLine.components.length + 1, targetNodeCode: componentForm.targetNodeCode, itemId: componentForm.itemId, sourceLineNo: componentForm.demandType === 'TRANSFER' ? componentForm.sourceLineNo : undefined, sourceNodeCode: componentForm.demandType === 'TRANSFER' ? componentForm.sourceNodeCode : undefined, demandType: componentForm.demandType, requiredQtyPerUnit: componentForm.requiredQtyPerUnit, uomCode: componentForm.uomCode }); componentDialog.value = false }
async function submitOrder() { const payload = { orderNo: orderForm.orderNo, sourceSystem: orderForm.sourceSystem, externalId: orderForm.externalId || undefined, priority: orderForm.priority, lines: normalizeOrderLines(orderForm.lines) }; await saveOrder(payload, importMode.value); orderDialog.value = false; await refresh(); ElMessage.success(importMode.value ? '订单已按幂等键导入' : '订单已保存') }
async function expand(value: ApsOrder) { expansion.value = await expandOrder(value.id); selected.value = value; ElMessage.success(expansion.value.reused ? '已复用原任务网络' : '任务网络已展开') }
async function release(value: ApsOrder) { await releaseOrder(value.id, value.rowVersion); await refresh(); ElMessage.success('订单结构已释放') }
function openMigration() { const line = selected.value?.lines[0]; if (!line) return; migrationForm.lineId = line.id; migrationForm.targetRouteVersionId = ''; migrationDialog.value = true }
async function previewMigration() { if (!selected.value) return; const line = selected.value.lines.find(value => value.id === migrationForm.lineId); if (!line) return; const diff = await previewRouteMigration(line.id, migrationForm.targetRouteVersionId); const text = `新增节点：${diff.addedNodes.join(', ') || '无'}\n删除节点：${diff.removedNodes.join(', ') || '无'}\n变更工序：${diff.changedOperations.join(', ') || '无'}\n是否确认迁移？`; await ElMessageBox.confirm(text, '路线迁移差异', { type: 'warning' }); await confirmRouteMigration(line.id, { expectedCurrentRouteVersionId: line.routeVersionId, targetRouteVersionId: migrationForm.targetRouteVersionId, rowVersion: line.rowVersion, confirmed: true }); migrationDialog.value = false; await refresh(); ElMessage.success('未展开订单行已迁移路线') }
onMounted(refresh)
</script>

<style scoped>.aps-orders{display:grid;gap:16px}.section-card{margin-top:16px}.head{display:flex;align-items:center;justify-content:space-between;gap:12px}</style>
