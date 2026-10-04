import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from '../features/auth/LoginView.vue'
import TaskCenterView from '../features/task-center/TaskCenterView.vue'
import TaskDetailView from '../features/task-center/TaskDetailView.vue'
import CapabilityOverviewView from '../features/capability-overview/CapabilityOverviewView.vue'
import AboutView from '../features/about/AboutView.vue'
import CollectionView from '../features/collection/CollectionView.vue'
import { useSessionStore } from '../stores/session'

export const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', redirect: '/tasks' },
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/tasks', name: 'tasks', component: TaskCenterView },
    { path: '/tasks/:taskId', name: 'task-detail', component: TaskDetailView },
    { path: '/capabilities', name: 'capabilities', component: CapabilityOverviewView },
    { path: '/about', name: 'about', component: AboutView },
    { path: '/collection', name: 'collection', component: CollectionView }
  ]
})

router.beforeEach((to) => {
  const session = useSessionStore()
  if (!to.meta.public && session.phase !== 'authenticated') return { name: 'login' }
  if (to.name === 'login' && session.phase === 'authenticated') return { name: 'tasks' }
  return true
})
