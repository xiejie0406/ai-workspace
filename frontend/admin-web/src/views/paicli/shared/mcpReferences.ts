import type { PcId } from '@/types/paicli'

const reservedEnv = new Set(['RUNTIME_API_KEY','RUNNER_TOKEN','USER_TOKEN','PATH','PATHEXT','COMSPEC','SHELL','SYSTEMROOT','WINDIR','HOME','USERPROFILE','APPDATA','LOCALAPPDATA','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','LD_PRELOAD','LD_LIBRARY_PATH','HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','NO_PROXY'])
const reservedHeaders = new Set(['host','content-length','connection','transfer-encoding','proxy-authorization','upgrade','trailer'])

/** 只解析固定工作区的密钥库引用；原值不进入表单/API保存载荷。 */
export function parseMcpReferences(text: string, workspaceId: PcId, transport: 'STDIO'|'HTTP'): Record<string,string> {
  let parsed: unknown
  try { parsed = JSON.parse(text || '{}') } catch { throw new Error('凭证引用 JSON 格式不正确；请填写对象映射') }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('凭证引用必须是 JSON 对象')
  const entries = Object.entries(parsed)
  if (entries.length > 32) throw new Error('单个 MCP 凭证引用映射最多 32 项')
  if (!/^[1-9]\d*$/.test(workspaceId)) throw new Error('请先选择当前工作区')
  const names = new Set<string>(), result: Record<string,string> = {}
  for (const [name,value] of entries) {
    const upper = name.toUpperCase(), lower = name.toLowerCase()
    if (names.has(lower)) throw new Error('凭证目标名称不能以大小写重复')
    names.add(lower)
    const validName = transport === 'STDIO'
      ? /^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(name) && !upper.startsWith('PAICLI_') && !upper.startsWith('DYLD_') && !reservedEnv.has(upper)
      : /^[!#$%&'*+.^_`|~A-Za-z0-9-]{1,128}$/.test(name) && !reservedHeaders.has(lower)
    if (!validName) throw new Error('凭证目标名称非法或覆盖受保护的进程/HTTP 字段')
    if (typeof value !== 'string' || value.length > 128 || !new RegExp(`^ai\\.paicli\\.${workspaceId}\\.[A-Za-z0-9._-]+$`).test(value)) throw new Error('只能填写当前工作区 ai.paicli.<工作区 ID>.* 密钥库引用，不能填写凭证原值')
    if (['__proto__','constructor','prototype'].includes(name)) throw new Error('凭证目标名称不受支持')
    result[name] = value
  }
  return result
}

export function parseMcpPublicHeaders(text: string): Record<string,string> {
  let parsed: unknown
  try { parsed = JSON.parse(text || '{}') } catch { throw new Error('普通协议头 JSON 格式不正确；请填写对象映射') }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('普通协议头必须是 JSON 对象')
  const names = new Set<string>(), result: Record<string,string> = {}
  for (const [name,value] of Object.entries(parsed)) {
    const lower = name.toLowerCase()
    if (names.has(lower) || !['accept','content-type','mcp-protocol-version'].includes(lower)) throw new Error('普通头仅允许 Accept、Content-Type、MCP-Protocol-Version 且不能大小写重复')
    names.add(lower)
    if (typeof value !== 'string' || value.length > 256 || !(lower === 'mcp-protocol-version' ? /^\d{4}-\d{2}-\d{2}$/.test(value) : /^(?:application\/json(?:; ?charset=utf-8)?|text\/event-stream|\*\/\*)(?:, ?(?:application\/json|text\/event-stream))*$/i.test(value))) throw new Error('普通头只能填写实际协议格式；认证头须使用密钥库引用')
    result[name] = value
  }
  return result
}
