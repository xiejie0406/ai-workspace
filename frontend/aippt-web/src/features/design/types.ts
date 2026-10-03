import type { Task } from '@/api/task'
import type { StructureIssue } from '@/features/deck/types'
import type { Deck } from '@/render/types'

/** 样例沿用原内容模型；中心增加唯一目录的版本身份。 */
export type SampleDeck = Deck & { catalog_version: string }

export interface SampleQualityReport {
  catalog_version: string
  can_export: boolean
  issues: StructureIssue[]
  fonts_available?: boolean
  font_mode?: 'verified' | 'estimate'
  font_manifest_hash?: string
  version_key?: string
  /** 仅样例接口提供：正式报告页UUID映射到原目录样例页ID。 */
  sample_slide_ids?: Record<string, string>
}

export interface SampleExport extends Task {
  id: string
  verify?: Record<string, unknown>
}

/** 只保留当前用户发起的请求身份，不保存第二套正式任务状态。 */
export interface SampleExportCommand {
  key: string
  body: string
  accepted?: SampleExport
  terminal?: boolean
}
