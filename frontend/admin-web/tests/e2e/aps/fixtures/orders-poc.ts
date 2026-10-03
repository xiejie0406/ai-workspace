import {createMemoryHistory,createRouter} from 'vue-router'
import 'element-plus/dist/index.css'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import ApsOrders from '@/views/aps/orders/index.vue'

const app = createApp(ApsOrders)
app.use(createRouter({history:createMemoryHistory(),routes:[{path:'/',component:ApsOrders}]}));app.use(ElementPlus)
app.directive('hasPermi', { mounted: () => undefined })
app.mount('#app')
