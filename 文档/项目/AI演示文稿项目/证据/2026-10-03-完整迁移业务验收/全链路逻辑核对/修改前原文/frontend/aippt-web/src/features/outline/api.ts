import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, request } from '@/api/client'
import { readProject } from '@/features/projects/api'
import type {
  Outline,
  OutlineGenerateAccepted,
  OutlineUpdate,
} from '@/features/outline/types'

export const outlineKey = (projectId: string) => ['projects', projectId, 'outline'] as const
const projectKey = (projectId: string) => ['projects', projectId] as const
const projectListKey = ['projects'] as const

export function useOutline(projectId: string, enabled = true) {
  return useQuery({
    queryKey: outlineKey(projectId),
    queryFn: async () => {
      try {
        const outline = await request<Outline>(`/projects/${projectId}/outline`)
        return outline.status as string === 'empty' ? null : outline
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
    retry: false,
    enabled,
  })
}

export function useGenerateOutline(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const project = await readProject(projectId)
      return request<OutlineGenerateAccepted>(`/projects/${projectId}/outline/generate`, { method: 'POST', body: JSON.stringify({ input_revision: project.input_revision }) })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: outlineKey(projectId) }),
  })
}

export function useUpdateOutline(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: OutlineUpdate) =>
      request<Outline>(`/projects/${projectId}/outline`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    onSuccess: (outline) => queryClient.setQueryData(outlineKey(projectId), outline),
  })
}

function useRevisionMutation(projectId: string, action: 'confirm') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (revision: number) => { const project = await readProject(projectId); return request<Outline>(`/projects/${projectId}/outline/${action}`, { method: 'POST', body: JSON.stringify({ revision, input_revision: project.input_revision }) }) },
    onSuccess: (outline) => {
      queryClient.setQueryData(outlineKey(projectId), outline)
      void queryClient.invalidateQueries({ queryKey: projectKey(projectId) })
      void queryClient.invalidateQueries({ queryKey: projectListKey })
    },
  })
}

export function useConfirmOutline(projectId: string) {
  return useRevisionMutation(projectId, 'confirm')
}
