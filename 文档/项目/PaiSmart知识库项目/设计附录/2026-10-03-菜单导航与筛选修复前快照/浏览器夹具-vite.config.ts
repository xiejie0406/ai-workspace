import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
const root=path.resolve('tests/unit/paismart/browser-preview'),api=path.join(root,'mock-api.ts')
export default defineConfig({plugins:[vue()],root,cacheDir:path.resolve('node_modules/.vite-paismart-migration-preview'),resolve:{alias:[...['usage','spaces','upload','client','chat','documents','governance','operations','conversations'].map(name=>({find:`@/api/paismart/${name}`,replacement:api})),{find:'vue-router',replacement:path.join(root,'mock-router.ts')},{find:'@',replacement:path.resolve('src')}]},server:{host:'127.0.0.1',port:18149,strictPort:true,fs:{allow:[path.resolve('.')]}}})
