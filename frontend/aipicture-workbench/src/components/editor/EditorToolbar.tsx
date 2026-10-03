import { useEffect, useRef, useState } from 'react'
import { Link } from '@/host/router'

import type { SessionDetail } from '@/api/sessions'
import Button from '@/components/ui/Button'
import { buttonClass } from '@/components/ui/buttonStyles'
import type { SessionSelection } from '@/hooks/useSelection'
import type { SessionTools } from '@/hooks/useSessions'
import { wallOnly } from '@/lib/layers'
import { ZOOM_STEP, useCanvasView } from '@/stores/canvasView'
import { useEditorUi, type CropRatio, type CropRect } from '@/stores/editorUi'
import { useBusinessState, useHostRuntime, useToast } from '@/host/HostContext'
import { codePointLength } from '@/lib/text'

const CROP_RATIOS: { value: CropRatio; label: string }[] = [
  { value: 'free', label: '自由' },
  { value: '1:1', label: '1:1' },
  { value: '4:5', label: '4:5' },
  { value: '3:4', label: '3:4' },
  { value: '9:16', label: '9:16' },
  { value: '16:9', label: '16:9' },
]

export default function EditorToolbar({
  session,
  tools,
  picking,
  onRename,
}: {
  session: SessionDetail
  tools: SessionTools
  picking: SessionSelection
  onRename: (title: string) => Promise<unknown>
}) {
  const scale = useCanvasView((state) => state.scale)
  const fit = useCanvasView((state) => state.fit)
  const stepZoom = useCanvasView((state) => state.stepZoom)
  const zoomTo = useCanvasView((state) => state.zoomTo)
  const ui = useEditorUi()
  const uiStore = useBusinessState().editor
  const runtime = useHostRuntime()
  const [submittedCrop, setSubmittedCrop] = useState<CropRect | null>(null)
  const cropPending = ui.cropOpen && submittedCrop !== null && submittedCrop === ui.cropRect
  const cropRequest = useRef(0)
  const submittingCrop = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useEffect(() => {
    if (ui.cropOpen) return
    cropRequest.current++
    submittingCrop.current = false
  }, [ui.cropOpen])

  // 只有发起的那个按钮显示百分比，其余按钮照常禁用
  const running = (tool: string, match?: (params: Record<string, unknown>) => boolean) =>
    tools.isRunning(tool, match) ? tools.pendingProgress : null

  const confirmCrop = async () => {
    const current = uiStore.getState()
    if (!current.cropRect || !current.cropOpen || tools.toolBusy || submittingCrop.current) return
    const submitted = current.cropRect
    const signal = runtime.signal
    const request = ++cropRequest.current
    submittingCrop.current = true
    setSubmittedCrop(submitted)
    try {
      await tools.invokeAsync('crop_canvas', {
        rect: {
          x: submitted.x / session.document.width,
          y: submitted.y / session.document.height,
          width: submitted.width / session.document.width,
          height: submitted.height / session.document.height,
        },
      }, {})
      if (!mounted.current || signal.aborted || request !== cropRequest.current) return
      const latest = uiStore.getState()
      if (latest.cropOpen && latest.cropRect === submitted) ui.closeCrop()
    } catch { /* 原错误由命令链显示，保留裁剪框供用户明确重试。 */ }
    finally {
      if (mounted.current && request === cropRequest.current) {
        submittingCrop.current = false
        setSubmittedCrop(null)
      }
    }
  }

  return (
    <header className="border-line bg-paper scrollbar-slim flex h-14 shrink-0 items-center gap-2 overflow-x-auto border-b px-3">
      <TitleField value={session.title} onCommit={onRename} disabled={!tools.canEdit || tools.busy} />

      <span className="text-faint hidden shrink-0 text-xs tabular-nums sm:block">
        {session.document.width} × {session.document.height}
      </span>

      <div className="ml-1 flex shrink-0 items-center gap-1">
        {ui.selectMode ? (
          <>
            <Button
              active={ui.selectMode === 'point'}
              disabled={tools.toolBusy || picking.busy}
              title="点击物体生成选区"
              onClick={() => ui.setSelectMode(ui.selectMode === 'point' ? null : 'point')}
            >
              点选
            </Button>
            <Button
              active={ui.selectMode === 'brush'}
              disabled={tools.toolBusy || picking.busy}
              title="涂抹出选区"
              onClick={() => ui.setSelectMode(ui.selectMode === 'brush' ? null : 'brush')}
            >
              笔刷
            </Button>
            <Button
              disabled={tools.toolBusy || picking.busy}
              progress={running('erase_region')}
              title={picking.selection ? '消除选中区域；调用图像模型可能产生费用' : '无选区，消除整个目标图层；调用图像模型可能产生费用'}
              onClick={() =>
                tools.invoke('erase_region', {
                  mask_asset_id: picking.selection?.maskId,
                  revision: session.revision,
                  layer_id: ui.selectedLayerId,
                })
              }
            >
              消除
            </Button>
            <Button
              disabled={tools.toolBusy || picking.busy}
              active={ui.panel === 'replace'}
              title={picking.selection ? '按描述替换选区' : '无选区，按描述替换整个目标图层'}
              onClick={() => ui.setPanel(ui.panel === 'replace' ? null : 'replace')}
            >
              替换
            </Button>
            <Button
              disabled={tools.toolBusy || picking.busy || !picking.selection}
              progress={running('promote_object_to_layer')}
              title={picking.selection ? '把选区提升为独立图层，可撤销' : '先点选或涂抹'}
              onClick={() =>
                tools.invoke('promote_object_to_layer', {
                  mask_asset_id: picking.selection?.maskId,
                  revision: session.revision,
                })
              }
            >
              成层
            </Button>
            <Button
              disabled={!picking.selection || tools.busy || picking.toolBusy}
              title="清除当前选区"
              onClick={picking.clear}
            >
              清除
            </Button>
            <Button title="退出选择 Esc" onClick={() => ui.setSelectMode(null)}>
              完成
            </Button>
            {!picking.selection && <span className="text-danger text-[11px]">无选区，区域工具作用整层 · 可能产生费用</span>}
          </>
        ) : ui.cropOpen ? (
          <>
            {CROP_RATIOS.map((item) => (
              <Button
                key={item.value}
                active={ui.cropRatio === item.value}
                disabled={tools.toolBusy || cropPending}
                title={item.value === 'free' ? '自由裁剪' : `按 ${item.label} 裁剪`}
                onClick={() => ui.setCropRatio(item.value, session.document)}
              >
                {item.label}
              </Button>
            ))}
            <Button title="按当前框裁剪画布，可撤销" disabled={tools.toolBusy || cropPending} onClick={confirmCrop}>
              确定
            </Button>
            <Button title="退出裁剪 Esc" onClick={ui.closeCrop}>
              取消
            </Button>
          </>
        ) : (
          <>
            <Button
              disabled={tools.toolBusy}
              active={ui.cropOpen}
              title="按比例裁剪画布"
              onClick={() => ui.openCrop(session.document)}
            >
              裁剪
            </Button>
            <Button
              disabled={tools.toolBusy}
              progress={running('flip_layer', (params) => params.direction === 'horizontal')}
              title="左右翻转当前图层"
              onClick={() =>
                tools.invoke('flip_layer', {
                  direction: 'horizontal',
                  layer_id: ui.selectedLayerId,
                })
              }
            >
              水平翻转
            </Button>
            <Button
              disabled={tools.toolBusy}
              progress={running('flip_layer', (params) => params.direction === 'vertical')}
              title="上下翻转当前图层"
              onClick={() =>
                tools.invoke('flip_layer', {
                  direction: 'vertical',
                  layer_id: ui.selectedLayerId,
                })
              }
            >
              垂直翻转
            </Button>
            <Button
              disabled={tools.toolBusy}
              progress={running('remove_background')}
              title="抠出主体，背景变透明"
              onClick={() => tools.invoke('remove_background', { layer_id: ui.selectedLayerId })}
            >
              去背景
            </Button>
            <Button
              disabled={tools.toolBusy}
              active={ui.panel === 'background'}
              title="按描述替换背景"
              onClick={() => ui.setPanel(ui.panel === 'background' ? null : 'background')}
            >
              换背景
            </Button>
            <Button
              disabled={tools.toolBusy}
              active={ui.panel === 'expand'}
              title="扩展画布到新比例"
              onClick={() => ui.setPanel(ui.panel === 'expand' ? null : 'expand')}
            >
              扩图
            </Button>
            <Button
              disabled={tools.toolBusy}
              progress={running('upscale_image')}
              title={
                wallOnly(session.document)
                  ? '放大到两倍，已拆层所以结果只进图片墙'
                  : '把当前画布放大到两倍'
              }
              onClick={() => tools.invoke('upscale_image', { scale: 2 })}
            >
              超分
            </Button>
            <Button
              active={ui.panel === 'adjust'}
              disabled={tools.toolBusy}
              title="调整亮度、对比度和色彩"
              onClick={() => ui.setPanel(ui.panel === 'adjust' ? null : 'adjust')}
            >
              调色
            </Button>
            <Button
              disabled={tools.toolBusy}
              progress={running('split_layers')}
              title="拆成背景和主体，文字需在图层面板勾选，可撤销"
              onClick={() => tools.invoke('split_layers', { include_text: ui.splitIncludeText })}
            >
              拆层
            </Button>
            <Button
              disabled={tools.toolBusy}
              title="点选物体建立选区"
              onClick={() => ui.setSelectMode('point')}
            >
              点选
            </Button>
            <Button
              disabled={tools.toolBusy}
              title="涂抹建立选区"
              onClick={() => ui.setSelectMode('brush')}
            >
              笔刷
            </Button>
            <Button disabled={tools.toolBusy || picking.busy} progress={running('erase_region')}
              title={picking.selection ? '仅消除选区；调用图像模型可能产生费用' : '无选区，作用整个目标图层；调用图像模型可能产生费用'}
              onClick={() => tools.invoke('erase_region', { layer_id: ui.selectedLayerId })}>
              {picking.selection ? '消除选区' : '整层消除'}
            </Button>
            <Button disabled={tools.toolBusy || picking.busy} active={ui.panel === 'replace'}
              title={picking.selection ? '仅替换选区；调用图像模型可能产生费用' : '无选区，作用整个目标图层；调用图像模型可能产生费用'}
              onClick={() => ui.setPanel(ui.panel === 'replace' ? null : 'replace')}>
              {picking.selection ? '替换选区' : '整层替换'}
            </Button>
          </>
        )}
      </div>

      <div className="ml-auto flex shrink-0 items-center gap-1">
        <Button
          disabled={tools.busy || picking.busy || (!picking.canUndo && !tools.canEdit) || !(picking.canUndo || session.can_undo)}
          title={
            picking.canUndo
              ? '撤销选区 ⌘Z'
              : session.can_undo
                ? '撤销上一步 ⌘Z'
                : '没有可撤销的操作'
          }
          onClick={() => (picking.canUndo ? picking.undo() : tools.undo())}
        >
          撤销
        </Button>
        <Button
          disabled={tools.busy || picking.busy || (!picking.canRedo && !tools.canEdit) || !(picking.canRedo || session.can_redo)}
          title={
            picking.canRedo
              ? '重做选区 ⌘⇧Z'
              : session.can_redo
                ? '重做 ⌘⇧Z'
                : '没有可重做的操作'
          }
          onClick={() => (picking.canRedo ? picking.redo() : tools.redo())}
        >
          重做
        </Button>
        <Button
          active={ui.compareOpen}
          disabled={!session.previous_document}
          title={session.previous_document ? '与上一版对比' : '还没有上一版'}
          onClick={() => ui.setCompareOpen(!ui.compareOpen)}
        >
          对比
        </Button>

        <div className="border-line rounded-control ml-1 flex items-center gap-0.5 border p-0.5">
          <Button
            variant="icon"
            size="sm"
            title="缩小 -"
            onClick={() => stepZoom(1 / ZOOM_STEP)}
          >
            －
          </Button>
          <Button
            size="sm"
            title="实际像素 · 按 1"
            className="w-12 tabular-nums"
            onClick={() => zoomTo(1)}
          >
            {Math.round(scale * 100)}%
          </Button>
          <Button variant="icon" size="sm" title="放大 +" onClick={() => stepZoom(ZOOM_STEP)}>
            ＋
          </Button>
          <Button
            size="sm"
            title="适应窗口 · 按 0，画布内双击同样生效"
            onClick={() => fit(session.document)}
          >
            适应
          </Button>
        </div>

        <Link to={`/marketing/${session.id}`} title="导出营销图和投放尺寸" className={buttonClass()}>
          导出
        </Link>
        <Button
          active={ui.panel === 'layers'}
          title="图层、变换和编辑记录"
          onClick={() => ui.setPanel(ui.panel === 'layers' ? null : 'layers')}
        >
          图层
        </Button>
      </div>
    </header>
  )
}

function TitleField({ value, onCommit, disabled }: { value: string; onCommit: (title: string) => Promise<unknown>; disabled?: boolean }) {
  const [draft, setDraft] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const submitting = useRef(false)
  const mounted = useRef(true)
  const runtime = useHostRuntime()
  const toast = useToast()
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  const commit = async () => {
    if (submitting.current || disabled) return
    const next = draft?.trim()
    if (!next || next === value) { setDraft(null); return }
    // 与正式 SessionService 一致：先折叠 ASCII 空白，再按码点判定标题上限。
    if (codePointLength(next.replace(/[ \t\n\v\f\r]+/g, ' ')) > 80) { toast('会话标题最多 80 个字符', 'danger'); return }
    const submitted = draft, signal = runtime.signal
    submitting.current = true
    setPending(true)
    try {
      await onCommit(next)
      if (mounted.current && !signal.aborted && runtime.active) setDraft((current) => current === submitted ? null : current)
    } catch { /* 保留新标题，正式错误由会话命令反馈；不自动再 PATCH。 */ }
    finally {
      if (mounted.current && !signal.aborted) { submitting.current = false; setPending(false) }
    }
  }

  return (
    <input
      value={draft ?? value}
      disabled={disabled || pending}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={() => { void commit() }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' && !event.nativeEvent.isComposing) event.currentTarget.blur()
        if (event.key === 'Escape') setDraft(null)
      }}
      aria-label="会话标题"
      className="text-ink hover:bg-soft focus:bg-soft rounded-chip w-28 shrink-0 px-2 py-1 text-sm font-medium transition-colors duration-150 outline-none sm:w-40"
    />
  )
}
