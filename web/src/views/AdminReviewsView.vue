<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElButton, ElMessage, ElPagination, ElTag } from 'element-plus'

import {
  getAuctionAsset,
  getPendingAuctionAssets,
  reviewAuctionAsset,
} from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import {
  conditionLabel,
  formatMoney,
  formatShanghaiTime,
} from '@/features/auction/presentation'
import { useAuthStore } from '@/stores/auth'
import type { AdminPendingAssetSummary, AuctionAssetDetail } from '@/types/auction'

const PAGE_SIZE = 12
const authStore = useAuthStore()
const items = ref<AdminPendingAssetSummary[]>([])
const currentPage = ref(1)
const total = ref(0)
const identityResolved = ref(false)
const queueLoading = ref(false)
const detailLoading = ref(false)
const reviewing = ref(false)
const selected = ref<AuctionAssetDetail | null>(null)
const comment = ref('')
const validationMessage = ref<string | null>(null)
const pageError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)

const isAdmin = computed(() => authStore.profile?.roles.includes('ADMIN') ?? false)

async function loadQueue(page = currentPage.value): Promise<void> {
  queueLoading.value = true
  pageError.value = null
  try {
    const result = await getPendingAuctionAssets(page, PAGE_SIZE)
    items.value = result.data.items
    currentPage.value = result.data.page
    total.value = result.data.total
    lastTraceId.value = result.traceId
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    queueLoading.value = false
  }
}

async function openReview(item: AdminPendingAssetSummary): Promise<void> {
  detailLoading.value = true
  pageError.value = null
  validationMessage.value = null
  comment.value = ''
  try {
    const result = await getAuctionAsset(item.itemId)
    lastTraceId.value = result.traceId
    if (
      result.data.reviewStatus !== 'PENDING_REVIEW' ||
      result.data.submissionVersion !== item.submissionVersion
    ) {
      selected.value = null
      ElMessage.warning('审核队列已经变化，已为你刷新。')
      await loadQueue(currentPage.value)
      return
    }
    selected.value = result.data
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    detailLoading.value = false
  }
}

function closeReview(): void {
  selected.value = null
  comment.value = ''
  validationMessage.value = null
}

async function submitDecision(decision: 'APPROVE' | 'REJECT'): Promise<void> {
  if (!selected.value || reviewing.value) return
  const normalizedComment = comment.value.trim()
  if (normalizedComment.length > 500) {
    validationMessage.value = '审核意见不能超过 500 个字符。'
    return
  }
  if (decision === 'REJECT' && normalizedComment.length === 0) {
    validationMessage.value = '驳回时必须填写审核意见。'
    return
  }

  reviewing.value = true
  validationMessage.value = null
  pageError.value = null
  try {
    const result = await reviewAuctionAsset(selected.value.itemId, {
      decision,
      submissionVersion: selected.value.submissionVersion,
      comment: normalizedComment || null,
    })
    lastTraceId.value = result.traceId
    ElMessage.success(decision === 'APPROVE' ? '拍品审核通过。' : '拍品已驳回。')
    closeReview()
    await loadQueue(currentPage.value)
  } catch (error) {
    pageError.value = normalizeApiError(error)
    if (pageError.value.status === 409) {
      closeReview()
      await loadQueue(currentPage.value)
    }
  } finally {
    reviewing.value = false
  }
}

onMounted(async () => {
  try {
    await authStore.loadProfile()
    if (isAdmin.value) {
      await loadQueue(1)
    }
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    identityResolved.value = true
  }
})
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main admin-review-main">
      <div class="auction-heading">
        <div>
          <p class="section-eyebrow">Review console</p>
          <h1>拍品审核</h1>
          <p>按送审时间处理待审核拍品，每次裁决都绑定当前送审版本。</p>
        </div>
        <ElButton v-if="isAdmin" :loading="queueLoading" plain @click="loadQueue(currentPage)">
          刷新队列
        </ElButton>
      </div>
      <ApiErrorNotice :error="pageError" />

      <section v-if="identityResolved && !isAdmin" class="empty-state" data-test="admin-denied">
        <h2>当前账号没有审核权限</h2>
        <p>只有拥有 ADMIN 角色的账号才能查看待审核内容和执行裁决。</p>
        <RouterLink class="secondary-link-button" :to="{ name: 'auctions' }">返回竞价大厅</RouterLink>
      </section>

      <div v-else-if="isAdmin" class="admin-review-layout">
        <section class="admin-review-queue" aria-label="待审核拍品">
          <article v-for="item in items" :key="item.itemId" class="admin-review-row">
            <div class="admin-review-row__cover">
              <img v-if="item.coverImage?.previewUrl" :src="item.coverImage.previewUrl" :alt="item.title" />
              <div v-else class="auction-card__placeholder">TB</div>
            </div>
            <div class="admin-review-row__body">
              <div class="seller-asset-row__tags">
                <ElTag effect="plain">第 {{ item.submissionVersion }} 版</ElTag>
                <span>卖家 {{ item.sellerId }}</span>
              </div>
              <h2>{{ item.title }}</h2>
              <p>{{ item.category }} · {{ conditionLabel(item.itemCondition) }}</p>
              <p>送审于 {{ formatShanghaiTime(item.submittedAt) }}</p>
            </div>
            <div class="admin-review-row__price">
              <small>起拍价</small>
              <strong>{{ formatMoney(item.startPrice) }}</strong>
            </div>
            <ElButton data-test="open-review" type="primary" @click="openReview(item)">查看审核</ElButton>
          </article>

          <section v-if="!queueLoading && items.length === 0 && !pageError" class="empty-state">
            <h2>审核队列为空</h2>
            <p>当前没有等待处理的拍品。</p>
          </section>

          <footer v-if="total > PAGE_SIZE" class="auction-pagination">
            <ElPagination
              :current-page="currentPage"
              :page-size="PAGE_SIZE"
              :total="total"
              background
              layout="prev, pager, next"
              @current-change="loadQueue"
            />
          </footer>
        </section>

        <aside v-if="selected" class="admin-review-detail" aria-label="审核详情">
          <div class="admin-review-detail__heading">
            <div>
              <p class="section-eyebrow">Submission {{ selected.submissionVersion }}</p>
              <h2>{{ selected.title }}</h2>
            </div>
            <ElButton text @click="closeReview">关闭</ElButton>
          </div>
          <div class="admin-review-gallery">
            <figure v-for="image in selected.images" :key="image.imageId">
              <img v-if="image.previewUrl" :src="image.previewUrl" :alt="selected.title" />
              <div v-else class="auction-card__placeholder">图片暂不可预览</div>
            </figure>
          </div>
          <p class="admin-review-description">{{ selected.description }}</p>
          <dl class="detail-facts admin-review-facts">
            <div><dt>类目 / 成色</dt><dd>{{ selected.category }} / {{ conditionLabel(selected.itemCondition) }}</dd></div>
            <div><dt>卖家编号</dt><dd>{{ selected.sellerId }}</dd></div>
            <div><dt>起拍 / 加价</dt><dd>{{ formatMoney(selected.startPrice) }} / {{ formatMoney(selected.bidIncrement) }}</dd></div>
            <div><dt>保证金</dt><dd>{{ formatMoney(selected.depositAmount) }}</dd></div>
            <div><dt>开始时间</dt><dd>{{ formatShanghaiTime(selected.startAt) }}</dd></div>
            <div><dt>结束时间</dt><dd>{{ formatShanghaiTime(selected.endAt) }}</dd></div>
          </dl>
          <label class="admin-review-comment">
            <span>审核意见 <small>驳回时必填，最多 500 字</small></span>
            <textarea v-model="comment" class="native-field" rows="4" maxlength="500" />
          </label>
          <p v-if="validationMessage" class="admin-review-validation">{{ validationMessage }}</p>
          <div class="admin-review-actions">
            <ElButton
              data-test="reject-review"
              :loading="reviewing"
              type="danger"
              plain
              @click="submitDecision('REJECT')"
            >驳回</ElButton>
            <ElButton
              data-test="approve-review"
              :loading="reviewing"
              type="primary"
              @click="submitDecision('APPROVE')"
            >审核通过</ElButton>
          </div>
        </aside>
      </div>
      <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
    </main>
  </div>
</template>
