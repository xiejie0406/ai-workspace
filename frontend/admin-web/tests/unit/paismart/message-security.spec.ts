import { describe,expect,it,vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessage from '@/views/paismart/chat/components/ChatMessage.vue'
import type { Reference } from '@/types/paismart'
vi.mock('@/api/paismart/chat',()=>({sendFeedback:vi.fn()}))
describe('回答与引用输出边界',()=>{
  it('HTML 和脚本即使出现在回答及代码块中，也只能成为文本',()=>{
    const payload='<img src=x onerror="alert(1)"><script>alert(1)</script>\n```html\n<iframe src="javascript:alert(1)"></iframe>\n```'
    const wrapper=mount(ChatMessage,{props:{message:{id:'m',role:'assistant',content:payload},streaming:true},global:{stubs:{'el-button':{template:'<button><slot/></button>'}},directives:{hasPermi:()=>{}}}})
    expect(wrapper.find('img').exists()).toBe(false);expect(wrapper.find('script').exists()).toBe(false);expect(wrapper.find('iframe').exists()).toBe(false);expect(wrapper.text()).toContain('<script>alert(1)</script>');expect(wrapper.find('code').text()).toContain('<iframe');wrapper.unmount()
  })
  it('引用按钮回传固定引用 ID，而不打开原文中的外部 URL',async()=>{
    const reference:Reference={id:'r1',documentId:'d1',title:'合同',contentVersion:1,page:2,text:'证据'}
    const wrapper=mount(ChatMessage,{props:{message:{id:'m',role:'assistant',content:'回答[1]',references:[reference]},streaming:true},global:{stubs:{'el-button':{template:'<button @click="$emit(\'click\')"><slot/></button>'}},directives:{hasPermi:()=>{}}}})
    await wrapper.find('button').trigger('click');expect(wrapper.emitted('reference')?.[0]).toEqual([reference]);expect(wrapper.find('a').exists()).toBe(false);wrapper.unmount()
  })
})
