import {createMemoryHistory,createRouter} from 'vue-router'
import 'element-plus/dist/index.css'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import ApsWorkbench from '@/views/aps/workbench/index.vue'

createApp(ApsWorkbench).use(createRouter({history:createMemoryHistory(),routes:[{path:'/',component:ApsWorkbench}]})).use(ElementPlus).mount('#app')
