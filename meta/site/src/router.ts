import { createRouter, createWebHistory } from 'vue-router'
import Home from '@/pages/Home.vue'
import Privacy from '@/pages/Privacy.vue'
import PrivacyZh from '@/pages/PrivacyZh.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: Home },
    { path: '/privacy', name: 'privacy', component: Privacy },
    { path: '/privacy/zh', name: 'privacy-zh', component: PrivacyZh },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})
