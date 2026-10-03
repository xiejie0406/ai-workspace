import {reactive} from 'vue'
import type {Project} from '@/types/aippt'
import type {ThemeOverrides} from '@/views/aippt/render/themeOverrides'
import {cloneJson} from '@/views/aippt/render/clone'
import {commandErrorStatus} from './commandError'
export type ThemeDraft={project_id:string;theme_id:string;theme_overrides:ThemeOverrides|null}
type Command={draft:ThemeDraft;key:string;body?:Record<string,unknown>;resolve:(p:Project)=>void;reject:(e:unknown)=>void}
export function createThemeSaveQueue(initial:Project,send:(pid:string,body:Record<string,unknown>,key:string)=>Promise<Project>,commit:(p:Project)=>void=()=>{}){
 const state=reactive({project:cloneJson(initial),draft:null as ThemeDraft|null,pending:0,status:'saved' as 'saved'|'saving'|'failed'|'conflict',error:''})
 const commands:Command[]=[];let tail=Promise.resolve(),disposed=false,committed=commit
 function run(){if(disposed||state.status!=='saved'||!commands.length)return;const command=commands[0];state.status='saving';tail=tail.then(async()=>{try{command.body||={theme_revision:state.project.theme_revision,theme_id:command.draft.theme_id,theme_overrides:cloneJson(command.draft.theme_overrides)};const result=await send(command.draft.project_id,cloneJson(command.body),command.key);if(disposed)return;state.project=result;commands.shift();state.pending--;state.draft=commands.length?cloneJson(commands[commands.length-1].draft):null;state.status='saved';state.error='';committed(result);command.resolve(result);run()}catch(e:any){if(disposed)return;state.error=e.message||'主题保存失败，预览草稿已保留';state.status=commandErrorStatus(e)===409?'conflict':'failed';command.reject(e)}})}
 function enqueue(draft:ThemeDraft){if(disposed)return Promise.reject(new Error('主题编辑会话已关闭'));if(draft.project_id!==initial.id)return Promise.reject(new Error('主题草稿所属项目已改变'));state.draft=cloneJson(draft);state.pending++;const result=new Promise<Project>((resolve,reject)=>commands.push({draft:cloneJson(draft),key:crypto.randomUUID(),resolve,reject}));run();return result}
 async function flush(){while(state.pending){await tail;if(state.status==='failed'||state.status==='conflict')throw new Error(state.error);if(disposed)throw new Error('主题编辑会话已关闭')}return state.project}
 function retry(server?:Project){if(state.status==='saving')return;if(server){if(state.status!=='conflict'||server.id!==initial.id)throw new Error('只有明确版本冲突可以按同项目最新版本重新应用');state.project=cloneJson(server);commands.forEach(command=>{command.body=undefined;command.key=crypto.randomUUID()})}state.status='saved';state.error='';run()}
 function replaceServer(server:Project){if(!state.pending&&server.id===initial.id&&server.theme_revision>=state.project.theme_revision)state.project=cloneJson(server)}
 function dispose(){disposed=true;commands.splice(0).forEach(command=>command.reject(new Error('主题编辑会话已关闭')));state.pending=0;state.draft=null}
 function setOnCommit(callback:(p:Project)=>void){committed=callback}
 return{state,enqueue,flush,retry,replaceServer,dispose,setOnCommit}
}
export type ThemeSaveQueue=ReturnType<typeof createThemeSaveQueue>

