<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElMessage, ElSkeleton, ElTag } from 'element-plus'

import {
  getAuctionBidHistory,
  getAuctionDetail,
  getAuctionRegistration,
  placeAuctionBid,
  registerForAuction,
} from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import { createClientRequestId } from '@/api/http'
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
} from '@/types/auction'

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
const pageError = ref<ApiError | null>(null)
const actionError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)
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

watch(auctionId, () => void loadDetail())
onMounted(() => void loadDetail())
onBeforeUnmount(cancelRegistrationPoll)
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
              </template>
            </section>
            <ElButton :loading="loading" plain @click="loadDetail()">手动刷新最终状态</ElButton>
            <p class="detail-refresh-note">阶段 03 不提供实时推送；价格和关拍结果以手动刷新为准。</p>
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
