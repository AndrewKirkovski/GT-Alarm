import { createRouter, createWebHistory } from 'vue-router'
import Home from '@/pages/Home.vue'
import Privacy from '@/pages/Privacy.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: Home },
    { path: '/privacy', name: 'privacy', component: Privacy },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})
