/** 若依薄入口提供所有平台能力。不得向 React 传入认证令牌或 Provider 密钥。 */
export const DEFAULT_TEXT_FONT_FAMILY = '"Microsoft YaHei", "PingFang SC", "WenQuanYi Micro Hei", "Noto Sans CJK SC", sans-serif'

export type BusinessPage = 'create' | 'editor' | 'candidates' | 'marketing' | 'batch' | 'guide' | 'exports'

export type PageParams = { sessionId?: string; runId?: string }
export type HostRoute = { page: BusinessPage; params?: PageParams }
export type HostRequest = {
  url: string
  method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  data?: unknown
  params?: Record<string, unknown>
  responseType?: 'json' | 'blob'
  signal?: AbortSignal
  headers?: Record<string, string>
}
export type HostNotice = {
  message: string
  type: 'success' | 'error' | 'warning' | 'info'
  action?: { label: string; run: () => void }
}
export interface AiPictureHost {
  identity: { userId: string | number; displayName?: string }
  page: BusinessPage
  params?: PageParams
  /** TagsView 页面失活时停订阅与查询，激活后重新读取正式状态。 */
  active?: boolean
  /** 已在浏览器环境可用的字体 family；受控自定义字体须与 Worker FONT_PATH 配对。 */
  textFontFamily?: string
  hasPermission: (permission: string) => boolean
  /** 返回共享 request 的原始 AjaxResult / TableDataInfo，库内统一解包。 */
  request: <T = unknown>(request: HostRequest) => Promise<T>
  download: (blob: Blob, filename: string) => void | Promise<void>
  navigate: (route: HostRoute, options?: { replace?: boolean }) => void
  notify: (notice: HostNotice) => void
  /** 有认证的共享事件连接；可选，未提供时库使用有界快照轮询。 */
  subscribeRun?: (runId: string, onSnapshot: (snapshot: unknown) => void, onError: (error: unknown) => void) => (() => void)
}
export interface AiPictureMount {
  update: (host: AiPictureHost) => void
  unmount: () => void
}
