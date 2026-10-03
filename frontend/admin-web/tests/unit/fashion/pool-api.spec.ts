import {beforeEach,describe,expect,it,vi} from 'vitest'
const {request}=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('@/api/fashion/client',()=>({fashionRequest:request}))
import {createPool,getPoolOptions,getPoolPage} from '@/api/fashion/pool'
import type {PoolCombination} from '@/api/fashion/poolTypes'
import {blankConditionDraft,conditionRequest} from '@/views/fashion/pool/conditionState'
describe('选品Java契约和推荐数据',()=>{
 beforeEach(()=>request.mockReset())
 it('选项原样读取真实推荐可用性、原因、默认仓库和权威字段',async()=>{const data={categories:[],brands:[],seasons:[],genders:[],warehouses:['MAIN'],defaultWarehouseCode:'MAIN',warehouseOptions:[{code:'MAIN',name:'主仓',isDefault:true}],recommendations:{stock:{available:true,source:'snapshot'},sales:{available:false,periodDays:30,reason:'真实销量来源未接通'}},attributeFields:[{key:'supplierName',label:'供应商',group:'来源',maxLength:120}]};request.mockResolvedValue({code:200,data});const result=await getPoolOptions();expect(result.data).toBe(data);expect(result.data.recommendations?.sales.available).toBe(false);expect(result.data.recommendations?.sales.reason).toBe('真实销量来源未接通');expect(result.data.defaultWarehouseCode).toBe('MAIN');expect(request).toHaveBeenCalledWith({path:'pools/options'})})
 it('二组请求仅两组生效并同时携带完整四组草稿和继承，库存排序原样传递',async()=>{const form=blankConditionDraft('MAIN');form.title='团购';form.activeGroupCount=2;form.totalConditions.quantity=100;form.draftGroups[2].skuCodes=['hidden-001'];request.mockResolvedValue({code:200,data:{}});const body=conditionRequest(form);await createPool(body);expect(body.groups).toHaveLength(2);expect(body.groups.every(g=>g.quantity===100)).toBe(true);expect(body.draftGroups![2].skuCodes).toEqual(['hidden-001']);expect(request).toHaveBeenNthCalledWith(1,{path:'pools',method:'post',body,timeoutMs:120000});const signal=new AbortController().signal;const params={page:2,pageSize:20,sort:'stockDesc',keyword:'001'};await getPoolPage('pool-1',params,signal);expect(request).toHaveBeenNthCalledWith(2,{path:'pools/pool-1/combinations',params,signal})})
 it('分页保留需求满足三态，未知库存不转换为不满足',async()=>{
   const items:PoolCombination[]=[{id:'unknown',items:[],stockUnknown:true,meetsDemand:null},{id:'enough',items:[],stockUnknown:false,meetsDemand:true},{id:'short',items:[],stockUnknown:false,meetsDemand:false}]
   request.mockResolvedValue({code:200,data:{items,total:3,page:1,pageSize:20,rawCount:3}})
   const result=await getPoolPage('pool-1',{page:1,pageSize:20,sort:'stockDesc',keyword:''})
   expect(result.data.items.map(row=>row.meetsDemand)).toEqual([null,true,false]);expect(result.data.items[0].stockUnknown).toBe(true)
 })
})
