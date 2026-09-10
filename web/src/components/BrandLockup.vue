<script setup lang="ts">
withDefaults(
  defineProps<{
    compact?: boolean
  }>(),
  {
    compact: false,
  },
)
</script>

<template>
  <div class="brand-lockup" :class="{ 'brand-lockup--compact': compact }">
    <span class="brand-lockup__mark" aria-hidden="true">T</span>
    <span>
      <strong>TideBid</strong>
      <small v-if="!compact">实时竞价交易平台</small>
    </span>
  </div>
</template>
