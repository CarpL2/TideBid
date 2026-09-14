import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import App from '../App.vue'
import { createAppRouter } from '../router'

describe('App', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('renders the login route through the installed application plugins', { timeout: 10_000 }, async () => {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
      },
    })
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('登录 TideBid')
    expect(wrapper.get('[data-testid="login-submit"]').text()).toContain('登录并进入工作台')
  })
})
