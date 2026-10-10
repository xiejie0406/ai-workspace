import {beforeEach,describe,expect,it,vi} from 'vitest'
import {sendFeedback} from '@/api/paismart/chat'
const mocks=vi.hoisted(()=>({post:vi.fn()}))
vi.mock('@/api/paismart/client',()=>({get:vi.fn(),post:mocks.post,pathId:String,newKey:()=> 'feedback-operation-key'}))
beforeEach(()=>vi.clearAllMocks())
describe('消息评价原API的兼容输入',()=>{
  it('当前界面发送0或实际版本并沿用原幂等键',()=>{
    sendFeedback('message','good','',0)
    expect(mocks.post).toHaveBeenCalledWith('/chat/feedback',{messageId:'message',rating:'good',reason:'',expectedVersion:0},'feedback-operation-key')
  })
  it('旧调用未传版本时省略该字段，不伪造0版本',()=>{
    sendFeedback('message','bad','原因')
    expect(mocks.post).toHaveBeenCalledWith('/chat/feedback',{messageId:'message',rating:'bad',reason:'原因'},'feedback-operation-key')
  })
})
