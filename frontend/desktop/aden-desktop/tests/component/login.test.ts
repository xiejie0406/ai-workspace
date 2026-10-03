// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from '../../src/renderer/src/features/auth/LoginView.vue'
import { useSessionStore } from '../../src/renderer/src/stores/session'

describe('日常账号登录', () => {
  afterEach(() => vi.unstubAllGlobals())

  it.each([
    { name: '正式渠道', defaults: null },
    { name: '本地测试渠道', defaults: { username: 'synthetic-test-operator', password: 'synthetic-test-password' } }
  ])('$name initializes channel defaults without submitting and permits user edits', async ({ defaults }) => {
    vi.stubGlobal('__ADEN_TEST_LOGIN__', defaults)
    const pinia = createPinia(); setActivePinia(pinia)
    const session = useSessionStore()
    vi.spyOn(session, 'loadCaptcha').mockResolvedValue()
    const login = vi.spyOn(session, 'login').mockResolvedValue()
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/login', component: LoginView },
      { path: '/home', component: { template: '<div />' } },
      { path: '/settings', component: { template: '<div />' } }
    ] })
    await router.push('/login'); await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    const username = wrapper.get<HTMLInputElement>('input[name="username"]')
    const password = wrapper.get<HTMLInputElement>('input[name="password"]')
    expect(username.element.value).toBe(defaults?.username ?? '')
    expect(password.element.value).toBe(defaults?.password ?? '')
    expect(password.element.type).toBe('password')
    expect(login).not.toHaveBeenCalled()
    await username.setValue('用户填写的账号')
    await password.setValue('user-entered-test-password')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(login).toHaveBeenCalledExactlyOnceWith({ username: '用户填写的账号', password: 'user-entered-test-password' })
    expect(router.currentRoute.value.path).toBe('/home')
    wrapper.unmount()
  })
})
