import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { http } from '@/api/http'
import { useFoundationStore } from '@/stores/foundation'

describe('frontend foundation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('registers the expected infrastructure modules in Pinia state', () => {
    const store = useFoundationStore()

    expect(store.readyCount).toBe(4)
    expect(store.modules.map((module) => module.name)).toEqual([
      'Router',
      'Pinia',
      'Axios',
      'Element Plus',
    ])
  })

  it('keeps the HTTP client on the same-origin API prefix', () => {
    expect(http.defaults.baseURL).toBe('/api')
    expect(http.defaults.timeout).toBe(10_000)
  })
})
