import {describe,it,expect} from 'vitest'
import {commandErrorStatus} from '@/composables/aippt/commandError'
describe('平台业务错误分类',()=>{
 it('HTTP200业务409/401/403优先于成功传输码',()=>{for(const code of [409,401,403]){expect(commandErrorStatus({status:code,response:{status:200,data:{code}}})).toBe(code);expect(commandErrorStatus({response:{status:200,data:{code}}})).toBe(code)}})
 it('兼容真实HTTP错误，不将取消或网络字符串当业务冲突',()=>{expect(commandErrorStatus({response:{status:409}})).toBe(409);expect(commandErrorStatus({code:'ERR_CANCELED'})).toBe(0);expect(commandErrorStatus(new Error('网络断线'))).toBe(0)})
})
