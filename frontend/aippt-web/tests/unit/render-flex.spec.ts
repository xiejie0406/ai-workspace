import { describe, expect, it } from 'vitest'
import {
  clearRectOverrides,
  solve,
  solveNodeAreas,
  solveWithFrames,
  type FlexContainer,
} from '@/render/flexLayout'
import { SAFE_AREA, type Rect } from '@/render/types'

// 预期使用全页归一化坐标；不用被测 solver 生成自己的正确答案。
const expectRect = (actual: Rect | undefined, expected: Rect) => {
  expect(actual).toBeDefined()
  for (const key of ['x', 'y', 'w', 'h'] as const) {
    expect(actual![key]).toBeCloseTo(expected[key], 12)
  }
}

const absolute = { x: 0.02, y: 0.03, w: 0.24, h: 0.17 }
const customCanvas = { x: 0.1, y: 0.2, w: 0.8, h: 0.6 }
const overriddenTree = (): FlexContainer => ({
  type: 'column', id: 'root', gap_pt: 54,
  children: [
    { type: 'row', id: 'nested', preset: 'solid_boxes', children: [
      { type: 'block', id: 'leaf', block_id: 'block', rect_override: { ...absolute },
        bleed: true, offset_x_pt: 96, offset_y_pt: 54, text_style: 'title' },
    ] },
  ],
})

describe('原灵活布局算法与固定页首帧覆盖', () => {
  it('solveNode 使用全页绝对覆盖，不随父区域、皮肤内缩、出血或平移改变', () => {
    const result = solveWithFrames(overriddenTree(), customCanvas)
    expect(result.placed).toHaveLength(1)
    expect(result.placed[0]).toMatchObject({ block_id: 'block', text_style: 'title' })
    expectRect(result.placed[0]!.rect, absolute)
    expect(result.frames).toHaveLength(1)
    // 皮肤仍在原树分配区域，叶框覆盖不能重写皮肤和父容器。
    expectRect(result.frames[0]!.rect, customCanvas)
  })

  it('solveNodeAreas 的叶节点命中相同绝对框，拉伸手柄与渲染首帧一致', () => {
    const areas = solveNodeAreas(overriddenTree(), customCanvas)
    expectRect(areas.get('leaf'), absolute)
    expectRect(areas.get('root'), customCanvas)
    expectRect(areas.get('nested'), customCanvas)
  })

  it('不同叶覆盖各自保留，不把第二页框归一到父区域或共享第一框', () => {
    const second = { x: 0.63, y: 0.71, w: 0.31, h: 0.22 }
    const tree: FlexContainer = { type: 'row', id: 'root', ratios: [1, 3], children: [
      { type: 'block', id: 'left', block_id: 'a', rect_override: absolute },
      { type: 'block', id: 'right', block_id: 'b', rect_override: second },
    ] }
    const placed = solve(tree, customCanvas)
    const areas = solveNodeAreas(tree, customCanvas)
    expectRect(placed[0]!.rect, absolute)
    expectRect(placed[1]!.rect, second)
    expectRect(areas.get('left'), absolute)
    expectRect(areas.get('right'), second)
    expect(placed.map((item) => item.block_id)).toEqual(['a', 'b'])
  })

  it.each(['omitted', 'null'] as const)('覆盖为 %s 时保留原 row 比例、pt 间隙及皮肤内缩', (mode) => {
    const tree: FlexContainer = { type: 'row', id: 'root', gap_pt: 96,
      ratios: [1, 3], preset: 'solid_boxes', children: [
        { type: 'block', id: 'left', block_id: 'a', ...(mode === 'null' ? { rect_override: null } : {}) },
        { type: 'block', id: 'right', block_id: 'b' },
      ] }
    const placed = solve(tree, customCanvas)
    const areas = solveNodeAreas(tree, customCanvas)
    // 宽 .8 - 间隙 .1 后按 1:3 分配；12pt 皮肤左右各 .0125，上下各 1/45。
    const expected = [
      { x: 0.1125, y: 0.2222222222222222, w: 0.15, h: 0.5555555555555556 },
      { x: 0.3875, y: 0.2222222222222222, w: 0.5, h: 0.5555555555555556 },
    ]
    expected.forEach((rect, index) => expectRect(placed[index]!.rect, rect))
    expectRect(areas.get('left'), expected[0]!)
    expectRect(areas.get('right'), expected[1]!)
  })

  it('无覆盖的 column 保留 grow、空 spacer 和旧间隙分配', () => {
    const tree: FlexContainer = { type: 'column', id: 'root', gap_pt: 54, children: [
      { type: 'block', id: 'top', block_id: 'a', grow: 1 },
      { type: 'row', id: 'spacer', grow: 1, children: [] },
      { type: 'block', id: 'bottom', block_id: 'b', grow: 2 },
    ] }
    const canvas = { x: 0, y: 0, w: 1, h: 1 }
    const placed = solve(tree, canvas)
    const areas = solveNodeAreas(tree, canvas)
    expect(placed.map((item) => item.block_id)).toEqual(['a', 'b'])
    expectRect(placed[0]!.rect, { x: 0, y: 0, w: 1, h: 0.2 })
    expectRect(areas.get('spacer'), { x: 0, y: 0.3, w: 1, h: 0.2 })
    expectRect(placed[1]!.rect, { x: 0, y: 0.6, w: 1, h: 0.4 })
    expectRect(areas.get('bottom'), { x: 0, y: 0.6, w: 1, h: 0.4 })
  })

  it('无覆盖时仍先出血再应用 pt 平移，两种求解路径相同', () => {
    const tree: FlexContainer = { type: 'row', id: 'root', children: [
      { type: 'block', id: 'leaf', block_id: 'image', bleed: true, offset_x_pt: 96, offset_y_pt: 54 },
    ] }
    const canvas = { x: SAFE_AREA.x, y: SAFE_AREA.y, w: 0.3, h: 0.2 }
    const expected = { x: 0.1, y: 0.1, w: 0.3541666666666667, h: 0.2777777777777778 }
    expectRect(solve(tree, canvas)[0]!.rect, expected)
    expectRect(solveNodeAreas(tree, canvas).get('leaf'), expected)
  })

  it('递归清除覆盖后恢复自然树，原树及其他叶字段不被修改', () => {
    const second = { x: 0.6, y: 0.7, w: 0.3, h: 0.2 }
    const tree: FlexContainer = { type: 'column', id: 'root', gap_pt: 0, children: [
      { type: 'block', id: 'top', block_id: 'a', rect_override: { ...absolute }, text_style: 'title' },
      { type: 'row', id: 'nested', gap_pt: 0, children: [
        { type: 'block', id: 'bottom', block_id: 'b', rect_override: second },
      ] },
    ] }
    const before = structuredClone(tree)
    const cleared = clearRectOverrides(tree)
    expect(tree).toEqual(before)
    expect(cleared).not.toBe(tree)
    expect(cleared.children[0]).not.toHaveProperty('rect_override')
    expect((cleared.children[1] as FlexContainer).children[0]).not.toHaveProperty('rect_override')
    expect(cleared.children[0]).toMatchObject({ block_id: 'a', text_style: 'title' })
    const placed = solve(cleared, customCanvas)
    const areas = solveNodeAreas(cleared, customCanvas)
    expectRect(placed[0]!.rect, { x: 0.1, y: 0.2, w: 0.8, h: 0.3 })
    expectRect(placed[1]!.rect, { x: 0.1, y: 0.5, w: 0.8, h: 0.3 })
    expectRect(areas.get('top'), placed[0]!.rect)
    expectRect(areas.get('bottom'), placed[1]!.rect)
    expectRect(solve(tree, customCanvas)[0]!.rect, absolute)
    expectRect(solve(tree, customCanvas)[1]!.rect, second)
  })
})
