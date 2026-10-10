import { describe, expect, it } from 'vitest'
import { parseMcpReferences, parseMcpPublicHeaders } from '../shared/mcpReferences'

describe('MCP 密钥库引用', () => {
  it('保存实际当前用户引用并支持空映射', () => {
    expect(parseMcpReferences('{"MCP_ACCESS_TOKEN":"ai.paicli.user.7.mcp"}','7','STDIO')).toEqual({ MCP_ACCESS_TOKEN:'ai.paicli.user.7.mcp' })
    expect(parseMcpReferences('{"Authorization":"ai.paicli.user.7.mcp"}','7','HTTP')).toEqual({ Authorization:'ai.paicli.user.7.mcp' })
    expect(parseMcpReferences('{}','7','HTTP')).toEqual({})
  })
  it('拒绝凭证原值、跨用户与错误对象形状', () => {
    for (const text of ['{"Authorization":"Bearer real-value"}','{"Authorization":"ai.paicli.user.8.mcp"}','[]','null','{"Authorization":123}']) expect(() => parseMcpReferences(text,'7','HTTP')).toThrow()
    try { parseMcpReferences('opaque-original-credential','7','HTTP') } catch (error) { expect(String(error)).not.toContain('opaque-original-credential') }
  })
  it('拒绝受保护环境、传输头、大小写重复与超限映射', () => {
    for (const name of ['PATH','PAICLI_RUNNER_TOKEN','JAVA_TOOL_OPTIONS','DYLD_INSERT_LIBRARIES']) expect(() => parseMcpReferences(JSON.stringify({ [name]:'ai.paicli.user.7.mcp' }),'7','STDIO')).toThrow()
    expect(() => parseMcpReferences('{"Host":"ai.paicli.user.7.mcp"}','7','HTTP')).toThrow()
    expect(() => parseMcpReferences('{"Authorization":"ai.paicli.user.7.mcp","authorization":"ai.paicli.user.7.mcp"}','7','HTTP')).toThrow()
    const refs = Object.fromEntries(Array.from({ length:33 },(_,i) => [`MCP_TOKEN_${i}`,'ai.paicli.user.7.mcp']))
    expect(() => parseMcpReferences(JSON.stringify(refs),'7','STDIO')).toThrow()
  })
  it('普通头只接受真实协议值并拒绝认证或隐含原值', () => {
    expect(parseMcpPublicHeaders('{"Accept":"application/json, text/event-stream","MCP-Protocol-Version":"2025-03-26"}')).toEqual({ Accept:'application/json, text/event-stream','MCP-Protocol-Version':'2025-03-26' })
    for (const text of ['{"Authorization":"opaque-secret"}','{"X-Api-Key":"opaque-secret"}','{"Accept":"opaque-secret"}','{"Accept":"application/json","accept":"text/event-stream"}','{"Content-Type":"application/json\\r\\nX-Token: hidden"}']) expect(() => parseMcpPublicHeaders(text)).toThrow()
  })
})
