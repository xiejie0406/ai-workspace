import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChartView } from '@/render/ChartView'
import { getTheme } from '@/render/design'
import type { ChartBlock, Slot } from '@/render/types'

// 记录送入原 Recharts 的实际数据/图例契约；预期来自输入系列位置，不依赖组件的内部键名。
const received = vi.hoisted(() => ({
  data: [] as Array<Record<string, string | number>>,
  series: [] as Array<{ dataKey: string; name: string | undefined }>,
  pie: null as { dataKey: string; nameKey: string } | null,
}))
vi.mock('recharts', () => {
  const noPaint = () => null
  const plot = (props: Record<string, unknown>) => {
    received.data = props.data as Array<Record<string, string | number>>
    return <div>{props.children as ReactNode}</div>
  }
  const series = (props: Record<string, unknown>) => {
    received.series.push({ dataKey: props.dataKey as string, name: props.name as string | undefined })
    return null
  }
  return {
    BarChart: plot, LineChart: plot,
    PieChart: (props: Record<string, unknown>) => <div>{props.children as ReactNode}</div>,
    Bar: series, Line: series,
    Pie: (props: Record<string, unknown>) => {
      received.data = props.data as Array<Record<string, string | number>>
      received.pie = { dataKey: props.dataKey as string, nameKey: props.nameKey as string }
      return <div>{props.children as ReactNode}</div>
    },
    CartesianGrid: noPaint, Cell: noPaint, Legend: noPaint, XAxis: noPaint, YAxis: noPaint,
  }
})

class Observer {
  callback: ResizeObserverCallback
  constructor(callback: ResizeObserverCallback) { this.callback = callback }
  observe(element: Element) {
    this.callback([{ target: element, contentRect: { width: 960 } } as ResizeObserverEntry], this as unknown as ResizeObserver)
  }
  disconnect() {}
  unobserve() {}
}
const slot = { id: 'chart', rect: { x: 0, y: 0, w: 0.7, h: 0.5 }, accepts: ['chart'] } as Slot
const theme = getTheme('ivory')
const make = (kind: ChartBlock['chart_type'], names: string[]): ChartBlock => ({
  id: 'chart-input', slot_id: 'chart', type: 'chart', chart_type: kind,
  categories: ['东部', '西部'], unit: null,
  series: names.map((name, index) => ({ name, values: index === 0 ? [7, 11] : [23, 31] })),
})
let root: Root, box: HTMLDivElement
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  vi.stubGlobal('ResizeObserver', Observer)
  box = document.createElement('div'); document.body.append(box); root = createRoot(box)
})
afterEach(async () => { await act(async () => root.unmount()); box.remove(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const render = async (block: ChartBlock) => {
  received.data = []; received.series = []; received.pie = null
  await act(async () => root.render(<ChartView block={block} slot={slot} theme={theme} />))
}
const assertOriginalData = (block: ChartBlock) => {
  expect(received.data.map(row => row.name)).toEqual(block.categories)
  if (block.chart_type === 'pie') {
    expect(received.pie?.nameKey).toBe('name')
    expect(received.data.map(row => row[received.pie!.dataKey])).toEqual(block.series[0].values)
  } else {
    expect(received.series).toHaveLength(block.series.length)
    expect(new Set(received.series.map(item => item.dataKey)).size).toBe(block.series.length)
    for (const [index, item] of received.series.entries()) {
      expect(item.name ?? item.dataKey).toBe(block.series[index].name)
      expect(received.data.map(row => row[item.dataKey])).toEqual(block.series[index].values)
    }
  }
}
describe('合法图表名称与数值身份', () => {
  for (const kind of ['bar', 'column', 'line', 'pie'] as const) {
    it(kind + '：名为name的系列保留类别和原值', async () => {
      const input = make(kind, ['name', '其他组'])
      await render(input); assertOriginalData(input)
    })
    it(kind + '：同名系列不覆盖原值或图例', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
      const input = make(kind, ['同名', '同名'])
      await render(input); assertOriginalData(input)
      expect(spy.mock.calls.some(call => String(call[0]).includes('same key'))).toBe(false)
    })
  }
  it('重新排序/改名后按当前系列位置映射，原输入不被修改', async () => {
    const original = make('column', ['系列一', '系列二'])
    const saved = JSON.stringify(original)
    await render(original); assertOriginalData(original)
    const renamed = { ...original, series: [{ ...original.series[1], name: '' }, { ...original.series[0], name: 'name' }] }
    await render(renamed); assertOriginalData(renamed)
    expect(JSON.stringify(original)).toBe(saved)
  })
})
