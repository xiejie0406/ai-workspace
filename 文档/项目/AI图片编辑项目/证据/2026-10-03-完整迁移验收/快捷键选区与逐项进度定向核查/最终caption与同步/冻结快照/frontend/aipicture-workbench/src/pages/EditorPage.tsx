import { useEffect, useLayoutEffect, useMemo } from 'react'
import { Link, useParams } from '@/host/router'

import CanvasHint from '@/components/editor/CanvasHint'
import CanvasStage from '@/components/editor/CanvasStage'
import EditorToolbar from '@/components/editor/EditorToolbar'
import ImageWall from '@/components/editor/ImageWall'
import LayerPanel from '@/components/editor/LayerPanel'
import SessionSidebar from '@/components/editor/SessionSidebar'
import ProgressBar from '@/components/ui/ProgressBar'
import { buttonClass } from '@/components/ui/buttonStyles'
import { useMountTransition } from '@/hooks/useMountTransition'
import { useSelection } from '@/hooks/useSelection'
import { usePatchSession, useSession, useSessionTools } from '@/hooks/useSessions'
import { ZOOM_STEP, useCanvasView } from '@/stores/canvasView'
import { useEditorUi } from '@/stores/editorUi'
import { useBusinessState, useHostRuntime } from '@/host/HostContext'
import { errorMessage, isNotFoundError } from '@/lib/errors'
import Button from '@/components/ui/Button'

// 与 index.css 的 slide-out 时长保持一致
const PANEL_EXIT_MS = 200

export default function EditorPage() {
  const { sessionId = '' } = useParams()

  return (
    // 编辑器自成一屏：不让工具条或画布把外层 main 撑出滚动条，否则画布会被滚出视口
    <div className="flex h-full overflow-hidden">
      <SessionSidebar activeId={sessionId} />
      {sessionId ? (
        <Workspace sessionId={sessionId} />
      ) : (
        <Notice title="选择一个会话" hint="从左侧打开历史对话，或回到创作页开始新的一张。">
          <CreateLink />
        </Notice>
      )}
    </div>
  )
}

function Workspace({ sessionId }: { sessionId: string }) {
  const runtime = useHostRuntime()
  const { editor } = useBusinessState()
  const { data: session, error, refetch } = useSession(sessionId)
  const patch = usePatchSession(sessionId)
  const tools = useSessionTools(sessionId)
  const picking = useSelection(sessionId, session?.revision ?? 0)

  const cropOpen = useEditorUi((state) => state.cropOpen)
  const compareOpen = useEditorUi((state) => state.compareOpen)
  const panel = useEditorUi((state) => state.panel)
  const selectMode = useEditorUi((state) => state.selectMode)
  const closeCrop = useEditorUi((state) => state.closeCrop)
  const setCompareOpen = useEditorUi((state) => state.setCompareOpen)
  const setSelectMode = useEditorUi((state) => state.setSelectMode)
  const setPanel = useEditorUi((state) => state.setPanel)
  const heldPanel = useMountTransition(panel, PANEL_EXIT_MS)

  const fit = useCanvasView((state) => state.fit)
  const stepZoom = useCanvasView((state) => state.stepZoom)
  const zoomTo = useCanvasView((state) => state.zoomTo)

  // 只在正式文档提交后校准；PATCH失败继续保留原图选择。
  // 覆盖墙切换、撤销/重做和GET回读，避免视觉fallback与真实工具目标不同。
  useLayoutEffect(() => {
    if (!session) return
    const state = editor.getState()
    if (state.selectedLayerId && !session.document.layers.some((layer) => layer.id === state.selectedLayerId)) state.selectLayer(null)
    const preview = editor.getState().layerPreview
    if (preview && !session.document.layers.some((layer) => layer.id === preview.id)) editor.getState().setLayerPreview(null)
  }, [editor, session])

  const urls = useMemo(
    () => new Map((session?.assets ?? []).map((asset) => [asset.id, asset.url])),
    [session?.assets],
  )

  useEffect(() => {
    if (!session) return
    const onKey = (event: KeyboardEvent) => {
      if (!runtime.active || !runtime.mountRoot || !event.composedPath().includes(runtime.mountRoot)) return
      const origin = event.composedPath()[0]
      if (origin instanceof HTMLInputElement || origin instanceof HTMLTextAreaElement || (origin instanceof HTMLElement && origin.isContentEditable)) {
        return
      }
      // Esc 一次只收一层，先退临时模式，都退完了才关右侧面板
      if (event.key === 'Escape') {
        if (cropOpen) closeCrop()
        else if (compareOpen) setCompareOpen(false)
        else if (selectMode) setSelectMode(null)
        else if (panel) setPanel(null)
        return
      }

      const meta = event.metaKey || event.ctrlKey
      if (meta && event.key.toLowerCase() === 'z') {
        event.preventDefault()
        if (event.repeat || tools.busy || picking.busy) return
        if (event.shiftKey) {
          if (picking.canRedo) picking.redo()
          else if (session.can_redo) tools.redo()
        } else if (picking.canUndo) {
          picking.undo()
        } else if (session.can_undo) {
          tools.undo()
        }
        return
      }
      if (meta && event.key.toLowerCase() === 'y') {
        event.preventDefault()
        if (event.repeat || tools.busy || picking.busy) return
        if (picking.canRedo) picking.redo()
        else if (session.can_redo) tools.redo()
        return
      }

      // 视图快捷键不带修饰键，避开浏览器自身的缩放
      if (meta || event.shiftKey || event.altKey) return
      if (event.key === '0') fit(session.document)
      else if (event.key === '1') zoomTo(1)
      else if (event.key === '=' || event.key === '+') stepZoom(ZOOM_STEP)
      else if (event.key === '-') stepZoom(1 / ZOOM_STEP)
    }
    const target = runtime.mountRoot
    target?.addEventListener('keydown', onKey)
    return () => target?.removeEventListener('keydown', onKey)
  }, [
    session,
    tools,
    picking,
    cropOpen,
    compareOpen,
    selectMode,
    panel,
    closeCrop,
    setCompareOpen,
    setSelectMode,
    setPanel,
    fit,
    stepZoom,
    zoomTo,
    runtime,
  ])

  if (!session) {
    return session === null || isNotFoundError(error) ? (
      <Notice title="会话不存在" hint="链接可能已失效，回到创作页新建一个。">
        <CreateLink />
      </Notice>
    ) : error ? (
      <Notice title="会话读取失败" hint={errorMessage(error)} alert>
        <Button className="mt-4" onClick={() => { void refetch() }}>重新读取</Button>
      </Notice>
    ) : (
      <Notice title="加载中" hint="正在读取会话状态" />
    )
  }

  return (
    <>
      <div className="flex min-w-0 flex-1 flex-col">
        <EditorToolbar
          session={session}
          tools={tools}
          picking={picking}
          onRename={(title) => patch.mutate({ title })}
        />
        {(tools.runLoadError || tools.runNotFound) && <div className="bg-paper text-danger shrink-0 px-4 py-2 text-xs" role="alert">
          {tools.runNotFound ? '任务不存在，当前处理状态无法读取。' : errorMessage(tools.runLoadError)}
          <Button variant="outline" size="sm" className="ml-2" onClick={tools.retryRun}>重新读取任务</Button>
        </div>}
        {tools.pending && (
          <div className="bg-paper shrink-0"><ProgressBar value={tools.pendingProgress} tone="brand" className="h-0.5" /><button className="text-danger m-2 text-xs" onClick={tools.cancel}>取消尚未开始的处理（进行中的供应商请求可能继续）</button></div>
        )}

        <div className="relative flex min-h-0 min-w-0 flex-1">
          <div className="relative min-h-0 min-w-0 flex-1">
            <CanvasStage
              document={session.document}
              previous={session.previous_document}
              urls={urls}
              selection={picking.selection}
              selectionRevision={session.revision}
              selectionFailure={picking.failureSequence}
              readOnly={tools.toolBusy}
              onPoint={tools.busy || picking.toolBusy ? undefined : picking.addPoint}
              onStroke={tools.busy || picking.toolBusy ? undefined : picking.addStroke}
              onMove={(layer_id, x, y) => tools.invokeAsync('move_layer', { layer_id, x, y }, { retry: true })}
              onScale={(layer_id, scale, x, y) =>
                tools.invokeAsync('scale_layer', { layer_id, scale_x: scale, scale_y: scale, x, y }, { retry: true })
              }
              onEditText={(layer_id, text) => tools.invokeAsync('set_layer_text', { layer_id, text }, {})}
            />
            <CanvasHint
              {...canvasHint({
                busy: tools.busy,
                pending: tools.pending,
                stage: tools.pendingStage,
                progress: tools.pendingProgress,
                cropOpen,
                compareOpen,
                selectMode,
                picking: picking.busy,
              })}
            />
          </div>
          {heldPanel && (
            <div
              className={`shadow-panel w-72 shrink-0 max-[960px]:absolute max-[960px]:inset-y-0 max-[960px]:right-0 max-[960px]:z-20 ${
                heldPanel.exiting ? 'animate-slide-out' : 'animate-slide-in'
              }`}
            >
              <LayerPanel session={session} tools={tools} panel={heldPanel.value} />
            </div>
          )}
        </div>

        <ImageWall
          assets={session.assets}
          currentId={session.current_asset_id}
          disabled={patch.isPending || tools.busy || !tools.canEdit}
          onPick={(current_asset_id) => patch.mutate({ current_asset_id })}
        />
      </div>
    </>
  )
}

/** 提示按模式分组，这样切模式会重播一次入场，但进度百分比刷新不会。 */
function canvasHint({
  busy,
  pending,
  stage,
  progress,
  cropOpen,
  compareOpen,
  selectMode,
  picking,
}: {
  busy: boolean
  pending: boolean
  stage: string
  progress: number
  cropOpen: boolean
  compareOpen: boolean
  selectMode: 'point' | 'brush' | null
  picking: boolean
}) {
  // 撤销、重做没有任务进度，只说在忙
  if (busy) {
    return { mode: 'busy', text: pending ? `${stage || '处理中'} · ${progress}%` : '处理中' }
  }
  if (cropOpen) return { mode: 'crop', text: '拖动裁剪框，点确定应用 · Esc 取消' }
  if (compareOpen) return { mode: 'compare', text: '拖动画布上的圆点对比上一版 · Esc 退出' }
  if (selectMode === 'point') {
    return picking
      ? { mode: 'point-busy', text: '正在处理选区…' }
      : { mode: 'point', text: '点击物体建立选区，可连续点选 · Esc 退出' }
  }
  if (selectMode === 'brush') {
    return { mode: 'brush', text: '按住圈出要改的区域，松手即选中圈内 · Esc 退出' }
  }
  return { mode: 'idle', text: null }
}

function CreateLink() {
  return (
    <Link to="/create" className={`${buttonClass({ variant: 'solid' })} mt-5 px-4 py-2 text-sm`}>
      去创作
    </Link>
  )
}

function Notice({
  title,
  hint,
  children,
  alert,
}: {
  title: string
  hint: string
  children?: React.ReactNode
  alert?: boolean
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
      <h1 className="text-ink text-lg font-semibold">{title}</h1>
      <p className="text-muted mt-1 max-w-sm text-sm" role={alert ? 'alert' : undefined}>{hint}</p>
      {children}
    </div>
  )
}
