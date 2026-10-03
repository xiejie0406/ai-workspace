import {createMemoryHistory,createRouter} from 'vue-router'
import 'element-plus/dist/index.css'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import ApsRoutes from '@/views/aps/routes/index.vue'

const app = createApp(ApsRoutes)
app.use(createRouter({history:createMemoryHistory(),routes:[{path:'/',component:ApsRoutes}]})).use(ElementPlus)
app.directive('hasPermi', { mounted: () => undefined })
app.mount('#app')
