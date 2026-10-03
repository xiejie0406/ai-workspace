import fs from 'node:fs'
const root='D:/2025Ai/26-05-23/java-workspace/frontend/admin-web/src/views/paismart/'
function replace(file,changes){let text=fs.readFileSync(root+file,'utf8');for(const [before,after]of changes){if(!text.includes(before))throw new Error(file+' 缺少预期原文 '+before);text=text.replace(before,after)}fs.writeFileSync(root+file,text)}
replace('chat/components/ChatMessage.vue',[
 ['import {renderSafeMarkdown}',"import {referenceVerdict} from '../../composables/referencePresentation'\nimport {renderSafeMarkdown}"],
 ['{{reference.page?\'· 第 \'+reference.page+\' 页\':\'\'}}</el-button>','{{reference.page?\'· 第 \'+reference.page+\' 页\':\'\'}} <span class="citation-state" :class="{\'citation-state--suspicious\':referenceVerdict(reference).suspicious}">{{referenceVerdict(reference).label}}</span><small v-if="referenceVerdict(reference).suspicious" class="citation-reason">{{referenceVerdict(reference).reason}}</small></el-button>'],
 ['.references{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}', '.references{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.references :deep(.el-button){height:auto;white-space:normal;max-width:100%;text-align:left}.references :deep(.el-button>span){display:block;overflow-wrap:anywhere}']
])
replace('chat/index.vue',[
 ["import { errorText } from '../composables/usePage'", "import { errorText } from '../composables/usePage'\nimport {referenceVerdict} from '../composables/referencePresentation'"],
 ["{{reference.page?'第 '+reference.page+' 页':''}}</el-button>","{{reference.page?'第 '+reference.page+' 页':''}} <span class=\"citation-state\" :class=\"{'citation-state--suspicious':referenceVerdict(reference).suspicious}\">{{referenceVerdict(reference).label}}</span><small v-if=\"referenceVerdict(reference).suspicious\" class=\"citation-reason\">{{referenceVerdict(reference).reason}}</small></el-button>"]
])
replace('spaces/index.vue',[
 ["deleteSpace, listSpaces, saveSpace, setPrimarySpace", "deleteSpace, listSpaces, myMembership, saveSpace, setPrimarySpace"],
 ["import { onMounted, ref }", "import { computed, onMounted, ref }"],
 ['@click="load()">刷新', '@click="refresh">刷新'],
 ['</div><el-table :data="rows"', '</div><p role="status">当前主空间：{{primaryLoaded?(rows.find(s=>String(s.id)===String(primarySpaceId))?.name||(primarySpaceId?\'空间 \'+primarySpaceId:\'尚未设置\')):\'未读取\'}}</p><el-alert v-if="membershipError" :title="membershipError" type="error" :closable="false"/><el-table :data="rows"'],
 ['maxMiB=ref(0)', "maxMiB=ref(0),primarySpaceId=ref<string|number>(),primaryLoaded=ref(false),membershipError=ref('')"],
 ['async function primary(row:Space){try{await setPrimarySpace(row.id);ElMessage.success(\'主空间已更新\')}catch(e){pageError.value=errorText(e)}}', "async function loadPrimary(){primaryLoaded.value=false;membershipError.value='';try{const member=(await myMembership()).data;if(member.eligible===false){membershipError.value='当前知识库资格未启用，主空间操作仍由服务端核对';return}primarySpaceId.value=member.primarySpaceId;primaryLoaded.value=true}catch(e){membershipError.value=errorText(e)}}\nasync function refresh(){await Promise.all([load(),loadPrimary()])}\nasync function primary(row:Space){pageError.value='';try{await setPrimarySpace(row.id);await loadPrimary();ElMessage.success('主空间已更新')}catch(e){pageError.value=errorText(e)}}"],
 ['onMounted(()=>void load())','onMounted(()=>void refresh())']
])
// 共用已有业务样式，不引入全局组件/导航。
fs.appendFileSync(root+'common.css','\n.citation-state{margin-left:6px;font-size:12px;color:var(--el-text-color-secondary)}.citation-state--suspicious{color:var(--el-color-warning-dark-2);font-weight:600}.citation-reason{display:block;margin-top:4px;font-weight:normal;color:var(--el-text-color-regular);white-space:normal;overflow-wrap:anywhere}\n')
