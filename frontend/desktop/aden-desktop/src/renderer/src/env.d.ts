/// <reference types="vite/client" />
import type { AdenDesktopApi } from '../../shared/contracts/desktop-api'

declare global {
  const __ADEN_TEST_LOGIN__: Readonly<{ username: string; password: string }> | null
  interface Window {
    adenDesktop: AdenDesktopApi
  }
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}
