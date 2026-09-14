<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElSkeleton, ElTag } from 'element-plus'

import { getAuctionBidHistory, getAuctionDetail } from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import {
  conditionLabel,
  formatMoney,
  formatShanghaiTime,
  registrationStatusLabel,
  sessionStatusLabel,
} from '@/features/auction/presentation'
import type { AuctionBidHistoryItem, AuctionDetail } from '@/types/auction'

const route = useRoute()
const detail = ref<AuctionDetail | null>(null)
const bids = ref<AuctionBidHistoryItem[]>([])
const loading = ref(false)
const pageError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)

const auctionId = computed(() => String(route.params.auctionId ?? ''))

async function loadDetail(): Promise<void> {
  loading.value = true
  pageError.value = null
  try {
    const [detailResult, historyResult] = await Promise.all([
      getAuctionDetail(auctionId.value),
      getAuctionBidHistory(auctionId.value, 1, 10),
    ])
    detail.value = detailResult.data
    bids.value = historyResult.data.items
    lastTraceId.value = historyResult.traceId ?? detailResult.traceId
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    loading.value = false
  }
}

watch(auctionId, () => loadDetail())
onMounted(loadDetail)
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
              <ElTag :class="`status-tag status-tag--${detail.sessionStatus.toLowerCase()}`" effect="dark">
                {{ sessionStatusLabel(detail.sessionStatus) }}
              </ElTag>
              <ElTag effect="plain">{{ detail.category }}</ElTag>
              <ElTag effect="plain">{{ conditionLabel(detail.itemCondition) }}</ElTag>
            </div>
            <p class="section-eyebrow">Auction #{{ detail.auctionId }}</p>
            <h1>{{ detail.title }}</h1>
            <p class="detail-summary__description">{{ detail.description }}</p>
            <div class="detail-current-price">
              <span>当前展示价</span>
              <strong>{{ formatMoney(detail.displayPrice) }}</strong>
              <small>下一笔最低 {{ formatMoney(detail.minimumNextBid) }}</small>
            </div>
            <dl class="detail-facts">
              <div><dt>起拍价</dt><dd>{{ formatMoney(detail.startPrice) }}</dd></div>
              <div><dt>加价幅度</dt><dd>{{ formatMoney(detail.bidIncrement) }}</dd></div>
              <div><dt>保证金</dt><dd>{{ formatMoney(detail.depositAmount) }}</dd></div>
              <div><dt>报价次数</dt><dd>{{ detail.bidCount }}</dd></div>
              <div><dt>开始时间</dt><dd>{{ formatShanghaiTime(detail.startAt) }}</dd></div>
              <div><dt>结束时间</dt><dd>{{ formatShanghaiTime(detail.endAt) }}</dd></div>
            </dl>
            <div v-if="detail.ownedByCurrentUser" class="detail-notice">这是你发布的拍品，卖家不能报名或出价。</div>
            <div v-else-if="detail.myRegistration" class="detail-notice">
              报名状态：{{ registrationStatusLabel(detail.myRegistration.status) }}
            </div>
            <div v-else class="detail-notice detail-notice--muted">尚未报名；报名与出价操作将在下一步接入。</div>
            <ElButton :loading="loading" plain @click="loadDetail">刷新最终状态</ElButton>
          </article>
        </section>

        <section class="bid-history" aria-labelledby="bid-history-title">
          <div class="bid-history__heading">
            <div>
              <p class="section-eyebrow">MySQL final records</p>
              <h2 id="bid-history-title">最近报价</h2>
            </div>
            <span>身份已脱敏，仅标记自己的报价</span>
          </div>
          <div v-if="bids.length > 0" class="bid-list">
            <article v-for="bid in bids" :key="bid.bidId" :class="['bid-row', { 'bid-row--mine': bid.mine }]">
              <span class="bid-row__sequence">#{{ bid.sequenceNo }}</span>
              <strong>{{ formatMoney(bid.amount) }}</strong>
              <span>{{ formatShanghaiTime(bid.createdAt) }}</span>
              <ElTag v-if="bid.mine" effect="plain">我的报价</ElTag>
              <span v-else>匿名竞拍者</span>
            </article>
          </div>
          <div v-else class="bid-history__empty">还没有报价，首笔最低为 {{ formatMoney(detail.minimumNextBid) }}。</div>
        </section>
        <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
      </template>
    </main>
  </div>
</template>
