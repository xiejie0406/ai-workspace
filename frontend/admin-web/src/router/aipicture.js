import Layout from '@/layout'

// 若依 filterDynamicRoutes 只筛选顶层，因此每类隐藏详情分别声明权限。
export const aiPictureDetailRoutes = [
  {
    path: '/aipicture-detail/candidates', component: Layout, hidden: true,
    permissions: ['aipicture:image:generate'],
    children: [{
      path: ':runId', component: () => import('@/views/aipicture/candidates/index.vue'),
      name: 'AiPictureCandidates', meta: { title: '图片候选', activeMenu: '/aipicture/create', noCache: true }
    }]
  },
  {
    path: '/aipicture-detail/editor', component: Layout, hidden: true,
    permissions: ['aipicture:session:query'],
    children: [{
      path: ':sessionId', component: () => import('@/views/aipicture/editor/index.vue'),
      name: 'AiPictureSession', meta: { title: '图片会话', activeMenu: '/aipicture/editor', noCache: true }
    }]
  },
  {
    path: '/aipicture-detail/batch', component: Layout, hidden: true,
    permissions: ['aipicture:batch:list'],
    children: [{
      path: ':runId', component: () => import('@/views/aipicture/batch/index.vue'),
      name: 'AiPictureBatchDetail', meta: { title: '批量任务详情', activeMenu: '/aipicture/batch', noCache: true }
    }]
  },
  {
    path: '/aipicture-detail/exports', component: Layout, hidden: true,
    permissions: ['aipicture:export:download'],
    children: [{
      path: ':sessionId', component: () => import('@/views/aipicture/exports/index.vue'),
      name: 'AiPictureExports', meta: { title: '图片物料导出', activeMenu: '/aipicture/marketing', noCache: true }
    }]
  },
  {
    path: '/aipicture-detail/guide', component: Layout, hidden: true,
    permissions: ['aipicture:image:generate', 'aipicture:asset:upload'],
    children: [{
      path: '', component: () => import('@/views/aipicture/guide/index.vue'),
      name: 'AiPictureGuide', meta: { title: '图片创作指南', activeMenu: '/aipicture/create', noCache: true }
    }]
  }
]
