<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { RouterLink } from 'vue-router'
import {
  BellRing,
  Watch,
  Vibrate,
  ImageIcon,
  ShieldCheck,
  Globe,
  Mic,
  LayoutGrid,
} from 'lucide-vue-next'

// AppGallery uses the package-name URL form (verified to resolve) — no C-number
// needed. These go live once the apps pass review. NOTE: the watch app can't be
// installed from a web link; it's installed on the watch via Huawei Health, so
// the watch URL is an info page and the copy below explains the real steps.
const PLAY_URL = 'https://play.google.com/store/apps/details?id=com.kirkouski.gtwake.companion'
const APPGALLERY_PHONE = 'https://appgallery.huawei.com/app/detail?id=com.kirkouski.gtwake.companion'
const APPGALLERY_WATCH = 'https://appgallery.huawei.com/app/detail?id=com.kirkouski.gtwatch.watch'
const CONTACT = 'mailto:andrew.kirkovski@gmail.com'

const features = [
  { icon: BellRing, title: 'In-sync ringing', text: 'Alarms ring on your phone and your Huawei Watch GT 6 Pro at the same time.' },
  { icon: Watch, title: 'Act from the wrist', text: 'Snooze or dismiss from the watch — no need to reach for the phone.' },
  { icon: LayoutGrid, title: 'Every alarm type', text: 'Recurring, one-time, and timer (“in X minutes”) alarms, with skip-next.' },
  { icon: Vibrate, title: 'Patterns & ramp', text: 'Vibration patterns, gradual volume ramp, and snooze limits.' },
  { icon: ImageIcon, title: 'Your backgrounds', text: 'A background image per alarm — on the lock screen and the watch.' },
  { icon: Mic, title: 'Voice & widget', text: 'Set alarms by voice with your assistant; a home-screen widget shows the next one.' },
  { icon: Globe, title: 'Six languages', text: 'English, Русский, Беларуская, Українська, Polski, 中文.' },
  { icon: ShieldCheck, title: 'Private by design', text: 'No ads, no account, no tracking. Nothing leaves your devices.' },
]
</script>

<template>
  <!-- Hero -->
  <section class="relative overflow-hidden">
    <div class="bg-brand-radial absolute inset-0 -z-10"></div>
    <div class="mx-auto max-w-5xl px-5 pt-20 pb-16 text-center sm:pt-28">
      <span class="inline-flex items-center gap-2 rounded-full border border-border bg-card/60 px-3.5 py-1.5 text-xs font-medium text-muted-foreground">
        <span class="h-1.5 w-1.5 rounded-full bg-brand-gradient"></span>
        Phone + Huawei Watch GT 6 series
      </span>
      <h1 class="mt-6 text-5xl font-extrabold tracking-tight sm:text-6xl">
        Alarms on your phone,<br />
        <span class="text-brand-gradient">on your wrist.</span>
      </h1>
      <p class="mx-auto mt-5 max-w-xl text-lg text-muted-foreground">
        GT Wake rings your alarms on your phone and your Huawei Watch GT 6 Pro, in sync —
        and lets you snooze or dismiss from whichever is closer.
      </p>
      <div class="mt-8 flex flex-wrap items-center justify-center gap-3">
        <Button as="a" :href="PLAY_URL" variant="brand" size="lg">Get it on Google Play</Button>
        <Button as="a" :href="APPGALLERY_PHONE" variant="outline" size="lg">Phone · AppGallery</Button>
        <Button as="a" :href="APPGALLERY_WATCH" variant="outline" size="lg">Watch · AppGallery</Button>
      </div>
      <p class="mt-4 text-sm text-muted-foreground">
        The watch app needs the Companion on your phone and a watch paired through Huawei Health.
      </p>
    </div>
  </section>

  <!-- Features -->
  <section class="mx-auto max-w-5xl px-5 py-14">
    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <div
        v-for="f in features"
        :key="f.title"
        class="rounded-2xl border border-border bg-card p-5 transition-shadow hover:shadow-md"
      >
        <component :is="f.icon" class="h-6 w-6 text-brand-indigo" :stroke-width="2" />
        <h3 class="mt-3 font-semibold">{{ f.title }}</h3>
        <p class="mt-1 text-sm text-muted-foreground">{{ f.text }}</p>
      </div>
    </div>
  </section>

  <!-- Device + contribute -->
  <section class="mx-auto max-w-5xl px-5 pb-20">
    <div class="overflow-hidden rounded-3xl border border-border bg-card">
      <div class="grid items-center gap-8 p-8 sm:p-12 lg:grid-cols-2">
        <div>
          <h2 class="text-2xl font-bold tracking-tight sm:text-3xl">
            Made for the <span class="text-brand-gradient">Huawei Watch GT 6 series</span>
          </h2>
          <p class="mt-4 text-muted-foreground">
            GT Wake is made for the Huawei Watch GT 6 series. Got a different Huawei watch and want
            GT Wake on it? You can help — donate a device, or just get in touch.
          </p>
          <div class="mt-6 flex flex-wrap gap-3">
            <Button as="a" :href="CONTACT" variant="brand">Help test more watches</Button>
            <Button as="a" :href="APPGALLERY_WATCH" variant="outline">Get the watch app</Button>
          </div>
        </div>
        <div class="relative grid place-items-center">
          <img
            src="/favicon.svg"
            alt="GT Wake app icon"
            class="aspect-square w-44 rounded-[2.5rem] shadow-2xl sm:w-52"
          />
        </div>
      </div>
    </div>

    <p class="mt-8 text-center text-sm text-muted-foreground">
      Read our <RouterLink to="/privacy" class="font-medium text-primary hover:underline">privacy policy</RouterLink> —
      no accounts, no tracking, nothing leaves your devices.
    </p>
  </section>
</template>
