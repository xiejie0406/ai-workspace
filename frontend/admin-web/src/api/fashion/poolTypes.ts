export interface PoolFilterGroup {
  label: string; categoryLeafCodes: string[]; seasons: string[]; genders: string[]; brands: string[];
  itemNos: string[]; articleNos: string[]; mainSkuCodes: string[]; keyword: string; colors: string[]; sizes: string[];
  warehouseCode: string; stockStatus: 'all'|'positive'|'zero'|'unknown'; stockMin?: number; stockMax?: number;
  requireAllSizes?:boolean;stockFreshness?:'all'|'fresh'|'stale';styles?:string[];fabricComposition?:string;fits?:string[];scenes?:string[];hasMainImage?:boolean;imagesConfirmed?:boolean;
  groupPriceMin?: number; groupPriceMax?: number; vipPriceMin?: number; vipPriceMax?: number;
}
export interface PoolRequest {title:string;groups:PoolFilterGroup[];priceBasis:'group'|'vip';totalMin?:number;totalMax?:number}
export interface PoolVariant {productId:string;skuCode:string;colorCode:string;colorName:string;sizeCode:string;groupPrice?:number;vipPrice?:number;availableQty?:number;mainImageUrl?:string}
export interface MainSku {key:string;mainSkuCode:string;name:string;sourceCode:string;categoryLeafCode:string;brand?:string;itemNo?:string;articleNo?:string;productId:string;mainImageUrl?:string;groupPriceMin?:number;groupPriceMax?:number;vipPriceMin?:number;vipPriceMax?:number;availableQty?:number;stockUnknown:boolean;variants:PoolVariant[]}
export interface PoolCombination {id:string;items:MainSku[];groupPriceMin?:number;groupPriceMax?:number;vipPriceMin?:number;vipPriceMax?:number;stockUnknown:boolean}
export interface PoolSnapshot {id:string;title:string;groups:{label:string;items:MainSku[]}[];rawCount:number;total:number;createdAt:string;request?:PoolRequest}
export interface PoolPage {items:PoolCombination[];total:number;page:number;pageSize:number;rawCount:number}
export interface PoolSelection {poolId?:string;rowVersion:number;all:boolean;selectedIds:string[];excludedIds:string[];names:Record<string,string>;orderedIds:string[];variantSelections:Record<string,Record<string,string>>}
export interface PoolOptions {categories:{code:string;path:string}[];brands:string[];seasons:string[];genders:string[];warehouses:string[]}
export function blankGroup(index:number):PoolFilterGroup{return {label:`品类 ${index+1}`,categoryLeafCodes:[],seasons:[],genders:[],brands:[],itemNos:[],articleNos:[],mainSkuCodes:[],keyword:'',colors:[],sizes:[],warehouseCode:'',stockStatus:'all'}}
export function isSelected(selection:PoolSelection,id:string){return selection.all?!selection.excludedIds.includes(id):selection.selectedIds.includes(id)}
export function toggleSelected(selection:PoolSelection,id:string,value:boolean){const key=selection.all?'excludedIds':'selectedIds';const shouldContain=selection.all?!value:value;selection[key]=selection[key].filter(x=>x!==id);if(shouldContain)selection[key].push(id)}
export function priceRange(min:unknown,max:unknown){if(min==null||max==null)return '未知';return String(min)===String(max)?`¥${min}`:`¥${min}～${max}`}
