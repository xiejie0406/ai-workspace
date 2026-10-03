import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { ApiError, newCommandKey, request, requestPrepared } from '@/api/client'
import { can } from '@/features/auth/store'
import { tokenStore } from '@/features/auth/token'
import { deckKey } from '@/features/deck/api'
import type {
  Project,
  ProjectCreate,
  ProjectDetail,
  ProjectSource,
  ProjectUpdate,
} from '@/features/projects/types'
import type { ThemeOverrides } from '@/render/themeOverrides'

export function readProject(id: string) { return request<Project>(`/projects/${id}`) }

export async function fetchProject(id: string): Promise<ProjectDetail> {
  const [project, sources] = await Promise.all([request<ProjectDetail>(`/projects/${id}`), can('aippt:source:query') ? request<ProjectSource[]>(`/projects/${id}/sources`) : Promise.resolve([])])
  return { ...project, sources }
}

async function waitSource(projectId: string, sourceId: string) {
  const token = tokenStore.get()
  for (;;) {
    if (tokenStore.get() !== token) throw new DOMException('登录会话已变化', 'AbortError')
    const sources = await request<ProjectSource[]>(`/projects/${projectId}/sources`)
    const source = sources.find((item) => item.id === sourceId)
    if (source?.parse_status === 'ready') return source
    if (!source || source.parse_status === 'failed') throw new Error((source as ProjectSource & { error?: string })?.error ?? '材料解析失败')
    await new Promise((resolve) => setTimeout(resolve, 700))
  }
}

const listKey = ['projects'] as const
const detailKey = (id: string) => ['projects', id] as const
export function useProjectsPage(params: { pageNum: number; pageSize: number; q: string; status: string }) {
  const query = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)]))
  return useQuery({ queryKey: [...listKey, params], queryFn: () => request<{ rows: Project[]; total: number }>(`/projects?${query}`, { table: true }) })
}

export function useProjects() {
  return useQuery({
    queryKey: listKey,
    queryFn: async () => {
      const all: Project[] = []; let pageNum = 1
      for (;;) { const rows = await request<Project[]>(`/projects?pageNum=${pageNum}&pageSize=100`); all.push(...rows); if (rows.length < 100) return all; pageNum++ }
    },
  })
}

export function useProject(id: string, enabled = true) {
  return useQuery({
    queryKey: detailKey(id),
    queryFn: () => fetchProject(id),
    enabled,
    refetchInterval: (query) => query.state.data?.sources.some((source) => ['pending', 'queued', 'running'].includes(source.parse_status ?? '')) ? 1000 : false,
  })
}

export function useUpdateProject(id: string) {
  const queryClient = useQueryClient()
  const command = useRef<{ input: string; steps: Array<{ path: string; body: string; key: string }>; next: number } | null>(null)
  useEffect(() => () => { command.current = null }, [id])
  return useMutation({
    mutationFn: async (body: ProjectUpdate) => {
      const input = JSON.stringify(body)
      if (command.current && command.current.input !== input) throw new ApiError(409, '上次参数修改结果未确认，请先重试原操作')
      if (!command.current) {
        const baseline = queryClient.getQueryData<ProjectDetail>(detailKey(id)) ?? await readProject(id)
        const { theme_id, ...remaining } = body
        const steps: Array<{ path: string; body: string; key: string }> = []
        if (theme_id !== undefined) steps.push({ path: `/projects/${id}/theme`, body: JSON.stringify({ theme_id, theme_revision: baseline.theme_revision }), key: newCommandKey() })
        if (Object.keys(remaining).length) steps.push({ path: `/projects/${id}`, body: JSON.stringify({ ...remaining, input_revision: baseline.input_revision }), key: newCommandKey() })
        command.current = { input, steps, next: 0 }
      }
      const own = command.current
      try {
        for (; own.next < own.steps.length; own.next++) { const step = own.steps[own.next]!; await request(step.path, { method: 'PATCH', body: step.body, idempotencyKey: step.key }) }
        const project = await fetchProject(id)
        if (command.current === own) command.current = null
        return project
      } catch (cause) { if (cause instanceof ApiError && cause.status < 500 && command.current === own) command.current = null; throw cause }
    },
    onSuccess: (project) => {
      queryClient.setQueryData(detailKey(id), project)
      void queryClient.invalidateQueries({ queryKey: listKey })
    },
  })
}

export function useUpdateProjectTheme(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ key, theme_revision, ...body }: { theme_id?: string; overrides?: ThemeOverrides; theme_revision?: number; key?: string }) => {
      const baseline = queryClient.getQueryData<ProjectDetail>(detailKey(id)) ?? await fetchProject(id)
      await request(`/projects/${id}/theme`, { method: 'PATCH', body: JSON.stringify({ ...body, theme_revision: theme_revision ?? baseline.theme_revision }), idempotencyKey: key })
      return fetchProject(id)
    },
    onSuccess: (project) => {
      queryClient.setQueryData(detailKey(id), project)
      void queryClient.invalidateQueries({ queryKey: listKey })
      // 字号变化会重算 issues，刷新 deck 缓存
      void queryClient.invalidateQueries({ queryKey: deckKey(id) })
    },
  })
}

export function useDeleteProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => requestPrepared(`/projects/${id}`, 'DELETE', id, async () => { const p = queryClient.getQueryData<ProjectDetail>(detailKey(id)) ?? await readProject(id); return JSON.stringify({ input_revision: p.input_revision }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: listKey }),
  })
}

export type DraftMode = 'topic' | 'text' | 'document'

export interface DraftInput {
  mode: DraftMode
  /** topic / text 模式的正文；document 模式忽略 */
  content: string
  files: File[]
  title: string
  audience: string | null
  tone: NonNullable<ProjectCreate['tone']>
  pageCount: number
  themeId: string
  layoutMode?: 'fixed' | 'flex'
  contentDensity?: 'concise' | 'medium' | 'detailed'
  onStep?: (step: string) => void
}

/**
 * 创作单屏的唯一提交动作：建项目 → attach 素材 → 起大纲任务。
 *
 * 后续步骤失败保留项目与已成功的材料，用户可从材料弹层补充并继续。
 */
export class DraftCreationError extends Error {
  readonly projectId: string
  constructor(projectId: string, cause: unknown) { super(`项目与已成功材料已保留。${cause instanceof Error ? cause.message : '后续处理失败'}`); this.projectId = projectId }
}
export function useCreateDraft() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (input: DraftInput): Promise<ProjectDetail> => {
      input.onStep?.('正在创建 PPT…')
      const project = await request<ProjectDetail>('/projects', {
        method: 'POST',
        body: JSON.stringify({
          title: input.title,
          audience: input.audience,
          tone: input.tone,
          page_count: input.pageCount,
          theme_id: input.themeId,
          layout_mode: input.layoutMode ?? 'flex',
          content_density: input.contentDensity ?? 'medium',
        }),
      })

      try {
        if (input.mode === 'document') {
          for (const [index, file] of input.files.entries()) {
            input.onStep?.(`正在读取文档（${index + 1}/${input.files.length}）…`)
            const form = new FormData()
            form.append('file', file)
            const current = await readProject(project.id)
            form.append('input_revision', String(current.input_revision))
            const source = await request<ProjectSource>(`/projects/${project.id}/sources/upload`, {
              method: 'POST',
              body: form,
            })
            if (source.job_id || source.parse_status === 'pending') await waitSource(project.id, source.id)
          }
        } else {
          input.onStep?.('正在整理内容…')
          const source = await request<ProjectSource>(`/projects/${project.id}/sources`, {
            method: 'POST',
            body: JSON.stringify({ kind: input.mode, text: input.content, input_revision: project.input_revision }),
          })
          if (source.job_id || source.parse_status === 'pending') await waitSource(project.id, source.id)
        }

        input.onStep?.('正在生成大纲…')
        const current = await readProject(project.id)
        await request(`/projects/${project.id}/outline/generate`, { method: 'POST', body: JSON.stringify({ input_revision: current.input_revision }) })
      } catch (error) {
        throw new DraftCreationError(project.id, error)
      }

      return project
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: listKey }),
    onError: () => queryClient.invalidateQueries({ queryKey: listKey }),
  })
}
