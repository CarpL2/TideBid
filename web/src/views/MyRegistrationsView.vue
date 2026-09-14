<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElButton, ElPagination, ElTag } from 'element-plus'
import { storeToRefs } from 'pinia'

import { getAuctionRegistration, getMyAuctionRegistrations } from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import {
  formatMoney,
  formatShanghaiTime,
  registrationFailureLabel,
  registrationStatusLabel,
} from '@/features/auction/presentation'
import { useAuthStore } from '@/stores/auth'
import type { AuctionRegistrationRecord } from '@/types/auction'

const PAGE_SIZE = 12
const POLL_INTERVAL_MS = 2_000
const MAX_POLL_ROUNDS = 5

const authStore = useAuthStore()
const { wallet } = storeToRefs(authStore)
const items = ref<AuctionRegistrationRecord[]>([])
const currentPage = ref(1)
const total = ref(0)
const loading = ref(false)
const walletLoading = ref(false)
const polling = ref(false)
const pollRounds = ref(0)
const pageError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const pendingCount = computed(() => items.value.filter((item) => item.status === 'PENDING_HOLD').length)

function cancelPoll(): void {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function statusTagType(status: AuctionRegistrationRecord['status']): 'warning' | 'success' | 'danger' {
  if (status === 'REGISTERED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

async function refreshWallet(): Promise<void> {
  walletLoading.value = true
  try {
    await authStore.loadDashboard()
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    walletLoading.value = false
  }
}

function schedulePoll(): void {
  cancelPoll()
  if (pendingCount.value === 0 || pollRounds.value >= MAX_POLL_ROUNDS) return
  pollTimer = setTimeout(() => void pollPending(), POLL_INTERVAL_MS)
}

async function pollPending(): Promise<void> {
  const pending = items.value.filter((item) => item.status === 'PENDING_HOLD')
  if (pending.length === 0 || pollRounds.value >= MAX_POLL_ROUNDS || polling.value) return

  polling.value = true
  pollRounds.value += 1
  const previousStatuses = new Map(pending.map((item) => [item.registrationId, item.status]))
  const results = await Promise.allSettled(
    pending.map((item) => getAuctionRegistration(item.registrationId)),
  )

  let becameFinal = false
  results.forEach((result) => {
    if (result.status === 'fulfilled') {
      const registration = result.value.data
      const itemIndex = items.value.findIndex(
        (item) => item.registrationId === registration.registrationId,
      )
      if (itemIndex >= 0) items.value[itemIndex] = registration
      lastTraceId.value = result.value.traceId
      becameFinal ||=
        previousStatuses.get(registration.registrationId) === 'PENDING_HOLD' &&
        registration.status !== 'PENDING_HOLD'
    } else if (!pageError.value) {
      pageError.value = normalizeApiError(result.reason)
    }
  })

  polling.value = false
  if (becameFinal) await refreshWallet()
  schedulePoll()
}

async function loadRegistrations(page = currentPage.value, resetPolling = true): Promise<void> {
  cancelPoll()
  loading.value = true
  pageError.value = null
  if (resetPolling) pollRounds.value = 0
  try {
    const result = await getMyAuctionRegistrations(page, PAGE_SIZE)
    items.value = result.data.items
    currentPage.value = result.data.page
    total.value = result.data.total
    lastTraceId.value = result.traceId
    schedulePoll()
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await Promise.all([loadRegistrations(currentPage.value), refreshWallet()])
}

onMounted(() => {
  void refreshAll()
})
onBeforeUnmount(cancelPoll)
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main registrations-main">
      <div class="auction-heading">
        <div>
          <p class="section-eyebrow">Buyer workspace</p>
          <h1>我的报名</h1>
          <p>保证金处理状态来自 Auction，钱包余额来自 Account。</p>
        </div>
        <ElButton :loading="loading || walletLoading" plain @click="refreshAll">刷新状态</ElButton>
      </div>
      <ApiErrorNotice :error="pageError" />

      <section class="registration-wallet" aria-label="钱包余额">
        <div>
          <small>可用余额</small>
          <strong>{{ formatMoney(wallet?.availableBalance) }}</strong>
        </div>
        <div>
          <small>冻结余额</small>
          <strong>{{ formatMoney(wallet?.frozenBalance) }}</strong>
        </div>
        <p v-if="pendingCount > 0">
          {{ pendingCount }} 笔保证金仍在处理中
          <span v-if="polling">，正在查询</span>
          <span v-else-if="pollRounds < MAX_POLL_ROUNDS">，将自动刷新</span>
          <span v-else>，自动查询已结束，可稍后手动刷新</span>
        </p>
      </section>

      <section v-if="items.length > 0" class="registration-list" aria-label="报名记录">
        <article v-for="item in items" :key="item.registrationId" class="registration-row">
          <div class="registration-row__status">
            <ElTag :type="statusTagType(item.status)" effect="plain">
              {{ registrationStatusLabel(item.status) }}
            </ElTag>
            <small>报名编号 {{ item.registrationId }}</small>
          </div>
          <div class="registration-row__auction">
            <small>竞价场次</small>
            <RouterLink :to="{ name: 'auction-detail', params: { auctionId: item.auctionId } }">
              {{ item.auctionId }}
            </RouterLink>
          </div>
          <div>
            <small>冻结保证金</small>
            <strong>{{ formatMoney(item.depositAmount) }}</strong>
          </div>
          <div>
            <small>{{ item.registeredAt ? '报名成功时间' : '发起时间' }}</small>
            <span>{{ formatShanghaiTime(item.registeredAt ?? item.createdAt) }}</span>
          </div>
          <p v-if="item.status === 'FAILED'" class="registration-row__failure">
            {{ registrationFailureLabel(item.failureCode) }}
          </p>
        </article>
      </section>

      <section v-else-if="!loading && !pageError" class="empty-state">
        <h2>还没有报名记录</h2>
        <p>从竞价大厅进入未开始的场次即可报名。</p>
        <RouterLink class="primary-link-button" :to="{ name: 'auctions' }">前往竞价大厅</RouterLink>
      </section>

      <footer v-if="total > PAGE_SIZE" class="auction-pagination">
        <ElPagination
          :current-page="currentPage"
          :page-size="PAGE_SIZE"
          :total="total"
          background
          layout="prev, pager, next"
          @current-change="loadRegistrations"
        />
      </footer>
      <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
    </main>
  </div>
</template>
