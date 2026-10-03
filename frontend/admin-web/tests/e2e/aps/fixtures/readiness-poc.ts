import 'element-plus/dist/index.css'
import {createApp,h} from 'vue'
import ElementPlus from 'element-plus'
import PlanLauncher from '@/views/aps/workbench/PlanLauncher.vue'
const app=createApp({render:()=>h(PlanLauncher,{readinessOnly:true})})
app.use(ElementPlus).directive('hasPermi',{mounted:()=>undefined}).mount('#app')
