import { describe, expect, it } from 'vitest'
import { platformLoginUrl } from '@/features/auth/token'
describe('原平台直接登录回 React 深链', () => {
  it('保留同源业务query/hash，只有redirect且不再经过桥页', () => {
    const value = '/aippt-web/projects/123?panel=export#slide3'
    const result = platformLoginUrl(value)
    expect(result).toBe('/login?redirect=' + encodeURIComponent(value))
    expect(result).not.toContain('aippt-entry'); expect(result).not.toContain('returnTo=')
  })
  it.each(['https://evil.example/aippt-web/projects', '//evil/aippt-web/projects', '/aippt-web/projects?token=private', '/aippt-web/projects?%61ccess_token=private', '/aippt-web/projects#access_token=private', '/aippt-web/projects#%73ecret=private', '/aippt-web/projects%5ca', '/aippt-web/projects?panel=%0a', '/aippt-web/%2e%2e/projects', '/aippt-web/projects?x=' + 'a'.repeat(4096)])('不复制凭据、外域或非法路径：%s', (value) => { expect(platformLoginUrl(value)).toBe('/login?redirect=%2Faippt-web%2Fprojects') })
})
