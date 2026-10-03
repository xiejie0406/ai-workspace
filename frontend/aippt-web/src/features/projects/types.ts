import type { components } from '@/api/schema'

type Schemas = components['schemas']

export type Project = Schemas['ProjectPublic'] & { input_revision: number; theme_revision: number; deck_revision: number }
export type ProjectDetail = Omit<Schemas['ProjectDetail'], 'sources'> & { sources: ProjectSource[]; input_revision: number; theme_revision: number; deck_revision: number }
export type ProjectCreate = Schemas['ProjectCreate']
export type ProjectUpdate = Schemas['ProjectUpdate']
export type ProjectSource = Schemas['SourcePublic'] & { parse_status?: string; job_id?: string }
export type Tone = ProjectCreate['tone']

export const TONE_LABEL: Record<NonNullable<Tone>, string> = {
  professional: '专业严谨',
  plain: '通俗易懂',
  punchy: '简洁有力',
}

export const PAGE_COUNT_RANGE = { min: 5, max: 20 } as const
export const ACCEPTED_UPLOAD = '.pdf,.docx,.md,.markdown,.txt'
