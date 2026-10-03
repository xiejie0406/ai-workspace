import {describe,it,expect} from 'vitest'
import {taskProgress} from '@/composables/aippt/taskProgress'
describe('中心任务进度展示',()=>{
 it('结构化progress展示percent/message，保留旧数值兼容',()=>{expect(taskProgress({status:'running',progress:{percent:42.4,message:'正在整理材料',stage:'outline'}})).toEqual({percent:42,message:'正在整理材料'});expect(taskProgress({status:'running',progress:25})).toEqual({percent:25,message:''})})
 it('空值、非法数值和越界不会产生NaN或无效进度条',()=>{expect(taskProgress(null).percent).toBe(0);expect(taskProgress({status:'running',progress:{percent:NaN}}).percent).toBe(0);expect(taskProgress({status:'running',progress:{percent:-5}}).percent).toBe(0);expect(taskProgress({status:'succeeded',progress:{percent:120}}).percent).toBe(100)})
})
