import type { components } from '@/api/schema'

type Schemas = components['schemas']

export type Outline = Schemas['OutlinePublic'] & {
  /** 中心按正式提纲版本保存的来源裁剪提示；旧响应可缺省，不由草稿编辑生成。 */
  warnings?: string[]
  source_budget?: { original_chars: number; used_chars: number }
}
export type OutlinePage = Schemas['OutlinePage']
export type OutlineUpdate = Schemas['OutlineUpdate']
export type OutlineGenerateAccepted = Schemas['OutlineGenerateAccepted']

export interface OutlineProgressEvent {
  type: 'snapshot' | 'progress' | 'completed' | 'failed'
  status: Outline['status']
  progress: number
  message: string
  revision?: number | null
}
