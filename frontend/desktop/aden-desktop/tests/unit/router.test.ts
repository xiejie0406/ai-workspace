// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { router } from '../../src/renderer/src/router'
import { useSessionStore } from '../../src/renderer/src/stores/session'

describe('renderer router', () => {
  it('registers approved routes including collection and guards private views', async () => {
    setActivePinia(createPinia())
    const paths = router.getRoutes().filter((route) => route.path !== '/').map((route) => route.path).sort()
    expect(paths).toEqual(['/about', '/capabilities', '/collection', '/login', '/tasks', '/tasks/:taskId'])
    useSessionStore().phase = 'anonymous'
    await router.push('/tasks')
    expect(router.currentRoute.value.path).toBe('/login')
    await router.push('/collection')
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
