/** 正式契约只包含 JSON 数据；深拷贝同时剥离 Vue 响应式代理。 */
export function cloneJson<T>(value:T):T { return JSON.parse(JSON.stringify(value)) as T }
