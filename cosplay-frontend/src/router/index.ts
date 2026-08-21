import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },

    // ── 公开页面 ──────────────────────────────────────────
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue')
    },
    {
      path: '/home',
      name: 'home',
      component: () => import('@/views/HomeView.vue')
    },
    {
      path: '/merchant/:id',
      name: 'merchantDetail',
      component: () => import('@/views/MerchantDetailView.vue')
    },
    {
      path: '/feed',
      name: 'feed',
      component: () => import('@/views/PostFeedView.vue')
    },

    // ── 登录后所有用户可访问（不再区分角色） ──────────────
    {
      path: '/messages',
      name: 'messages',
      component: () => import('@/views/MessageView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/orders/my',
      name: 'myBookings',
      component: () => import('@/views/BookingsView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/orders/received',
      name: 'receivedBookings',
      component: () => import('@/views/BookingsView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/follows',
      name: 'follows',
      component: () => import('@/views/FollowsView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/merchant/profile',
      name: 'merchantProfile',
      component: () => import('@/views/MerchantProfileView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/schedule/manage',
      name: 'scheduleManage',
      component: () => import('@/views/ScheduleManageView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/views/MerchantDashboardView.vue'),
      meta: { requireAuth: true }
    },
    {
      path: '/questionnaire/manage',
      name: 'questionnaireManage',
      component: () => import('@/views/QuestionnaireManageView.vue'),
      meta: { requireAuth: true }
    }
  ]
})

router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  if (to.meta.requireAuth && !userStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

declare module 'vue-router' {
  interface RouteMeta {
    requireAuth?: boolean
  }
}

export default router
