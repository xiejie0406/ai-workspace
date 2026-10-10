<template><div class="app-container ps-page"><h2>充值套餐</h2><el-alert v-if="error||pageError" :title="error||pageError" type="error" :closable="false"/><div class="ps-toolbar"><el-button v-hasPermi="['paismart:recharge:manage']" type="primary" @click="edit()">新增套餐</el-button><el-button @click="load()">刷新</el-button></div><el-table :data="rows" v-loading="loading"><el-table-column prop="name" label="套餐名称" min-width="200"/><el-table-column label="售价" width="130"><template #default="{row}">¥ {{(row.amountFen/100).toFixed(2)}}</template></el-table-column><el-table-column prop="llmTokens" label="对话 Token" width="140"/><el-table-column prop="embeddingTokens" label="向量 Token" width="140"/><el-table-column prop="description" label="套餐说明" min-width="220"/><el-table-column prop="benefit" label="权益说明" min-width="220"/><el-table-column prop="sortOrder" label="排序" width="100"/><el-table-column label="状态" width="110"><template #default="{row}">{{row.enabled?'已上架':'已下架'}}</template></el-table-column><el-table-column label="操作" width="180"><template #default="{row}"><el-button v-hasPermi="['paismart:recharge:manage']" link @click="edit(row)">编辑</el-button><el-button v-hasPermi="['paismart:recharge:manage']" link type="danger" @click="remove(row)">删除</el-button></template></el-table-column></el-table><el-dialog v-model="open" :title="form.id?'编辑套餐':'新增套餐'" width="600px"><el-form label-width="120px"><el-form-item label="套餐名称" required><el-input v-model="form.name" maxlength="100"/></el-form-item><el-form-item label="价格（分）"><el-input-number v-model="form.amountFen" :min="1" :max="10000000" :precision="0"/></el-form-item><el-form-item label="对话 Token"><el-input-number v-model="form.llmTokens" :min="0" :max="1000000000" :precision="0"/></el-form-item><el-form-item label="向量 Token"><el-input-number v-model="form.embeddingTokens" :min="0" :max="1000000000" :precision="0"/></el-form-item><el-form-item label="套餐说明"><el-input v-model="form.description" type="textarea" maxlength="500"/></el-form-item><el-form-item label="权益说明"><el-input v-model="form.benefit" type="textarea" maxlength="1000"/></el-form-item><el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="100000" :precision="0"/></el-form-item><el-form-item label="上架"><el-switch v-model="form.enabled"/></el-form-item></el-form><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><template #footer><el-button @click="open=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存套餐</el-button></template></el-dialog></div></template>
<script setup lang="ts">
import { onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { adminPackages,deletePackage,savePackage } from '@/api/paismart/recharge'
import type { RechargePackage } from '@/types/paismart'
import { errorText,usePage } from '../../composables/usePage'
import '../../common.css'
const {rows,loading,error,load}=usePage(()=>adminPackages()),open=ref(false),saving=ref(false),pageError=ref(''),formError=ref(''),form=ref<Partial<RechargePackage>>({})
let formSerial=0,active=true
watch(form,()=>{formSerial++},{deep:true,flush:'sync'})
watch(open,value=>{if(!value)formSerial++},{flush:'sync'})
function edit(row?:RechargePackage){formSerial++;form.value=row?{...row}:{name:'',amountFen:100,llmTokens:0,embeddingTokens:0,enabled:false,description:'',benefit:'',sortOrder:0};formError.value='';open.value=true}
async function save(){
  if(saving.value||!active||!open.value)return
  if(!form.value.name?.trim()||!(form.value.llmTokens||form.value.embeddingTokens)){formError.value='请填写套餐名称和至少一种 Token 数量';return}
  const input={...form.value},current=formSerial,sameForm=()=>active&&open.value&&current===formSerial
  saving.value=true;formError.value=''
  try{await savePackage(input);if(sameForm())open.value=false;if(active)await load()}
  catch(e){if(sameForm())formError.value=errorText(e)}
  finally{if(active)saving.value=false}
}
async function remove(row:RechargePackage){try{await ElMessageBox.confirm(`删除「${row.name}」不会改写已下单订单的价格快照。`, '删除套餐',{type:'warning'});await deletePackage(row);await load()}catch(e){if(e!=='cancel'&&e!=='close')pageError.value=errorText(e)}}
onMounted(()=>void load())
onBeforeUnmount(()=>{active=false;formSerial++})
</script>
