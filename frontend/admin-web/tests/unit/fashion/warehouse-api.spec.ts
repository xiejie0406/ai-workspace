import { beforeEach, describe, expect, it, vi } from 'vitest'
const {request}=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('@/api/fashion/client',()=>({fashionRequest:request}))
import { activeWarehouseOptions, createWarehouse, listWarehouses, setDefaultWarehouse, setWarehouseStatus, updateWarehouse, type FashionWarehouse } from '@/api/fashion/warehouse'
const warehouse=(code:string,status:FashionWarehouse['status']='active',isDefault=false):FashionWarehouse=>({code,name:'空仓库',status,isDefault,stockSkuCount:0,lastStockAsOf:null,sort:0,remark:''})
describe('仓库管理和库存选项',()=>{
  beforeEach(()=>request.mockReset())
  it('启用空仓仍可选，保留默认标记，停用仓不进入新业务选项',()=>{expect(activeWarehouseOptions([warehouse('W-01','active',true),warehouse('W-02','inactive')])).toEqual([{label:'空仓库（W-01）',value:'W-01',isDefault:true}])})
  it('编辑名称不会改变编码，启停和默认动作由Java校验',async()=>{request.mockResolvedValue({code:200,data:{}});await listWarehouses();await createWarehouse({code:'W-01',name:'第一仓',status:'active',sort:1,remark:''});await updateWarehouse('W-01',{name:'改名仓',status:'active',sort:2,remark:'调整'});await setWarehouseStatus('W-01','inactive');await setDefaultWarehouse('W-02');expect(request).toHaveBeenNthCalledWith(3,{path:'warehouses/W-01',method:'put',body:{code:'W-01',name:'改名仓',status:'active',sort:2,remark:'调整'}});expect(request).toHaveBeenNthCalledWith(4,{path:'warehouses/W-01/status',method:'put',body:{status:'inactive'}});expect(request).toHaveBeenNthCalledWith(5,{path:'warehouses/W-02/default',method:'put'})})
})
