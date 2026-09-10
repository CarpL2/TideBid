<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  type FormInstance,
  type FormItemRule,
  type FormRules,
} from 'element-plus'

import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AuthShell from '@/components/AuthShell.vue'
import {
  nicknameValidationMessage,
  passwordValidationMessage,
  usernameValidationMessage,
} from '@/features/auth/validation'
import { useAuthStore } from '@/stores/auth'

interface RegisterForm {
  username: string
  nickname: string
  password: string
  confirmPassword: string
}

const authStore = useAuthStore()
const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const submitError = ref<ApiError | null>(null)
const form = reactive<RegisterForm>({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
})

function validationRule(check: (value: string) => string | null): FormItemRule {
  return {
    validator: (_rule, value, callback) => {
      const message = check(typeof value === 'string' ? value : '')
      callback(message ? new Error(message) : undefined)
    },
    trigger: 'blur',
  }
}

const rules: FormRules<RegisterForm> = {
  username: [validationRule(usernameValidationMessage)],
  nickname: [validationRule(nicknameValidationMessage)],
  password: [validationRule(passwordValidationMessage)],
  confirmPassword: [
    {
      validator: (_rule, value, callback) => {
        if (typeof value !== 'string' || !value) {
          callback(new Error('请再次输入密码'))
          return
        }
        callback(value === form.password ? undefined : new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
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
    const account = await authStore.register({
      username: form.username,
      nickname: form.nickname.trim(),
      password: form.password,
    })
    await router.push({ name: 'login', query: { registered: account.username } })
  } catch (error) {
    submitError.value = normalizeApiError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Create account"
    title="创建演示账号"
    description="注册会同时建立 USER 角色、虚拟钱包和初始化资金流水。"
  >
    <ApiErrorNotice :error="submitError" />

    <ElForm
      ref="formRef"
      class="auth-form"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <div class="auth-form__row">
        <ElFormItem label="用户名" prop="username">
          <ElInput
            v-model="form.username"
            autocomplete="username"
            maxlength="32"
            placeholder="例如 carp_bidder"
          />
        </ElFormItem>

        <ElFormItem label="昵称" prop="nickname">
          <ElInput
            v-model="form.nickname"
            autocomplete="nickname"
            maxlength="64"
            placeholder="用于页面展示"
          />
        </ElFormItem>
      </div>

      <ElFormItem label="密码" prop="password">
        <ElInput
          v-model="form.password"
          autocomplete="new-password"
          maxlength="64"
          placeholder="8～64 位，UTF-8 不超过 72 字节"
          show-password
          type="password"
        />
      </ElFormItem>

      <ElFormItem label="确认密码" prop="confirmPassword">
        <ElInput
          v-model="form.confirmPassword"
          autocomplete="new-password"
          maxlength="64"
          placeholder="再次输入密码"
          show-password
          type="password"
        />
      </ElFormItem>

      <ElButton
        class="auth-submit"
        data-testid="register-submit"
        :loading="submitting"
        native-type="submit"
        type="primary"
      >
        创建账号
      </ElButton>
    </ElForm>

    <p class="auth-switch">已经有账号？<RouterLink to="/login">返回登录</RouterLink></p>
  </AuthShell>
</template>
