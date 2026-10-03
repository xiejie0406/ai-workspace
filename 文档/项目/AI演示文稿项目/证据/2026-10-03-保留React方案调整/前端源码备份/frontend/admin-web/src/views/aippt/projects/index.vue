<script setup lang="ts">
defineOptions({name:'AipptProjects'})
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/aippt'
import type { Project } from '@/types/aippt'
const router=useRouter(),rows=ref<Project[]>([]),total=ref(0),loading=ref(false),error=ref('')
const filters=reactive({pageNum:1,pageSize:12,title:'',status:''})
async function load(){loading.value=true;error.value='';try{const result=await api.projects(filters);rows.value=result.rows;total.value=result.total}catch(e:any){error.value=e.message||'项目读取失败'}finally{loading.value=false}}
function open(project:Project){router.push({path:project.status==='ready'||project.status==='partial'?'/aippt/editor':'/aippt/detail',query:{projectId:project.id}})}
async function remove(project:Project){try{await ElMessageBox.confirm(`删除“${project.title}”将停止新任务并清理所属文件，是否继续？`,'删除演示文稿',{type:'warning'});await api.remove(project.id,project.input_revision);ElMessage.success('已提交删除与文件清理');await load()}catch(e:any){if(e!=='cancel'&&e!=='close')error.value=e.message}}
async function rename(project:Project){try{const r=await ElMessageBox.prompt('请输入项目名称','修改名称',{inputValue:project.title,inputValidator:v=>!!v?.trim()&&v.length<=200||'名称需要1–200字'});await api.update(project.id,{title:r.value.trim(),input_revision:project.input_revision});await load()}catch{}}
onMounted(load)
</script>
<template><div class="app-container aippt-projects"><div class="heading"><div><h2>我的演示文稿</h2><p>从材料到提纲、生成、编辑与可编辑 PPTX。</p></div><el-button v-hasPermi="['aippt:project:add']" type="primary" @click="router.push('/aippt/create')">新建演示文稿</el-button></div>
<el-form inline @submit.prevent="filters.pageNum=1;load()"><el-form-item label="名称"><el-input v-model="filters.title" clearable placeholder="查找项目"/></el-form-item><el-form-item label="状态"><el-select v-model="filters.status" clearable style="width:160px"><el-option v-for="s in ['draft','outline_ready','generating','ready','partial','failed']" :key="s" :value="s" :label="({draft:'草稿',outline_ready:'待生成',generating:'生成中',ready:'就绪',partial:'部分完成',failed:'失败'} as Record<string,string>)[s]"/></el-select></el-form-item><el-button @click="filters.pageNum=1;load()">查询</el-button></el-form>
<el-alert v-if="error" type="error" :title="error" show-icon><el-button @click="load">重新读取</el-button></el-alert>
<el-table v-loading="loading" :data="rows" empty-text="暂无项目，点击新建开始"><el-table-column prop="title" label="演示文稿" min-width="220"><template #default="{row}"><el-link type="primary" @click="open(row)">{{row.title}}</el-link></template></el-table-column><el-table-column prop="audience" label="受众" min-width="130"/><el-table-column prop="page_count" label="页数" width="80"/><el-table-column prop="status" label="状态" width="130"/><el-table-column prop="updated_at" label="更新时间" min-width="170"/><el-table-column label="操作" width="230"><template #default="{row}"><el-button link type="primary" @click="open(row)">继续编辑</el-button><el-button v-hasPermi="['aippt:project:edit']" link @click="rename(row)">改名</el-button><el-button v-hasPermi="['aippt:project:remove']" link type="danger" @click="remove(row)">删除</el-button></template></el-table-column></el-table>
<el-pagination v-model:current-page="filters.pageNum" v-model:page-size="filters.pageSize" :total="total" :page-sizes="[12,24,48]" layout="total, sizes, prev, pager, next" @change="load"/></div></template>
<style scoped>.heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.heading p{color:#606266}.el-pagination{margin-top:20px;justify-content:flex-end}</style>
