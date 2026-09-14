<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'

import BrandLockup from '@/components/BrandLockup.vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const activeSection = computed(() => String(route.meta.section ?? ''))

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
      <span class="app-nav__item app-nav__item--disabled">我的订单 <small>阶段 03</small></span>
      <span class="app-nav__item app-nav__item--disabled">管理台 <small>建设中</small></span>
    </nav>

    <ElButton plain @click="logout">退出登录</ElButton>
  </header>
</template>
