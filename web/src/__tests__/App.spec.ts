import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import App from '../App.vue'
import router from '../router'

describe('App', () => {
  it('renders the foundation route through the installed application plugins', async () => {
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
      },
    })

    expect(wrapper.get('[data-testid="app-title"]').text()).toBe('TideBid')
    expect(wrapper.text()).toContain('前端基础设施已就绪')
  })
})
