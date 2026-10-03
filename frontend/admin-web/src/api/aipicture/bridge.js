import request from '@/utils/request'
import auth from '@/plugins/auth'
import { ElMessage } from 'element-plus'
import { saveAs } from 'file-saver'
import { h } from 'vue'
import { resolveAiPictureRoute } from './routes'

/** 只桥接若依已有设施，不向业务组件传递 token、Cookie 或 Provider 密钥。 */
export function createAiPictureHost({ user, router, page, params = {}, active = true, textFontFamily = import.meta.env.VITE_AIPICTURE_TEXT_FONT_FAMILY }) {
  // 若依退出清 token 而保留 id；只映射已有登录状态，不把凭据传入业务库。
  const authenticated = Boolean(user.token && user.id)
  return {
    identity: { userId: authenticated ? user.id : 0, displayName: authenticated ? user.nickName || user.name : undefined },
    page,
    params,
    active: active && authenticated,
    textFontFamily: typeof textFontFamily === 'string' ? textFontFamily.trim() || undefined : undefined,
    hasPermission: permission => auth.hasPermi(permission),
    request: options => {
      if (!/^\/aipicture(?:\/|$)/.test(options.url)) {
        return Promise.reject(new Error('图片业务请求超出授权接口范围'))
      }
      return request({
        url: options.url,
        method: options.method.toLowerCase(),
        data: options.data,
        params: options.params,
        responseType: options.responseType,
        signal: options.signal,
        preserveBusinessError: true,
        timeout: 30000,
        // 沿用平台 multipart 方式，让浏览器生成 boundary；文件不能进入 JSON 防重缓存。
        headers: {
          ...options.headers,
          ...(options.data instanceof FormData ? { 'Content-Type': undefined, repeatSubmit: false } : {})
        }
      })
    },
    download: (blob, filename) => saveAs(blob, filename),
    navigate: (route, options = {}) => {
      const target = resolveAiPictureRoute(route)
      return options.replace ? router.replace(target) : router.push(target)
    },
    notify: notice => {
      let message
      const content = notice.action ? h('span', [
        notice.message,
        h('button', { type: 'button', class: 'el-button el-button--primary is-link', onClick: () => { message?.close(); notice.action.run() } }, notice.action.label)
      ]) : notice.message
      message = ElMessage({ message: content, type: notice.type, showClose: true })
    }
    // 不创建原生 EventSource。React 缺省通过此 request 有界轮询任务快照。
  }
}

/** 固定同源构建产物入口；动态导入不会接收服务器给出的 URL。 */
export function loadAiPictureWorkbench() {
  const base = import.meta.env.BASE_URL || '/'
  return import(/* @vite-ignore */ `${base}aipicture/aipicture-workbench.js`)
}
