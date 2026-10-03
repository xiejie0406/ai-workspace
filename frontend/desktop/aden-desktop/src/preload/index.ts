import { contextBridge } from 'electron'
import { createPreloadApi } from './api'

const runtime = Object.freeze({
  appVersion: process.argv.find(argument => argument.startsWith('--aden-desktop-version='))?.split('=')[1] ?? '未读取',
  platform: process.platform,
  versions: Object.freeze({
    electron: process.versions.electron,
    chrome: process.versions.chrome
  })
})

contextBridge.exposeInMainWorld('adenDesktop', createPreloadApi(runtime))
