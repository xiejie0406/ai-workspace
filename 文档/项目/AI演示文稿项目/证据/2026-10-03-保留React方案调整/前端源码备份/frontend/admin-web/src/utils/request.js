import axios from 'axios'
import { ElNotification , ElMessageBox, ElMessage, ElLoading } from 'element-plus'
import { getToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { tansParams, blobValidate } from '@/utils/ruoyi'
import cache from '@/plugins/cache'
import { saveAs } from 'file-saver'
import useUserStore from '@/store/modules/user'
import { rejectBusinessError } from '@/utils/structuredBusinessError'

let downloadLoadingInstance
// 是否显示重新登录
export let isRelogin = { show: false }

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
// 创建axios实例
const service = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: import.meta.env.VITE_APP_BASE_API,
  // 超时
  timeout: 10000
})

// request拦截器
service.interceptors.request.use(config => {
  // 持续流显式复用同一请求实例、Token 和取消机制，普通请求行为保持原样。
  if (config.aipptStream === true) {
    if (config.method !== 'get' || !/^\/aippt\//.test(config.url || '')) {
      throw new Error('演示文稿进度流只允许同源业务 GET 请求')
    }
    config.adapter = 'fetch'
    config.responseType = 'stream'
    config.timeout = 0
    config.headers.Accept = 'text/event-stream'
    config.preserveBusinessError = true
  }
  // 是否需要设置 token
  const isToken = (config.headers || {}).isToken === false
  // 是否需要防止数据重复提交
  const isRepeatSubmit = (config.headers || {}).repeatSubmit === false
  // 密钥写入请求正文不可进入通用重复提交 sessionStorage 缓存。
  const isManagedSecretRequest = /^\/(system\/platformSecret|ai\/(secret|role))(\/|$)/.test(config.url || '')
  // 间隔时间(ms)，小于此时间视为重复提交
  const interval = (config.headers || {}).interval || 1000
  if (getToken() && !isToken) {
    config.headers['Authorization'] = 'Bearer ' + getToken() // 让每个请求携带自定义token 请根据实际情况自行修改
  }
  // get请求映射params参数
  if (config.method === 'get' && config.params) {
    let url = config.url + '?' + tansParams(config.params)
    url = url.slice(0, -1)
    config.params = {}
    config.url = url
  }
  if (!isRepeatSubmit && !isManagedSecretRequest && (config.method === 'post' || config.method === 'put')) {
    const requestObj = {
      url: config.url,
      data: typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
      time: new Date().getTime()
    }
    const requestSize = Object.keys(JSON.stringify(requestObj)).length // 请求数据大小
    const limitSize = 5 * 1024 * 1024 // 限制存放数据5M
    if (requestSize >= limitSize) {
      console.warn(`[${config.url}]: ` + '请求数据大小超出允许的5M限制，无法进行防重复提交验证。')
      return config
    }
    const sessionObj = cache.session.getJSON('sessionObj')
    if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
      cache.session.setJSON('sessionObj', requestObj)
    } else {
      const s_url = sessionObj.url                // 请求地址
      const s_data = sessionObj.data              // 请求数据
      const s_time = sessionObj.time              // 请求时间
      if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
        const message = '数据正在处理，请勿重复提交'
        console.warn(`[${s_url}]: ` + message)
        return Promise.reject(new Error(message))
      } else {
        cache.session.setJSON('sessionObj', requestObj)
      }
    }
  }
  return config
}, error => {
    console.log(error)
    Promise.reject(error)
})

// 响应拦截器
service.interceptors.response.use(async res => {
    if (res.config.aipptStream === true) {
      if (String(res.headers['content-type'] || '').includes('text/event-stream')) {
        return res.data
      }
      // 会话到期可能以 HTTP 200 的若依 JSON 信封返回，不能当作正常事件流。
      res.data = await readStreamError(res.data)
      if (!res.data.code || res.data.code === 200) {
        res.data = { code: 502, msg: '进度接口未返回事件流，请查询最新状态' }
      }
    }
    if (res.config.responseType === 'blob') {
      // 私有资产的 HTTP 200 会话/权限错误仍须走平台处理，不能当图片或文件保存。
      if ((res.config.aipptBinary === true || res.config.preserveBusinessError === true) && String(res.headers['content-type'] || '').includes('application/json')) {
        const body = res.data?.size > 65536
          ? { code: 502, msg: '文件接口错误响应超出限制' }
          : await readStreamError(res.data?.stream ? res.data.stream() : res.data)
        if (body.code && body.code !== 200) res.data = body
        else return res.data
      } else return res.data
    }
    // 未设置状态码则默认成功状态
    const code = res.data.code || 200
    // 获取错误信息
    const msg = errorCode[code] || res.data.msg || errorCode['default']
    // 二进制数据则直接返回
    if (res.config.responseType === 'arraybuffer') {
      return res.data
    }
    if (code === 401) {
      offerRelogin()
      return rejectBusinessError(res, msg, '无效的会话，或者会话已过期，请重新登录。')
    } else if (code === 500) {
      ElMessage({ message: msg, type: 'error' })
      return rejectBusinessError(res, msg, new Error(msg))
    } else if (code === 601) {
      ElMessage({ message: msg, type: 'warning' })
      return rejectBusinessError(res, msg, new Error(msg))
    } else if (code !== 200) {
      ElNotification.error({ title: msg })
      return rejectBusinessError(res, msg, 'error')
    } else {
      return  Promise.resolve(res.data)
    }
  },
  async error => {
    if (error.config?.aipptStream === true && error.response) {
      error.response.data = await readStreamError(error.response.data)
      if (error.response.status === 401 || error.response.data.code === 401) {
        offerRelogin()
      }
    }
    // 声明保留业务错误的文件接口也要回到既有认证和错误处理，不能保存 JSON 错误为图片。
    if (error.response && error.config?.responseType === 'blob'
        && (error.config.aipptBinary === true || error.config.preserveBusinessError === true)
        && String(error.response.headers?.['content-type'] || '').includes('application/json')) {
      const data = error.response.data
      error.response.data = data?.size > 65536
        ? { code: 502, msg: '文件接口错误响应超出限制' }
        : await readStreamError(data?.stream ? data.stream() : data)
      if (error.response.status === 401 || error.response.data.code === 401) offerRelogin()
    }
    // 主动离页取消订阅不属于连接故障。
    if ((error.config?.aipptStream === true || error.config?.preserveBusinessError === true) && axios.isCancel(error)) return Promise.reject(error)
    console.log('err' + error)
    let { message } = error
    if (message == "Network Error") {
      message = "后端接口连接异常"
    } else if (message.includes("timeout")) {
      message = "系统接口请求超时"
    } else if (message.includes("Request failed with status code")) {
      message = "系统接口" + message.slice(-3) + "异常"
    }
    ElMessage({ message: message, type: 'error', duration: 5 * 1000 })
    return Promise.reject(error)
  }
)

function offerRelogin() {
  if (isRelogin.show) return
  isRelogin.show = true
  ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', {
    confirmButtonText: '重新登录', cancelButtonText: '取消', type: 'warning'
  }).then(() => {
    isRelogin.show = false
    useUserStore().logOut().then(() => { location.href = '/index' })
  }).catch(() => { isRelogin.show = false })
}

async function readStreamError(data) {
  if (!data?.getReader) return data || {}
  const reader = data.getReader()
  const decoder = new TextDecoder()
  let text = ''
  let bytes = 0
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      bytes += value.byteLength
      if (bytes > 65536) throw new Error('进度接口错误响应超出限制')
      text += decoder.decode(value, { stream: true })
    }
    text += decoder.decode()
    return text ? JSON.parse(text) : {}
  } catch {
    return { code: 502, msg: '进度接口返回了无法读取的错误信息' }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

// 通用下载方法
export function download(url, params, filename, config) {
  downloadLoadingInstance = ElLoading.service({ text: "正在下载数据，请稍候", background: "rgba(0, 0, 0, 0.7)", })
  return service.post(url, params, {
    transformRequest: [(params) => { return tansParams(params) }],
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    responseType: 'blob',
    ...config
  }).then(async (data) => {
    const isBlob = blobValidate(data)
    if (isBlob) {
      const blob = new Blob([data])
      saveAs(blob, filename)
    } else {
      const resText = await data.text()
      const rspObj = JSON.parse(resText)
      const errMsg = errorCode[rspObj.code] || rspObj.msg || errorCode['default']
      ElMessage.error(errMsg)
    }
    downloadLoadingInstance.close()
  }).catch((r) => {
    console.error(r)
    ElMessage.error('下载文件出现错误，请联系管理员！')
    downloadLoadingInstance.close()
  })
}

export default service
