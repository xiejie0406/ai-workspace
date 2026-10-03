import type {AxiosRequestConfig} from 'axios'
/** 共享 request 已实现的可选错误保留字段；局部适配不改变全局 Axios 类型或请求实现。 */
export const businessErrorResponse:AxiosRequestConfig & {preserveBusinessError:true}={preserveBusinessError:true}
