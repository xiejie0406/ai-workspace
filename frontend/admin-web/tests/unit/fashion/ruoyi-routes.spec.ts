import { beforeEach, describe, it, expect, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineStore } from 'pinia'
const access=vi.hoisted(()=>({permissions:[] as string[]}))
vi.mock('@/layout',()=>({default:{template:'<router-view />'}}))
vi.mock('@/layout/index',()=>({default:{template:'<router-view />'}}))
vi.mock('@/components/ParentView',()=>({default:{template:'<router-view />'}}))
vi.mock('@/layout/components/InnerLink',()=>({default:{template:'<div />'}}))
vi.mock('@/api/menu',()=>({getRouters:vi.fn()}))
vi.mock('@/plugins/auth',()=>({default:{hasPermiOr:(permissions:string[])=>access.permissions.includes('*:*:*')||permissions.some(permission=>access.permissions.includes(permission)),hasRoleOr:()=>false}}))
import { dynamicRoutes } from '@/router'
vi.stubGlobal('defineStore',defineStore)
const { filterDynamicRoutes }=await import('@/store/modules/permission')
vi.unstubAllGlobals()
describe('若依授权菜单作为选品页面入口',()=>{
 beforeEach(()=>{access.permissions=[]})
 it('前端不预注册商品页；后台授权页面加入后只有一个页面匹配',()=>{
   access.permissions=['*:*:*']
   const router=createRouter({history:createMemoryHistory(),routes:[...filterDynamicRoutes(dynamicRoutes),{path:'/:pathMatch(.*)*',name:'NotFound',component:{template:'<div />'}}]})
   expect(router.resolve('/fashion/product').name).toBe('NotFound')
   router.addRoute({path:'/fashion/product',name:'FashionProduct',component:{template:'<div />'}})
   expect(router.resolve('/fashion/product').matched).toHaveLength(1)
   expect(router.resolve('/fashion/product').name).toBe('FashionProduct')
 })
 it('旧库存深链仅在库存读权限下注册，重定向保留仓库条件与hash',async()=>{
   access.permissions=['fashion:stock:list']
   const routes=filterDynamicRoutes(dynamicRoutes)
   const router=createRouter({history:createMemoryHistory(),routes:[...routes,{path:'/fashion/stock',name:'FashionStock',component:{template:'<div />'}}]})
   await router.push('/fashion/stock-import?warehouseCode=WH1&mode=stale#matrix')
   expect(router.currentRoute.value.path).toBe('/fashion/stock')
   expect(router.currentRoute.value.query).toEqual({warehouseCode:'WH1',mode:'stale'})
   expect(router.currentRoute.value.hash).toBe('#matrix')
   access.permissions=[]
   expect(filterDynamicRoutes(dynamicRoutes).some(route=>route.path==='/fashion/stock-import')).toBe(false)
 })
})
