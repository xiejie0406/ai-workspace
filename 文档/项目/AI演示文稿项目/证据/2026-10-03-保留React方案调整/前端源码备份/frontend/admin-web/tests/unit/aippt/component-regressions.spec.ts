import {describe,it,expect,vi} from 'vitest'
import {mount} from '@vue/test-utils'
import {defineComponent} from 'vue'
import ivory from '../../../../../contracts/aippt/catalog/themes/ivory.json'
import obsidian from '../../../../../contracts/aippt/catalog/themes/obsidian.json'
import {registerCatalog} from '@/views/aippt/render/design'
import ThemePanel from '@/views/aippt/editor/ThemePanel.vue'
import BlockView from '@/views/aippt/editor/BlockView.vue'
import type {Project,Theme} from '@/types/aippt'
import type {Slot} from '@/views/aippt/render/types'
vi.mock('@/api/aippt',()=>({api:{binary:vi.fn()}}))

const select=defineComponent({props:['modelValue'],emits:['update:modelValue','change'],template:'<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot/></select>'})
const button=defineComponent({emits:['click'],template:'<button @click="$emit(\'click\')"><slot/></button>'})
const global={stubs:{ElForm:{template:'<form><slot/></form>'},ElFormItem:{template:'<label><slot/></label>'},ElSelect:select,ElOption:{props:['value','label'],template:'<option :value="value">{{label}}</option>'},ElColorPicker:true,ElInputNumber:true,ElButton:button}}
function project(id:string,theme_id:string,theme_revision=1):Project{return{id,title:id,audience:'受众',tone:'professional',page_count:5,layout_mode:'fixed',content_density:'medium',theme_id,theme_revision,input_revision:1,deck_revision:1,status:'ready',theme_overrides:{}}}
function themes(){registerCatalog({themes:[ivory,obsidian] as Theme[],layouts:[]})}
describe('主题表单项目边界',()=>{
 it('同revision的A/B项目切换必须发送B的主题与微调',async()=>{themes();const wrapper=mount(ThemePanel,{props:{project:project('A','ivory')},global});await wrapper.setProps({project:{...project('B','obsidian'),theme_overrides:{palette:{accent:'#123456'}}}});await wrapper.findAll('button')[0].trigger('click');expect(wrapper.emitted('change')?.[0]).toEqual(['obsidian',{palette:{accent:'#123456'}}]);wrapper.unmount()})
 it('同一项目后台刷新保留未保存选择，切换项目清除旧项目草稿',async()=>{themes();const wrapper=mount(ThemePanel,{props:{project:project('A','ivory')},global});await wrapper.find('select').setValue('obsidian');await wrapper.setProps({project:project('A','ivory',2)});expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('obsidian');await wrapper.setProps({project:project('B','ivory',2)});await wrapper.findAll('button')[0].trigger('click');expect(wrapper.emitted('change')?.[0]).toEqual(['ivory',{}]);wrapper.unmount()})
 it('未编辑时同项目主题值改变可更新，即使revision相同',async()=>{themes();const wrapper=mount(ThemePanel,{props:{project:project('A','ivory')},global});await wrapper.setProps({project:project('A','obsidian')});expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('obsidian');wrapper.unmount()})
})
describe('提示块实际可见背景',()=>{
 const slot={id:'body',rect:{x:0,y:0,w:1,h:1},text_style:'body'} as Slot
 for(const variant of ['source','note'] as const)it(`${variant}提示块自定义填充覆盖默认主题底色`,()=>{const wrapper=mount(BlockView,{props:{block:{type:'callout',id:'b',slot_id:'body',text:'提示',variant,icon:null,locked:false,style:{fill:'#FF0000'}},theme:ivory as Theme,slot},global:{stubs:{ChartView:true}}});expect((wrapper.find('.callout').element as HTMLElement).style.background).toBe('rgb(255, 0, 0)');wrapper.unmount()})
})
