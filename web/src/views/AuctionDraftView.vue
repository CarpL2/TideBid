<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElInput, ElOption, ElSelect } from 'element-plus'

import {
  createAuctionDraft,
  createUploadIntent,
  getAuctionAsset,
  putObjectToSignedUrl,
  updateAuctionDraft,
} from '@/api/auction'
import { normalizeApiError, type ApiError } from '@/api/errors'
import ApiErrorNotice from '@/components/ApiErrorNotice.vue'
import AppHeader from '@/components/AppHeader.vue'
import {
  localDateTimeToInstant,
  toLocalDateTimeInput,
  validateAuctionDraft,
} from '@/features/auction/draft-validation'
import { AUCTION_CATEGORY_OPTIONS, reviewStatusLabel } from '@/features/auction/presentation'
import { sha256Hex, validateUploadFiles } from '@/features/auction/upload'
import type {
  AuctionAssetDetail,
  AuctionDraftFields,
} from '@/types/auction'

interface SelectedImage {
  file: File
  previewUrl: string
}

const route = useRoute()
const router = useRouter()
const assetId = computed(() => (route.params.assetId ? String(route.params.assetId) : null))
const editing = computed(() => assetId.value !== null)
const source = ref<AuctionAssetDetail | null>(null)
const selectedImages = ref<SelectedImage[]>([])
const loading = ref(false)
const saving = ref(false)
const uploadProgress = ref('')
const validationErrors = ref<string[]>([])
const pageError = ref<ApiError | null>(null)

const startDefault = new Date(Date.now() + 24 * 60 * 60 * 1000)
const endDefault = new Date(startDefault.getTime() + 2 * 60 * 60 * 1000)
const form = reactive<AuctionDraftFields>({
  title: '',
  description: '',
  category: 'ELECTRONICS',
  itemCondition: 'GOOD',
  startPrice: '',
  bidIncrement: '',
  depositAmount: '',
  startAt: toLocalDateTimeInput(startDefault),
  endAt: toLocalDateTimeInput(endDefault),
})

const canEdit = computed(
  () => !source.value || source.value.reviewStatus === 'DRAFT' || source.value.reviewStatus === 'REJECTED',
)

function selectImages(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? []).slice(0, 9)
  selectedImages.value.forEach((image) => URL.revokeObjectURL(image.previewUrl))
  selectedImages.value = files.map((file) => ({ file, previewUrl: URL.createObjectURL(file) }))
}

async function loadAsset(): Promise<void> {
  if (!assetId.value) return
  loading.value = true
  pageError.value = null
  try {
    const result = await getAuctionAsset(assetId.value)
    source.value = result.data
    Object.assign(form, {
      title: result.data.title,
      description: result.data.description,
      category: result.data.category,
      itemCondition: result.data.itemCondition,
      startPrice: String(result.data.startPrice),
      bidIncrement: String(result.data.bidIncrement),
      depositAmount: String(result.data.depositAmount),
      startAt: toLocalDateTimeInput(new Date(result.data.startAt)),
      endAt: toLocalDateTimeInput(new Date(result.data.endAt)),
    })
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    loading.value = false
  }
}

async function uploadSelectedImages(): Promise<string[]> {
  const keys: string[] = []
  for (const [index, image] of selectedImages.value.entries()) {
    uploadProgress.value = `正在上传第 ${index + 1}/${selectedImages.value.length} 张图片…`
    const checksumSha256 = await sha256Hex(image.file)
    const intent = await createUploadIntent({
      originalFilename: image.file.name,
      contentType: image.file.type,
      contentLength: image.file.size,
      checksumSha256,
    })
    await putObjectToSignedUrl(intent.data, image.file)
    keys.push(intent.data.objectKey)
  }
  return keys
}

async function save(): Promise<void> {
  if (saving.value || !canEdit.value) return
  validationErrors.value = validateAuctionDraft(form, selectedImages.value.length, !editing.value)
  if (!editing.value) {
    validationErrors.value.push(...validateUploadFiles(selectedImages.value.map((image) => image.file)))
    validationErrors.value = [...new Set(validationErrors.value)]
  }
  if (validationErrors.value.length > 0) return

  saving.value = true
  pageError.value = null
  try {
    const fields = {
      title: form.title.trim(),
      description: form.description.trim(),
      category: form.category,
      itemCondition: form.itemCondition,
      startPrice: form.startPrice,
      bidIncrement: form.bidIncrement,
      depositAmount: form.depositAmount,
      startAt: localDateTimeToInstant(form.startAt),
      endAt: localDateTimeToInstant(form.endAt),
    }
    if (editing.value && assetId.value && source.value) {
      await updateAuctionDraft(assetId.value, {
        ...fields,
        itemVersion: source.value.itemVersion,
        sessionVersion: source.value.sessionVersion,
      })
    } else {
      const imageObjectKeys = await uploadSelectedImages()
      await createAuctionDraft({ ...fields, imageObjectKeys })
    }
    await router.push({ name: 'my-assets' })
  } catch (error) {
    pageError.value = normalizeApiError(error)
  } finally {
    saving.value = false
    uploadProgress.value = ''
  }
}

onMounted(loadAsset)
onBeforeUnmount(() => selectedImages.value.forEach((image) => URL.revokeObjectURL(image.previewUrl)))
</script>

<template>
  <div class="app-page">
    <AppHeader />
    <main class="auction-main draft-main">
      <RouterLink class="back-link" :to="{ name: 'my-assets' }">← 返回我的拍品</RouterLink>
      <div class="auction-heading">
        <div>
          <p class="section-eyebrow">Seller draft</p>
          <h1>{{ editing ? '编辑拍品草稿' : '创建拍品草稿' }}</h1>
          <p>图片先直传私有 OSS，保存时后端会重新 HEAD 校验并原子绑定。</p>
        </div>
      </div>
      <ApiErrorNotice :error="pageError" />

      <div v-if="source?.latestReview?.decision === 'REJECTED'" class="review-feedback">
        <strong>第 {{ source.latestReview.submissionVersion }} 版审核意见</strong>
        <p>{{ source.latestReview.comment }}</p>
      </div>
      <div v-if="source && !canEdit" class="review-feedback">
        当前拍品状态为“{{ reviewStatusLabel(source.reviewStatus) }}”，字段已锁定，不能继续修改。
      </div>

      <form v-if="!loading" class="draft-form" @submit.prevent="save">
        <section class="draft-panel">
          <h2>拍品信息</h2>
          <label>拍品标题 <ElInput v-model="form.title" :disabled="!canEdit" maxlength="80" show-word-limit /></label>
          <label>详细描述 <ElInput v-model="form.description" :disabled="!canEdit" maxlength="2000" :rows="6" resize="vertical" show-word-limit type="textarea" /></label>
          <div class="draft-form__grid">
            <label>分类
              <ElSelect v-model="form.category" :disabled="!canEdit">
                <ElOption v-for="option in AUCTION_CATEGORY_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
              </ElSelect>
            </label>
            <label>成色
              <ElSelect v-model="form.itemCondition" :disabled="!canEdit">
                <ElOption label="全新" value="NEW" /><ElOption label="几乎全新" value="LIKE_NEW" />
                <ElOption label="成色良好" value="GOOD" /><ElOption label="正常使用痕迹" value="FAIR" />
              </ElSelect>
            </label>
          </div>
        </section>

        <section class="draft-panel">
          <h2>竞价参数</h2>
          <div class="draft-form__grid draft-form__grid--three">
            <label>起拍价（元） <ElInput v-model="form.startPrice" :disabled="!canEdit" inputmode="decimal" /></label>
            <label>最低加价（元） <ElInput v-model="form.bidIncrement" :disabled="!canEdit" inputmode="decimal" /></label>
            <label>报名保证金（元） <ElInput v-model="form.depositAmount" :disabled="!canEdit" inputmode="decimal" /></label>
          </div>
          <div class="draft-form__grid">
            <label>开始时间 <input v-model="form.startAt" :disabled="!canEdit" class="native-field" type="datetime-local" /></label>
            <label>结束时间 <input v-model="form.endAt" :disabled="!canEdit" class="native-field" type="datetime-local" /></label>
          </div>
        </section>

        <section class="draft-panel">
          <h2>拍品图片</h2>
          <template v-if="editing">
            <p class="draft-help">编辑不会替换已绑定图片；图片集合在创建草稿时固定。</p>
            <div class="draft-image-grid">
              <img v-for="image in source?.images" :key="image.imageId" :src="image.previewUrl ?? ''" :alt="image.originalFilename" />
            </div>
          </template>
          <template v-else>
            <input accept="image/jpeg,image/png,image/webp" data-testid="draft-images" multiple type="file" @change="selectImages" />
            <p class="draft-help">选择 1～9 张 JPEG、PNG 或 WebP，单张最大 10MB；保存时计算 SHA-256 后直传。</p>
            <div class="draft-image-grid">
              <img v-for="image in selectedImages" :key="image.previewUrl" :src="image.previewUrl" :alt="image.file.name" />
            </div>
          </template>
        </section>

        <ul v-if="validationErrors.length > 0" class="draft-validation" aria-label="表单问题">
          <li v-for="error in validationErrors" :key="error">{{ error }}</li>
        </ul>
        <div class="draft-actions">
          <RouterLink class="secondary-link-button" :to="{ name: 'my-assets' }">取消</RouterLink>
          <ElButton data-testid="draft-save" :disabled="!canEdit" :loading="saving" native-type="submit" type="primary">
            {{ uploadProgress || (editing ? '保存修改' : '上传并保存草稿') }}
          </ElButton>
        </div>
      </form>
    </main>
  </div>
</template>
