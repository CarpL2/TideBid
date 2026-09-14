<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElPagination, ElSkeleton, ElSkeletonItem, ElTag } from 'element-plus'

import { getAuctionLobby } from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import {
  conditionLabel,
  formatMoney,
  formatShanghaiTime,
  sessionStatusLabel,
} from '@/features/auction/presentation'
import type { AuctionLobbyItem } from '@/types/auction'

const PAGE_SIZE = 12
const items = ref<AuctionLobbyItem[]>([])
const currentPage = ref(1)
const total = ref(0)
const loading = ref(false)
const pageError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)

async function loadLobby(page = currentPage.value): Promise<void> {
  loading.value = true
  pageError.value = null
  try {
    const result = await getAuctionLobby(page, PAGE_SIZE)
    items.value = result.data.items
    currentPage.value = result.data.page
    total.value = result.data.total
    lastTraceId.value = result.traceId
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    loading.value = false
  }
}

onMounted(() => loadLobby())
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main">
      <div class="auction-heading">
        <div>
          <p class="section-eyebrow">Live auction floor</p>
          <h1>竞价大厅</h1>
          <p>所有场次和价格都来自 MySQL 最终状态，当前阶段需登录浏览。</p>
        </div>
        <ElButton :loading="loading" plain @click="loadLobby()">刷新场次</ElButton>
      </div>

      <ApiErrorNotice :error="pageError" />

      <div v-if="loading && items.length === 0" class="auction-grid" aria-label="正在加载拍品">
        <ElSkeleton v-for="index in 6" :key="index" class="auction-card" animated>
          <template #template>
            <ElSkeletonItem variant="image" class="auction-card__skeleton-image" />
            <ElSkeletonItem variant="h3" style="width: 72%" />
            <ElSkeletonItem variant="text" style="width: 45%" />
          </template>
        </ElSkeleton>
      </div>

      <section v-else-if="items.length > 0" class="auction-grid" aria-label="可参与的拍卖场次">
        <RouterLink
          v-for="item in items"
          :key="item.auctionId"
          class="auction-card"
          :to="{ name: 'auction-detail', params: { auctionId: item.auctionId } }"
        >
          <div class="auction-card__media">
            <img v-if="item.coverImage?.previewUrl" :src="item.coverImage.previewUrl" :alt="item.title" />
            <div v-else class="auction-card__placeholder" aria-hidden="true">TB</div>
            <ElTag :class="`status-tag status-tag--${item.sessionStatus.toLowerCase()}`" effect="dark">
              {{ sessionStatusLabel(item.sessionStatus) }}
            </ElTag>
          </div>
          <div class="auction-card__body">
            <div class="auction-card__meta">
              <span>{{ item.category }}</span>
              <span>{{ conditionLabel(item.itemCondition) }}</span>
            </div>
            <h2>{{ item.title }}</h2>
            <div class="auction-card__price">
              <span>当前展示价</span>
              <strong>{{ formatMoney(item.displayPrice) }}</strong>
            </div>
            <dl>
              <div><dt>下一最低价</dt><dd>{{ formatMoney(item.minimumNextBid) }}</dd></div>
              <div><dt>报价次数</dt><dd>{{ item.bidCount }}</dd></div>
            </dl>
            <p class="auction-card__time">{{ formatShanghaiTime(item.startAt) }} 开始</p>
          </div>
        </RouterLink>
      </section>

      <section v-else-if="!pageError" class="empty-state">
        <p class="section-eyebrow">No active lots</p>
        <h2>暂时没有可展示的拍卖</h2>
        <p>已审核且进入排期的拍品会出现在这里。</p>
        <ElButton type="primary" @click="loadLobby(1)">重新加载</ElButton>
      </section>

      <footer v-if="total > PAGE_SIZE" class="auction-pagination">
        <ElPagination
          :current-page="currentPage"
          :page-size="PAGE_SIZE"
          :total="total"
          background
          layout="prev, pager, next"
          @current-change="loadLobby"
        />
      </footer>
      <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
    </main>
  </div>
</template>
