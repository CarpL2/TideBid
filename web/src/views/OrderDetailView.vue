<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElMessage, ElSkeleton, ElTag } from 'element-plus'

import { normalizeApiError, type ApiError } from '@/api/errors'
import { createClientRequestId } from '@/api/http'
import { getOrder, payOrder } from '@/api/trade'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import { formatMoney, formatShanghaiTime } from '@/features/auction/presentation'
import {
  maskUserId,
  orderStatusLabel,
  orderStatusTagType,
  paymentFailureLabel,
  paymentStatusLabel,
  settlementStatusLabel,
} from '@/features/trade/presentation'
import { useAuthStore } from '@/stores/auth'
import type { TradeOrder, TradePaymentAttempt } from '@/types/trade'

const POLL_INTERVAL_MS = 2_000
const MAX_POLL_ROUNDS = 5

const route = useRoute()
const authStore = useAuthStore()
const order = ref<TradeOrder | null>(null)
const lastPayment = ref<TradePaymentAttempt | null>(null)
const loading = ref(false)
const paying = ref(false)
const polling = ref(false)
const pollRounds = ref(0)
const pageError = ref<ApiError | null>(null)
const actionError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let paymentRequestId: string | null = null

const orderId = computed(() => String(route.params.orderId ?? ''))
const isSeller = computed(() => authStore.profile?.userId === order.value?.sellerId)
const shouldPoll = computed(() => order.value?.status === 'PAYMENT_PROCESSING')

function cancelPoll(): void {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function schedulePoll(): void {
  cancelPoll()
  if (!shouldPoll.value || pollRounds.value >= MAX_POLL_ROUNDS) return
  pollTimer = setTimeout(() => void pollOrder(), POLL_INTERVAL_MS)
}

async function pollOrder(): Promise<void> {
  if (!shouldPoll.value || polling.value || pollRounds.value >= MAX_POLL_ROUNDS) return
  polling.value = true
  pollRounds.value += 1
  try {
    await loadOrder(false, false)
  } finally {
    polling.value = false
    schedulePoll()
  }
}

async function loadOrder(showLoading = true, resetPolling = true): Promise<void> {
  cancelPoll()
  if (showLoading) loading.value = true
  pageError.value = null
  if (resetPolling) pollRounds.value = 0
  try {
    const result = await getOrder(orderId.value)
    order.value = result.data
    lastTraceId.value = result.traceId
    if (result.data.status !== 'PAYMENT_PROCESSING') paymentRequestId = null
    schedulePoll()
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    if (showLoading) loading.value = false
  }
}

async function pay(): Promise<void> {
  if (!order.value?.paymentEligible || paying.value) return
  paying.value = true
  actionError.value = null
  lastPayment.value = null
  paymentRequestId ??= createClientRequestId()
  try {
    const result = await payOrder(order.value.orderId, paymentRequestId)
    lastPayment.value = result.data
    lastTraceId.value = result.traceId
    if (result.data.status === 'SUCCEEDED') {
      ElMessage.success('虚拟钱包支付成功。')
      paymentRequestId = null
    } else if (result.data.status === 'REJECTED') {
      paymentRequestId = null
    } else {
      ElMessage.info('支付结果仍在确认，页面将有限次数查询订单状态。')
    }
    await loadOrder(false)
  } catch (error) {
    actionError.value = normalizeApiError(error)
    if (actionError.value.status > 0 && actionError.value.status < 500) paymentRequestId = null
  } finally {
    paying.value = false
  }
}

async function refreshManually(): Promise<void> {
  await loadOrder(true, true)
}

watch(orderId, () => void loadOrder())
onMounted(() => {
  if (!authStore.profile) void authStore.loadProfile().catch(() => undefined)
  void loadOrder()
})
onBeforeUnmount(cancelPoll)
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main order-detail-main">
      <RouterLink class="back-link" :to="{ name: 'my-orders' }">← 返回我的订单</RouterLink>
      <ApiErrorNotice :error="pageError" />
      <ElSkeleton v-if="loading && !order" :rows="8" animated />

      <template v-else-if="order">
        <div class="auction-heading order-detail-heading">
          <div>
            <p class="section-eyebrow">Order {{ order.orderNo }}</p>
            <h1>{{ order.itemTitle }}</h1>
            <p>成交快照不会随拍品后续展示变化而改变。</p>
          </div>
          <ElTag :type="orderStatusTagType(order.status)" effect="plain" size="large">
            {{ orderStatusLabel(order.status) }}
          </ElTag>
        </div>

        <ApiErrorNotice :error="actionError" />
        <section
          v-if="lastPayment?.status === 'REJECTED'"
          class="order-payment-notice order-payment-notice--danger"
          role="status"
        >
          <strong>{{ paymentStatusLabel(lastPayment.status) }}</strong>
          <span>{{ paymentFailureLabel(lastPayment.failureCode) }}</span>
        </section>
        <section
          v-else-if="order.status === 'PAYMENT_PROCESSING'"
          class="order-payment-notice"
          role="status"
        >
          <strong>支付结果确认中</strong>
          <span v-if="pollRounds < MAX_POLL_ROUNDS">
            已查询 {{ pollRounds }}/{{ MAX_POLL_ROUNDS }} 次<span v-if="polling">，正在刷新</span>。
          </span>
          <span v-else>自动查询已停止，请稍后手动刷新；当前结果不会被当作成功或失败。</span>
        </section>

        <section class="order-detail-grid" aria-label="订单详情">
          <article class="order-detail-card order-detail-card--highlight">
            <p class="section-eyebrow">成交金额</p>
            <strong class="order-detail-price">{{ formatMoney(order.finalPrice) }}</strong>
            <dl>
              <div><dt>保证金抵扣</dt><dd>{{ formatMoney(order.capturedDepositAmount) }}</dd></div>
              <div><dt>待付尾款</dt><dd>{{ formatMoney(order.payableAmount) }}</dd></div>
              <div><dt>卖家应收</dt><dd>{{ formatMoney(order.sellerReceivableAmount) }}</dd></div>
            </dl>
          </article>

          <article class="order-detail-card">
            <h2>成交快照</h2>
            <dl>
              <div><dt>订单编号</dt><dd>{{ order.orderNo }}</dd></div>
              <div><dt>订单 ID</dt><dd>{{ order.orderId }}</dd></div>
              <div><dt>拍卖场次</dt><dd>{{ order.auctionId }}</dd></div>
              <div><dt>拍品 ID</dt><dd>{{ order.itemId }}</dd></div>
              <div><dt>买家</dt><dd>{{ isSeller ? maskUserId(order.buyerId) : '当前账户' }}</dd></div>
              <div><dt>关拍时间</dt><dd>{{ formatShanghaiTime(order.auctionClosedAt) }}</dd></div>
            </dl>
          </article>

          <article class="order-detail-card">
            <h2>支付与结算</h2>
            <dl>
              <div><dt>订单状态</dt><dd>{{ orderStatusLabel(order.status) }}</dd></div>
              <div><dt>支付截止</dt><dd>{{ formatShanghaiTime(order.paymentDeadline) }}</dd></div>
              <div><dt>支付完成</dt><dd>{{ formatShanghaiTime(order.paidAt) }}</dd></div>
              <div><dt>超时时间</dt><dd>{{ formatShanghaiTime(order.timedOutAt) }}</dd></div>
              <div><dt>卖家结算</dt><dd>{{ settlementStatusLabel(order.sellerSettlementStatus) }}</dd></div>
              <div><dt>卖家入账</dt><dd>{{ formatShanghaiTime(order.sellerCreditedAt) }}</dd></div>
            </dl>
          </article>
        </section>

        <section class="order-detail-actions">
          <div>
            <strong v-if="order.paymentEligible">使用虚拟钱包支付 {{ formatMoney(order.payableAmount) }}</strong>
            <strong v-else-if="order.status === 'PAID'">订单已经支付完成</strong>
            <strong v-else-if="order.status === 'PAYMENT_TIMEOUT'">订单已经超过支付期限</strong>
            <strong v-else>当前订单无需手动支付</strong>
            <small>页面仅展示服务端最终状态；支付结果以 HTTP/MySQL 为准，实时竞价推送不替代订单查询。</small>
          </div>
          <div class="order-detail-actions__buttons">
            <ElButton :loading="loading || polling" plain @click="refreshManually">手动刷新</ElButton>
            <ElButton
              v-if="order.paymentEligible"
              data-test="pay-order"
              :loading="paying"
              type="primary"
              @click="pay"
            >
              确认支付
            </ElButton>
          </div>
        </section>
        <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
      </template>
    </main>
  </div>
</template>
