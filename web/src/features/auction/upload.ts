const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const MAX_IMAGE_BYTES = 10 * 1024 * 1024

export function validateUploadFiles(files: File[]): string[] {
  const errors: string[] = []
  if (files.length < 1 || files.length > 9) {
    errors.push('请选择 1～9 张拍品图片。')
  }
  for (const file of files) {
    if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
      errors.push(`${file.name} 不是支持的 JPEG、PNG 或 WebP 图片。`)
    } else if (file.size <= 0 || file.size > MAX_IMAGE_BYTES) {
      errors.push(`${file.name} 必须大于 0 字节且不超过 10MB。`)
    }
  }
  return errors
}

export async function sha256Hex(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error('SHA-256 is unavailable in this browser')
  }
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
