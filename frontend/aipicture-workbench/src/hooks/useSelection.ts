import { useApis, useHostRuntime, usePermission } from '@/host/HostContext'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'

import { type SelectInput, type Selection } from '@/api/sessions'
import { errorMessage } from '@/lib/errors'
import { BRUSH_RADIUS } from '@/lib/brush'
import { createSelectionHistory, inputsOf, type SelectionEntry } from '@/lib/selectionHistory'
import { useToast } from '@/host/HostContext'
import { useEditorUi, type CanvasSelection } from '@/stores/editorUi'

function toCanvas(selection: Selection): CanvasSelection {

  return {
    revision: selection.revision,
    maskId: selection.mask.id,
    maskUrl: selection.mask.url,
    markers: selection.markers,
  }
}

function entryOf(input: SelectInput): SelectionEntry | null {

  if (input.strokes?.[0]) return { kind: 'stroke', points: input.strokes[0] }
  if (input.points?.[0]) return { kind: 'point', point: input.points[0] }
  return null
}

export function useSelection(sessionId: string, revision: number) {
  const { sessions: sessionsApi } = useApis()
  const runtime = useHostRuntime()
  const queryClient = useQueryClient()
  const permitted = usePermission('aipicture:tool:execute')
  const toast = useToast()
  const toastRef = useRef(toast)
  useLayoutEffect(() => { toastRef.current = toast }, [toast])
  const setSelection = useEditorUi((state) => state.setSelection)
  const dropStaleSelection = useEditorUi((state) => state.dropStaleSelection)
  const selection = useEditorUi((state) => state.selection)
  const history = useRef(createSelectionHistory())
  const [, bump] = useState(0)
  const [failureSequence, setFailureSequence] = useState(0)
  const touch = () => bump((value) => value + 1)
  const current = useRef({ sessionId, revision })
  useLayoutEffect(() => { current.current = { sessionId, revision } }, [sessionId, revision])
  const context = () => ({ sessionId, revision, signal: runtime.signal })
  type Context = ReturnType<typeof context>
  const isCurrent = (captured: Context | undefined) => Boolean(captured && !captured.signal.aborted && runtime.active
    && current.current.sessionId === captured.sessionId && current.current.revision === captured.revision)

  // DELETE可能已经成功；失败后只回读正式mask，不假设回滚或自动重发选择。
  const recover = async (error: Error, captured: Context | undefined) => {
    if (error.name === 'AbortError' || !isCurrent(captured)) return
    // 原失败先反馈并结束本次临时预览；GET恢复只负责正式状态，不延迟失败提示。
    setFailureSequence((value) => value + 1)
    toast(errorMessage(error), 'danger')
    try {
      const body = await sessionsApi.getSelection(sessionId, { recover: true, signal: captured!.signal })
      if (!isCurrent(captured)) return
      setSelection(body && body.revision === captured!.revision ? toCanvas(body) : null)
    } catch (readError) {
      if (!isCurrent(captured)) return
      toast(errorMessage(readError), 'danger')
    }
    if (!isCurrent(captured)) return
    // 旧修订拒绝也回读会话，沿用正式文档版本，不自动覆盖它。
    await queryClient.invalidateQueries({ queryKey: ['session', sessionId], exact: true })
  }

  useEffect(() => {
    dropStaleSelection(revision)
    history.current.reset()
    touch()
  }, [revision, dropStaleSelection])

  useEffect(() => {
    if (!Number.isInteger(revision) || revision < 0) return
    let canceled = false
    void sessionsApi.getSelection(sessionId).then((body) => {
      if (!canceled && body && body.revision === revision) setSelection(toCanvas(body))
    }).catch((error) => { if (!canceled) toastRef.current(errorMessage(error), 'danger') })
    return () => { canceled = true }
  }, [sessionId, revision, sessionsApi, setSelection])

  const selectMode = useEditorUi((state) => state.selectMode)

  useEffect(() => {
    if (selectMode !== 'point' || !Number.isInteger(revision) || revision < 0 || !permitted) return
    const signal = runtime.signal
    void sessionsApi.prepareSelection(sessionId).catch((error) => {
      if (!signal.aborted && runtime.active && error.name !== 'AbortError') toastRef.current(errorMessage(error), 'danger')
    })
  }, [selectMode, sessionId, revision, permitted, sessionsApi, runtime])

  const select = useMutation({
    onMutate: context,
    mutationFn: (input: SelectInput) => sessionsApi.select(sessionId, input),
    onSuccess: (body, input, captured) => {
      if (!isCurrent(captured) || body.revision !== captured!.revision) return
      const entry = entryOf(input)
      if (entry) history.current.commit(entry)
      setSelection(toCanvas(body))
      touch()
    },
    onError: (error, _input, captured) => recover(error, captured),
  })

  const clear = useMutation({
    onMutate: context,
    mutationFn: () => sessionsApi.clearSelection(sessionId),
    onSuccess: (_body, _input, captured) => {
      if (!isCurrent(captured)) return
      history.current.clear()
      setSelection(null)
      touch()
    },
    onError: (error, _input, captured) => recover(error, captured),
  })

  const replay = useMutation({
    onMutate: context,
    mutationFn: async ({ entries }: { entries: SelectionEntry[]; direction: 'undo' | 'redo' }) => {
      await sessionsApi.clearSelection(sessionId)
      if (!entries.length) return null
      const { strokes, points } = inputsOf(entries)
      let body: Selection | null = null
      if (points.length) {
        body = await sessionsApi.select(sessionId, { revision, points, append: false })
      }
      if (strokes.length) {
        body = await sessionsApi.select(sessionId, { revision, strokes, radius: BRUSH_RADIUS })
      }
      return body
    },
    onSuccess: (body, { direction }, captured) => {
      if (!isCurrent(captured)) return
      history.current[direction]()
      setSelection(body ? toCanvas(body) : null)
    },
    onError: (error, _input, captured) => recover(error, captured),
    onSettled: touch,
  })

  const busy = select.isPending || clear.isPending || replay.isPending

  return {
    selection,
    failureSequence,
    busy,
    toolBusy: busy || !permitted,
    canUndo: permitted && history.current.canUndo(),
    canRedo: permitted && history.current.canRedo(),
    addPoint: (x: number, y: number) =>
      select.mutateAsync({
        revision,
        points: [{ x, y }],
        append: Boolean(selection && selection.revision === revision),
      }),
    addStroke: (points: { x: number; y: number }[]) =>
      select.mutateAsync({ revision, strokes: [points], radius: BRUSH_RADIUS }),
    clear: () => clear.mutate(),
    undo: () => {
      if (busy || !permitted) return
      const entries = history.current.peekUndo()
      if (entries !== null) replay.mutate({ entries, direction: 'undo' })
    },
    redo: () => {
      if (busy || !permitted) return
      const entries = history.current.peekRedo()
      if (entries !== null) replay.mutate({ entries, direction: 'redo' })
    },
  }
}

export type SessionSelection = ReturnType<typeof useSelection>
