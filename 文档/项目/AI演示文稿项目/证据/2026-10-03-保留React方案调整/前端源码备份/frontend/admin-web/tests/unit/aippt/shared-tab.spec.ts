import { beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({
  route: { path: '/aippt/editor/p1', fullPath: '/aippt/editor/p1', query: {}, matched: [] },
  store: {
    visitedViews: [{ path: '/first', meta: {} }, { path: '/aippt/editor/p1', meta: {} }, { path: '/last', meta: {} }],
    cachedViews: ['Editor'], delView: vi.fn(), delCachedView: vi.fn(), delAllViews: vi.fn(),
    delLeftTags: vi.fn(), delRightTags: vi.fn(), delOthersViews: vi.fn(), addView: vi.fn(), updateVisitedView: vi.fn()
  },
  push: vi.fn(), replace: vi.fn()
}))
vi.mock('@/store/modules/tagsView', () => ({ default: () => state.store }))
vi.mock('@/router', () => ({ default: { currentRoute: { value: state.route }, push: state.push, replace: state.replace } }))
import tab, { addCloseGuard } from '@/plugins/tab'

const cleanup: (() => void)[] = []
beforeEach(() => {
  cleanup.splice(0).forEach(fn => fn())
  vi.clearAllMocks()
  state.store.delView.mockResolvedValue({ visitedViews: state.store.visitedViews, cachedViews: [] })
  state.store.delCachedView.mockResolvedValue([])
  state.store.delAllViews.mockResolvedValue({ visitedViews: [] })
  state.store.delLeftTags.mockResolvedValue([])
  state.store.delRightTags.mockResolvedValue([])
  state.store.delOthersViews.mockResolvedValue({ visitedViews: [] })
})

describe('平台页签关闭守卫', () => {
  it('未注册守卫的旧页面仍调用既有store', async () => {
    await tab.closePage({ path: '/first' })
    expect(state.store.delView).toHaveBeenCalledWith({ path: '/first' })
  })
  it('拒绝关闭不清除缓存或导航', async () => {
    cleanup.push(addCloseGuard(state.route.path, async () => false))
    expect(await tab.closePage()).toMatchObject({ cancelled: true })
    expect(state.store.delView).not.toHaveBeenCalled()
    expect(state.push).not.toHaveBeenCalled()
  })
  it('必须等待保存完成后再删除页签', async () => {
    let finish!: (value: boolean) => void
    cleanup.push(addCloseGuard(state.route.path, () => new Promise<boolean>(resolve => { finish = resolve })))
    const closing = tab.closePage(state.route)
    await Promise.resolve()
    expect(state.store.delView).not.toHaveBeenCalled()
    finish(true)
    await closing
    expect(state.store.delView).toHaveBeenCalledOnce()
  })
  it('刷新也在delCachedView之前保护草稿', async () => {
    cleanup.push(addCloseGuard(state.route.path, () => false))
    expect(await tab.refreshPage({ path: state.route.path, query: {}, name: 'Editor' })).toMatchObject({ cancelled: true })
    expect(state.store.delCachedView).not.toHaveBeenCalled()
    expect(state.replace).not.toHaveBeenCalled()
  })
  it('批量关闭有任一失败则不作部分清除', async () => {
    cleanup.push(addCloseGuard('/first', () => true))
    cleanup.push(addCloseGuard('/last', () => false))
    await tab.closeAllPage()
    expect(state.store.delAllViews).not.toHaveBeenCalled()
  })
  it('只保护实际将关闭的左右页签', async () => {
    const protect = vi.fn(() => false)
    cleanup.push(addCloseGuard('/last', protect))
    await tab.closeLeftPage(state.route)
    expect(protect).not.toHaveBeenCalled()
    expect(state.store.delLeftTags).toHaveBeenCalledOnce()
    await tab.closeRightPage(state.route)
    expect(protect).toHaveBeenCalledOnce()
    expect(state.store.delRightTags).not.toHaveBeenCalled()
  })
  it('守卫清理后不留下跨项目回调', async () => {
    const guard = vi.fn(() => false)
    addCloseGuard(state.route.path + '?projectId=p1', guard)()
    await tab.closePage(state.route)
    expect(guard).not.toHaveBeenCalled()
    expect(state.store.delView).toHaveBeenCalledOnce()
  })
  it('守卫异常安全保留页签', async () => {
    cleanup.push(addCloseGuard(state.route.path, () => { throw new Error('保存失败') }))
    expect(await tab.closeOpenPage({ path: '/first' })).toMatchObject({ cancelled: true })
    expect(state.store.delView).not.toHaveBeenCalled()
    expect(state.push).not.toHaveBeenCalled()
  })
})
