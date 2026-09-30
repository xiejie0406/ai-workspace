import {fashionRequest} from './client'
import type {RuoYiResult} from './types'
import type {PoolOptions,PoolRequest,PoolSnapshot,PoolPage,PoolSelection} from './poolTypes'
export const getPoolOptions=()=>fashionRequest<RuoYiResult<PoolOptions>>({path:'pools/options'})
export const createPool=(body:PoolRequest)=>fashionRequest<RuoYiResult<PoolSnapshot>>({path:'pools',method:'post',body,timeoutMs:120000})
export const getPool=(id:string)=>fashionRequest<RuoYiResult<PoolSnapshot>>({path:`pools/${id}`})
export const getPoolPage=(id:string,params:{page:number;pageSize:number;sort:string;keyword:string;selectedOnly?:boolean},signal?:AbortSignal)=>fashionRequest<RuoYiResult<PoolPage>>({path:`pools/${id}/combinations`,params,signal})
export const getPoolSelection=(id:string)=>fashionRequest<RuoYiResult<PoolSelection>>({path:`pools/${id}/selection`})
export const savePoolSelection=(id:string,body:PoolSelection)=>fashionRequest<RuoYiResult<PoolSelection>>({path:`pools/${id}/selection`,method:'put',body})
