<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElMessage, ElSkeleton, ElTag } from 'element-plus'

import {
  getAuctionBidHistory,
  getAuctionDetail,
  getAuctionRegistration,
  getMyAuctionProxyBid,
  upsertAuctionProxyBid,
  disableAuctionProxyBid,
  placeAuctionBid,
  registerForAuction,
} from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import { createClientRequestId } from '@/api/http'
import { RealtimeAuctionClient } from '@/features/realtime/client'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import { validateBidAmount } from '@/features/auction/bid-validation'
import {
  conditionLabel,
  formatMoney,
  formatShanghaiTime,
  registrationFailureLabel,
  registrationStatusLabel,
  sessionStatusLabel,
} from '@/features/auction/presentation'
import { useAuthStore } from '@/stores/auth'
import type {
  AuctionBidConflict,
  AuctionBidHistoryItem,
  AuctionDetail,
  AuctionRegistrationRecord,
  AuctionProxyBidDetail,
  AuctionProxyBidResult,
} from '@/types/auction'
import type {
  RealtimeAuctionClosed,
  RealtimeAuctionExtended,
  RealtimeBidAccepted,
  RealtimeConnectionState,
  RealtimeSnapshot,
} from '@/types/realtime'

const REGISTRATION_POLL_INTERVAL_MS = 2_000
const MAX_REGISTRATION_POLL_ROUNDS = 5

const route = useRoute()
const authStore = useAuthStore()
const detail = ref<AuctionDetail | null>(null)
const bids = ref<AuctionBidHistoryItem[]>([])
const loading = ref(false)
const registering = ref(false)
const bidding = ref(false)
const registrationPolling = ref(false)
const registrationPollRounds = ref(0)
const bidAmount = ref('')
const bidValidation = ref<string | null>(null)
const proxyBid = ref<AuctionProxyBidDetail | null>(null)
const proxyMaxAmount = ref('')
const proxyLoading = ref(false)
const proxyValidation = ref<string | null>(null)
const proxyLeading = ref(false)
const pageError = ref<ApiError | null>(null)
const actionError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)
const realtimeState = ref<RealtimeConnectionState>('idle')
const realtimeDetail = ref<string | null>(null)
let realtimeClient: RealtimeAuctionClient | null = null
let registrationPollTimer: ReturnType<typeof setTimeout> | null = null
let bidAttempt: { amount: string; requestId: string } | null = null

const auctionId = computed(() => String(route.params.auctionId ?? ''))
const canRegister = computed(() => {
  if (!detail.value || detail.value.ownedByCurrentUser || detail.value.myRegistration) return false
  return detail.value.sessionStatus === 'SCHEDULED' && new Date(detail.value.startAt).getTime() > Date.now()
})
const canBid = computed(
  () =>
    detail.value?.sessionStatus === 'OPEN' &&
    !detail.value.ownedByCurrentUser &&
    detail.value.myRegistration?.status === 'REGISTERED',
)

function applyRealtimeSnapshot(snapshot: RealtimeSnapshot): void {
  if (!detail.value || snapshot.auctionId !== detail.value.auctionId) return
  detail.value.sessionStatus = snapshot.status
  detail.value.displayPrice = snapshot.displayPrice
  detail.value.currentPrice = snapshot.status === 'CLOSED_UNSOLD' ? null : snapshot.displayPrice
  detail.value.minimumNextBid = snapshot.minimumNextBid
  detail.value.bidCount = snapshot.bidCount
  detail.value.endAt = snapshot.endAt
  detail.value.closedAt = snapshot.closedAt
  detail.value.finalPrice = snapshot.status === 'CLOSED_SOLD' ? snapshot.displayPrice : null
  bids.value = snapshot.bids.map((bid) => ({
    bidId: bid.bidId,
    amount: bid.amount,
    previousPrice: null,
    sequenceNo: bid.sequenceNo,
    createdAt: bid.acceptedAt,
    mine: bid.mine,
  }))
  bidAmount.value = String(snapshot.minimumNextBid)
  proxyLeading.value = snapshot.leading
  if (!snapshot.proxyActive) {
    proxyBid.value = proxyBid.value?.status === 'ACTIVE' ? proxyBid.value : null
  }
}

function applyRealtimeBid(bid: RealtimeBidAccepted): void {
  if (!detail.value || bid.auctionId !== detail.value.auctionId) return
  detail.value.displayPrice = bid.amount
  detail.value.currentPrice = bid.amount
  detail.value.bidCount = Math.max(detail.value.bidCount, bid.sequenceNo)
  detail.value.minimumNextBid = (Number(bid.amount) + Number(detail.value.bidIncrement)).toFixed(2)
  if (!bids.value.some((item) => item.sequenceNo === bid.sequenceNo || item.bidId === bid.bidId)) {
    bids.value = [...bids.value, {
      bidId: bid.bidId,
      amount: bid.amount,
      previousPrice: null,
      sequenceNo: bid.sequenceNo,
      createdAt: bid.acceptedAt,
      mine: bid.mine,
    }].sort((left, right) => left.sequenceNo - right.sequenceNo).slice(-10)
  }
  bidAmount.value = String(detail.value.minimumNextBid)
  if (proxyBid.value?.status === 'ACTIVE' && bid.mine) proxyLeading.value = true
}

function applyRealtimeExtended(event: RealtimeAuctionExtended): void {
  if (!detail.value || event.auctionId !== detail.value.auctionId) return
  detail.value.endAt = event.endAt
  detail.value.sessionStatus = 'OPEN'
  ElMessage.info(`反狙击延时至 ${formatShanghaiTime(event.endAt)}`)
}

function applyRealtimeClosed(event: RealtimeAuctionClosed): void {
  if (!detail.value || event.auctionId !== detail.value.auctionId) return
  detail.value.sessionStatus = event.status
  detail.value.closedAt = event.closedAt
  detail.value.finalPrice = event.finalPrice
  detail.value.wonByCurrentUser = event.wonByCurrentUser
  if (event.status === 'CLOSED_SOLD' && event.finalPrice !== null) {
    detail.value.displayPrice = event.finalPrice
    detail.value.currentPrice = event.finalPrice
  }
}

function applyProxyResult(result: AuctionProxyBidResult): void {
  if (!detail.value || result.auctionId !== detail.value.auctionId) return
  detail.value.displayPrice = result.displayPrice
  detail.value.currentPrice = result.displayPrice
  detail.value.minimumNextBid = result.minimumNextBid
  detail.value.bidCount = result.bidCount
  detail.value.endAt = result.endAt
  proxyBid.value = result.proxyBid
  proxyLeading.value = result.leading
  proxyMaxAmount.value = result.proxyBid ? String(result.proxyBid.maxAmount) : ''
  bidAmount.value = String(result.minimumNextBid)
  if (result.extended) ElMessage.info(`反狙击延时至 ${formatShanghaiTime(result.endAt)}`)
  ElMessage.success(result.leading ? '代理规则已生效，你当前领先。' : '代理规则已生效，当前展示价已按规则更新。')
}

async function loadProxyBid(): Promise<void> {
  if (!detail.value || detail.value.sessionStatus !== 'OPEN' || !detail.value.myRegistration ||
      detail.value.myRegistration.status !== 'REGISTERED' || typeof getMyAuctionProxyBid !== 'function') return
  try {
    const result = await getMyAuctionProxyBid(detail.value.auctionId)
    proxyBid.value = result.data
    proxyMaxAmount.value = result.data ? String(result.data.maxAmount) : ''
  } catch (error) {
    actionError.value = normalizeApiError(error)
  }
}

function validateProxyMax(): string | null {
  const value = proxyMaxAmount.value.trim()
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return '代理最高价必须是最多两位小数的正数。'
  if (Number(value) < Number(detail.value?.minimumNextBid ?? 0)) return '代理最高价不能低于当前最低报价。'
  return null
}

async function saveProxyBid(): Promise<void> {
  if (!detail.value || proxyLoading.value || typeof upsertAuctionProxyBid !== 'function') return
  proxyValidation.value = validateProxyMax()
  if (proxyValidation.value) return
  proxyLoading.value = true
  actionError.value = null
  try {
    const result = await upsertAuctionProxyBid(
      detail.value.auctionId, proxyMaxAmount.value.trim(), createClientRequestId())
    lastTraceId.value = result.traceId ?? lastTraceId.value
    applyProxyResult(result.data)
  } catch (error) {
    actionError.value = normalizeApiError(error)
  } finally {
    proxyLoading.value = false
  }
}

async function disableProxyBid(): Promise<void> {
  if (!detail.value || proxyLoading.value || typeof disableAuctionProxyBid !== 'function') return
  proxyLoading.value = true
  actionError.value = null
  try {
    const result = await disableAuctionProxyBid(detail.value.auctionId, createClientRequestId())
    lastTraceId.value = result.traceId ?? lastTraceId.value
    applyProxyResult(result.data)
    proxyBid.value = null
    proxyMaxAmount.value = ''
    ElMessage.info('代理规则已停用，历史已接受报价不会撤回。')
  } catch (error) {
    actionError.value = normalizeApiError(error)
  } finally {
    proxyLoading.value = false
  }
}

function connectRealtime(): void {
  realtimeClient?.stop()
  realtimeClient = new RealtimeAuctionClient({
    onState: (state, message) => {
      realtimeState.value = state
      realtimeDetail.value = message ?? null
    },
    onSnapshot: applyRealtimeSnapshot,
    onBidAccepted: applyRealtimeBid,
    onAuctionExtended: applyRealtimeExtended,
    onAuctionClosed: applyRealtimeClosed,
  })
  const lastKnownSequenceNo = Math.max(
    detail.value?.bidCount ?? 0,
    ...bids.value.map((bid) => bid.sequenceNo),
  )
  realtimeClient.start(auctionId.value, Math.max(0, lastKnownSequenceNo))
}

function reconnectRealtime(): void {
  realtimeClient?.reconnectNow()
}

function handleBrowserOffline(): void {
  realtimeClient?.notifyOffline()
}

function handleBrowserOnline(): void {
  realtimeClient?.notifyOnline()
}

function handleVisibilityChange(): void {
  if (document.visibilityState === 'hidden') {
    realtimeClient?.pause()
  } else {
    realtimeClient?.resume()
  }
}

function cancelRegistrationPoll(): void {
  if (registrationPollTimer) {
    clearTimeout(registrationPollTimer)
    registrationPollTimer = null
  }
}

function toRegistrationSummary(registration: AuctionRegistrationRecord) {
  return {
    registrationId: registration.registrationId,
    status: registration.status,
    failureCode: registration.failureCode,
    registeredAt: registration.registeredAt,
  }
}

function scheduleRegistrationPoll(): void {
  cancelRegistrationPoll()
  if (
    detail.value?.myRegistration?.status !== 'PENDING_HOLD' ||
    registrationPollRounds.value >= MAX_REGISTRATION_POLL_ROUNDS
  ) return
  registrationPollTimer = setTimeout(() => void pollRegistration(), REGISTRATION_POLL_INTERVAL_MS)
}

async function refreshWallet(): Promise<void> {
  try {
    await authStore.loadDashboard()
  } catch (error) {
    actionError.value = normalizeApiError(error)
  }
}

async function pollRegistration(): Promise<void> {
  const registration = detail.value?.myRegistration
  if (
    !registration || registration.status !== 'PENDING_HOLD' || registrationPolling.value ||
    registrationPollRounds.value >= MAX_REGISTRATION_POLL_ROUNDS
  ) return

  registrationPolling.value = true
  registrationPollRounds.value += 1
  try {
    const result = await getAuctionRegistration(registration.registrationId)
    lastTraceId.value = result.traceId
    if (detail.value) detail.value.myRegistration = toRegistrationSummary(result.data)
    if (result.data.status === 'REGISTERED') {
      ElMessage.success('保证金冻结成功，报名已完成。')
      await Promise.all([loadDetail(false), refreshWallet()])
    } else if (result.data.status === 'FAILED') {
      await refreshWallet()
    }
  } catch (error) {
    actionError.value = normalizeApiError(error)
  } finally {
    registrationPolling.value = false
    scheduleRegistrationPoll()
  }
}

async function loadDetail(resetRegistrationPolling = true): Promise<void> {
  cancelRegistrationPoll()
  loading.value = true
  pageError.value = null
  if (resetRegistrationPolling) registrationPollRounds.value = 0
  try {
    const [detailResult, historyResult] = await Promise.all([
      getAuctionDetail(auctionId.value),
      getAuctionBidHistory(auctionId.value, 1, 10),
    ])
    detail.value = detailResult.data
    bids.value = historyResult.data.items
    bidAmount.value = String(detailResult.data.minimumNextBid)
    bidValidation.value = null
    lastTraceId.value = historyResult.traceId ?? detailResult.traceId
    scheduleRegistrationPoll()
    if (detailResult.data.sessionStatus === 'OPEN') {
      connectRealtime()
      void loadProxyBid()
    } else {
      realtimeClient?.stop()
      realtimeClient = null
      realtimeState.value = 'idle'
      realtimeDetail.value = null
    }
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    loading.value = false
  }
}

async function register(): Promise<void> {
  if (!detail.value || !canRegister.value || registering.value) return
  registering.value = true
  actionError.value = null
  registrationPollRounds.value = 0
  try {
    const result = await registerForAuction(detail.value.auctionId)
    detail.value.myRegistration = toRegistrationSummary(result.data)
    lastTraceId.value = result.traceId
    if (result.data.status === 'REGISTERED') {
      ElMessage.success('报名成功，保证金已冻结。')
      await refreshWallet()
    } else if (result.data.status === 'PENDING_HOLD') {
      ElMessage.info('保证金正在处理，页面会有限次数查询结果。')
    }
    await loadDetail(false)
  } catch (error) {
    actionError.value = normalizeApiError(error)
  } finally {
    registering.value = false
  }
}

function isBidConflict(value: unknown): value is AuctionBidConflict {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<AuctionBidConflict>
  return candidate.auctionId === auctionId.value &&
    (typeof candidate.minimumNextBid === 'string' || typeof candidate.minimumNextBid === 'number') &&
    typeof candidate.bidCount === 'number'
}

async function placeBid(): Promise<void> {
  if (!detail.value || !canBid.value || bidding.value) return
  const amount = bidAmount.value.trim()
  const validation = validateBidAmount(amount, detail.value.minimumNextBid)
  if (validation) {
    bidValidation.value = validation
    return
  }

  if (!bidAttempt || bidAttempt.amount !== amount) {
    bidAttempt = { amount, requestId: createClientRequestId() }
  }
  bidding.value = true
  actionError.value = null
  bidValidation.value = null
  try {
    const result = await placeAuctionBid(detail.value.auctionId, amount, bidAttempt.requestId)
    lastTraceId.value = result.traceId
    bidAttempt = null
    ElMessage.success(`报价 ${formatMoney(result.data.amount)} 已被 MySQL 接受。`)
    await loadDetail(false)
  } catch (error) {
    actionError.value = normalizeApiError(error)
    if (isBidConflict(actionError.value.data) && detail.value) {
      detail.value.currentPrice = actionError.value.data.currentPrice
      detail.value.displayPrice = actionError.value.data.currentPrice ?? detail.value.startPrice
      detail.value.minimumNextBid = actionError.value.data.minimumNextBid
      detail.value.bidCount = actionError.value.data.bidCount
      detail.value.sessionStatus = actionError.value.data.status
      bidAmount.value = String(actionError.value.data.minimumNextBid)
    }
    if (actionError.value.status !== 0 && actionError.value.status < 500) bidAttempt = null
  } finally {
    bidding.value = false
  }
}

watch(auctionId, () => {
  realtimeClient?.stop()
  realtimeClient = null
  void loadDetail()
})
onMounted(() => {
  window.addEventListener('offline', handleBrowserOffline)
  window.addEventListener('online', handleBrowserOnline)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void loadDetail()
})
onBeforeUnmount(() => {
  cancelRegistrationPoll()
  window.removeEventListener('offline', handleBrowserOffline)
  window.removeEventListener('online', handleBrowserOnline)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  realtimeClient?.stop()
})
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main auction-detail-main">
      <RouterLink class="back-link" :to="{ name: 'auctions' }">← 返回竞价大厅</RouterLink>
      <ApiErrorNotice :error="pageError" />
      <ElSkeleton v-if="loading && !detail" class="detail-skeleton" :rows="8" animated />

      <template v-else-if="detail">
        <section class="detail-hero">
          <div class="detail-gallery">
            <div v-if="detail.images.length > 0" class="detail-gallery__grid">
              <figure v-for="image in detail.images" :key="image.imageId">
                <img v-if="image.previewUrl" :src="image.previewUrl" :alt="`${detail.title} 图片 ${image.sortOrder + 1}`" />
                <div v-else class="auction-card__placeholder">图片暂不可用</div>
              </figure>
            </div>
            <div v-else class="detail-gallery__empty">暂无可预览图片</div>
          </div>

          <article class="detail-summary">
            <div class="detail-summary__tags">
              <ElTag :class="`status-tag status-tag--${detail.sessionStatus.toLowerCase()}`" effect="dark">{{ sessionStatusLabel(detail.sessionStatus) }}</ElTag>
              <ElTag effect="plain">{{ detail.category }}</ElTag>
              <ElTag effect="plain">{{ conditionLabel(detail.itemCondition) }}</ElTag>
            </div>
            <p class="section-eyebrow">Auction #{{ detail.auctionId }}</p>
            <h1>{{ detail.title }}</h1>
            <p class="detail-summary__description">{{ detail.description }}</p>
            <div class="detail-current-price">
              <span>{{ detail.sessionStatus === 'CLOSED_SOLD' ? '成交价' : detail.sessionStatus === 'CLOSED_UNSOLD' ? '最终结果' : '当前展示价' }}</span>
              <strong>{{ detail.sessionStatus === 'CLOSED_UNSOLD' ? '流拍' : formatMoney(detail.finalPrice ?? detail.displayPrice) }}</strong>
              <small v-if="detail.sessionStatus === 'CLOSED_SOLD'">{{ detail.wonByCurrentUser ? '恭喜，你是本场买家' : '本场竞价已成交' }}</small>
              <small v-else-if="detail.sessionStatus === 'CLOSED_UNSOLD'">本场没有产生有效成交</small>
              <small v-else>下一笔最低 {{ formatMoney(detail.minimumNextBid) }}</small>
            </div>
            <dl class="detail-facts">
              <div><dt>起拍价</dt><dd>{{ formatMoney(detail.startPrice) }}</dd></div>
              <div><dt>加价幅度</dt><dd>{{ formatMoney(detail.bidIncrement) }}</dd></div>
              <div><dt>保证金</dt><dd>{{ formatMoney(detail.depositAmount) }}</dd></div>
              <div><dt>报价次数</dt><dd>{{ detail.bidCount }}</dd></div>
              <div><dt>开始时间</dt><dd>{{ formatShanghaiTime(detail.startAt) }}</dd></div>
              <div><dt>结束时间</dt><dd>{{ formatShanghaiTime(detail.endAt) }}</dd></div>
              <div v-if="detail.closedAt"><dt>关拍时间</dt><dd>{{ formatShanghaiTime(detail.closedAt) }}</dd></div>
            </dl>

            <div v-if="detail.sessionStatus === 'CLOSED_SOLD' || detail.sessionStatus === 'CLOSED_UNSOLD'" class="auction-result-notice">
              <strong>{{ detail.sessionStatus === 'CLOSED_SOLD' ? '竞价已成交' : '竞价已流拍' }}</strong>
              <span v-if="detail.wonByCurrentUser">订单会在保证金结算后出现在“我的订单”。</span>
              <span v-else-if="detail.ownedByCurrentUser && detail.sessionStatus === 'CLOSED_SOLD'">可前往“我的订单”的卖出视图查看订单和入账状态。</span>
              <span v-else>此处展示 Auction 服务记录的最终关拍结果。</span>
              <RouterLink v-if="detail.wonByCurrentUser || detail.ownedByCurrentUser" :to="{ name: 'my-orders', query: detail.ownedByCurrentUser ? { view: 'sales' } : {} }">查看相关订单</RouterLink>
            </div>

            <section class="auction-action-panel" aria-label="报名与出价">
              <ApiErrorNotice :error="actionError" />
              <div v-if="detail.ownedByCurrentUser" class="detail-notice">这是你发布的拍品，卖家不能报名或出价。</div>
              <template v-else-if="!detail.myRegistration">
                <div v-if="canRegister" class="auction-action-panel__row">
                  <div><strong>冻结 {{ formatMoney(detail.depositAmount) }} 保证金后报名</strong><small>报名结果由 Account 钱包最终确认</small></div>
                  <ElButton data-test="register-auction" :loading="registering" type="primary" @click="register">立即报名</ElButton>
                </div>
                <div v-else class="detail-notice detail-notice--muted">当前场次已不在可报名时间内。</div>
              </template>
              <template v-else>
                <div class="auction-action-panel__status">
                  <span>报名状态：</span>
                  <ElTag :type="detail.myRegistration.status === 'REGISTERED' ? 'success' : detail.myRegistration.status === 'FAILED' ? 'danger' : 'warning'" effect="plain">{{ registrationStatusLabel(detail.myRegistration.status) }}</ElTag>
                  <span v-if="detail.myRegistration.status === 'PENDING_HOLD'">第 {{ registrationPollRounds }}/{{ MAX_REGISTRATION_POLL_ROUNDS }} 次查询 <small v-if="registrationPolling">处理中</small></span>
                  <span v-else-if="detail.myRegistration.status === 'FAILED'">{{ registrationFailureLabel(detail.myRegistration.failureCode) }}</span>
                  <RouterLink :to="{ name: 'my-registrations' }">查看我的报名</RouterLink>
                </div>

                <div v-if="detail.myRegistration.status === 'REGISTERED'" class="manual-bid-form">
                  <template v-if="canBid">
                    <label for="bid-amount">手动报价</label>
                    <input id="bid-amount" v-model="bidAmount" class="native-field" inputmode="decimal" autocomplete="off" />
                    <ElButton data-test="place-bid" :loading="bidding" type="primary" @click="placeBid">提交报价</ElButton>
                    <small>最低 {{ formatMoney(detail.minimumNextBid) }}；结果由 MySQL CAS 最终裁决。</small>
                    <p v-if="bidValidation" class="admin-review-validation">{{ bidValidation }}</p>
                  </template>
                  <div v-else-if="detail.sessionStatus === 'SCHEDULED'" class="detail-notice detail-notice--muted">报名已完成，开拍后可手动出价。</div>
                  <div v-else class="detail-notice detail-notice--muted">场次已经结束，不能继续出价。</div>
                </div>
                <div v-if="detail.sessionStatus === 'OPEN'" class="proxy-bid-panel" data-test="proxy-bid-panel">
                  <div class="proxy-bid-panel__heading">
                    <div>
                      <strong>代理竞价</strong>
                      <small>只对你可见。系统会自动用维持领先所需的最小金额出价。</small>
                    </div>
                    <ElTag v-if="proxyBid?.status === 'ACTIVE'" type="success" effect="plain">代理有效</ElTag>
                  </div>
                  <div class="proxy-bid-panel__row">
                    <label for="proxy-max-amount">我的最高价</label>
                    <input id="proxy-max-amount" v-model="proxyMaxAmount" class="native-field" inputmode="decimal" autocomplete="off" placeholder="例如 1500.00" />
                    <ElButton :loading="proxyLoading" type="primary" @click="saveProxyBid">{{ proxyBid?.status === 'ACTIVE' ? '更新代理' : '启用代理' }}</ElButton>
                    <ElButton v-if="proxyBid?.status === 'ACTIVE'" :loading="proxyLoading" plain type="danger" @click="disableProxyBid">停用</ElButton>
                  </div>
                  <p v-if="proxyValidation" class="admin-review-validation">{{ proxyValidation }}</p>
                  <p v-if="proxyBid?.status === 'ACTIVE'" class="proxy-bid-panel__result">
                    {{ proxyLeading ? '当前领先；新的有效报价会触发最小必要反击。' : '报价有效但被代理超过；代理仍有效，直到达到你的最高价。' }}
                  </p>
                </div>
              </template>
            </section>
            <ElButton :loading="loading" plain @click="loadDetail()">手动刷新最终状态</ElButton>
            <div class="realtime-status" :class="`realtime-status--${realtimeState}`">
              <span class="realtime-status__dot" aria-hidden="true" />
              <strong>
                {{ realtimeState === 'live' ? '实时连接' : realtimeState === 'recovering' ? '正在恢复' : realtimeState === 'offline' ? '实时离线' : realtimeState === 'connecting' ? '连接中' : '未连接' }}
              </strong>
              <span v-if="realtimeDetail">{{ realtimeDetail }}</span>
              <ElButton v-if="realtimeState === 'offline'" text type="primary" @click="reconnectRealtime">重新连接</ElButton>
            </div>
            <p class="detail-refresh-note">实时消息用于快速更新展示；报价、关拍和资金结果仍以 HTTP/MySQL 最终裁决为准。</p>
          </article>
        </section>

        <section class="bid-history" aria-labelledby="bid-history-title">
          <div class="bid-history__heading"><div><p class="section-eyebrow">MySQL final records</p><h2 id="bid-history-title">最近报价</h2></div><span>身份已脱敏，仅标记自己的报价</span></div>
          <div v-if="bids.length > 0" class="bid-list">
            <article v-for="bid in bids" :key="bid.bidId" :class="['bid-row', { 'bid-row--mine': bid.mine }]">
              <span class="bid-row__sequence">#{{ bid.sequenceNo }}</span><strong>{{ formatMoney(bid.amount) }}</strong><span>{{ formatShanghaiTime(bid.createdAt) }}</span><ElTag v-if="bid.mine" effect="plain">我的报价</ElTag><span v-else>匿名竞拍者</span>
            </article>
          </div>
          <div v-else class="bid-history__empty">还没有报价，首笔最低为 {{ formatMoney(detail.minimumNextBid) }}。</div>
        </section>
        <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
      </template>
    </main>
  </div>
</template>
