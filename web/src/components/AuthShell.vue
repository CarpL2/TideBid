<script setup lang="ts">
import BrandLockup from '@/components/BrandLockup.vue'

defineProps<{
  eyebrow: string
  title: string
  description: string
}>()
</script>

<template>
  <main class="auth-page">
    <section class="auth-context" aria-label="TideBid 平台说明">
      <BrandLockup />

      <div class="auth-context__copy">
        <p class="section-eyebrow">Verified auction flow</p>
        <h2>从可信身份开始，连接每一次出价与资金变化。</h2>
        <p>
          当前开放账号与虚拟钱包能力。拍品、竞价、订单和实时推送将在后续阶段依次接入。
        </p>
      </div>

      <dl class="auth-context__facts">
        <div>
          <dt>统一入口</dt>
          <dd>Gateway : 9000</dd>
        </div>
        <div>
          <dt>会话策略</dt>
          <dd>RS256 · 2 小时</dd>
        </div>
      </dl>
    </section>

    <section class="auth-workspace">
      <div class="auth-card">
        <header class="auth-card__header">
          <p class="section-eyebrow">{{ eyebrow }}</p>
          <h1>{{ title }}</h1>
          <p>{{ description }}</p>
        </header>
        <slot />
      </div>
    </section>
  </main>
</template>
