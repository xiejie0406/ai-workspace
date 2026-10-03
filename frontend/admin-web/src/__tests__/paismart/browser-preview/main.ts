import {createApp} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'katex/dist/katex.min.css'
import Demo from './Harness.vue'
createApp(Demo).use(ElementPlus).directive('hasPermi',{}).mount('#app')
