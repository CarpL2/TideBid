import { createApp } from 'vue'
import { ElMessage } from 'element-plus'
import { createPinia } from 'pinia'

import { configureUnauthorizedHandler } from './api/http'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import 'element-plus/dist/index.css'
import './assets/main.css'

const app = createApp(App)
const pinia = createPinia()
const authStore = useAuthStore(pinia)

authStore.restoreSession()
configureUnauthorizedHandler(() => {
  const redirect = router.currentRoute.value.fullPath
  authStore.logout()
  ElMessage.warning('登录状态已失效，请重新登录。')
  void router.replace({
    name: 'login',
    query: redirect.startsWith('/login') ? undefined : { redirect },
  })
})

app.use(pinia)
app.use(router)

app.mount('#app')
