<template>
  <section aria-label="个人文件"><div class="pc-toolbar"><el-button v-hasPermi="['paicli:artifact:write']" type="primary" :loading="uploading" @click="input?.click()">上传文件</el-button><el-button :loading="loading" @click="load">刷新文件</el-button><input ref="input" type="file" hidden @change="upload" /><p class="pc-muted">上传的资料和生成成果归当前用户所有。点击分析会回到编码对话，由你发送要求。</p></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-table v-loading="loading" :data="files"><el-table-column prop="fileName" label="文件" min-width="240" /><el-table-column label="大小" width="120"><template #default="{row}">{{row.size}} B</template></el-table-column><el-table-column label="来源" width="140"><template #default="{row}">{{row.purpose === 'USER_FILE' ? '上传资料' : row.purpose === 'TURN_IMAGE' ? '对话图片' : '执行成果'}}</template></el-table-column><el-table-column label="操作" width="280"><template #default="{row}"><PersonalFilePreview :file="row" /><el-button v-hasPermi="['paicli:artifact:read']" link :loading="downloading === row.id" @click="download(row)">下载</el-button><el-button v-hasPermi="['paicli:session:write']" :disabled="row.supportedAnalysis !== true" :title="row.analysisReason || '仅支持已确认可解析的 UTF-8 文本'" link @click="analyse(row)">交给助手分析</el-button></template></el-table-column></el-table><el-empty v-if="!loading && !files.length" description="这里保存你的资料和真实文件成果" :image-size="70" /><Pagination v-if="total > pageSize" v-model:page="page" v-model:limit="pageSize" :total="total" @pagination="load" /></section>
</template>
<script setup lang="ts">
import {onBeforeUnmount,onMounted,ref} from 'vue'
import Pagination from '@/components/Pagination/index.vue'
import PersonalFilePreview from './PersonalFilePreview.vue'
import {checkPermi} from '@/utils/permission'
import {listPersonalFiles,uploadPersonalFile} from '@/api/paicli/files'
import {downloadArtifact} from '@/api/paicli/sessions'
import type {PcPersonalFile} from '@/types/paicli'
import {errorText,operationKeys,saveDownload} from '../model'
import {usePersonalContext} from '../usePersonalContext'
const emit=defineEmits<{analyse:[file:PcPersonalFile]}>(),input=ref<HTMLInputElement>(),personal=usePersonalContext(),files=ref<PcPersonalFile[]>([]),total=ref(0),page=ref(1),pageSize=ref(20),loading=ref(false),uploading=ref(false),downloading=ref(''),error=ref(''),keys=operationKeys()
let generation=0
async function load(){if(!checkPermi(['paicli:artifact:read']))return;const g=++generation;loading.value=true;error.value='';try{const result=await listPersonalFiles(page.value,pageSize.value);if(g===generation){files.value=result.rows;total.value=result.total}}catch(cause){if(g===generation)error.value=errorText(cause)}finally{if(g===generation)loading.value=false}}
async function upload(event:Event){const target=event.target as HTMLInputElement,file=target.files?.[0];if(!file||uploading.value||!checkPermi(['paicli:artifact:write']))return;uploading.value=true;error.value='';try{const context=await personal.prepare();if(!context)throw new Error(personal.error.value);const intent={action:'upload-file',name:file.name,size:file.size,modified:file.lastModified,directoryContextId:personal.directoryContextId.value};await uploadPersonalFile(file,personal.directoryContextId.value,keys.get(intent));keys.done(intent);target.value='';await load()}catch(cause){error.value=errorText(cause)}finally{uploading.value=false}}
async function download(file:PcPersonalFile){if(!checkPermi(['paicli:artifact:read']))return;downloading.value=file.id;try{await saveDownload(await downloadArtifact(file.id),file.fileName.replace(/[\\/\u0000-\u001f]/g,'_'))}catch(cause){error.value=errorText(cause)}finally{downloading.value=''}}
function analyse(file:PcPersonalFile){if(file.supportedAnalysis === true && checkPermi(['paicli:session:write']))emit('analyse',file)}
onMounted(load);onBeforeUnmount(()=>generation++)
</script>
