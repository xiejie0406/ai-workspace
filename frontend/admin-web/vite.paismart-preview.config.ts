import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
export default defineConfig({plugins:[vue()],root:path.resolve('src/__tests__/paismart/browser-preview'),cacheDir:path.resolve('node_modules/.vite-paismart-preview'),resolve:{alias:[{find:'@/api/paismart/chat',replacement:path.resolve('src/__tests__/paismart/browser-preview/mock-chat-api.ts')},{find:'@',replacement:path.resolve('src')}]},server:{host:'127.0.0.1',port:18147,strictPort:true,fs:{allow:[path.resolve('.')]}}})
