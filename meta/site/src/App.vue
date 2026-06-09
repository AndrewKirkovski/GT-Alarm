<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="min-h-dvh flex flex-col">
    <header class="sticky top-0 z-30 border-b border-border/70 bg-background/80 backdrop-blur">
      <div class="mx-auto flex h-16 max-w-5xl items-center justify-between px-5">
        <RouterLink to="/" class="flex items-center gap-2.5">
          <span class="grid h-8 w-8 place-items-center rounded-lg bg-brand-gradient text-sm font-bold text-white">GT</span>
          <span class="text-lg font-semibold tracking-tight">GT&nbsp;Wake</span>
        </RouterLink>
        <nav class="flex items-center gap-5 text-sm text-muted-foreground">
          <RouterLink to="/" class="hover:text-foreground">Home</RouterLink>
          <RouterLink to="/privacy" class="hover:text-foreground">Privacy</RouterLink>
        </nav>
      </div>
    </header>

    <main class="flex-1">
      <RouterView />
    </main>

    <footer class="border-t border-border/70">
      <div class="mx-auto flex max-w-5xl flex-col gap-2 px-5 py-8 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <span>© 2026 Andrei Kirkouski</span>
        <span class="flex gap-5">
          <RouterLink to="/" class="hover:text-foreground">Home</RouterLink>
          <RouterLink to="/privacy" class="hover:text-foreground">Privacy policy</RouterLink>
        </span>
      </div>
    </footer>
  </div>
</template>
