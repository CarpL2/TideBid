<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'

import BrandLockup from '@/components/BrandLockup.vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const activeSection = computed(() => String(route.meta.section ?? ''))
const isAdmin = computed(() => authStore.profile?.roles.includes('ADMIN') ?? false)

onMounted(() => {
  if (authStore.isAuthenticated && !authStore.profile) {
    void authStore.loadProfile().catch(() => undefined)
  }
})

async function logout(): Promise<void> {
  authStore.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <header class="app-header">
    <RouterLink :to="{ name: 'auctions' }" aria-label="返回 TideBid 竞价大厅">
      <BrandLockup compact />
    </RouterLink>

    <nav class="app-nav" aria-label="主要导航">
      <RouterLink
        :class="['app-nav__item', { 'app-nav__item--active': activeSection === 'dashboard' }]"
        :to="{ name: 'dashboard' }"
      >
        账户工作台
      </RouterLink>
      <RouterLink
        :class="['app-nav__item', { 'app-nav__item--active': activeSection === 'auctions' }]"
        :to="{ name: 'auctions' }"
      >
        竞价大厅
      </RouterLink>
      <RouterLink
        :class="['app-nav__item', { 'app-nav__item--active': activeSection === 'seller' }]"
        :to="{ name: 'my-assets' }"
      >
        我的拍品
      </RouterLink>
      <RouterLink
        :class="['app-nav__item', { 'app-nav__item--active': activeSection === 'buyer' }]"
        :to="{ name: 'my-registrations' }"
      >
        我的报名
      </RouterLink>
      <RouterLink
        :class="['app-nav__item', { 'app-nav__item--active': activeSection === 'orders' }]"
        :to="{ name: 'my-orders' }"
      >
        我的订单
      </RouterLink>
      <RouterLink
        v-if="isAdmin"
        :class="['app-nav__item', { 'app-nav__item--active': activeSection === 'admin' }]"
        :to="{ name: 'admin-reviews' }"
      >
        拍品审核
      </RouterLink>
    </nav>

    <ElButton plain @click="logout">退出登录</ElButton>
  </header>
</template>
