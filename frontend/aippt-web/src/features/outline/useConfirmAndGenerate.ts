import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { ApiError, request, requestPrepared } from '@/api/client'
import { tokenStore } from '@/features/auth/token'
import { deckKey } from '@/features/deck/api'
import { readProject } from '@/features/projects/api'
import { outlineKey, useUpdateOutline } from '@/features/outline/api'
import type { Outline, OutlinePage } from '@/features/outline/types'
import { useUpdateProject } from '@/features/projects/api'

interface LaunchInput {
  revision: number
  /** 有未保存的改动时先落库：确认校验的是服务端的大纲 */
  pages?: OutlinePage[]
  /** 与当前项目主题不同时才提交 */
  themeId?: string
}

/**
 * 「生成 PPT」这一次点击背后的完整链路：存大纲 → 定主题 → 确认 → 起生成任务。
 *
 * 合成一个动作而不是让用户依次点四个按钮，是因为这四步对用户是同一个意图；
 * 拆开只会让中间态（已确认但没生成）暴露出来，而那个状态没人想停留。
 */
export function useConfirmAndGenerate(projectId: string) {
  const updateOutline = useUpdateOutline(projectId)
  const updateProject = useUpdateProject(projectId)
  const queryClient = useQueryClient()
  const pendingInput = useRef<{ token: string | null; input: LaunchInput; accepted: boolean } | null>(null)
  const [unconfirmed, setUnconfirmed] = useState(false)
  useEffect(() => { pendingInput.current = null; setUnconfirmed(false) }, [projectId])

  const mutation = useMutation({
    mutationFn: async (input: LaunchInput) => {
      const token = tokenStore.get()
      if (pendingInput.current?.token !== token) pendingInput.current = null
      const own = pendingInput.current ?? { token, input: structuredClone(input), accepted: false }
      pendingInput.current = own
      setUnconfirmed(true)
      try {
        if (!own.accepted) await requestPrepared(`/projects/${projectId}/outline/confirm-and-generate`, 'POST', 'confirm-and-generate', async () => {
          const { revision, pages, themeId } = own.input
          let current = revision

          if (pages) {
            const saved = await updateOutline.mutateAsync({ revision, pages })
            current = saved.revision
            own.input = { ...own.input, revision: current, pages: undefined }
          }
          // 主题不参与大纲输入指纹，可以在确认前安全落库。
          if (themeId) {
            await updateProject.mutateAsync({ theme_id: themeId })
            own.input = { ...own.input, themeId: undefined }
          }

          const project = await readProject(projectId)
          return JSON.stringify({ revision: current, input_revision: project.input_revision })
        })
        own.accepted = true
        const confirmed = await request<Outline>(`/projects/${projectId}/outline`)
        queryClient.setQueryData(outlineKey(projectId), confirmed)
        void queryClient.invalidateQueries({ queryKey: deckKey(projectId) })
        void queryClient.invalidateQueries({ queryKey: ['projects', projectId], exact: true })
        if (pendingInput.current === own) { pendingInput.current = null; setUnconfirmed(false) }
        return confirmed
      } catch (cause) {
        if (cause instanceof ApiError && cause.status < 500 && pendingInput.current === own) { pendingInput.current = null; setUnconfirmed(false) }
        throw cause
      }
    },
  })
  return { ...mutation, unconfirmed }
}
