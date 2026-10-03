import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import * as reactReturn from '../../src/utils/aipptEntry.js'

const projectPath = '/aippt-web/projects/1a33de39-37f9-4482-ae6b-41a4c6c9c22e?panel=export#slide-3'

async function executeLogin(query, reject = false) {
  const source = await readFile(new URL('../../src/views/login.vue', import.meta.url), 'utf8')
  const start = source.indexOf('function handleLogin() {')
  const end = source.indexOf('\nfunction getCode()', start)
  assert.ok(start > 0 && end > start, '执行实际 login.vue 成功/失败分支')
  const result = { routed: [], replaced: [], loginCalls: 0, loading: { value: false } }
  const context = {
    proxy: { $refs: { loginRef: { validate: (callback) => callback(true) } } },
    loginForm: { value: { rememberMe: false } },
    loading: result.loading,
    Cookies: { remove() {}, set() {} },
    userStore: { login: async () => { result.loginCalls++; if (reject) throw new Error('fixture login rejected') } },
    route: { query },
    redirect: { value: query.redirect },
    router: { push: (location) => result.routed.push(JSON.parse(JSON.stringify(location))) },
    window: { location: { replace: (path) => result.replaced.push(path) } },
    captchaEnabled: { value: false },
    resolveAipptLoginReturnTo: reactReturn.resolveAipptLoginReturnTo,
  }
  vm.runInNewContext(source.slice(start, end) + '\nhandleLogin()', context)
  await new Promise((resolve) => setImmediate(resolve))
  return result
}

test('React 深链保留项目、查询和定位，工具不承担页面权限', () => {
  assert.equal(reactReturn.normalizeAipptReturnTo(projectPath), projectPath)
  assert.equal('aipptEntryPermission' in reactReturn, false)
})

test('拒绝外站、路径逃逸、反斜线、控制字符和 URL 凭证', () => {
  for (const value of [
    undefined, null, ['https://evil.example'], 'https://evil.example/aippt-web/projects',
    '//evil.example/aippt-web/projects', '/aippt-web/../../login', '/aippt-web/%2e%2e/login',
    '/aippt-web/\\evil.example', '/aippt-web/%5cevil.example', '/aippt-web/%2f%2fevil.example',
    '/aippt-web/%252e%252e/login', '/aippt-web/projects\n', '/aippt-web/projects%0a',
    '/aippt-web/projects?token=secret', '/aippt-web/projects?%61ccess_token=secret',
    '/aippt-web/projects#refresh_token=secret', '/aippt-web/projects?secret=secret',
    '/aippt-web/projects?bad=%zz', '/aippt-web/' + 'x'.repeat(4096),
  ]) assert.equal(reactReturn.normalizeAipptReturnTo(value), '/aippt-web/projects', String(value))
})

test('登录回跳合并非凭证参数，保留原深链 hash 与重复参数', () => {
  const path = reactReturn.resolveAipptLoginReturnTo(projectPath, { panel: 'quality', tag: ['a', 'b'], blank: null })
  const parsed = new URL(path, 'https://fixture.invalid')
  assert.equal(parsed.pathname, '/aippt-web/projects/1a33de39-37f9-4482-ae6b-41a4c6c9c22e')
  assert.equal(parsed.searchParams.get('panel'), 'quality')
  assert.deepEqual(parsed.searchParams.getAll('tag'), ['a', 'b'])
  assert.equal(parsed.searchParams.get('blank'), '')
  assert.equal(parsed.hash, '#slide-3')
  assert.equal(reactReturn.resolveAipptLoginReturnTo('/system/user', {}), null)
  assert.equal(reactReturn.resolveAipptLoginReturnTo('https://evil.example/aippt-web/projects', {}), null)
  assert.equal(reactReturn.resolveAipptLoginReturnTo('/aippt-web/create', { token: 'fixture' }), '/aippt-web/projects')
  assert.equal(reactReturn.resolveAipptLoginReturnTo('/aippt-web/create', { bad: { nested: 'value' } }), '/aippt-web/projects')
})

test('实际登录成功分支直接整页返回 React，不进入 VueRouter 或薄页', async () => {
  const result = await executeLogin({ redirect: projectPath, from: 'login' })
  assert.equal(result.loginCalls, 1)
  assert.deepEqual(result.routed, [])
  assert.deepEqual(result.replaced, [projectPath.replace('?panel=export', '?panel=export&from=login')])
  const create = await executeLogin({ redirect: '/aippt-web/create/' })
  assert.deepEqual(create.replaced, ['/aippt-web/create/'])
})

test('其他实际登录导航保持原 path/query 行为，失败不回跳', async () => {
  for (const redirect of [undefined, '/system/user', '/index']) {
    const result = await executeLogin({ redirect, page: '2' })
    assert.deepEqual(result.replaced, [])
    assert.deepEqual(result.routed, [{ path: redirect || '/', query: { page: '2' } }])
  }
  const failed = await executeLogin({ redirect: projectPath }, true)
  assert.deepEqual(failed.replaced, [])
  assert.deepEqual(failed.routed, [])
  assert.equal(failed.loading.value, false)
})

test('外链菜单候选须显式核定同源地址，不依赖薄页或改角色授权', async () => {
  const sql = await readFile(new URL('../../../../ruoyi-backend/sql/ruoyi-aippt-menu.sql', import.meta.url), 'utf8')
  assert.match(sql, /SET @aippt_public_origin\s*=\s*NULL;/)
  assert.match(sql, /CONCAT\(@aippt_public_origin, '\/aippt-web\/projects'\)/)
  assert.match(sql, /CONCAT\(@aippt_public_origin, '\/aippt-web\/create'\)/)
  assert.match(sql, /is_frame='0'/)
  assert.doesNotMatch(sql, /SET component='aippt-entry\/index'/)
  assert.doesNotMatch(sql, /(?:INSERT INTO|UPDATE|DELETE FROM) sys_role_menu/i)
  assert.doesNotMatch(sql, /https?:\/\/[^\s'";]+:8080/)
  const actions = sql.split(/\r?\n/).filter(line => line.startsWith('INSERT INTO sys_menu') && line.includes(",'F',"))
  assert.equal(actions.length, 24)
  assert.equal(new Set(actions.flatMap(line => [...line.matchAll(/'(aippt:[a-z]+:[a-z]+)'/g)].map(item => item[1]))).size, 24)
  const mutations = sql.split(';').filter(statement => /\b(?:INSERT INTO|UPDATE) sys_menu\b/.test(statement))
  assert.equal(mutations.length, 29)
  for (const mutation of mutations) assert.match(mutation, /WHERE @aippt_origin_valid=1/)
})
