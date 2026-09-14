import 'vue-router'

export {}

declare module 'vue-router' {
  interface RouteMeta {
    title: string
    requiresAuth?: boolean
    guestOnly?: boolean
    section?: 'dashboard' | 'auctions' | 'seller' | 'admin'
  }
}
