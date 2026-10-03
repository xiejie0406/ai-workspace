import assert from 'node:assert/strict'
import test from 'node:test'
import { createMemoryHistory, createRouter } from 'vue-router'
import { aipptEntryPermission, normalizeAipptReturnTo } from '../../src/utils/aipptEntry.js'

test('平台登录后保留 React 深链及非凭证参数', () => {
  const destination = '/aippt-web/projects/1a33de39-37f9-4482-ae6b-41a4c6c9c22e?panel=export#slide-3'
  assert.equal(normalizeAipptReturnTo(destination), destination)
  assert.equal(aipptEntryPermission(destination), 'aippt:project:list')
  assert.equal(aipptEntryPermission('/aippt-web/create?from=projects'), 'aippt:project:add')
  assert.equal(aipptEntryPermission('/aippt-web/create/?from=projects'), 'aippt:project:add')
})

test('拒绝外站、路径逃逸、反斜线、控制字符和 URL 凭证', () => {
  for (const value of [
    undefined, null, ['https://evil.example'], 'https://evil.example/aippt-web/projects',
    '//evil.example/aippt-web/projects', '/aippt-web/../../login', '/aippt-web/%2e%2e/login',
    '/aippt-web/\\evil.example', '/aippt-web/%5cevil.example', '/aippt-web/%2f%2fevil.example',
    '/aippt-web/%252e%252e/login', '/aippt-web/projects\n', '/aippt-web/projects%0a',
    '/aippt-web/projects?token=secret', '/aippt-web/projects?%61ccess_token=secret',
    '/aippt-web/projects#refresh_token=secret', '/aippt-web/projects?secret=secret',
    '/aippt-web/projects?bad=%zz', '/aippt-web/' + 'x'.repeat(4096)
  ]) assert.equal(normalizeAipptReturnTo(value), '/aippt-web/projects', String(value))
})

test('原若依登录的其他 query 合并方式保留 React 深链', () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/aippt-entry', component: {} }, { path: '/login', component: {} }] })
  const returnTo = '/aippt-web/projects/1a33de39-37f9-4482-ae6b-41a4c6c9c22e?panel=export#slide-3'
  const login = router.resolve('/login?redirect=%2Faippt-entry&returnTo=' + encodeURIComponent(returnTo))
  const query = Object.fromEntries(Object.entries(login.query).filter(([key]) => key !== 'redirect'))
  const entry = router.resolve({ path: login.query.redirect, query })
  assert.equal(entry.path, '/aippt-entry')
  assert.equal(normalizeAipptReturnTo(entry.query.returnTo), returnTo)
  // VueRouter 实际会丢弃 location.path 内部的 query，避免回归为旧传法。
  const old = router.resolve({ path: '/aippt-entry?returnTo=' + encodeURIComponent(returnTo), query: {} })
  assert.equal(old.query.returnTo, undefined)
})
