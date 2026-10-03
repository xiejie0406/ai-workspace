import {mount,flushPromises} from '@vue/test-utils'
import {describe,it,expect,vi} from 'vitest'
import ElementPlus from 'element-plus'
import OperationEditor from '@/views/aps/routes/OperationEditor.vue'
import {saveOperation} from '@/api/aps/manufacturing'
vi.mock('@/api/aps/manufacturing',()=>({saveOperation:vi.fn()}))
describe('多阶段工序编辑',()=>{
  const props={centers:[],resources:[],value:{id:'o1',code:'OP1',name:'加工',mode:'MAN_MACHINE' as const,status:'DRAFT' as const,interruptible:false,qualityGateRequired:false,rowVersion:2,phases:[{phaseNo:1,phaseType:'SETUP' as const,name:'准备',durationModel:'FIXED' as const,fixedSeconds:30,secondsPerUnit:0,resourceHoldPolicy:'PHASE_ONLY' as const,requirements:[{requirementNo:1,resourceType:'PERSON' as const,seatCount:1,optional:false}]},{phaseNo:2,phaseType:'RUN' as const,name:'加工',durationModel:'PER_UNIT' as const,fixedSeconds:0,secondsPerUnit:20,resourceHoldPolicy:'PHASE_ONLY' as const,requirements:[{requirementNo:1,resourceType:'MACHINE' as const,seatCount:1,optional:false}]}]}}
  it('保存准备和运行各自角色并补齐服务端必须的分段字段',async()=>{vi.mocked(saveOperation).mockResolvedValue(props.value);const w=mount(OperationEditor,{props,global:{plugins:[ElementPlus]}});await w.findAll('button').find(b=>b.text()==='保存草稿')!.trigger('click');await flushPromises();expect(saveOperation).toHaveBeenCalledWith(expect.objectContaining({rowVersion:2,phases:[expect.objectContaining({phaseType:'SETUP',maxSegments:1,segmentResourcePolicy:'SAME_RESOURCES',requirements:[expect.objectContaining({resourceType:'PERSON'})]}),expect.objectContaining({phaseType:'RUN',secondsPerUnit:20,requirements:[expect.objectContaining({resourceType:'MACHINE'})]})]}));w.unmount()})
  it('生效版本仅可查看，不能提交或增删阶段',()=>{const w=mount(OperationEditor,{props:{...props,readOnly:true},global:{plugins:[ElementPlus]}});expect(w.text()).not.toContain('保存草稿');expect(w.text()).not.toContain('添加阶段');w.unmount()})
})
