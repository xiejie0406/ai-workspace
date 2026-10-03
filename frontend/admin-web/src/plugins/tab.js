import useTagsViewStore from '@/store/modules/tagsView'
import router from '@/router'

export default {
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
    await useTagsViewStore().delView(router.currentRoute.value)
    if (obj !== undefined) {
      return router.push(obj)
    }
  },
  // 关闭指定tab页签
  async closePage(obj) {
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
    return useTagsViewStore().delAllViews()
  },
  // 关闭左侧tab页签
  async closeLeftPage(obj) {
    const target = obj || router.currentRoute.value
    return useTagsViewStore().delLeftTags(target)
  },
  // 关闭右侧tab页签
  async closeRightPage(obj) {
    const target = obj || router.currentRoute.value
    return useTagsViewStore().delRightTags(target)
  },
  // 关闭其他tab页签
  async closeOtherPage(obj) {
    const target = obj || router.currentRoute.value
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
