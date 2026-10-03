export interface PoolFilterGroup {
  label: string; categoryLeafCodes: string[]; seasons: string[]; genders: string[]; brands: string[];
  itemNos: string[]; articleNos: string[]; mainSkuCodes: string[]; keyword: string; colors: string[]; sizes: string[];
  warehouseCode: string; stockStatus: 'all'|'positive'|'zero'|'unknown'; stockMin?: number; stockMax?: number;
  requireAllSizes?:boolean;stockFreshness?:'all'|'fresh'|'stale';styles?:string[];fabricComposition?:string;fits?:string[];scenes?:string[];hasMainImage?:boolean;imagesConfirmed?:boolean;
  groupPriceMin?: number; groupPriceMax?: number; vipPriceMin?: number; vipPriceMax?: number;
  sourceCodes?: string[]; styleCodes?: string[]; skuCodes?: string[]; sizeSystems?: string[];
  attributeFilters?: Record<string,string>; attributeRanges?: Record<string,{from?:string;to?:string}>;
  quantity?:number; unitsPerSet?:number; stockAsOfFrom?:string; stockAsOfTo?:string; attributesConfirmed?:boolean;
  material?:string; tags?:string[];
}
export type PoolInheritance = Partial<Record<keyof PoolFilterGroup,'follow'|'independent'>>
export interface PoolRequest {title:string;groups:PoolFilterGroup[];priceBasis:'group'|'vip';totalMin?:number;totalMax?:number;activeGroupCount?:2|3|4;draftGroups?:PoolFilterGroup[];totalConditions?:PoolFilterGroup;inheritance?:PoolInheritance[]}
export interface PoolConditionDraft extends PoolRequest {activeGroupCount:2|3|4;draftGroups:PoolFilterGroup[];totalConditions:PoolFilterGroup;inheritance:PoolInheritance[]}
export interface PoolVariant {productId:string;skuCode:string;colorCode:string;colorName:string;sizeCode:string;sizeSystem?:string;unit?:string;stockAsOf?:string|null;groupPrice?:number;vipPrice?:number;availableQty?:number;mainImageUrl?:string}
export interface MainSku {key:string;mainSkuCode:string;name:string;sourceCode:string;categoryLeafCode:string;categoryLeafPath?:string;styleCode?:string;attributes?:Record<string,string>;stockAsOf?:string|null;brand?:string;itemNo?:string;articleNo?:string;productId:string;mainImageUrl?:string;groupPriceMin?:number;groupPriceMax?:number;vipPriceMin?:number;vipPriceMax?:number;availableQty?:number;stockUnknown:boolean;variants:PoolVariant[]}
export interface PoolHistory {id:string;title:string;createdAt:string;ownerId?:string|number;ownerName?:string|null;activeGroupCount?:number;quantity?:number|null;budgetMin?:number|null;budgetMax?:number|null;priceBasis?:string;totalCount?:number;rawCount?:number;selectedCount?:number;draftVersion?:number;updatedAt?:string|null;stage?:'generated'|'draft_saved'}
export interface PoolCombination {id:string;items:MainSku[];groupPriceMin?:number;groupPriceMax?:number;vipPriceMin?:number;vipPriceMax?:number;stockUnknown:boolean;availableSets?:number;meetsDemand?:boolean|null}
export interface PoolSnapshot {id:string;title:string;groups:{label:string;items:MainSku[]}[];rawCount:number;total:number;createdAt:string;request?:PoolRequest}
export interface PoolPage {items:PoolCombination[];total:number;page:number;pageSize:number;rawCount:number}
export interface PoolSelection {poolId?:string;rowVersion:number;all:boolean;selectedIds:string[];excludedIds:string[];names:Record<string,string>;orderedIds:string[];variantSelections:Record<string,Record<string,string>>}
export interface PoolRecommendations {stock:{available:boolean;source:string};sales:{available:boolean;periodDays:number;reason?:string;source?:string}}
export interface PoolOptions {categories:{code:string;path:string}[];brands:string[];seasons:string[];genders:string[];warehouses:string[];warehouseOptions?:{code:string;name:string;isDefault?:boolean}[];defaultWarehouseCode?:string;recommendations?:PoolRecommendations;attributeFields?:import('./types').ProductAttributeField[]}
export function blankGroup(index:number):PoolFilterGroup{return {label:`品类 ${index+1}`,categoryLeafCodes:[],seasons:[],genders:[],brands:[],itemNos:[],articleNos:[],mainSkuCodes:[],keyword:'',colors:[],sizes:[],warehouseCode:'',stockStatus:'all',sourceCodes:[],styleCodes:[],skuCodes:[],sizeSystems:[],styles:[],fits:[],scenes:[],tags:[],attributeFilters:{},attributeRanges:{},unitsPerSet:1}}
export function isSelected(selection:PoolSelection,id:string){return selection.all?!selection.excludedIds.includes(id):selection.selectedIds.includes(id)}
export function toggleSelected(selection:PoolSelection,id:string,value:boolean){const key=selection.all?'excludedIds':'selectedIds';const shouldContain=selection.all?!value:value;selection[key]=selection[key].filter(x=>x!==id);if(shouldContain)selection[key].push(id)}
export function priceRange(min:unknown,max:unknown){if(min==null||max==null)return '未知';return String(min)===String(max)?`¥${min}`:`¥${min}～${max}`}
/** 仅用于筛选表单反馈；正式筛选仍由 Java 校验。清空单边不等于零。 */
export function filterRangeError(min:number|null|undefined,max:number|null|undefined,label:string){
  if([min,max].some(value=>value!=null&&(!Number.isFinite(value)||value<0)))return `${label}必须为非负数`
  if(min!=null&&max!=null&&min>max)return `${label}下限不能大于上限`
  return ''
}
export function groupRangeError(group:PoolFilterGroup){
  if(group.quantity!=null&&(!Number.isSafeInteger(group.quantity)||group.quantity<1))return '需求数量必须为正整数'
  if(group.unitsPerSet!=null&&group.unitsPerSet!==1)return '本期每套每组用量固定为1件／双'
  if(group.stockAsOfFrom&&group.stockAsOfTo&&Date.parse(group.stockAsOfFrom)>Date.parse(group.stockAsOfTo))return '库存取得时间开始不能晚于结束'
  return filterRangeError(group.groupPriceMin,group.groupPriceMax,'团购价')||filterRangeError(group.vipPriceMin,group.vipPriceMax,'VIP价')||filterRangeError(group.stockMin,group.stockMax,'可售数量')
}
