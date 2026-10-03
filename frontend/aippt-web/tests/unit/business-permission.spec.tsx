import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { Filmstrip } from '@/features/deck/Filmstrip'
import type { DeckSlide } from '@/features/deck/types'
import { getTheme } from '@/render/design'

const retry = vi.hoisted(() => vi.fn())
vi.mock('@/features/deck/api', () => ({ useRetrySlide: () => ({ isPending: false, mutate: retry }) }))
vi.mock('@/render/SlideView', () => ({ SlideView: () => <div>画布</div> }))
let root: Root, container: HTMLDivElement
const slide = { id: 's', project_id: 'p', status: 'failed', blocks: [], issues: [], revision: 3, sort_order: 0, title: '失败页', layout_id: 'bullets', layout_mode: 'fixed', speaker_notes: '' } as unknown as DeckSlide
beforeEach(() => { Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); container = document.createElement('div'); document.body.append(container); root = createRoot(container); retry.mockReset() })
afterEach(async () => { await act(async () => root.unmount()); container.remove() })
async function render(permissions: string[], locked: boolean, generating = false) {
  useAuthStore.setState({ permissions })
  await act(async () => root.render(<Filmstrip projectId="p" slides={[slide]} theme={getTheme('ivory')} activeId="s" locked={locked} generating={generating} busy={false} onSelect={() => {}} onReorder={() => {}} onInsert={() => {}} onDuplicate={() => {}} onDelete={() => {}} />))
  return [...container.querySelectorAll('button')].find((node) => node.textContent === '重试这一页')!
}
describe('缩略图重试与页面编辑分别授权', () => {
  it('只有deck:generate无slide:edit仍可重试失败页，不开放页面编辑菜单', async () => {
    const button = await render(['aippt:deck:generate'], true)
    expect(button.disabled).toBe(false)
    expect(container.querySelector('[aria-label="页面操作"]')).toBeNull()
    await act(async () => button.click())
    expect(retry).toHaveBeenCalledWith('s')
  })
  it('只有slide:edit可操作页面但不能发生成重试', async () => {
    const button = await render(['aippt:slide:edit'], false)
    expect(button.disabled).toBe(true)
    expect(container.querySelector('[aria-label="页面操作"]')).not.toBeNull()
    await act(async () => button.click())
    expect(retry).not.toHaveBeenCalled()
  })
  it('整份生成期间即使有生成权限也禁用重试', async () => {
    const button = await render(['aippt:deck:generate'], true, true)
    expect(button.disabled).toBe(true)
  })
})
