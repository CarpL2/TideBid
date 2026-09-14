import { describe, expect, it, vi } from 'vitest'

import { putObjectToSignedUrl } from '@/api/auction'
import { validateUploadFiles } from '@/features/auction/upload'

describe('OSS browser upload', () => {
  it('rejects unsupported and oversized files before requesting an upload intent', () => {
    const unsupported = new File(['text'], 'notes.txt', { type: 'text/plain' })
    const oversized = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'large.webp', {
      type: 'image/webp',
    })

    expect(validateUploadFiles([unsupported, oversized])).toEqual([
      'notes.txt 不是支持的 JPEG、PNG 或 WebP 图片。',
      'large.webp 必须大于 0 字节且不超过 10MB。',
    ])
  })

  it('uses the signed URL and omits browser-forbidden headers', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['image'], 'item.webp', { type: 'image/webp' })

    await putObjectToSignedUrl(
      {
        imageId: '9007199254740993',
        objectKey: 'dev/users/42/202609/image.webp',
        uploadUrl: 'https://bucket.example/image?signature=secret',
        requiredHeaders: {
          'Content-Type': 'image/webp',
          'Content-Length': '5',
          Host: 'bucket.example',
          'x-oss-forbid-overwrite': 'true',
        },
        expiresAt: '2026-09-14T12:00:00Z',
      },
      file,
    )

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toContain('signature=secret')
    const headers = init?.headers as Headers
    expect(headers.get('Content-Type')).toBe('image/webp')
    expect(headers.get('Content-Length')).toBeNull()
    expect(headers.get('Host')).toBeNull()
    expect(headers.get('x-oss-forbid-overwrite')).toBe('true')
    vi.unstubAllGlobals()
  })
})
