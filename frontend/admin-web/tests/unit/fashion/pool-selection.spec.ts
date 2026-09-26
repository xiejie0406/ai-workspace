import {describe,it,expect} from 'vitest'
import {blankGroup,isSelected,toggleSelected,priceRange,type PoolSelection} from '@/api/fashion/poolTypes'
const selection=():PoolSelection=>({rowVersion:0,all:false,selectedIds:[],excludedIds:[],names:{},orderedIds:[],variantSelections:{}})
describe('方案池跨页选择状态',()=>{
 it('跨页反复勾选不会重复，移除只影响目标组合',()=>{const s=selection();toggleSelected(s,'page1',true);toggleSelected(s,'page2',true);toggleSelected(s,'page1',true);expect(s.selectedIds).toEqual(['page2','page1']);toggleSelected(s,'page1',false);expect(isSelected(s,'page2')).toBe(true);expect(isSelected(s,'page1')).toBe(false)})
 it('全量选择后排除并重新选回，未访问的页仍被选中',()=>{const s=selection();s.all=true;toggleSelected(s,'excluded',false);expect(isSelected(s,'unvisited')).toBe(true);expect(isSelected(s,'excluded')).toBe(false);toggleSelected(s,'excluded',true);expect(s.excludedIds).toEqual([])})
 it('保存回读可恢复全选排除与颜色关系',()=>{const s=selection();s.all=true;s.variantSelections={'combo-1':{'main-1':'size-blue-M'}};toggleSelected(s,'combo-2',false);const restored=JSON.parse(JSON.stringify(s));expect(isSelected(restored,'combo-1')).toBe(true);expect(isSelected(restored,'combo-2')).toBe(false);expect(restored.variantSelections['combo-1']['main-1']).toBe('size-blue-M')})
 it('筛选组不共享分类数组，复制修改不会串组',()=>{const a=blankGroup(0),b=blankGroup(1);a.categoryLeafCodes.push('top');expect(b.categoryLeafCodes).toEqual([])})
 it('未知价格不展示成零，价格区间不伪装固定价',()=>{expect(priceRange(null,null)).toBe('未知');expect(priceRange(0,0)).toBe('¥0');expect(priceRange(10,20)).toBe('¥10～20')})
})
