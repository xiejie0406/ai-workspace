import {createMemoryHistory,createRouter} from 'vue-router'
import 'element-plus/dist/index.css'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import ApsResources from '@/views/aps/resources/index.vue'

const app = createApp(ApsResources)
app.use(createRouter({history:createMemoryHistory(),routes:[{path:'/',component:ApsResources}]}));app.use(ElementPlus)
app.directive('hasPermi', { mounted: () => undefined })
app.mount('#app')
