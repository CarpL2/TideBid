import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'foundation',
      component: () => import('@/views/FoundationView.vue'),
    },
  ],
})

export default router
