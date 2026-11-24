import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('./components/AppShell.vue'),
      children: [
        { path: '', name: 'home', component: () => import('./views/HomeView.vue') },
        { path: 'spaces/:spaceId(\\d+)', name: 'space', component: () => import('./views/SpaceHome.vue'), props: true },
        { path: 'spaces/:spaceId(\\d+)/pages/:pageId(\\d+)', name: 'page', component: () => import('./views/PageView.vue'), props: true },
        { path: 'spaces/:spaceId(\\d+)/settings', name: 'space-settings', component: () => import('./views/SpaceSettings.vue'), props: true },
        { path: 'admin', name: 'admin', component: () => import('./views/AdminView.vue'), meta: { admin: true } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuth()
  if (!auth.loaded) await auth.load().catch(() => {})
  if (to.meta.public) return auth.user && to.name === 'login' ? { name: 'home' } : true
  if (!auth.user) return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return { name: 'home' }
  return true
})

export default router
