<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElPagination, ElTag } from 'element-plus'

import { getMyOrders, getMySales } from '@/api/trade'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import { formatMoney, formatShanghaiTime } from '@/features/auction/presentation'
import {
  maskUserId,
  orderStatusLabel,
  orderStatusTagType,
  settlementStatusLabel,
} from '@/features/trade/presentation'
import type { TradeOrder } from '@/types/trade'

type OrderView = 'buying' | 'sales'

const PAGE_SIZE = 12
const route = useRoute()
const router = useRouter()
const activeView = ref<OrderView>(route.query.view === 'sales' ? 'sales' : 'buying')
const items = ref<TradeOrder[]>([])
const currentPage = ref(1)
const total = ref(0)
const loading = ref(false)
const pageError = ref<ApiError | null>(null)
const lastTraceId = ref<string | null>(null)

const emptyTitle = computed(() =>
  activeView.value === 'buying' ? '还没有买入订单' : '还没有卖出成交',
)
const emptyDescription = computed(() =>
  activeView.value === 'buying'
    ? '竞价胜出并完成保证金结算后，订单会出现在这里。'
    : '你发布的拍品成交后，卖家结算状态会出现在这里。',
)

async function loadOrders(page = currentPage.value): Promise<void> {
  loading.value = true
  pageError.value = null
  try {
    const result = activeView.value === 'buying'
      ? await getMyOrders(page, PAGE_SIZE)
      : await getMySales(page, PAGE_SIZE)
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

async function switchView(view: OrderView): Promise<void> {
  if (activeView.value === view) return
  activeView.value = view
  currentPage.value = 1
  await router.replace({ name: 'my-orders', query: view === 'sales' ? { view } : {} })
  await loadOrders(1)
}

onMounted(() => void loadOrders())
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main orders-main">
      <div class="auction-heading">
        <div>
          <p class="section-eyebrow">Trade workspace</p>
          <h1>我的订单</h1>
          <p>订单、支付和卖家入账状态均来自 Trade 服务的最终记录。</p>
        </div>
        <ElButton :loading="loading" plain @click="loadOrders()">刷新订单</ElButton>
      </div>

      <div class="order-view-switch" role="tablist" aria-label="订单视图">
        <button
          :class="{ 'order-view-switch__button--active': activeView === 'buying' }"
          role="tab"
          :aria-selected="activeView === 'buying'"
          @click="switchView('buying')"
        >
          我买到的
        </button>
        <button
          :class="{ 'order-view-switch__button--active': activeView === 'sales' }"
          role="tab"
          :aria-selected="activeView === 'sales'"
          @click="switchView('sales')"
        >
          我卖出的
        </button>
      </div>

      <ApiErrorNotice :error="pageError" />

      <section v-if="items.length > 0" class="order-list" aria-label="订单列表">
        <article v-for="order in items" :key="order.orderId" class="order-row">
          <div class="order-row__main">
            <div class="order-row__tags">
              <ElTag :type="orderStatusTagType(order.status)" effect="plain">
                {{ orderStatusLabel(order.status) }}
              </ElTag>
              <span>订单 {{ order.orderNo }}</span>
            </div>
            <h2>{{ order.itemTitle }}</h2>
            <p>
              成交于 {{ formatShanghaiTime(order.auctionClosedAt) }} ·
              <template v-if="activeView === 'sales'">买家 {{ maskUserId(order.buyerId) }}</template>
              <template v-else>拍卖场次 {{ order.auctionId }}</template>
            </p>
          </div>

          <dl class="order-row__facts">
            <div><dt>成交价</dt><dd>{{ formatMoney(order.finalPrice) }}</dd></div>
            <div v-if="activeView === 'buying'">
              <dt>待付尾款</dt><dd>{{ formatMoney(order.payableAmount) }}</dd>
            </div>
            <div v-else>
              <dt>卖家应收</dt><dd>{{ formatMoney(order.sellerReceivableAmount) }}</dd>
            </div>
            <div v-if="activeView === 'buying' && order.paymentDeadline">
              <dt>支付截止</dt><dd>{{ formatShanghaiTime(order.paymentDeadline) }}</dd>
            </div>
            <div v-else-if="activeView === 'sales'">
              <dt>结算状态</dt><dd>{{ settlementStatusLabel(order.sellerSettlementStatus) }}</dd>
            </div>
          </dl>

          <RouterLink
            class="secondary-link-button"
            :to="{ name: 'order-detail', params: { orderId: order.orderId } }"
          >
            查看详情
          </RouterLink>
        </article>
      </section>

      <section v-else-if="!loading && !pageError" class="empty-state">
        <h2>{{ emptyTitle }}</h2>
        <p>{{ emptyDescription }}</p>
        <RouterLink class="primary-link-button" :to="{ name: 'auctions' }">前往竞价大厅</RouterLink>
      </section>

      <footer v-if="total > PAGE_SIZE" class="auction-pagination">
        <ElPagination
          :current-page="currentPage"
          :page-size="PAGE_SIZE"
          :total="total"
          background
          layout="prev, pager, next"
          @current-change="loadOrders"
        />
      </footer>
      <p v-if="lastTraceId" class="trace-footer">最近请求追踪编号：{{ lastTraceId }}</p>
    </main>
  </div>
</template>
