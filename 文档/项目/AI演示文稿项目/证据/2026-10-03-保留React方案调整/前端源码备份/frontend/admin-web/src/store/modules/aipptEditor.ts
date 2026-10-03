import { defineStore } from 'pinia'
import { shallowReactive } from 'vue'
import { createOutlineSaveQueue,type OutlineSaveQueue } from '@/composables/aippt/useOutlineSaveQueue'
import { createSlideSaveQueue } from '@/composables/aippt/useSlideSaveQueue'
import type { SlideSaveQueue } from '@/composables/aippt/useSlideSaveQueue'
import {createThemeSaveQueue,type ThemeSaveQueue} from '@/composables/aippt/useThemeSaveQueue'
export const useAipptEditorStore=defineStore('aipptEditor',()=>{
 const themeQueues=shallowReactive(new Map<string,ThemeSaveQueue>())
 function themeQueue(project:import('@/types/aippt').Project,sender:Parameters<typeof createThemeSaveQueue>[1],commit:NonNullable<Parameters<typeof createThemeSaveQueue>[2]>){let q=themeQueues.get(project.id);if(!q){q=createThemeSaveQueue(project,sender,commit);themeQueues.set(project.id,q)}else{q.setOnCommit(commit);q.replaceServer(project)}return q}
 const outlineQueues=shallowReactive(new Map<string,OutlineSaveQueue>())
 function outlineQueue(project:string,outline:import('@/types/aippt').Outline,sender:Parameters<typeof createOutlineSaveQueue>[1]){let q=outlineQueues.get(project);if(!q){q=createOutlineSaveQueue(outline,sender);outlineQueues.set(project,q)}else{q.setSender(sender);q.replace(outline)}return q}
 const queues=shallowReactive(new Map<string,SlideSaveQueue>())
 function queue(project:string,slide:import('@/types/aippt').Slide,onCommit:(slide:import('@/types/aippt').Slide)=>void){const key=project+':'+slide.id;let q=queues.get(key);if(!q){q=createSlideSaveQueue(slide,onCommit);queues.set(key,q)}else {q.setOnCommit(onCommit);q.replaceServer(slide)};return q}
 async function flush(project:string){await Promise.all([...queues.entries()].filter(([key])=>key.startsWith(project+':')).map(([,q])=>q.flush()))}
 function dirty(project:string){return !!themeQueues.get(project)?.state.pending||[...queues.entries()].some(([key,q])=>key.startsWith(project+':')&&(q.state.pending>0||q.state.draft))}
 function clear(){outlineQueues.clear();queues.forEach(q=>q.dispose());queues.clear();themeQueues.forEach(q=>q.dispose());themeQueues.clear()}
 return {queues,outlineQueues,themeQueues,themeQueue,outlineQueue,queue,flush,dirty,clear}
})
