<template>
  <div class="app-container aps-routing"><el-alert v-if="pageError" :title="pageError" type="error" :closable="false"/>
    <el-alert title="路线以版本发布；已发布工序和已展开任务不会被后续 V2 静默改写。SAME_START 可保存在草稿，但 P0 阻止发布。" type="info" :closable="false" />
    <el-row :gutter="16">
      <el-col :xs="24" :lg="8">
        <el-card class="section-card">
          <template #header><div class="head"><span>产品与物料</span><el-button v-hasPermi="['aps:routing:add']" @click="openItem()">新增</el-button></div></template>
          <el-table :data="items" highlight-current-row @current-change="selectItem">
            <el-table-column prop="code" label="编码" /><el-table-column prop="name" label="名称" />
            <el-table-column prop="type" label="类型" width="120"/><el-table-column label="操作" width="80"><template #default="s"><el-button link v-hasPermi="['aps:routing:edit']" @click.stop="openItem(s.row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="16">
        <el-card class="section-card">
          <template #header><div class="head"><span>可复用工序</span><el-button v-hasPermi="['aps:routing:add']" @click="openOperation()">新增工序</el-button></div></template>
          <el-table :data="operations">
            <el-table-column prop="code" label="编码" /><el-table-column prop="name" label="名称" />
            <el-table-column prop="mode" label="模式" width="130" /><el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="阶段" width="80"><template #default="s">{{ s.row.phases.length }}</template></el-table-column>
            <el-table-column label="操作" width="150"><template #default="s"><el-button link @click="openOperation(s.row)">{{s.row.status==='DRAFT'?'编辑':'详情'}}</el-button><el-button v-if="s.row.status==='DRAFT'" link v-hasPermi="['aps:routing:publish']" @click="activate(s.row)">生效</el-button></template></el-table-column>
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
      <template #header><div class="head"><span>{{ graph.route.routeCode }} / {{ graph.route.versionNo }} 路线 DAG</span><div><el-button :disabled="graph.route.status!=='DRAFT'" @click="openNode">加节点</el-button><el-button :disabled="graph.route.status!=='DRAFT'||graph.nodes.length<2" @click="edgeIndex=-1;formError='';Object.assign(edgeForm,{predecessorNodeCode:'',successorNodeCode:'',dependencyType:'FINISH',thresholdQty:undefined});edgeDialog=true">加依赖</el-button><el-button :disabled="graph.route.status!=='DRAFT'" v-hasPermi="['aps:routing:edit']" @click="saveGraph">保存图</el-button><el-button type="primary" :disabled="graph.route.status!=='DRAFT'" v-hasPermi="['aps:routing:publish']" @click="publish">校验并发布</el-button></div></div></template>
      <el-row :gutter="16"><el-col :span="12"><h4>节点</h4><el-table :data="graph.nodes"><el-table-column prop="nodeCode" label="节点" /><el-table-column prop="nodeName" label="名称"/><el-table-column prop="quantityMultiplier" label="数量倍率"/><el-table-column label="操作"><template #default="v"><el-button link :disabled="graph.route.status!=='DRAFT'" @click="editNode(v.row)">编辑</el-button><el-button link :disabled="graph.route.status!=='DRAFT'" @click="removeNode(v.row.id)">移除</el-button></template></el-table-column><el-table-column label="工序"><template #default="s">{{ operationName(s.row.operationSpecId) }}</template></el-table-column></el-table></el-col><el-col :span="12"><h4>显式依赖</h4><el-table :data="editableEdges"><el-table-column prop="predecessorNodeCode" label="来源" /><el-table-column prop="successorNodeCode" label="目标" /><el-table-column prop="dependencyType" label="类型"/><el-table-column label="操作"><template #default="v"><el-button link :disabled="graph.route.status!=='DRAFT'" @click="editEdge(v.$index)">编辑</el-button><el-button link :disabled="graph.route.status!=='DRAFT'" @click="removeEdge(v.$index)">移除</el-button></template></el-table-column></el-table></el-col></el-row>
      <el-alert v-if="validation && !validation.publishable" class="issues" type="error" :closable="false" :title="`存在 ${validation.issues.length} 个发布问题`"><template #default><div v-for="item in validation.issues" :key="item.objectId+item.code">{{ item.code }} · {{ item.message }}</div></template></el-alert>
    </el-card>

    <el-dialog v-model="itemDialog" title="产品与物料" width="520px"><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-form label-width="100px"><el-form-item label="编码"><el-input v-model="itemForm.code" /></el-form-item><el-form-item label="名称"><el-input v-model="itemForm.name" /></el-form-item><el-form-item label="类型"><el-select v-model="itemForm.type"><el-option v-for="v in ['PRODUCT','SEMI_FINISHED','MATERIAL']" :key="v" :value="v" /></el-select></el-form-item><el-form-item label="基础单位"><el-input v-model="itemForm.baseUomCode" /></el-form-item><el-form-item label="规格"><el-input v-model="itemForm.specification"/></el-form-item><el-form-item label="状态"><el-select v-model="itemForm.status"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="INACTIVE"/></el-select></el-form-item></el-form><template #footer><el-button @click="itemDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitItem">保存</el-button></template></el-dialog>
    <el-dialog v-model="operationDialog" :title="selectedOperation?.status==='ACTIVE'?'工序生效版本详情':'工序阶段与资源要求'" width="min(1100px,96vw)" destroy-on-close><OperationEditor v-if="operationDialog" :value="selectedOperation" :centers="directory.centers" :resources="directory.resources" :read-only="!!selectedOperation && selectedOperation.status!=='DRAFT'" @saved="operationSaved" @cancel="operationDialog=false" /></el-dialog>
    <el-dialog v-model="routeDialog" title="新建路线版本" width="500px"><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-form label-width="90px"><el-form-item label="路线编码"><el-input v-model="routeForm.routeCode" /></el-form-item><el-form-item label="版本号"><el-input v-model="routeForm.versionNo" /></el-form-item><el-form-item label="变更说明"><el-input v-model="routeForm.changeNote" /></el-form-item></el-form><template #footer><el-button @click="routeDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitRoute">创建</el-button></template></el-dialog>
    <el-dialog v-model="nodeDialog" title="添加路线节点" width="500px"><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-form label-width="90px"><el-form-item label="节点编码"><el-input v-model="nodeForm.nodeCode" /></el-form-item><el-form-item label="节点名称"><el-input v-model="nodeForm.nodeName" /></el-form-item><el-form-item label="数量倍率"><el-input-number v-model="nodeForm.quantityMultiplier" :min="0.000001"/></el-form-item><el-form-item label="工序"><el-select v-model="nodeForm.operationSpecId"><el-option v-for="v in activeOperations" :key="v.id" :label="`${v.code} · ${v.name}`" :value="v.id" /></el-select></el-form-item></el-form><template #footer><el-button @click="nodeDialog=false">取消</el-button><el-button type="primary" @click="addNode">添加</el-button></template></el-dialog>
    <el-dialog v-model="edgeDialog" title="添加显式依赖" width="520px"><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-form label-width="100px"><el-form-item label="来源节点"><el-select v-model="edgeForm.predecessorNodeCode"><el-option v-for="v in graph?.nodes" :key="v.id" :value="v.nodeCode" /></el-select></el-form-item><el-form-item label="目标节点"><el-select v-model="edgeForm.successorNodeCode"><el-option v-for="v in graph?.nodes" :key="v.id" :value="v.nodeCode" /></el-select></el-form-item><el-form-item label="关系类型"><el-select v-model="edgeForm.dependencyType"><el-option v-for="v in ['FINISH','QUANTITY','SAME_START']" :key="v" :value="v" /></el-select></el-form-item><el-form-item v-if="edgeForm.dependencyType==='QUANTITY'" label="数量门槛"><el-input-number v-model="edgeForm.thresholdQty" :min="0.000001" /></el-form-item></el-form><template #footer><el-button @click="edgeDialog=false">取消</el-button><el-button type="primary" @click="addEdge">添加</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { activateOperation, copyRoute, createRoute, getRoute, listItems, listOperations, listRoutes, publishRoute, saveItem, saveOperation, saveRouteGraph, validateRoute } from '@/api/aps/manufacturing'
import type { ApsItem, ApsOperation, ApsRouteGraph, ApsRouteVersion, ApsRouteValidation } from '@/types/aps/manufacturing'
import OperationEditor from './OperationEditor.vue'
import { getResourceDirectory, type ApsDirectory } from '@/api/aps/resource'
import { operationError } from '@/components/aps/shared/site-time'
import { routeGraphInput, validateRouteGraph, type EditableEdge } from './route-model'

const items = ref<ApsItem[]>([]), operations = ref<ApsOperation[]>([]), routes = ref<ApsRouteVersion[]>([])
const selectedItem = ref<ApsItem>(), graph = ref<ApsRouteGraph>(), validation = ref<ApsRouteValidation>()
const itemDialog = ref(false), operationDialog = ref(false), routeDialog = ref(false), nodeDialog = ref(false), edgeDialog = ref(false)
const itemForm = reactive<any>({ code: '', name: '', type: 'PRODUCT', baseUomCode: 'PCS', status: 'ACTIVE', rowVersion: 0 })
const routeForm = reactive<any>({ routeCode: '', versionNo: 'V1', changeNote: '' })
const nodeForm = reactive<any>({ nodeCode: '', nodeName: '', operationSpecId: '' })
const graphSaving=ref(false),edgeIndex=ref(-1)
const edgeForm = reactive<any>({ predecessorNodeCode: '', successorNodeCode: '', dependencyType: 'FINISH', thresholdQty: undefined })
const activeOperations = computed(() => operations.value.filter(value => value.status === 'ACTIVE'))
const editableEdges = computed<EditableEdge[]>(() => graph.value ? routeGraphInput(graph.value.nodes, graph.value.edges).edges : [])

async function refresh() { [items.value, operations.value] = await Promise.all([listItems(), listOperations()]) }
async function selectItem(value?:ApsItem){const token=++selection;selectedItem.value=value;graph.value=undefined;routes.value=[];try{const rows=value?await listRoutes(value.id):[];if(token===selection)routes.value=rows}catch(e){if(token===selection)pageError.value=operationError(e)}}
async function selectRoute(value?:ApsRouteVersion){const token=++selection;graph.value=undefined;validation.value=undefined;if(value)try{const result=await getRoute(value.id);if(token===selection)graph.value=result}catch(e){if(token===selection)pageError.value=operationError(e)}}
function operationName(id: string) { return operations.value.find(value => value.id === id)?.name ?? '未知工序' }
async function submitItem(){if(saving.value)return;saving.value=true;formError.value='';try{if(!itemForm.code?.trim()||!itemForm.name?.trim()||!itemForm.baseUomCode?.trim())throw new Error('请填写编码、名称和基础单位');await saveItem({...itemForm});itemDialog.value=false;await refresh();ElMessage.success('产品/物料已保存')}catch(e){formError.value=operationError(e)}finally{saving.value=false}}
async function activate(value: ApsOperation) { if(saving.value)return;saving.value=true;try{await activateOperation(value.id,value.rowVersion);await refresh();ElMessage.success('工序已生效，后续不可原位修改')}catch(e){pageError.value=operationError(e)}finally{saving.value=false} }
async function submitRoute() { if (!selectedItem.value||saving.value) return;saving.value=true;formError.value='';try{const value = await createRoute({ itemId: selectedItem.value.id, ...routeForm }); routeDialog.value = false; routes.value = await listRoutes(selectedItem.value.id); await selectRoute(value);}catch(e){formError.value=operationError(e)}finally{saving.value=false} }
function openNode() { for(const key of Object.keys(nodeForm))delete nodeForm[key];formError.value='';Object.assign(nodeForm, {quantityMultiplier:1, nodeCode: '', nodeName: '', operationSpecId: activeOperations.value[0]?.id ?? '' }); nodeDialog.value = true }
function addNode(){if(!graph.value)return;formError.value='';if(!nodeForm.nodeCode?.trim()||!nodeForm.nodeName?.trim()||!nodeForm.operationSpecId){formError.value='请填写节点编码、名称并选择工序';return}const code=nodeForm.nodeCode.trim().toUpperCase();if(graph.value.nodes.some(n=>n.nodeCode===code&&n.id!==nodeForm.id)){formError.value='节点编码重复';return}const old=graph.value.nodes.find(n=>n.id===nodeForm.id);if(old)Object.assign(old,nodeForm,{nodeCode:code});else graph.value.nodes.push({id:crypto.randomUUID(),routeVersionId:graph.value.route.id,operationSpecId:nodeForm.operationSpecId,nodeCode:code,nodeName:nodeForm.nodeName,displayOrder:(graph.value.nodes.length+1)*10,quantityMultiplier:nodeForm.quantityMultiplier,terminal:true,rowVersion:0});nodeDialog.value=false;validation.value=undefined}
function editNode(node:any){Object.assign(nodeForm,node);formError.value='';nodeDialog.value=true}
function terminals(){if(graph.value)for(const n of graph.value.nodes)n.terminal=!graph.value.edges.some(e=>e.predecessorNodeId===n.id)}
function removeNode(id:string){if(!graph.value)return;graph.value.nodes=graph.value.nodes.filter(n=>n.id!==id);graph.value.edges=graph.value.edges.filter(e=>e.predecessorNodeId!==id&&e.successorNodeId!==id);terminals();validation.value=undefined}
function editEdge(index:number){edgeIndex.value=index;Object.assign(edgeForm,editableEdges.value[index]);formError.value='';edgeDialog.value=true}
function removeEdge(index:number){graph.value?.edges.splice(index,1);terminals();validation.value=undefined}

function addEdge() { if (!graph.value) return;formError.value='';if(!edgeForm.predecessorNodeCode||!edgeForm.successorNodeCode||edgeForm.predecessorNodeCode===edgeForm.successorNodeCode){formError.value='请选择两个不同的合法节点';return}if(edgeForm.dependencyType==='QUANTITY'&&!(edgeForm.thresholdQty>0)){formError.value='数量依赖需要正数门槛';return}if(edgeIndex.value>=0)graph.value.edges.splice(edgeIndex.value,1);edgeIndex.value=-1; const ids = new Map(graph.value.nodes.map(value => [value.nodeCode, value.id])); const predecessor = graph.value.nodes.find(value => value.nodeCode === edgeForm.predecessorNodeCode); if (predecessor) predecessor.terminal = false; const successor = graph.value.nodes.find(value => value.nodeCode === edgeForm.successorNodeCode); if (successor && !graph.value.edges.some(value => value.predecessorNodeId === successor.id)) successor.terminal = true; graph.value.edges.push({ id: crypto.randomUUID(), routeVersionId: graph.value.route.id, predecessorNodeId: ids.get(edgeForm.predecessorNodeCode) ?? '', successorNodeId: ids.get(edgeForm.successorNodeCode) ?? '', dependencyType: edgeForm.dependencyType, thresholdQty: edgeForm.dependencyType === 'QUANTITY' ? edgeForm.thresholdQty : undefined, lagSeconds: 0, consumesOutput: false, rowVersion: 0 }); edgeDialog.value = false;terminals();validation.value=undefined }
async function saveGraph(): Promise<boolean> { if (!graph.value) return false; const input = {...routeGraphInput(graph.value.nodes, graph.value.edges),rowVersion:graph.value.route.rowVersion}; const issues = validateRouteGraph(graph.value.nodes, input.edges, false); if (issues.length) { ElMessage.error(issues[0].message); return false } if(graphSaving.value)return false;graphSaving.value=true;try{graph.value=await saveRouteGraph(graph.value.route.id,input);ElMessage.success('路线图已原子保存');return true}catch(e){pageError.value=operationError(e);return false}finally{graphSaving.value=false} }
async function publish() { if (!graph.value||saving.value)return;saving.value=true;try{if(!(await saveGraph()))return; validation.value = await validateRoute(graph.value.route.id); if (!validation.value.publishable) return ElMessage.error('路线存在发布阻断问题'); await publishRoute(graph.value.route.id, graph.value.route.rowVersion); const id=graph.value.route.id;await selectItem(selectedItem.value);await selectRoute(routes.value.find(r=>r.id===id));ElMessage.success('路线版本已发布')}catch(e){pageError.value=operationError(e)}finally{saving.value=false} }
async function copyVersion(value: ApsRouteVersion) { if(saving.value)return;try{const {value:version}=await ElMessageBox.prompt('请输入新版本号','复制路线版本');saving.value=true;await copyRoute(value.id,version,`复制自 ${value.versionNo}`);if(selectedItem.value)routes.value=await listRoutes(selectedItem.value.id);ElMessage.success('已复制为独立草稿')}catch(e){if(e!=='cancel'&&e!=='close')pageError.value=operationError(e)}finally{saving.value=false} }
const selectedOperation=ref<ApsOperation>(), directory=ref<ApsDirectory>({workshops:[],centers:[],resources:[]})
const pageError=ref(''),formError=ref(''),saving=ref(false)
function openItem(value?:ApsItem){for(const key of Object.keys(itemForm))delete itemForm[key];Object.assign(itemForm,value??{code:'',name:'',type:'PRODUCT',baseUomCode:'PCS',status:'ACTIVE',rowVersion:0});formError.value='';itemDialog.value=true}
async function openOperation(value?:ApsOperation){selectedOperation.value=value;try{directory.value=await getResourceDirectory();operationDialog.value=true}catch(e){pageError.value='资源目录读取失败：'+operationError(e)}}
async function operationSaved(){operationDialog.value=false;await refresh();ElMessage.success('工序草稿已保存')}
let selection=0
const pageRoute=useRoute()
async function locateIssue(){
 const id=pageRoute?.query.objectId;if(typeof id!=='string'||!id)return
 const operation=operations.value.find(o=>o.id===id||o.phases.some(p=>p.id===id||p.requirements.some(r=>r.id===id)))
 if(operation){await openOperation(operation);return}
 const versions=await listRoutes();let located:ApsRouteGraph|undefined
 for(const version of versions){const candidate=await getRoute(version.id);if(candidate.route.id===id||candidate.nodes.some(n=>n.id===id)||candidate.edges.some(e=>e.id===id)){located=candidate;break}}
 if(located){await selectItem(items.value.find(i=>i.id===located!.route.itemId));await selectRoute(located.route);return}
 const item=items.value.find(i=>i.id===id);if(item){await selectItem(item);return}
 pageError.value='问题对象已变更或不在当前可见目录，请重新执行预检。'
}
onMounted(async()=>{try{await refresh();await locateIssue()}catch(e){pageError.value=operationError(e)}})
</script>

<style scoped>.aps-routing{display:grid;gap:16px}.section-card{margin-top:16px}.head{display:flex;align-items:center;justify-content:space-between;gap:12px}.issues{margin-top:16px}</style>
