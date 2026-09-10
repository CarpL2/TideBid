<script setup lang="ts">
import { computed } from 'vue'
import { ElButton } from 'element-plus'

import BrandLockup from '@/components/BrandLockup.vue'
import { hasValidAuthSession } from '@/features/auth/session'

const target = computed(() => (hasValidAuthSession() ? '/dashboard' : '/login'))
</script>

<template>
  <main class="not-found-page">
    <BrandLockup />
    <p class="not-found-page__code">404</p>
    <h1>这里没有可用页面</h1>
    <p>地址可能已经改变，或当前阶段尚未开放该功能。</p>
    <RouterLink :to="target">
      <ElButton type="primary">返回 TideBid</ElButton>
    </RouterLink>
  </main>
</template>
