import { createContext, useContext } from 'react'
import { createClient } from '@/api/client'
import { createAssetsApi } from '@/api/assets'
import { createSessionsApi } from '@/api/sessions'
import { createRunsApi } from '@/api/runs'
import { createBatchesApi } from '@/api/batches'
import { createAgentApi } from '@/api/agent'
import { createCanvasViewStore } from '@/stores/canvasView'
import { createEditorUiStore } from '@/stores/editorUi'
import type { HostRuntime } from './HostRuntime'
import type { CanvasImage } from '@/hooks/useCanvasImage'

export function createBusinessState(runtime: HostRuntime) {
  const client = createClient(runtime)
  return {
    runtime,
    canvas: createCanvasViewStore(),
    editor: createEditorUiStore(),
    images: new Map<string, CanvasImage>(),
    apis: {
      assets: createAssetsApi(client), sessions: createSessionsApi(client, runtime),
      runs: createRunsApi(client), batches: createBatchesApi(client, runtime), agent: createAgentApi(client),
    },
  }
}
export type BusinessState = ReturnType<typeof createBusinessState>
export const HostContext = createContext<BusinessState | null>(null)
export function useBusinessState() {
  const state = useContext(HostContext)
  if (!state) throw new Error('业务组件必须通过若依宿主挂载')
  return state
}
export const useHost = () => useBusinessState().runtime.host
export const useApis = () => useBusinessState().apis
export const useHostRuntime = () => useBusinessState().runtime
export const usePermission = (permission: string) => useHost().hasPermission(permission)
export function useToast() {
  const host = useHost()
  return (message: string, tone: 'ok' | 'danger' = 'ok', options?: { action?: { label: string; run: () => void } }) =>
    host.notify({ message, type: tone === 'danger' ? 'error' : 'success', action: options?.action })
}
