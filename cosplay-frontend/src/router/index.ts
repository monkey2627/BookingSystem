import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由配置 + 导航守卫
 *
 * 路由懒加载（() => import(...)）：
 *   每个页面组件打包为单独的 JS chunk，首屏只加载 home 页代码，
 *   其他页面用到时才异步加载，减少首屏体积。
 *
 * 鉴权设计：
 *   meta.requireAuth=true 的路由在 beforeEach 守卫里检查登录态。
 *   未登录时重定向到 /login 并带上 redirect 参数，登录成功后可跳回原页面。
 *   不区分"商家路由"和"客人路由"，所有登录用户都能访问所有页面，
 *   页面内部根据 isMerchant 做差异化展示（如 BookingsView 同一组件两种视图）。
 */
const router = createRouter({
  history: createWebHistory(), // HTML5 History 模式，URL 无 # 号，需要服务器配置 fallback
  routes: [
    { path: '/', redirect: '/home' },

    // ── 公开页面（无需登录）──────────────────────────────
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
      path: '/merchant/:id',    // :id = merchantId，动态参数
      name: 'merchantDetail',
      component: () => import('@/views/MerchantDetailView.vue')
    },
    {
      path: '/feed',
      name: 'feed',
      component: () => import('@/views/PostFeedView.vue')
    },

    // ── 需要登录的页面 ─────────────────────────────────
    {
      path: '/messages',
      name: 'messages',
      component: () => import('@/views/MessageView.vue'),
      meta: { requireAuth: true }
    },
    {
      // 同一组件，route.name 区分"我的预约"和"收到的预约"两种视图
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

/**
 * 全局前置守卫
 * 每次路由跳转前执行，检查目标路由是否需要登录。
 */
router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  if (to.meta.requireAuth && !userStore.isLoggedIn) {
    // 带上 redirect，登录后 LoginView 可以拿到并跳回
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
