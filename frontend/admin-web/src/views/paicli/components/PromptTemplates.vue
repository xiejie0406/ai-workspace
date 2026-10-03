<template>
  <section class="pc-panel">
    <h2>个人与项目提示词覆盖</h2>
    <el-alert title="按原相对资源路径覆盖；项目层优先于个人层，builtin 保留在原生发行包。提示词正文属于任务资料，不能扩大执行权限。" type="info" :closable="false" />
    <el-alert v-if="!canRead" title="当前角色没有连接配置读取权限。" type="info" :closable="false" />
    <template v-else><div class="pc-actions"><el-button :loading="loading" @click="load">刷新模板元数据</el-button><el-button v-hasPermi="['paicli:connection:manage']" @click="create">添加提示词覆盖</el-button></div>
      <el-table :data="templates"><el-table-column prop="relativePath" label="相对资源路径" min-width="240" /><el-table-column label="范围"><template #default="{row}">{{row.scope==='PROJECT'?'当前项目':'当前用户'}}</template></el-table-column><el-table-column prop="version" label="版本" /><el-table-column prop="sha256" label="正文摘要" min-width="250" /><el-table-column label="状态"><template #default="{row}">{{row.enabled?'启用':'停用'}}</template></el-table-column><el-table-column label="操作" width="200"><template #default="{row}"><el-button v-hasPermi="['paicli:context:raw']" link @click="read(row)">读原始正文</el-button><el-button v-hasPermi="['paicli:connection:manage']" link @click="toggle(row)">{{row.enabled?'停用':'启用'}}</el-button></template></el-table-column></el-table><el-empty v-if="!templates.length" description="暂无个人或项目覆盖，沿用发行包 builtin" />
    </template>
    <el-alert v-if="error || localError" :title="error || localError" type="error" :closable="false" />
    <el-dialog v-model="open" :title="form.id?'读取或修改当前版本':'添加提示词覆盖'" width="760px" class="pc-dialog"><el-form label-width="140px"><el-form-item label="范围"><el-select v-model="form.scope" :disabled="!!form.id"><el-option label="当前用户个人" value="PERSONAL" /><el-option label="当前项目" value="PROJECT" /></el-select></el-form-item><el-form-item label="相对资源路径"><el-input v-model="form.relativePath" :disabled="!!form.id" placeholder="prompts/personalities/calm.md（与原资源路径一致）" /></el-form-item><el-form-item label="完整正文"><el-input v-model="form.content" type="textarea" :rows="12" :readonly="!canManage" /></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="!canManage" /></el-form-item></el-form><p class="pc-muted">正文最多 65536 UTF-8 字节；已登记范围和路径不能通过更新改写。实际任务装配沿用中心冻结版本。</p><el-alert v-if="error || localError" :title="error || localError" type="error" :closable="false" /><template #footer><el-button @click="open=false">关闭</el-button><el-button v-hasPermi="['paicli:connection:manage']" type="primary" :loading="busy" :disabled="!form.content?.trim() || !form.relativePath" @click="save">保存当前版本</el-button></template></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,reactive,ref,watch } from 'vue'
import { checkPermi } from '@/utils/permission'
import { getPromptTemplate,listPromptTemplates,savePromptTemplate } from '@/api/paicli/prompts'
import type { PcPromptTemplate } from '@/types/paicli'
import { errorText,operationKeys } from '../model'
import { useOperations } from '../useOperations'
const props=defineProps<{workspaceId:string}>(),{busy,error,run}=useOperations(),keys=operationKeys(),templates=ref<PcPromptTemplate[]>([]),loading=ref(false),localError=ref(''),open=ref(false),form=reactive<Partial<PcPromptTemplate>>({})
const canRead=computed(()=>checkPermi(['paicli:connection:read'])),canManage=computed(()=>checkPermi(['paicli:connection:manage']))
let generation=0
async function load(){const id=props.workspaceId,g=++generation;if(!id||!canRead.value)return;loading.value=true;localError.value='';try{const response=await listPromptTemplates(id);if(g===generation&&id===props.workspaceId)templates.value=response.data}catch(cause){if(g===generation)localError.value=errorText(cause)}finally{if(g===generation)loading.value=false}}
function replace(data:Partial<PcPromptTemplate>){for(const key of Object.keys(form))delete(form as Record<string,unknown>)[key];Object.assign(form,data);localError.value='';error.value=''}
function create(){if(!canManage.value)return;replace({scope:'PERSONAL',relativePath:'',content:'',enabled:true,version:0});open.value=true}
async function read(row:PcPromptTemplate){if(!checkPermi(['paicli:context:raw']))return;const g=generation;await run(async()=>{const response=await getPromptTemplate(row.id);if(g===generation){replace(response.data);open.value=true}})}
async function save(){if(!canManage.value)return;if(new TextEncoder().encode(form.content||'').length>65536){localError.value='正文超过 65536 UTF-8 字节';return};const data={...form,workspaceId:form.scope==='PROJECT'?props.workspaceId:undefined};await run(async()=>{await savePromptTemplate(data,keys.get(data));keys.done(data);open.value=false;await load()})}
async function toggle(row:PcPromptTemplate){if(!canManage.value)return;if(!checkPermi(['paicli:context:raw'])){localError.value='修改现有模板须先取得原始正文读取权限，不能以空正文覆盖';return};await run(async()=>{const full=(await getPromptTemplate(row.id)).data;const data={...full,enabled:!row.enabled};await savePromptTemplate(data,keys.get(data));keys.done(data);await load()})}
watch(()=>props.workspaceId,()=>{generation++;templates.value=[];open.value=false;void load()},{immediate:true});onBeforeUnmount(()=>{generation++})
</script>
