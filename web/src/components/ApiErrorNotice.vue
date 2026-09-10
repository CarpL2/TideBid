<script setup lang="ts">
import { ElAlert } from 'element-plus'

import type { ApiError } from '@/api/errors'

defineProps<{
  error: ApiError | null
}>()
</script>

<template>
  <ElAlert v-if="error" class="api-error" :closable="false" type="error" show-icon>
    <template #title>{{ error.userMessage }}</template>
    <p v-if="error.traceId" class="api-error__trace">追踪编号：{{ error.traceId }}</p>
  </ElAlert>
</template>
