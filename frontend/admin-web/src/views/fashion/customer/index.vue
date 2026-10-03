<template>
  <div class="app-container">
    <el-alert title="客户只对负责人、协作者和管理员可见；联系人与内部备注不会发送给 AI Runtime。" type="info" :closable="false" show-icon class="mb20" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb20" />
    <el-form :inline="true" :model="query" @submit.prevent="search">
      <el-form-item label="关键词"><el-input v-model="query.keyword" clearable placeholder="客户编码、名称、联系人" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" clearable style="width:130px"><el-option label="正常" value="active" /><el-option label="已归档" value="archived" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="search">查询</el-button></el-form-item>
    </el-form>
    <div class="toolbar mb8"><el-button type="primary" plain icon="Plus" v-hasPermi="['fashion:customer:add']" @click="openCreate">新增客户</el-button><el-button icon="Refresh" @click="load">刷新</el-button></div>
    <el-table v-loading="loading" :data="page.items" :empty-text="error?'客户读取失败，请重试':'没有可见客户'">
      <el-table-column prop="code" label="客户编码" min-width="130" /><el-table-column prop="name" label="客户名称" min-width="160" />
      <el-table-column label="类型" width="110"><template #default="{row}">{{ row.customerType === 'wholesale' ? '批发' : '团购' }}</template></el-table-column>
      <el-table-column prop="contactName" label="联系人" width="120" /><el-table-column prop="contactPhone" label="联系电话" width="150" />
      <el-table-column prop="region" label="地区" min-width="120" /><el-table-column label="负责人" min-width="170"><template #default="{row}">{{ row.salespersonName || `姓名未返回（ID ${row.salespersonId}）` }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '正常' : '已归档' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button link type="primary" v-hasPermi="['fashion:customer:edit']" @click="openEdit(row)">编辑</el-button><el-button v-if="row.status==='active'" link type="danger" v-hasPermi="['fashion:customer:edit']" @click="archive(row)">归档</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="page.total>0" :total="page.total" v-model:page="query.page" v-model:limit="query.pageSize" @pagination="load" />

    <el-dialog v-model="visible" :title="editingId ? '编辑客户' : '新增客户'" width="680px" append-to-body>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="客户编码" required><el-input v-model="form.code" :disabled="Boolean(editingId)" /></el-form-item></el-col><el-col :span="12"><el-form-item label="客户名称" required><el-input v-model="form.name" /></el-form-item></el-col></el-row>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="客户类型" required><el-select v-model="form.customerType" style="width:100%"><el-option label="团购" value="group_purchase" /><el-option label="批发" value="wholesale" /></el-select></el-form-item></el-col><el-col :span="12"><el-form-item label="地区"><el-input v-model="form.region" /></el-form-item></el-col></el-row>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactName" /></el-form-item></el-col><el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.contactPhone" /></el-form-item></el-col></el-row>
        <el-form-item label="负责人 ID"><el-input v-model="salespersonText" placeholder="留空表示当前操作人" /></el-form-item>
        <el-form-item label="协作者 ID"><el-input v-model="collaboratorText" placeholder="多个用户 ID 用逗号分隔" /></el-form-item>
        <el-form-item label="内部备注"><el-input v-model="form.internalNote" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { archiveCustomer, createCustomer, listCustomers, updateCustomer, type CustomerBody, type CustomerView } from '@/api/fashion/customer'
import type { FashionCustomer } from '@/api/fashion/types'

const loading=ref(false), saving=ref(false), visible=ref(false), editingId=ref(''),error=ref('')
const query=reactive({status:'',keyword:'',page:1,pageSize:20})
const page=reactive({items:[] as CustomerView[],total:0})
const form=reactive<CustomerBody>({code:'',name:'',customerType:'group_purchase',collaboratorIds:[]})
const salespersonText=ref(''), collaboratorText=ref('')
let controller:AbortController|undefined
let request=0
async function load(){controller?.abort();controller=new AbortController();const id=++request;loading.value=true;error.value='';page.items=[];page.total=0;try{const r=await listCustomers({...query},controller.signal);if(id===request){page.items=r.data.items;page.total=r.data.total}}catch(e){if(id===request)error.value=e instanceof Error?e.message:'客户读取失败，请重试'}finally{if(id===request)loading.value=false}}
function search(){query.page=1;return load()}
function reset(){Object.assign(form,{code:'',name:'',customerType:'group_purchase',contactName:'',contactPhone:'',region:'',salespersonId:undefined,collaboratorIds:[],internalNote:'',rowVersion:undefined});salespersonText.value='';collaboratorText.value=''}
function openCreate(){reset();editingId.value='';visible.value=true}
function openEdit(row:FashionCustomer){reset();editingId.value=row.id;Object.assign(form,row);salespersonText.value=row.salespersonId;collaboratorText.value=row.collaboratorIds.join(',');visible.value=true}
function ids(value:string){return value.split(',').map(v=>v.trim()).filter(Boolean).map(Number).filter(v=>Number.isSafeInteger(v)&&v>0)}
async function save(){saving.value=true;try{form.salespersonId=salespersonText.value.trim()?Number(salespersonText.value):undefined;form.collaboratorIds=ids(collaboratorText.value);if(editingId.value)await updateCustomer(editingId.value,form);else await createCustomer(form);ElMessage.success('客户已保存');visible.value=false;await load()}finally{saving.value=false}}
async function archive(row:FashionCustomer){await ElMessageBox.confirm(`确认归档客户“${row.name}”？`,'归档客户',{type:'warning'});await archiveCustomer(row.id,row.rowVersion);ElMessage.success('客户已归档');await load()}
onMounted(load);onBeforeUnmount(()=>{request++;controller?.abort()})
</script>

<style scoped>.toolbar{display:flex;gap:8px}</style>
