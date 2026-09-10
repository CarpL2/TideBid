import { computed, readonly, ref } from 'vue'
import { defineStore } from 'pinia'

export interface FoundationModule {
  name: string
  detail: string
}

export const useFoundationStore = defineStore('foundation', () => {
  const modules = ref<FoundationModule[]>([
    { name: 'Router', detail: '页面路由与导航守卫' },
    { name: 'Pinia', detail: '认证与应用状态管理' },
    { name: 'Axios', detail: '统一网关请求入口' },
    { name: 'Element Plus', detail: '管理端基础组件' },
  ])

  const readyCount = computed(() => modules.value.length)

  return {
    modules: readonly(modules),
    readyCount,
  }
})
