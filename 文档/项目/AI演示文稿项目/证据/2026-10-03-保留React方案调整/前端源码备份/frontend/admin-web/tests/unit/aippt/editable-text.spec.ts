import {describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import EditableText from '@/views/aippt/editor/EditableText.vue'
describe('纯文本和输入法编辑',()=>{
 it('输入法组合期间不提交，外部刷新不会覆盖焦点中的中文草稿',async()=>{const wrapper=mount(EditableText,{props:{value:'初始',label:'编辑文字',editable:true}});await wrapper.trigger('focus');await wrapper.trigger('compositionstart');wrapper.element.textContent='中文输入中';await wrapper.setProps({value:'服务器旧值'});expect(wrapper.element.textContent).toBe('中文输入中');await wrapper.trigger('blur');expect(wrapper.emitted('commit')).toBeUndefined();Object.defineProperty(wrapper.element,'innerText',{value:'完整中文',configurable:true});await wrapper.trigger('compositionend');await wrapper.trigger('blur');expect(wrapper.emitted('commit')).toEqual([['完整中文']]);wrapper.unmount()})
 it('展示只写textContent，不执行材料中的HTML',()=>{const value='<img src=x onerror="alert(1)">';const wrapper=mount(EditableText,{props:{value,label:'文字'}});expect(wrapper.element.textContent).toBe(value);expect(wrapper.find('img').exists()).toBe(false);wrapper.unmount()})
})
