import type { Asset } from '@/api/assets'
import type { ApiClient } from '@/api/client'
import type { HostRuntime } from '@/host/HostRuntime'
import { awaitExport, awaitRun, readWithRecovery } from '@/api/exports'
import type { Run } from '@/api/runs'

export type LayerKind = 'image' | 'text' | 'shape'

export type Transform = {
  x: number
  y: number
  scale_x: number
  scale_y: number
  rotation: number
}

export type Layer = {
  id: string
  kind: LayerKind
  name: string
  width: number
  height: number
  asset_id: string | null
  transform: Transform
  opacity: number
  visible: boolean
  locked: boolean
  source_hash?: string | null
  text?: string | null
  font_size?: number
  fill?: string
}

export type LayerDocument = {
  width: number
  height: number
  layers: Layer[]
}

export type Session = {
  id: string
  title: string
  revision: number
  row_version?: number
  active_run_id?: string | null
  history_seq: number
  original_asset_id: string
  current_asset_id: string
  created_at: string
  updated_at: string
}

export type SessionDetail = Session & {
  document: LayerDocument
  previous_document: LayerDocument | null
  assets: Asset[]
  can_undo: boolean
  can_redo: boolean
}

export type HistoryEntry = {
  seq: number
  action: string
  params: Record<string, unknown>
  result: Record<string, unknown>
  created_at: string
}

export type SessionCreateInput = {
  current_asset_id: string
  asset_ids?: string[]
  title?: string
}

export type SessionPatchInput = {
  title?: string
  current_asset_id?: string
}

export type ToolInvoke = {
  run: Run
  session: SessionDetail
}

export const ACTION_LABELS: Record<string, string> = {
  create_session: '新建会话',
  switch_current: '切换当前图',
  generate_image: '生成图片',
  replace_background: '换背景',
  expand_canvas: '扩图',
  upscale_image: '超分',
  remove_background: '去背景',
  adjust_image: '调色',
  erase_region: '局部消除',
  replace_region: '局部替换',
  crop_canvas: '裁剪',
  flip_layer: '翻转',
  set_layer_opacity: '透明度',
  set_layer_visible: '显隐',
  set_layer_text: '改文字',
  reorder_layer: '图层顺序',
  scale_layer: '缩放',
  rotate_layer: '旋转',
  move_layer: '移动',
  split_layers: '拆层',
  promote_object_to_layer: '提升为图层',
  generate_marketing: '营销图',
  prepare_delivery_sizes: '投放尺寸',
}

export type ExportPack = {
  blob: Blob
  filename: string
}

export type Marker = { index: number; x: number; y: number }

export type Selection = {
  revision: number
  mask: Asset
  markers: Marker[]
}

export type SelectInput = {
  revision: number
  points?: { x: number; y: number }[]
  strokes?: { x: number; y: number }[][]
  radius?: number
  append?: boolean
}

export type ExportInput = { asset_ids?: string[]; format?: 'png' | 'zip'; backgroundMode?: 'white' | 'transparent' }

export const createSessionsApi = (api: ApiClient, runtime: HostRuntime) => ({
  create: (input: SessionCreateInput) => api.post<SessionDetail>('/sessions', input, 'aipicture:session:create'),
  list: () => api.get<Session[]>('/sessions'),
  get: (id: string) => api.get<SessionDetail>(`/sessions/${id}`),
  patch: (id: string, input: SessionPatchInput) =>
    api.patch<SessionDetail>(`/sessions/${id}`, input, 'aipicture:session:edit'),
  history: (id: string) => api.get<HistoryEntry[]>(`/sessions/${id}/history`),
  invoke: (id: string, tool: string, params: Record<string, unknown> = {}, capturedExpectedVersion?: number) => {
    // 遮罩和修订由 Java 根据当前正式选区注入，客户端不能指定服务字段。
    const sanitized = { ...params }; delete sanitized.mask_asset_id; delete sanitized.revision
    return api.post<ToolInvoke>(`/sessions/${id}/tools`, { tool, params: sanitized }, 'aipicture:tool:execute', capturedExpectedVersion)
  },
  undo: (id: string) => api.post<SessionDetail>(`/sessions/${id}/undo`, undefined, 'aipicture:session:edit'),
  redo: (id: string) => api.post<SessionDetail>(`/sessions/${id}/redo`, undefined, 'aipicture:session:edit'),
  select: async (id: string, input: SelectInput) => {
    const signal = runtime.signal
    const run = await api.post<Run>(`/sessions/${id}/selection`, input, 'aipicture:tool:execute')
    await awaitRun(api, runtime, run, signal)
    const result = await readWithRecovery<Selection | null>(api, runtime, `/sessions/${id}/selection`, undefined, signal)
    if (!result || result.revision !== input.revision) throw new Error('选区未保存或画布已更新，请重新选择')
    return result
  },
  prepareSelection: async (id: string) => {
    const signal = runtime.signal
    const run = await api.post<Run>(`/sessions/${id}/selection/prepare`, undefined, 'aipicture:tool:execute')
    await awaitRun(api, runtime, run, signal)
  },
  getSelection: async (id: string, options?: { recover?: boolean; signal?: AbortSignal }) =>
    (await (options?.recover
      ? readWithRecovery<Selection | null>(api, runtime, `/sessions/${id}/selection`, undefined, options.signal ?? runtime.signal)
      : api.get<Selection | null>(`/sessions/${id}/selection`))) ?? null,
  clearSelection: (id: string) => api.delete<void>(`/sessions/${id}/selection`, 'aipicture:tool:execute'),
  cancelRun: (id: string) => api.post<Run>(`/runs/${id}/cancel`, undefined, 'aipicture:tool:execute'),
  waitForRun: async (run: Run, signal = runtime.signal) => { await awaitRun(api, runtime, run, signal) },
  export: (id: string, input: string[] | ExportInput = []) => awaitExport(api, runtime,
    `/sessions/${id}/exports`, Array.isArray(input) ? { asset_ids: input, format: 'zip' } : input),
})
