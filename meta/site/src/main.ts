import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import { router } from './router'

// Follow the system color scheme (the brand has matching light/dark palettes).
const mq = window.matchMedia('(prefers-color-scheme: dark)')
const applyScheme = () => document.documentElement.classList.toggle('dark', mq.matches)
applyScheme()
mq.addEventListener('change', applyScheme)

createApp(App).use(router).mount('#app')
