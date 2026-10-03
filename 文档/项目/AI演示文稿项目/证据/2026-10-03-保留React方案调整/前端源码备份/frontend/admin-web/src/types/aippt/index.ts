import type { Theme, Layout, Block } from '@/views/aippt/render/types'
import type { DeckSlide as OriginalSlide } from '@/views/aippt/editor/algorithms/types'
export type { Block, Theme, Layout }
export type Slide = OriginalSlide & { status: string; revision: number; issues?: Issue[]; title: string; position: number }
export interface Project { id: string; title: string; audience: string|null; tone: string; page_count: number; layout_mode: 'fixed'|'flex'; content_density: string; theme_id: string; theme_overrides?: Record<string, unknown>|null; input_revision: number; theme_revision: number; deck_revision: number; status: string; created_at?: string; updated_at?: string }
export interface Section { id: string; heading: string; title?: string; text: string; level?: number; locator?: string; ref?: string }
export interface Source { id: string; kind: string; name?: string; filename?: string; text?: string; parse_status?: string; status?: string; sections: Section[]; warnings?: string[]; char_count?: number; job_id?: string }
export interface OutlinePage { id: string; title: string; objective: string; key_points: string[]; source_refs: string[]; layout_id: string; page_role: string; visual: string }
export interface Outline { id: string; pages: OutlinePage[]; revision: number; status: string; input_signature?: string; confirmed?: boolean; is_stale?: boolean; job_id?:string }
export interface QualityReport {issues?:Issue[];status?:string;job_id?:string;jobId?:string;can_export?:boolean;export_allowed?:boolean;deck_revision?:number;theme_revision?:number}
export interface Deck { slides: Slide[]; status: string; deck_revision: number; theme_id?: string; theme_overrides?: Record<string, unknown>; ready?: number; failed?: number; total?: number }
export interface Issue { severity: 'error'|'warning'|'info'; message: string; code?: string; slide_id?: string; slide_index?: number; block_id?: string; slot_id?: string }
export interface Task { id?: string; job_id?: string; jobId?: string; status: string; state?: string; progress?: number|{percent?:number;message?:string;stage?:string}; message?: string; result?: unknown }
export interface ProposalOperation { id?: string; op_id?: string; op: string; block_id: string; before?: unknown; after?: unknown; reason?: string; accepted?: boolean }
export interface Proposal { id?: string; proposal_id?: string; revision: number; status?: string; operations: ProposalOperation[]; dropped?: {reason:string}[]; discarded?: {reason:string;block_id?:string;op?:string}[]; warnings?: (Issue|string)[]; candidate_slide?: Slide; candidates?: {id: string; layout_tree: NonNullable<Slide['layout_tree']>}[] }
