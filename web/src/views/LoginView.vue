<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  type FormInstance,
  type FormRules,
} from 'element-plus'

import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AuthShell from '@/components/AuthShell.vue'
import { usernameValidationMessage } from '@/features/auth/validation'
import { useAuthStore } from '@/stores/auth'

interface LoginForm {
  username: string
  password: string
}

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const submitError = ref<ApiError | null>(null)
const form = reactive<LoginForm>({
  username: '',
  password: '',
})

const registeredUsername = computed(() =>
  typeof route.query.registered === 'string' ? route.query.registered : null,
)

const rules: FormRules<LoginForm> = {
  username: [
    {
      validator: (_rule, value, callback) => {
        const message = usernameValidationMessage(typeof value === 'string' ? value : '')
        callback(message ? new Error(message) : undefined)
      },
      trigger: 'blur',
    },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

function safeRedirect(): string {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/dashboard'
}

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) {
    return
  }

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  submitError.value = null
  try {
    await authStore.login({ username: form.username, password: form.password })
    await router.replace(safeRedirect())
  } catch (error) {
    submitError.value = normalizeApiError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Account access"
    title="登录 TideBid"
    description="使用已注册账号进入虚拟资金工作台。"
  >
    <ElAlert
      v-if="registeredUsername"
      class="auth-success"
      :closable="false"
      type="success"
      show-icon
      :title="`账号 ${registeredUsername} 注册成功，请登录。`"
    />
    <ApiErrorNotice :error="submitError" />

    <ElForm
      ref="formRef"
      class="auth-form"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <ElFormItem label="用户名" prop="username">
        <ElInput
          v-model="form.username"
          autocomplete="username"
          maxlength="32"
          placeholder="4～32 位字母、数字或下划线"
        />
      </ElFormItem>

      <ElFormItem label="密码" prop="password">
        <ElInput
          v-model="form.password"
          autocomplete="current-password"
          maxlength="64"
          placeholder="输入登录密码"
          show-password
          type="password"
        />
      </ElFormItem>

      <ElButton
        class="auth-submit"
        data-testid="login-submit"
        :loading="submitting"
        native-type="submit"
        type="primary"
      >
        登录并进入工作台
      </ElButton>
    </ElForm>

    <p class="auth-switch">还没有账号？<RouterLink to="/register">创建账号</RouterLink></p>
  </AuthShell>
</template>
