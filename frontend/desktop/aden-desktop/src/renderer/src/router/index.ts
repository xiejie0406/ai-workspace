import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from '../features/auth/LoginView.vue'
import AboutView from '../features/about/AboutView.vue'
import CollectionView from '../features/collection/CollectionView.vue'
import CollectionSetupView from '../features/collection/CollectionSetupView.vue'
import CollectionTasksView from '../features/collection/CollectionTasksView.vue'
import HomeView from '../features/home/HomeView.vue'
import CollectionExportsView from '../features/collection/CollectionExportsView.vue'
import { useSessionStore } from '../stores/session'

export const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/home', name: 'home', component: HomeView },
    { path: '/about', name: 'about', component: AboutView },
    { path: '/collection', name: 'collection', component: CollectionView },
    { path: '/collection/items/:itemId', name: 'collection-item-detail', component: CollectionView },
    { path: '/collection/exports', name: 'collection-exports', component: CollectionExportsView },
    { path: '/collection/tasks', name: 'collection-tasks', component: CollectionTasksView },
    { path: '/collection/tasks/:taskId', name: 'collection-task-detail', component: CollectionTasksView },
    { path: '/settings', name: 'settings', component: CollectionSetupView, meta: { public: true } },
    { path: '/collection/setup', redirect: '/settings' },
    { path: '/:pathMatch(.*)*', redirect: '/home' }
  ]
})

router.beforeEach((to) => {
  const session = useSessionStore()
  if (!to.meta.public && (session.phase !== 'authenticated' || !session.context.workspaceId)) return { name: 'login' }
  if (to.name === 'login' && session.phase === 'authenticated') return { name: 'home' }
  return true
})
