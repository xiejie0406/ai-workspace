<template>
  <div class="app-container">
    <el-alert title="仓库编码用于库存归属，创建后不可修改。停用默认仓库前必须先更换默认仓库；历史库存保留。" type="info" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="space" />
    <el-form inline class="space" @submit.prevent="load">
      <el-form-item label="仓库"><el-input v-model="keyword" clearable placeholder="名称或编码" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="status" clearable style="width:140px"><el-option label="启用" value="active" /><el-option label="停用" value="inactive" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" @click="load">刷新</el-button><el-button v-hasPermi="['fashion:stock:import']" @click="edit()">新增仓库</el-button></el-form-item>
    </el-form>
    <el-table :data="filtered" v-loading="loading" :empty-text="error?'仓库读取失败，请重试':'没有符合条件的仓库'">
      <el-table-column prop="code" label="仓库编码" min-width="150" /><el-table-column prop="name" label="仓库名称" min-width="160" />
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status==='active'?'success':'info'">{{row.status==='active'?'启用':'停用'}}</el-tag></template></el-table-column>
      <el-table-column label="默认" width="90"><template #default="{row}">{{row.isDefault?'默认仓库':'—'}}</template></el-table-column>
      <el-table-column prop="stockSkuCount" label="关联库存SKU数" width="150" /><el-table-column label="最近库存业务时间" min-width="200"><template #default="{row}">{{row.lastStockAsOf || '尚无库存记录'}}</template></el-table-column>
      <el-table-column prop="sort" label="顺序" width="80" /><el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
      <el-table-column label="操作" width="300" fixed="right"><template #default="{row}">
        <el-button link type="primary" v-hasPermi="['fashion:stock:import']" @click="edit(row)">编辑</el-button>
        <el-button link v-hasPermi="['fashion:stock:import']" :disabled="row.isDefault" @click="changeStatus(row)">{{row.status==='active'?'停用':'启用'}}</el-button>
        <el-button link type="primary" v-hasPermi="['fashion:stock:import']" :disabled="row.isDefault||row.status!=='active'" @click="makeDefault(row)">设为默认</el-button>
        <el-button link type="primary" @click="router.push({path:'/fashion/stock',query:{warehouseCode:row.code}})">查看库存</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="editing?'编辑仓库':'新增仓库'" width="560px" :close-on-click-modal="false">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="space" />
      <el-form label-width="100px" @submit.prevent="save">
        <el-form-item label="仓库编码" required><el-input v-model="form.code" :disabled="editing" maxlength="64" placeholder="大写字母、数字、下划线或连字符" /></el-form-item>
        <el-form-item label="仓库名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="显示顺序"><el-input-number v-model="form.sort" :min="0" :precision="0" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listWarehouses, createWarehouse, updateWarehouse, setWarehouseStatus, setDefaultWarehouse, type FashionWarehouse } from '@/api/fashion/warehouse'
const router=useRouter(),items=ref<FashionWarehouse[]>([]),keyword=ref(''),status=ref(''),loading=ref(false),saving=ref(false),error=ref(''),visible=ref(false),editing=ref(false),formError=ref('')
const form=reactive({code:'',name:'',status:'active' as FashionWarehouse['status'],sort:0,remark:''})
const filtered=computed(()=>items.value.filter(item=>(!status.value||item.status===status.value)&&(!keyword.value||`${item.code} ${item.name}`.includes(keyword.value.trim()))))
let request=0
async function load(){const id=++request;loading.value=true;error.value='';items.value=[];try{const result=await listWarehouses();if(id===request)items.value=result.data.items}catch(e){if(id===request)error.value=e instanceof Error?e.message:'仓库加载失败'}finally{if(id===request)loading.value=false}}
function edit(row?:FashionWarehouse){editing.value=Boolean(row);Object.assign(form,row?{code:row.code,name:row.name,status:row.status,sort:row.sort,remark:row.remark??''}:{code:'',name:'',status:'active',sort:0,remark:''});formError.value='';visible.value=true}
async function save(){if(!/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(form.code.trim())){formError.value='仓库编码须以大写字母或数字开头，仅含大写字母、数字、下划线和连字符';return}if(!form.name.trim()){formError.value='请填写仓库名称';return}saving.value=true;formError.value='';try{const value={name:form.name.trim(),status:form.status,sort:form.sort,remark:form.remark.trim()};if(editing.value)await updateWarehouse(form.code,value);else await createWarehouse({code:form.code.trim(),...value});visible.value=false;ElMessage.success('仓库已保存');await load()}catch(e){formError.value=e instanceof Error?e.message:'保存失败'}finally{saving.value=false}}
async function changeStatus(row:FashionWarehouse){try{await ElMessageBox.confirm(`确认${row.status==='active'?'停用':'启用'}仓库“${row.name}”？历史库存保留。`,'仓库状态');await setWarehouseStatus(row.code,row.status==='active'?'inactive':'active');await load()}catch(e){if(e instanceof Error)error.value=e.message}}
async function makeDefault(row:FashionWarehouse){try{await ElMessageBox.confirm(`将“${row.name}”设为默认仓库？`,'默认仓库');await setDefaultWarehouse(row.code);await load()}catch(e){if(e instanceof Error)error.value=e.message}}
onMounted(load)
</script>
<style scoped>.space{margin:16px 0}</style>
