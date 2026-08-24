import { createRouter, createWebHistory } from 'vue-router'
import Home from '@/pages/Home.vue'
import Download from '@/pages/Download.vue'
import Privacy from '@/pages/Privacy.vue'
import PrivacyZh from '@/pages/PrivacyZh.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: Home },
    { path: '/download', name: 'download', component: Download },
    { path: '/privacy', name: 'privacy', component: Privacy },
    { path: '/privacy/zh', name: 'privacy-zh', component: PrivacyZh },
    // A stale APK link (old version pruned on release) has no asset to serve,
    // so it falls through to the SPA. Land those on the download page with the
    // current version rather than dumping the user on the home page.
    { path: '/apk/:file(.*)', redirect: '/download' },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})
