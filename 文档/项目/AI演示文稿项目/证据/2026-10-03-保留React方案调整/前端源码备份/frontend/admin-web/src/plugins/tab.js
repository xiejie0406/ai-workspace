import useTagsViewStore from '@/store/modules/tagsView'
import router from '@/router'

// 业务页面可在销毁缓存之前保护未保存操作，平台导航与页签仍由原store维护。
const closeGuards = new Map()
const viewPath = view => String(typeof view === 'string' ? view : view?.path || view?.fullPath || '').split(/[?#]/)[0]
export function addCloseGuard(path, guard) {
  const key = viewPath(path)
  if (!key || typeof guard !== 'function') throw new Error('页签关闭守卫参数无效')
  let guards = closeGuards.get(key)
  if (!guards) { guards = new Set(); closeGuards.set(key, guards) }
  guards.add(guard)
  return () => { guards.delete(guard); if (!guards.size) closeGuards.delete(key) }
}
async function allowClose(views) {
  const checked = new Set()
  for (const view of views) {
    for (const guard of [...(closeGuards.get(viewPath(view)) || [])]) {
      if (checked.has(guard)) continue
      checked.add(guard)
      try { if (await guard(view) === false) return false } catch { return false }
    }
  }
  return true
}
function cancelled() {
  const store = useTagsViewStore()
  return { cancelled: true, visitedViews: [...store.visitedViews], cachedViews: [...store.cachedViews] }
}

export default {
  addCloseGuard,
  // 刷新当前tab页签
  async refreshPage(obj) {
    const { path, query, matched } = router.currentRoute.value
    // 防止在重定向过程中重复刷新
    if (path.startsWith('/redirect/')) {
      return Promise.resolve()
    }
    if (obj === undefined) {
      matched.forEach((m) => {
        if (m.components && m.components.default && m.components.default.name) {
          if (!['Layout', 'ParentView'].includes(m.components.default.name)) {
            obj = { name: m.components.default.name, path: path, query: query }
          }
        }
      })
    }
    if (!await allowClose([obj || router.currentRoute.value])) return cancelled()
    return useTagsViewStore().delCachedView(obj).then(() => {
      const { path, query } = obj
      router.replace({
        path: '/redirect' + path,
        query: query
      })
    })
  },
  // 关闭当前tab页签，打开新页签
  async closeOpenPage(obj) {
    if (!await allowClose([router.currentRoute.value])) return cancelled()
    await useTagsViewStore().delView(router.currentRoute.value)
    if (obj !== undefined) {
      return router.push(obj)
    }
  },
  // 关闭指定tab页签
  async closePage(obj) {
    if (!await allowClose([obj || router.currentRoute.value])) return cancelled()
    if (obj === undefined) {
      return useTagsViewStore().delView(router.currentRoute.value).then(({ visitedViews }) => {
        const latestView = visitedViews.slice(-1)[0]
        if (latestView) {
          return router.push(latestView.fullPath)
        }
        return router.push('/')
      })
    }
    return useTagsViewStore().delView(obj)
  },
  // 关闭所有tab页签
  async closeAllPage() {
    if (!await allowClose(useTagsViewStore().visitedViews)) return cancelled()
    return useTagsViewStore().delAllViews()
  },
  // 关闭左侧tab页签
  async closeLeftPage(obj) {
    const target = obj || router.currentRoute.value
    const views = useTagsViewStore().visitedViews
    const index = views.findIndex(v => v.path === target.path)
    if (!await allowClose(views.filter((v, i) => index >= 0 && i < index && !v.meta?.affix))) return [...views]
    return useTagsViewStore().delLeftTags(target)
  },
  // 关闭右侧tab页签
  async closeRightPage(obj) {
    const target = obj || router.currentRoute.value
    const views = useTagsViewStore().visitedViews
    const index = views.findIndex(v => v.path === target.path)
    if (!await allowClose(views.filter((v, i) => index >= 0 && i > index && !v.meta?.affix))) return [...views]
    return useTagsViewStore().delRightTags(target)
  },
  // 关闭其他tab页签
  async closeOtherPage(obj) {
    const target = obj || router.currentRoute.value
    if (!await allowClose(useTagsViewStore().visitedViews.filter(v => v.path !== target.path && !v.meta?.affix))) return cancelled()
    return useTagsViewStore().delOthersViews(target)
  },
  // 打开tab页签
  openPage(title, url, params) {
    const obj = { path: url, meta: { title: title } }
    useTagsViewStore().addView(obj)
    return router.push({ path: url, query: params })
  },
  // 修改tab页签
  updatePage(obj) {
    return useTagsViewStore().updateVisitedView(obj)
  }
}
