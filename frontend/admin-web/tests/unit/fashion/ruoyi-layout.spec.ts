import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { shallowMount, type VueWrapper } from '@vue/test-utils'
import { reactive, computed, ref, watch, watchEffect, nextTick } from 'vue'
const state=vi.hoisted(()=>({settings:{} as any,app:{} as any,route:{path:'/fashion/product',fullPath:'/fashion/product'}}))
vi.mock('@/store/modules/settings',()=>({default:()=>state.settings}))
vi.mock('@/store/modules/app',()=>({default:()=>state.app}))
vi.mock('@/store/modules/user',()=>({default:()=>({avatar:'',nickName:'用户',permissions:['*:*:*']})}))
vi.mock('@/store/modules/lock',()=>({default:()=>({lockScreen:vi.fn()})}))
vi.mock('@/components/Breadcrumb',()=>({default:{name:'Breadcrumb',template:'<nav />'}}))
vi.mock('@/components/Hamburger',()=>({default:{name:'Hamburger',template:'<button />'}}))
vi.mock('@/components/Screenfull',()=>({default:{name:'Screenfull',template:'<div />'}}))
vi.mock('@/components/SizeSelect',()=>({default:{name:'SizeSelect',template:'<div />'}}))
vi.mock('@/components/HeaderSearch',()=>({default:{name:'HeaderSearch',template:'<div />'}}))
vi.mock('@/components/RuoYi/Git',()=>({default:{name:'RuoYiGit',template:'<div />'}}))
vi.mock('@/components/RuoYi/Doc',()=>({default:{name:'RuoYiDoc',template:'<div />'}}))
vi.mock('@/layout/components/TopNav/index.vue',()=>({default:{name:'TopNav',template:'<nav />'}}))
vi.mock('@/layout/components/TopBar/index.vue',()=>({default:{name:'TopBar',template:'<nav />'}}))
vi.mock('@/layout/components/Sidebar/Logo.vue',()=>({default:{name:'Logo',template:'<div />'}}))
vi.mock('@/layout/components/HeaderNotice/index.vue',()=>({default:{name:'HeaderNotice',template:'<div />'}}))
vi.mock('@/layout/components/Sidebar/index.vue',()=>({default:{name:'Sidebar',template:'<aside />'}}))
vi.mock('@/layout/components',()=>({AppMain:{name:'AppMain',template:'<main />'},Navbar:{name:'Navbar',template:'<header />'},Settings:{name:'Settings',template:'<div />'},TagsView:{name:'TagsView',template:'<nav />'}}))
vi.mock('@vueuse/core',()=>({useWindowSize:()=>({width:ref(1440),height:ref(900)})}))
import Navbar from '@/layout/components/Navbar.vue'
import Layout from '@/layout/index.vue'
describe('选品页面沿用若依三种布局',()=>{
 let wrapper:VueWrapper
 beforeEach(()=>{vi.stubGlobal('computed',computed);vi.stubGlobal('ref',ref);vi.stubGlobal('watch',watch);vi.stubGlobal('watchEffect',watchEffect);vi.stubGlobal('useRoute',()=>state.route);vi.stubGlobal('useRouter',()=>({push:vi.fn()}));state.settings=reactive({navType:1,tagsView:true,fixedHeader:false,theme:'#409eff',sidebarLogo:true});state.app=reactive({sidebar:{opened:true,hide:false,withoutAnimation:false},device:'desktop',toggleDevice:vi.fn(),closeSideBar:vi.fn()})})
 afterEach(()=>{wrapper?.unmount();vi.unstubAllGlobals()})
 it('在选品路径切换左侧、混合和顶部时选择原有组件',async()=>{wrapper=shallowMount(Navbar,{global:{config:{warnHandler:()=>{}}}});expect(wrapper.findComponent({name:'Breadcrumb'}).exists()).toBe(true);expect(wrapper.findComponent({name:'TopBar'}).exists()).toBe(false);state.settings.navType=2;await nextTick();expect(wrapper.findComponent({name:'TopNav'}).exists()).toBe(true);expect(wrapper.findComponent({name:'Breadcrumb'}).exists()).toBe(false);state.settings.navType=3;await nextTick();expect(wrapper.findComponent({name:'TopBar'}).exists()).toBe(true);expect(wrapper.findComponent({name:'TopNav'}).exists()).toBe(false)})
 it('侧栏和页签仅由原设置决定，不由fashion路径强制关闭',async()=>{wrapper=shallowMount(Layout);expect(wrapper.findComponent({name:'Sidebar'}).exists()).toBe(true);expect(wrapper.findComponent({name:'TagsView'}).exists()).toBe(true);state.app.sidebar.hide=true;state.settings.tagsView=false;await nextTick();expect(wrapper.findComponent({name:'Sidebar'}).exists()).toBe(false);expect(wrapper.findComponent({name:'TagsView'}).exists()).toBe(false);expect(wrapper.find('.main-container').classes()).toContain('sidebarHide')})
})
