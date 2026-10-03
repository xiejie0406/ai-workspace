import {describe,expect,it} from 'vitest'
import {operationManifest} from '@/views/paismart/composables/operationManifest'
import {stateLabel,terminal} from '@/views/paismart/composables/usePage'
import {evaluationMetric,verdictLabel} from '@/views/paismart/composables/evaluationPresentation'
const source={id:'doc1',sourceKey:'paismart/source/a',targetKey:'paismart/documents/doc1/target',sha256:'a'.repeat(64),byteSize:12}
describe('真实维护对象清单',()=>{
  it('生成执行器实际消费的documents/files，不提交虚构清单ID',()=>{expect(operationManifest('object-migrations',JSON.stringify([source]))).toEqual({documents:[source]});expect(operationManifest('bootstrap-import',JSON.stringify([{sourceKey:'paismart/imports/a',filename:'a.pdf',sha256:'a'.repeat(64),byteSize:12,spaceId:'s1',visibility:'PRIVATE'}]))).toMatchObject({files:[{filename:'a.pdf',spaceId:'s1'}]});expect(operationManifest('cleanup-plans','')).toEqual({})})
  it('清单缺失、越界对象、重复对象和无效字节数不能生成可批准清单',()=>{expect(()=>operationManifest('object-migrations','[]')).toThrow('1 至 1000');expect(()=>operationManifest('object-migrations',JSON.stringify([{...source,targetKey:'other/target'}]))).toThrow('超出');expect(()=>operationManifest('object-migrations',JSON.stringify([source,source]))).toThrow('重复');expect(()=>operationManifest('object-migrations',JSON.stringify([{...source,byteSize:1.5}]))).toThrow('正整数')})
  it('取消任务和部分失败评测按真实终态结束轮询',()=>{expect(terminal('CANCELED')).toBe(true);expect(terminal('RETRY')).toBe(false);expect(stateLabel('COMPLETED_WITH_ERRORS')).toContain('部分样本失败')})
  it('旧分片搬迁和内置逻辑知识修复保留实际清单字段',()=>{expect(operationManifest('object-migrations',JSON.stringify([{legacySourceKey:'chunks/md5/0',uploadId:'u1',chunkNo:0,targetKey:'paismart/uploads/u1/0',sha256:'a'.repeat(64),byteSize:2}]))).toMatchObject({documents:[{uploadId:'u1',chunkNo:0}]});expect(operationManifest('bootstrap-import',JSON.stringify([{sourceKey:'paismart/imports/a',filename:'a.pdf',sha256:'a'.repeat(64),byteSize:12,visibility:'PRIVATE',logicalKey:'knowledge1',repair:true}]))).toMatchObject({files:[{logicalKey:'knowledge1',repair:true}]})})
  it('无样本指标与未判定结论不会被转换成0或不通过',()=>{expect(evaluationMetric(null,true)).toBe('无可计算样本');expect(evaluationMetric(undefined,true)).toBe('未返回');expect(verdictLabel(null)).toBe('需补证据');expect(verdictLabel(undefined)).toBe('未复核')})
})
