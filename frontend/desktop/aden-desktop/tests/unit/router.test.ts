// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { router } from '../../src/renderer/src/router'
import { useSessionStore } from '../../src/renderer/src/stores/session'

describe('renderer router', () => {
  it('includes the home and collection setup routes and guards private views', async () => {
    setActivePinia(createPinia())
    const paths = router.getRoutes().filter((route) => route.path !== '/').map((route) => route.path).sort()
    expect(paths).toEqual(['/:pathMatch(.*)*', '/about', '/collection', '/collection/exports', '/collection/items/:itemId', '/collection/setup', '/collection/tasks', '/collection/tasks/:taskId', '/home', '/login', '/settings'])
    useSessionStore().phase = 'anonymous'
    await router.push('/collection/tasks')
    expect(router.currentRoute.value.path).toBe('/login')
    await router.push('/settings')
    expect(router.currentRoute.value.path).toBe('/settings')
  })
})
