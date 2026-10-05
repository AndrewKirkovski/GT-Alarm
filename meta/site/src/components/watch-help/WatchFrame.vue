<script setup lang="ts">
// A round watch drawn around its own centre (0, 0): case radius 96, screen
// radius 84, straps reaching y = ±150. The slot draws the screen contents in
// the same coordinates. Colours come from the `.wh-*` classes in WatchHelpPage.
defineProps<{ cx: number; cy: number; scale?: number; off?: boolean }>()
</script>

<template>
  <g :transform="`translate(${cx} ${cy}) scale(${scale ?? 1})`">
    <rect class="wh-strap" x="-42" y="-150" width="84" height="96" rx="16" />
    <rect class="wh-strap" x="-42" y="54" width="84" height="96" rx="16" />
    <rect class="wh-dev" x="88" y="-15" width="15" height="30" rx="5" />
    <circle class="wh-dev" r="96" />
    <circle class="wh-bezel" r="90" />
    <circle :class="off ? 'wh-screen-off' : 'wh-screen'" r="84" />
    <slot />
  </g>
</template>
