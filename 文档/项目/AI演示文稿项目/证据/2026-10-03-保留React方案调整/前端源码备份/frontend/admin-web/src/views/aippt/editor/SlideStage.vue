<script setup lang="ts">
import { computed } from 'vue'
import type { Slide, Theme, Slot, EditableBlockCommit, Rect } from '../render/types'
import { solve } from '../render/flexLayout'
import { getLayout } from '../render/design'
import { rectToStyle, resolveColor, pt, webFontStack } from '../render/style'
import { iterAmbientShapes } from '../render/ambient'
import { iterSkinDecorations } from '../render/flexSkin'
import BlockView from './BlockView.vue'
const props=withDefaults(defineProps<{slide:Slide;theme:Theme;editable?:boolean;selected?:string|null;index?:number;reveal?:boolean}>(),{editable:false,selected:null,index:0,reveal:false});const emit=defineEmits<{select:[id:string|null];commit:[id:string,body:EditableBlockCommit]}>()
const fixed=computed(()=>props.slide.layout_mode!=='flex'||!props.slide.layout_tree?getLayout(props.slide.layout_id):null)
const placements=computed(()=>new Map(props.slide.layout_tree?solve(props.slide.layout_tree).map(p=>[p.block_id,p]):[]))
const entries=computed(()=>props.slide.blocks.flatMap(block=>{const p=placements.value.get(block.id),slot=fixed.value?.slots.find(s=>s.id===block.slot_id) as Slot|undefined;const rect=fixed.value?slot?.rect:p?.rect;if(!rect)return[];return[{block,rect,slot:slot||{id:block.slot_id,accepts:[block.type],required:true,rect,text_style:p?.text_style,capacity:{}} as Slot}]}))
const ambient=computed(()=>iterAmbientShapes(props.theme,props.slide.layout_id,props.index,entries.value.map(x=>x.rect)))
const skin=computed(()=>props.slide.layout_tree&&!fixed.value?iterSkinDecorations(props.slide.layout_tree):[])
function ambientStyle(a:any){return{...rectToStyle(a.rect),background:a.kind==='text'?undefined:a.color,color:a.color,borderRadius:a.kind==='ellipse'?'50%':undefined,fontFamily:webFontStack(a.font==='body'?props.theme.fonts.body:props.theme.fonts.display),fontSize:pt(a.size_pt||0),fontWeight:a.weight||400,letterSpacing:pt(a.letter_spacing_pt||0),textAlign:a.align}}
function skinStyle(d:any){const color=resolveColor(props.theme,d.color_token);return{...rectToStyle(d.rect),background:['outline_box'].includes(d.kind)?undefined:color,border:d.kind==='outline_box'?`${pt(1.5)} solid ${color}`:undefined,borderRadius:['timeline_dot','number_badge'].includes(d.kind)?'50%':pt(d.radius_pt||0),color:resolveColor(props.theme,'background'),fontSize:pt(11),display:'grid',placeItems:'center'}}
function deselect(e:PointerEvent){if(!(e.target as HTMLElement).closest('[data-block-id]'))emit('select',null)}
</script>
<template><div class="slide-stage" :class="{reveal}" :style="{background:resolveColor(theme,'background')}" @pointerdown="deselect"><div class="ambient"><div v-for="(a,i) in ambient" :key="i" aria-hidden="true" :style="ambientStyle(a)">{{a.kind==='text'?a.text:''}}</div></div><template v-if="fixed"><div v-for="(d,i) in fixed.decorations" :key="'dec'+i" aria-hidden="true" :style="{...rectToStyle(d.rect),background:resolveColor(theme,d.color)}"/></template><div v-for="(d,i) in skin" :key="'skin'+i" aria-hidden="true" :style="skinStyle(d)">{{(d as any).text||''}}</div><div v-for="e in entries" :key="e.block.id" :data-block-id="e.block.id" :class="['placed',{selected:selected===e.block.id}]" :style="{...rectToStyle(e.rect),overflow:reveal?'visible':'hidden'}" @pointerdown.stop="editable&&emit('select',e.block.id)"><span v-if="editable&&e.block.locked" class="locked" title="手工内容已保护，AI不会覆盖" aria-label="手工内容已保护" :style="{background:resolveColor(theme,'accent')}"/><BlockView :block="e.block" :slot="e.slot" :theme="theme" :editable="editable" @commit="(id,body)=>emit('commit',id,body)"/></div><slot/></div></template>
<style scoped>.slide-stage{container-type:size;position:relative;width:100%;aspect-ratio:16/9;overflow:hidden;isolation:isolate}.slide-stage.reveal{overflow:visible}.ambient{position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:0}.placed{z-index:1;outline:2px solid transparent;outline-offset:3px}.placed.selected{outline-color:#409eff;z-index:3}.locked{position:absolute;right:.4cqw;top:.4cqw;width:.65cqw;height:.65cqw;z-index:4;border-radius:50%}</style>
