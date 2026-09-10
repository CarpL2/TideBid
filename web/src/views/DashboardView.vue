<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElSkeleton, ElSkeletonItem, ElTag } from 'element-plus'
import { storeToRefs } from 'pinia'

import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import BrandLockup from '@/components/BrandLockup.vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const { profile, wallet, dashboardLoading, lastTraceId } = storeToRefs(authStore)
const pageError = ref<ApiError | null>(null)

const avatarText = computed(() => profile.value?.nickname.trim().slice(0, 1).toUpperCase() || 'T')

function formatMoney(value: number | string | undefined): string {
  const amount = Number(value)
  if (!Number.isFinite(amount)) {
    return '--'
  }
  return amount.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

async function refresh(): Promise<void> {
  pageError.value = null
  try {
    await authStore.loadDashboard()
  } catch (error) {
    pageError.value = normalizeApiError(error)
  }
}

async function logout(): Promise<void> {
  authStore.logout()
  await router.replace({ name: 'login' })
}

onMounted(refresh)
</script>

<template>
  <div class="dashboard-page">
    <header class="app-header">
      <BrandLockup compact />

      <nav class="app-nav" aria-label="主要导航">
        <span class="app-nav__item app-nav__item--active">账户工作台</span>
        <button disabled type="button">竞价大厅 <small>阶段 02</small></button>
        <button disabled type="button">我的订单 <small>阶段 03</small></button>
        <button disabled type="button">管理台 <small>待开放</small></button>
      </nav>

      <ElButton plain @click="logout">退出登录</ElButton>
    </header>

    <main class="dashboard-main">
      <div class="dashboard-heading">
        <div>
          <p class="section-eyebrow">Account overview</p>
          <h1>虚拟资金工作台</h1>
          <p>账户资料和余额均来自当前登录用户的实时接口。</p>
        </div>
        <ElButton :loading="dashboardLoading" plain @click="refresh">刷新数据</ElButton>
      </div>

      <ApiErrorNotice :error="pageError" />

      <ElSkeleton v-if="dashboardLoading && !profile" class="dashboard-skeleton" animated>
        <template #template>
          <ElSkeletonItem variant="rect" style="height: 172px" />
          <div class="dashboard-skeleton__row">
            <ElSkeletonItem variant="rect" style="height: 180px" />
            <ElSkeletonItem variant="rect" style="height: 180px" />
          </div>
        </template>
      </ElSkeleton>

      <template v-else-if="profile && wallet">
        <section class="identity-card" aria-labelledby="identity-title">
          <div class="identity-card__avatar" aria-hidden="true">{{ avatarText }}</div>
          <div class="identity-card__main">
            <p class="section-eyebrow">Authenticated identity</p>
            <h2 id="identity-title">{{ profile.nickname }}</h2>
            <p>@{{ profile.username }}</p>
          </div>
          <div class="identity-card__roles">
            <ElTag v-for="role in profile.roles" :key="role" effect="plain">{{ role }}</ElTag>
          </div>
          <dl class="identity-card__meta">
            <div>
              <dt>用户 ID</dt>
              <dd>{{ profile.userId }}</dd>
            </div>
            <div>
              <dt>账户状态</dt>
              <dd><span class="status-inline"><i></i>正常</span></dd>
            </div>
          </dl>
        </section>

        <section class="balance-grid" aria-label="虚拟钱包余额">
          <article class="balance-card balance-card--available">
            <p>可用余额</p>
            <strong><small>¥</small>{{ formatMoney(wallet.availableBalance) }}</strong>
            <span>可用于后续竞价报名与出价</span>
          </article>
          <article class="balance-card">
            <p>冻结余额</p>
            <strong><small>¥</small>{{ formatMoney(wallet.frozenBalance) }}</strong>
            <span>当前没有开放保证金操作</span>
          </article>
        </section>

        <ElAlert
          class="foundation-boundary"
          :closable="false"
          type="info"
          show-icon
          title="阶段 01 只展示真实账号与钱包数据；竞价、订单和管理功能尚未开放。"
        />

        <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
      </template>

      <section v-else-if="!pageError" class="empty-state">
        <h2>暂时无法显示账户数据</h2>
        <p>请重新加载，或退出后再次登录。</p>
        <ElButton type="primary" @click="refresh">重新加载</ElButton>
      </section>
    </main>
  </div>
</template>
