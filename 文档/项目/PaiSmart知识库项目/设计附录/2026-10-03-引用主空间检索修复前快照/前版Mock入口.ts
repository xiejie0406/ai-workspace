import {createApp} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import Harness from './Harness.vue'
import {createRouter,createWebHistory} from 'vue-router'
import Evaluations from '@/views/paismart/governance/evaluations/index.vue'
import Review from '@/views/paismart/governance/evaluations/review.vue'
import Documents from '@/views/paismart/documents/index.vue'
import Recharge from '@/views/paismart/recharge/index.vue'
// 路径与已批准菜单一致；这里只安装本地技术夹具路由，不写真实授权菜单。
const router=createRouter({history:createWebHistory(),routes:[{path:'/',component:{template:'<div/>'}},{path:'/paismart/evaluations',component:Evaluations},{path:'/paismart/review',component:Review},{path:'/paismart/documents',component:Documents},{path:'/paismart/recharge',component:Recharge}]})
createApp(Harness).use(router).use(ElementPlus).directive('hasPermi',{}).mount('#app')
