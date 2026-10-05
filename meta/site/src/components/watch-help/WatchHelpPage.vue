<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { AudioWaveform, BedDouble, ChevronRight, MoonStar, Smartphone, Vibrate, Watch } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { PLAY_URL, APPGALLERY_PHONE_WEB, detectPhone, preferAppGallery } from '@/data/stores'
import type { WatchHelpCopy } from '@/data/watchHelp'
import PhoneFrame from './PhoneFrame.vue'
import WatchFrame from './WatchFrame.vue'

defineProps<{ t: WatchHelpCopy }>()

// Same store ordering as the Download page: the sniff only orders the buttons
// and adds the HarmonyOS 5 note. Both stores stay reachable whatever it returns.
const platform = detectPhone()
const appGalleryFirst = preferAppGallery(platform)

// Icons follow the order of `vibRows`: vibration, Do Not Disturb, sleep, strength.
const vibIcons = [Vibrate, MoonStar, BedDouble, AudioWaveform]
</script>

<template>
  <article class="wh-page mx-auto max-w-3xl px-5 pt-10 pb-16">
    <!-- Shared paint servers and arrowheads for every figure on the page. -->
    <svg class="wh-defs" width="0" height="0" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="wh-snooze" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#ff9ab8" />
          <stop offset="1" stop-color="#f2668b" />
        </linearGradient>
        <linearGradient id="wh-stop" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#e3343f" />
          <stop offset="1" stop-color="#ec6a5e" />
        </linearGradient>
        <marker id="wh-arrow-link" viewBox="0 0 10 10" refX="7" refY="5" markerUnits="userSpaceOnUse"
          markerWidth="14" markerHeight="14" orient="auto-start-reverse">
          <path d="M0 0 L10 5 L0 10 z" class="wh-fill-link" />
        </marker>
        <marker id="wh-arrow-back" viewBox="0 0 10 10" refX="7" refY="5" markerUnits="userSpaceOnUse"
          markerWidth="14" markerHeight="14" orient="auto-start-reverse">
          <path d="M0 0 L10 5 L0 10 z" class="wh-fill-back" />
        </marker>
      </defs>
    </svg>

    <p class="text-sm"><RouterLink :to="t.otherLang.to" class="text-primary hover:underline">{{ t.otherLang.label }}</RouterLink></p>
    <h1 class="mt-2 text-4xl font-extrabold tracking-tight">{{ t.title }}</h1>

    <!-- How an alarm reaches the wrist: the numbered marks match the list below. -->
    <figure class="mt-8">
      <svg class="wh-fig wh-hero" viewBox="0 0 640 320" role="img" :aria-label="t.heroLabel">
        <g class="wh-sound">
          <path d="M30 76 Q 22 94 30 112" />
          <path d="M18 66 Q 6 94 18 122" />
        </g>
        <PhoneFrame :x="44" :y="36">
          <g transform="translate(68 76)">
            <path class="wh-white" d="M-9 6 C -9 -4 -7 -10 0 -11 C 7 -10 9 -4 9 6 L 11 9 L -11 9 Z" />
            <circle class="wh-white" cy="12.5" r="2.6" />
          </g>
          <text class="wh-time" x="68" y="142" font-size="38" font-weight="300">07:00</text>
          <rect class="wh-ink-faint" x="42" y="154" width="52" height="6" rx="3" />
          <rect x="28" y="196" width="80" height="32" rx="16" fill="url(#wh-stop)" />
          <rect class="wh-white" x="61" y="205" width="14" height="14" rx="3" />
        </PhoneFrame>

        <path class="wh-link wh-flow" d="M190 126 C 232 64 302 58 344 100" marker-end="url(#wh-arrow-link)" />
        <path class="wh-link wh-back" d="M344 220 C 302 262 232 262 190 204"
          marker-start="url(#wh-arrow-back)" marker-end="url(#wh-arrow-back)" />
        <path class="wh-bt" transform="translate(267 30) scale(1.5)" d="M-5 -5 L5 4 L0 8.5 L0 -8.5 L5 -4 L-5 5" />

        <g class="wh-buzz">
          <g class="wh-vib">
            <path d="M385 136 Q 375 160 385 184" />
            <path d="M371 124 Q 357 160 371 196" />
            <path d="M597 136 Q 607 160 597 184" />
            <path d="M611 124 Q 625 160 611 196" />
          </g>
          <WatchFrame :cx="490" :cy="160" :scale="0.95">
            <circle class="wh-ring-arc" r="78" />
            <g transform="translate(0 -50) scale(0.8)">
              <path class="wh-white" d="M-9 6 C -9 -4 -7 -10 0 -11 C 7 -10 9 -4 9 6 L 11 9 L -11 9 Z" />
              <circle class="wh-white" cy="12.5" r="2.6" />
            </g>
            <text class="wh-time" y="15" font-size="40" font-weight="200">07:00</text>
            <circle cx="-27" cy="48" r="17" fill="url(#wh-snooze)" />
            <text class="wh-z" x="-30" y="55" font-size="15">z</text>
            <text class="wh-z" x="-22" y="48" font-size="10">z</text>
            <circle cx="27" cy="48" r="17" fill="url(#wh-stop)" />
            <rect class="wh-white" x="21" y="42" width="12" height="12" rx="2.5" />
          </WatchFrame>
        </g>

        <g class="wh-badge" transform="translate(40 32)"><circle r="17" /><text y="7">1</text></g>
        <g class="wh-badge" transform="translate(267 74)"><circle r="17" /><text y="7">2</text></g>
        <g class="wh-badge" transform="translate(588 64)"><circle r="17" /><text y="7">3</text></g>
        <g class="wh-badge" transform="translate(267 250)"><circle r="17" /><text y="7">4</text></g>
      </svg>
      <figcaption>
        <ol class="wh-steps">
          <li v-for="(step, i) in t.steps" :key="i"><span class="wh-num" aria-hidden="true">{{ i + 1 }}</span>{{ step }}</li>
        </ol>
      </figcaption>
    </figure>

    <section class="mt-12 rounded-2xl border border-border bg-card p-6">
      <h2 class="text-xl font-bold">{{ t.appTitle }}</h2>
      <p class="mt-2 text-muted-foreground">{{ t.appText }}</p>
      <p v-if="platform === 'harmony-next'" class="mt-2 text-muted-foreground">
        {{ t.harmonyNote }} <RouterLink to="/harmony" class="text-primary hover:underline">{{ t.harmonyLink }}</RouterLink>
      </p>
      <div class="mt-4 flex flex-wrap gap-3">
        <Button as="a" :href="appGalleryFirst ? APPGALLERY_PHONE_WEB : PLAY_URL" variant="brand">
          {{ appGalleryFirst ? t.appGallery : t.play }}
        </Button>
        <Button as="a" :href="appGalleryFirst ? PLAY_URL : APPGALLERY_PHONE_WEB" variant="outline">
          {{ appGalleryFirst ? t.play : t.appGallery }}
        </Button>
      </div>
      <p class="mt-3 text-sm">
        <RouterLink to="/download" class="text-primary hover:underline">{{ t.apkLink }}</RouterLink>
      </p>
    </section>

    <!-- Optional list sync: the only difference between the panels is the arrow. -->
    <section class="mt-12">
      <h2 class="text-xl font-bold">{{ t.listTitle }}</h2>
      <div class="mt-4 grid gap-4 sm:grid-cols-2">
        <figure class="wh-panel">
          <h3 class="font-semibold">{{ t.listOff }}</h3>
          <svg class="wh-fig" viewBox="0 0 320 200" role="img" :aria-label="t.listOffLabel">
            <PhoneFrame :x="18" :y="22" :scale="0.62">
              <rect class="wh-row-hi" x="18" y="52" width="100" height="44" rx="11" />
              <text class="wh-time wh-start" x="30" y="83" font-size="24">07:00</text>
              <rect class="wh-row" x="18" y="106" width="100" height="44" rx="11" />
              <rect class="wh-ink-faint" x="30" y="122" width="44" height="12" rx="6" />
              <rect class="wh-row" x="18" y="160" width="100" height="44" rx="11" />
              <rect class="wh-ink-faint" x="30" y="176" width="44" height="12" rx="6" />
              <g transform="translate(110 56)">
                <circle class="wh-fill-edit" r="17" />
                <path class="wh-pencil" d="M-6 6 L-6 1.5 L4 -8.5 L8.5 -4 L-1.5 6 Z" />
              </g>
            </PhoneFrame>
            <WatchFrame :cx="240" :cy="100" :scale="0.56" off>
              <g class="wh-face">
                <line x1="0" y1="-72" x2="0" y2="-62" />
                <line x1="72" y1="0" x2="62" y2="0" />
                <line x1="0" y1="72" x2="0" y2="62" />
                <line x1="-72" y1="0" x2="-62" y2="0" />
                <line class="wh-hand" x1="0" y1="0" x2="-31" y2="-18" />
                <line class="wh-hand" x1="0" y1="0" x2="47" y2="-27" />
              </g>
              <circle class="wh-face-dot" r="5" />
            </WatchFrame>
          </svg>
          <figcaption class="text-sm text-muted-foreground">{{ t.listOffText }}</figcaption>
        </figure>

        <figure class="wh-panel">
          <h3 class="font-semibold">{{ t.listOn }}</h3>
          <svg class="wh-fig" viewBox="0 0 320 200" role="img" :aria-label="t.listOnLabel">
            <PhoneFrame :x="18" :y="22" :scale="0.62">
              <rect class="wh-row-hi" x="18" y="52" width="100" height="44" rx="11" />
              <text class="wh-time wh-start" x="30" y="83" font-size="24">07:00</text>
              <rect class="wh-row" x="18" y="106" width="100" height="44" rx="11" />
              <rect class="wh-ink-faint" x="30" y="122" width="44" height="12" rx="6" />
              <rect class="wh-row" x="18" y="160" width="100" height="44" rx="11" />
              <rect class="wh-ink-faint" x="30" y="176" width="44" height="12" rx="6" />
              <g transform="translate(110 56)">
                <circle class="wh-fill-edit" r="17" />
                <path class="wh-pencil" d="M-6 6 L-6 1.5 L4 -8.5 L8.5 -4 L-1.5 6 Z" />
              </g>
            </PhoneFrame>
            <path class="wh-link" d="M114 100 L 170 100" marker-end="url(#wh-arrow-link)" />
            <WatchFrame :cx="240" :cy="100" :scale="0.56">
              <rect class="wh-ink-faint" x="-22" y="-70" width="44" height="8" rx="4" />
              <g v-for="y in [-52, -14, 24]" :key="y">
                <rect class="wh-wrow" x="-58" :y="y" width="116" height="30" rx="9" />
                <circle class="wh-fill-on" cx="-46" :cy="y + 15" r="4" />
                <rect class="wh-ink" x="-36" :y="y + 9" width="38" height="12" rx="6" />
                <circle v-for="dx in [16, 26, 36, 46]" :key="dx" class="wh-fill-day" :cx="dx" :cy="y + 15" r="3.2" />
              </g>
            </WatchFrame>
          </svg>
          <figcaption class="text-sm text-muted-foreground">{{ t.listOnText }}</figcaption>
        </figure>
      </div>

      <p class="mt-4 text-muted-foreground">{{ t.listWhy }}</p>
      <p class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <Smartphone class="h-5 w-5 text-muted-foreground" aria-hidden="true" />
        <span class="sr-only">{{ t.listPathLabel }}:</span>
        <template v-for="(step, i) in t.listPath" :key="step">
          <ChevronRight v-if="i > 0" class="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <span class="wh-chip">{{ step }}</span>
        </template>
      </p>
    </section>

    <!-- Each control is drawn in the state it should be in. -->
    <section class="mt-12">
      <h2 class="text-xl font-bold">{{ t.vibTitle }}</h2>
      <ul class="mt-4 divide-y divide-border rounded-2xl border border-border bg-card">
        <li v-for="(row, i) in t.vibRows" :key="row.label" class="flex items-center gap-4 px-4 py-3.5">
          <component :is="vibIcons[i]" class="h-6 w-6 shrink-0 text-brand-indigo" aria-hidden="true" />
          <span class="flex min-w-0 flex-1 items-center gap-2">
            <span class="font-medium">{{ row.label }}</span>
            <component :is="row.device === 'phone' ? Smartphone : Watch" class="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span class="sr-only">({{ row.where }})</span>
          </span>
          <span v-if="row.state === 'notNone'" class="wh-seg" aria-hidden="true">
            <span class="wh-seg-none">{{ t.vibNone }}</span>
            <span class="wh-seg-lvl"><i /></span>
            <span class="wh-seg-lvl wh-seg-on"><i /><i /></span>
          </span>
          <span v-else class="wh-toggle" :class="{ 'wh-toggle-on': row.state === 'on' }" aria-hidden="true"><i /></span>
          <span class="sr-only">{{ t.stateText[row.state] }}</span>
        </li>
      </ul>
    </section>

    <p class="mt-10 text-sm">
      <RouterLink to="/harmony" class="font-medium text-primary hover:underline">{{ t.harmony }}</RouterLink>
    </p>
  </article>
</template>

<style>
/* Unscoped on purpose: PhoneFrame and WatchFrame draw with these classes too.
   Everything is prefixed `wh-` so nothing leaks into other pages. */
.wh-page {
  --wh-dev: #1c1826;
  --wh-dev-edge: #1c1826;
  --wh-strap: #2c2737;
  --wh-bezel: #2a2535;
  --wh-screen: #07060c;
  --wh-ink: #f1f5f9;
  --wh-row: #1d1a28;
  --wh-row-hi: #2a2540;
  --wh-wrow: #1b1930;
  --wh-face: #6b6680;
  --wh-link: var(--brand-cyan);
  --wh-back: var(--brand-indigo);
  --wh-vib: var(--brand-magenta);
}
.dark .wh-page {
  --wh-dev: #2a2436;
  --wh-dev-edge: #514862;
  --wh-strap: #3a3347;
  --wh-bezel: #3a3347;
  --wh-link: #2cb6ed;
  --wh-back: #8b97ff;
}

.wh-defs { position: absolute; }
.wh-fig { display: block; width: 100%; height: auto; }
/* On phones the hero takes the side gutters too: it is the page's main content. */
.wh-hero { width: calc(100% + 1.5rem); margin-inline: -0.75rem; }
@media (min-width: 640px) {
  .wh-hero { width: 100%; margin-inline: 0; }
}

.wh-dev { fill: var(--wh-dev); stroke: var(--wh-dev-edge); stroke-width: 2; }
.wh-strap { fill: var(--wh-strap); }
.wh-bezel { fill: var(--wh-bezel); }
.wh-screen, .wh-screen-off { fill: var(--wh-screen); }
.wh-slot { fill: #2c2737; }
.wh-white { fill: #fff; }
.wh-ink { fill: var(--wh-ink); }
.wh-ink-faint { fill: var(--wh-ink); opacity: 0.3; }
.wh-row { fill: var(--wh-row); }
.wh-row-hi { fill: var(--wh-row-hi); stroke: var(--brand-magenta); stroke-width: 2; }
.wh-wrow { fill: var(--wh-wrow); }
.wh-time {
  fill: var(--wh-ink);
  text-anchor: middle;
  font-variant-numeric: tabular-nums;
}
.wh-start { text-anchor: start; }
.wh-z { fill: #fff; font-weight: 700; text-anchor: middle; }
.wh-ring-arc { fill: none; stroke: #fca5a5; stroke-width: 3.5; }
.wh-fill-link { fill: var(--wh-link); }
.wh-fill-back { fill: var(--wh-back); }
.wh-fill-edit { fill: var(--brand-magenta); }
.wh-fill-on { fill: #3ccf5c; }
.wh-fill-day { fill: #4fc3f7; }
.wh-pencil { fill: none; stroke: #fff; stroke-width: 2; stroke-linejoin: round; }
.wh-face line { stroke: var(--wh-face); stroke-width: 4; stroke-linecap: round; }
.wh-face .wh-hand { stroke-width: 6; }
.wh-face-dot { fill: var(--wh-face); }

.wh-link { fill: none; stroke: var(--wh-link); stroke-width: 3.5; stroke-linecap: round; }
.wh-flow { stroke-dasharray: 8 8; }
.wh-back { stroke: var(--wh-back); stroke-dasharray: 2 7; }
.wh-bt { fill: none; stroke: var(--wh-link); stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.wh-sound path { fill: none; stroke: var(--wh-back); stroke-width: 3.5; stroke-linecap: round; }
.wh-vib path { fill: none; stroke: var(--wh-vib); stroke-width: 4; stroke-linecap: round; }

.wh-badge circle { fill: var(--foreground); }
.wh-badge text { fill: var(--background); font-size: 19px; font-weight: 700; text-anchor: middle; }

/* The one moving moment: the signal travels and the watch buzzes. */
@media (prefers-reduced-motion: no-preference) {
  .wh-flow { animation: wh-flow 0.9s linear infinite; }
  .wh-buzz { animation: wh-buzz 2.4s ease-in-out infinite; }
  .wh-vib { animation: wh-vib 2.4s linear infinite; }
}
@keyframes wh-flow { from { stroke-dashoffset: 16; } to { stroke-dashoffset: 0; } }
@keyframes wh-buzz {
  0%, 30%, 100% { transform: translateX(0); }
  4% { transform: translateX(-3px); }
  8% { transform: translateX(3px); }
  12% { transform: translateX(-3px); }
  16% { transform: translateX(3px); }
  20% { transform: translateX(-2px); }
  24% { transform: translateX(2px); }
}
@keyframes wh-vib {
  0%, 28% { opacity: 1; }
  38%, 100% { opacity: 0.15; }
}

.wh-steps {
  display: grid;
  gap: 0.75rem;
  margin-top: 1rem;
}
@media (min-width: 640px) {
  .wh-steps { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
.wh-steps li { display: flex; gap: 0.6rem; align-items: flex-start; line-height: 1.45; }
.wh-num {
  display: grid;
  place-items: center;
  flex: none;
  width: 1.6rem;
  height: 1.6rem;
  border-radius: 999px;
  background: var(--foreground);
  color: var(--background);
  font-size: 0.85rem;
  font-weight: 700;
}

.wh-panel {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 1rem;
  border: 1px solid var(--border);
  border-radius: 1rem;
  background: var(--card);
}

.wh-chip {
  padding: 0.15rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  background: var(--muted);
  font-weight: 600;
}

.wh-toggle {
  position: relative;
  flex: none;
  width: 2.75rem;
  height: 1.6rem;
  border-radius: 999px;
  background: var(--border);
}
.wh-toggle i {
  position: absolute;
  top: 0.2rem;
  left: 0.2rem;
  width: 1.2rem;
  height: 1.2rem;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(0 0 0 / 0.25);
}
.wh-toggle-on { background: var(--primary); }
.wh-toggle-on i { left: auto; right: 0.2rem; }

.wh-seg {
  display: flex;
  flex: none;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 0.6rem;
  font-size: 0.8rem;
}
.wh-seg > span {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  min-width: 2.4rem;
  height: 1.8rem;
  padding: 0 0.5rem;
}
.wh-seg > span + span { border-left: 1px solid var(--border); }
.wh-seg-none { color: var(--muted-foreground); text-decoration: line-through; }
.wh-seg-lvl i { width: 3px; height: 0.8rem; border-radius: 2px; background: var(--muted-foreground); }
.wh-seg-on { background: var(--primary); }
.wh-seg-on i { background: #fff; }
</style>
