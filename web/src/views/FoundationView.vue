<script setup lang="ts">
import { ElTag } from 'element-plus'
import { storeToRefs } from 'pinia'

import { useFoundationStore } from '@/stores/foundation'

const foundationStore = useFoundationStore()
const { modules, readyCount } = storeToRefs(foundationStore)
</script>

<template>
  <main class="foundation-page">
    <section class="foundation-panel" aria-labelledby="foundation-title">
      <header class="brand-row">
        <div class="brand-mark" aria-hidden="true">T</div>
        <div>
          <p class="eyebrow">Distributed Auction Platform</p>
          <h1 id="foundation-title" data-testid="app-title">TideBid</h1>
        </div>
        <el-tag class="foundation-tag" effect="dark" round>Foundation 01</el-tag>
      </header>

      <div class="status-block">
        <span class="status-dot" aria-hidden="true"></span>
        <div>
          <p class="status-label">前端基础设施已就绪</p>
          <p class="status-copy">
            当前完成工程骨架与工具链，认证页面和工作台将在下一里程碑接入真实网关接口。
          </p>
        </div>
      </div>

      <ul class="module-grid" aria-label="已接入前端模块">
        <li v-for="(module, index) in modules" :key="module.name" class="module-card">
          <span class="module-index">0{{ index + 1 }}</span>
          <strong>{{ module.name }}</strong>
          <span>{{ module.detail }}</span>
        </li>
      </ul>

      <footer class="foundation-footer">
        <span>{{ readyCount }} 项核心模块可用</span>
        <span>Node 24 · Vue 3 · TypeScript · Vite</span>
      </footer>
    </section>
  </main>
</template>
