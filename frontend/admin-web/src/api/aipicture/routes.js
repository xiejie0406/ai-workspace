const pages = new Set(['create', 'editor', 'candidates', 'marketing', 'batch', 'guide', 'exports'])

/** 业务导航回到若依 Router；隐藏详情沿用 Layout、权限过滤和 activeMenu。 */
export function resolveAiPictureRoute({ page, params = {} }) {
  if (!pages.has(page)) throw new Error('未知图片业务页面')
  if (page === 'candidates') {
    if (!params.runId) throw new Error('候选详情缺少任务编号')
    return { path: `/aipicture-detail/candidates/${encodeURIComponent(params.runId)}` }
  }
  if (page === 'editor' && params.sessionId) {
    return { path: `/aipicture-detail/editor/${encodeURIComponent(params.sessionId)}` }
  }
  if (page === 'exports') {
    if (!params.sessionId) throw new Error('导出清单缺少会话编号')
    return { path: `/aipicture-detail/exports/${encodeURIComponent(params.sessionId)}` }
  }
  if (page === 'guide') return { path: '/aipicture-detail/guide' }
  if (page === 'batch' && params.runId) {
    return { path: `/aipicture-detail/batch/${encodeURIComponent(params.runId)}` }
  }
  const query = params.sessionId ? { sessionId: params.sessionId } : {}
  return { path: `/aipicture/${page}`, query }
}
