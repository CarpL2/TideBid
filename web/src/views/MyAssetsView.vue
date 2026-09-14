<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElMessage, ElPagination, ElTag } from 'element-plus'

import { getMyAuctionAssets, submitAuctionAsset } from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import { conditionLabel, formatMoney, formatShanghaiTime, reviewStatusLabel } from '@/features/auction/presentation'
import type { AuctionAssetSummary } from '@/types/auction'

const PAGE_SIZE = 12
const items = ref<AuctionAssetSummary[]>([])
const currentPage = ref(1)
const total = ref(0)
const loading = ref(false)
const submittingId = ref<string | null>(null)
const pageError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)

async function loadAssets(page = currentPage.value): Promise<void> {
  loading.value = true
  pageError.value = null
  try {
    const result = await getMyAuctionAssets(page, PAGE_SIZE)
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

function editable(item: AuctionAssetSummary): boolean {
  return item.reviewStatus === 'DRAFT' || item.reviewStatus === 'REJECTED'
}

async function submitForReview(item: AuctionAssetSummary): Promise<void> {
  if (submittingId.value) return
  submittingId.value = item.itemId
  pageError.value = null
  try {
    const result = await submitAuctionAsset(item.itemId, item.itemVersion, item.sessionVersion)
    lastTraceId.value = result.traceId
    ElMessage.success('拍品已提交审核。')
    await loadAssets(currentPage.value)
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    submittingId.value = null
  }
}

onMounted(() => loadAssets())
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main">
      <div class="auction-heading">
        <div>
          <p class="section-eyebrow">Seller workspace</p>
          <h1>我的拍品</h1>
          <p>草稿、审核状态和场次版本都来自 Auction 服务。</p>
        </div>
        <RouterLink class="primary-link-button" :to="{ name: 'asset-create' }">创建拍品</RouterLink>
      </div>
      <ApiErrorNotice :error="pageError" />

      <section v-if="items.length > 0" class="seller-asset-list" aria-label="我的拍品列表">
        <article v-for="item in items" :key="item.itemId" class="seller-asset-row">
          <div class="seller-asset-row__cover">
            <img v-if="item.coverImage?.previewUrl" :src="item.coverImage.previewUrl" :alt="item.title" />
            <div v-else class="auction-card__placeholder">TB</div>
          </div>
          <div class="seller-asset-row__main">
            <div class="seller-asset-row__tags">
              <ElTag effect="plain">{{ reviewStatusLabel(item.reviewStatus) }}</ElTag>
              <span>{{ item.category }} · {{ conditionLabel(item.itemCondition) }}</span>
            </div>
            <h2>{{ item.title }}</h2>
            <p>{{ formatShanghaiTime(item.startAt) }} — {{ formatShanghaiTime(item.endAt) }}</p>
          </div>
          <dl class="seller-asset-row__facts">
            <div><dt>起拍价</dt><dd>{{ formatMoney(item.startPrice) }}</dd></div>
            <div><dt>报价</dt><dd>{{ item.currentPrice ? formatMoney(item.currentPrice) : '暂无' }}</dd></div>
          </dl>
          <div class="seller-asset-row__actions">
            <RouterLink
              v-if="editable(item)"
              class="secondary-link-button"
              :to="{ name: 'asset-edit', params: { assetId: item.itemId } }"
            >编辑草稿</RouterLink>
            <ElButton
              v-if="editable(item)"
              :loading="submittingId === item.itemId"
              type="primary"
              @click="submitForReview(item)"
            >提交审核</ElButton>
            <span v-else class="seller-asset-row__locked">当前状态不可编辑</span>
          </div>
        </article>
      </section>

      <section v-else-if="!loading && !pageError" class="empty-state">
        <h2>还没有拍品</h2>
        <p>先上传图片并保存第一份拍品草稿。</p>
        <RouterLink class="primary-link-button" :to="{ name: 'asset-create' }">创建第一件拍品</RouterLink>
      </section>

      <footer v-if="total > PAGE_SIZE" class="auction-pagination">
        <ElPagination
          :current-page="currentPage"
          :page-size="PAGE_SIZE"
          :total="total"
          background
          layout="prev, pager, next"
          @current-change="loadAssets"
        />
      </footer>
      <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
    </main>
  </div>
</template>
