import { ApiError, newCommandKey } from '@/api/client'
import {
  BarChart3,
  Columns2,
  Columns3,
  Hash,
  Image as ImageIcon,
  LayoutGrid,
  List,
  MessageSquare,
  Table2,
  Type,
} from 'lucide-react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import {
  createSlideBlock,
  updateFlexLayout,
} from '@/features/deck/api'
import {
  type FlexBlockType,
  findContainerById,
  resolveInsertAnchor,
  wrapBlockIdsAsColumns,
} from '@/features/deck/flexTree'
import {
  getSlideSaveHandlers,
  requestInsertDrag,
} from '@/features/deck/slideSaveBridge'
import type { DeckSlide } from '@/features/deck/types'
import { cn } from '@/lib/utils'
import type { FlexContainer } from '@/render/flexLayout'

const BLOCK_BUTTONS: Array<{
  type: FlexBlockType
  label: string
  icon: typeof Type
}> = [
  { type: 'text', label: '文本', icon: Type },
  { type: 'bullets', label: '列表', icon: List },
  { type: 'image', label: '图片', icon: ImageIcon },
  { type: 'kpi', label: 'KPI', icon: Hash },
  { type: 'cards', label: '卡片', icon: LayoutGrid },
  { type: 'callout', label: '提示', icon: MessageSquare },
  { type: 'table', label: '表格', icon: Table2 },
  { type: 'chart', label: '图表', icon: BarChart3 },
]

export type InsertAnchor = { parentId: string; index: number }

/** 每个用户意图保留已确认的子步骤；重试不会重复新增已落库块。 */
export function createColumnsInsertion(projectId: string, anchor: InsertAnchor, count: 2 | 3) {
  const ids: string[] = []
  let lastFormal: DeckSlide | null = null
  let pending: { body: { revision: number; type: 'text'; parent_id: string; index: number }; key: string; conflict: boolean } | null = null
  let wrap: { body: { revision: number; layout_tree: FlexContainer }; key: string; conflict: boolean } | null = null
  const run = async (slide: DeckSlide): Promise<DeckSlide> => {
    let current = lastFormal && slide.revision < lastFormal.revision ? lastFormal : slide
    if (ids.some((id) => !current.blocks.some((block) => block.id === id))) throw new Error('已新增块不在当前正式页面，保留部分结果等待人工核对')
    while (ids.length < count) {
      if (!pending || pending.conflict) pending = { key: newCommandKey(), body: { revision: current.revision, type: 'text', parent_id: anchor.parentId, index: anchor.index + ids.length }, conflict: false }
      const before = new Set(current.blocks.map((block) => block.id))
      try {
        current = await createSlideBlock(projectId, slide.id, pending.body, pending.key)
        const added = current.blocks.filter((block) => !before.has(block.id))
        if (added.length !== 1) throw new Error('新增块回执缺少唯一块，保留正式结果等待核对')
        ids.push(added[0]!.id); lastFormal = current; pending = null
      } catch (error) { if (error instanceof ApiError && error.status === 409) pending.conflict = true; throw error }
    }
    if (!current.layout_tree) throw new Error('列插入未得到预期布局树')
    const grouped = (node: FlexContainer): boolean => node.type === 'row' && node.children.length === count && node.children.every((child) => child.type === 'block' && ids.includes(child.block_id)) || node.children.some((child) => child.type !== 'block' && grouped(child))
    if (grouped(current.layout_tree)) return current
    if (!wrap || wrap.conflict) {
      if (!findContainerById(current.layout_tree, anchor.parentId)) throw new Error('插入目标已不存在；已新增的块仍保留')
      const tree = wrapBlockIdsAsColumns(current.layout_tree, ids)
      if (!tree) throw new Error('无法组装多列布局；已新增的块仍保留')
      wrap = { body: { revision: current.revision, layout_tree: tree }, key: newCommandKey(), conflict: false }
    }
    try { return await updateFlexLayout(projectId, slide.id, wrap.body, wrap.key) }
    catch (error) { if (error instanceof ApiError && error.status === 409) wrap.conflict = true; throw error }
  }
  // 只有部分正式写入后保留整组首次 before，以便撤销整组新增。
  Object.assign(run, { hasFormalWrites: () => ids.length > 0 })
  return run
}
export async function insertColumnsAt(projectId: string, slide: DeckSlide, anchor: InsertAnchor, count: 2 | 3): Promise<DeckSlide> {
  return createColumnsInsertion(projectId, anchor, count)(slide)
}

/**
 * 版式侧栏插入条：拖到画布落点插入；未拖动松手则落到选中块后/根末尾。
 */
export function BlockInsertPanel({
  projectId,
  slide,
  selectedBlockId,
  disabled,
}: {
  projectId: string
  slide: DeckSlide
  selectedBlockId: string | null
  disabled?: boolean
}) {
  if (slide.layout_mode !== 'flex' || slide.layout_tree == null) return null

  const tree = slide.layout_tree as FlexContainer
  const anchor = resolveInsertAnchor(tree, selectedBlockId)
  const handlers = getSlideSaveHandlers(slide.id)
  const locked = Boolean(disabled || !handlers)

  const insertBlock = (type: FlexBlockType) => {
    if (locked || !handlers) return
    handlers.commitCreateBlock({
      type,
      parent_id: anchor.parentId,
      index: anchor.index,
    })
  }

  // 入队即返回，失败由保存队列的状态提示；这里不做本地 try/catch 以免看起来已兜住
  const insertColumns = (count: 2 | 3) => {
    if (locked || !handlers) return
    handlers.commitMutate(createColumnsInsertion(projectId, anchor, count))
  }

  const beginDragBlock = (type: FlexBlockType, event: ReactPointerEvent) => {
    if (locked) return
    event.preventDefault()
    if (!requestInsertDrag(slide.id, { kind: 'block', type }, event.clientX, event.clientY)) {
      insertBlock(type)
    }
  }

  const beginDragColumns = (count: 2 | 3, event: ReactPointerEvent) => {
    if (locked) return
    event.preventDefault()
    if (
      !requestInsertDrag(
        slide.id,
        { kind: 'columns', count },
        event.clientX,
        event.clientY,
      )
    ) {
      insertColumns(count)
    }
  }

  return (
    <div className="rounded-xl border border-line bg-surface-soft/40 p-3">
      <p className="mb-2 text-[12px] font-medium text-ink-soft">插入内容</p>
      <InsertButtons
        locked={locked}
        onDragBlock={beginDragBlock}
        onDragColumns={beginDragColumns}
      />
    </div>
  )
}

function InsertButtons({
  locked,
  onDragBlock,
  onDragColumns,
}: {
  locked: boolean
  onDragBlock: (type: FlexBlockType, event: ReactPointerEvent) => void
  onDragColumns: (count: 2 | 3, event: ReactPointerEvent) => void
}) {
  return (
    <>
      <div className="grid grid-cols-3 gap-0.5">
        {BLOCK_BUTTONS.map(({ type, label, icon: Icon }) => (
          <button
            key={type}
            type="button"
            title={`拖入${label}，或轻点插入`}
            disabled={locked}
            onPointerDown={(event) => onDragBlock(type, event)}
            className={cn(
              'flex cursor-grab flex-col items-center gap-1 rounded-lg px-1.5 py-2.5 text-[11px] transition-colors',
              'text-ink-soft hover:bg-surface hover:text-ink active:cursor-grabbing',
              'disabled:opacity-40',
            )}
          >
            <Icon className="size-3.5" />
            {label}
          </button>
        ))}
      </div>
      <div className="mt-2 grid grid-cols-2 gap-0.5 border-t border-line pt-2">
        <button
          type="button"
          disabled={locked}
          title="拖入 2 列，或轻点插入"
          onPointerDown={(event) => onDragColumns(2, event)}
          className="flex cursor-grab items-center justify-center gap-1 rounded-lg px-2 py-1.5 text-[11px] text-ink-soft hover:bg-surface hover:text-ink active:cursor-grabbing disabled:opacity-40"
        >
          <Columns2 className="size-3.5" />
          2 列
        </button>
        <button
          type="button"
          disabled={locked}
          title="拖入 3 列，或轻点插入"
          onPointerDown={(event) => onDragColumns(3, event)}
          className="flex cursor-grab items-center justify-center gap-1 rounded-lg px-2 py-1.5 text-[11px] text-ink-soft hover:bg-surface hover:text-ink active:cursor-grabbing disabled:opacity-40"
        >
          <Columns3 className="size-3.5" />
          3 列
        </button>
      </div>
    </>
  )
}
