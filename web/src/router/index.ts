import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory,
} from 'vue-router'

import { hasValidAuthSession } from '@/features/auth/session'

export function createAppRouter(
  history: RouterHistory = createWebHistory(import.meta.env.BASE_URL),
): Router {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/',
        redirect: () => ({ name: hasValidAuthSession() ? 'dashboard' : 'login' }),
        meta: { title: 'TideBid' },
      },
      {
        path: '/login',
        name: 'login',
        component: () => import('@/views/LoginView.vue'),
        meta: { title: '登录 · TideBid', guestOnly: true },
      },
      {
        path: '/register',
        name: 'register',
        component: () => import('@/views/RegisterView.vue'),
        meta: { title: '注册 · TideBid', guestOnly: true },
      },
      {
        path: '/dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '账户工作台 · TideBid', requiresAuth: true, section: 'dashboard' },
      },
      {
        path: '/auctions',
        name: 'auctions',
        component: () => import('@/views/AuctionLobbyView.vue'),
        meta: { title: '竞价大厅 · TideBid', requiresAuth: true, section: 'auctions' },
      },
      {
        path: '/auctions/:auctionId',
        name: 'auction-detail',
        component: () => import('@/views/AuctionDetailView.vue'),
        meta: { title: '拍品详情 · TideBid', requiresAuth: true, section: 'auctions' },
      },
      {
        path: '/assets/mine',
        name: 'my-assets',
        component: () => import('@/views/MyAssetsView.vue'),
        meta: { title: '我的拍品 · TideBid', requiresAuth: true, section: 'seller' },
      },
      {
        path: '/assets/new',
        name: 'asset-create',
        component: () => import('@/views/AuctionDraftView.vue'),
        meta: { title: '创建拍品 · TideBid', requiresAuth: true, section: 'seller' },
      },
      {
        path: '/assets/:assetId/edit',
        name: 'asset-edit',
        component: () => import('@/views/AuctionDraftView.vue'),
        meta: { title: '编辑拍品 · TideBid', requiresAuth: true, section: 'seller' },
      },
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: { title: '页面不存在 · TideBid' },
      },
    ],
  })

  router.beforeEach((to) => {
    const authenticated = hasValidAuthSession()
    if (to.meta.requiresAuth && !authenticated) {
      return {
        name: 'login',
        query: { redirect: to.fullPath },
      }
    }

    if (to.meta.guestOnly && authenticated) {
      return { name: 'dashboard' }
    }

    return true
  })

  router.afterEach((to, _from, failure) => {
    if (!failure && typeof to.meta.title === 'string') {
      document.title = to.meta.title
    }
  })

  return router
}

const router = createAppRouter()

export default router
