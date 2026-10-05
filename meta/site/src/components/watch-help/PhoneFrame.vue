<script setup lang="ts">
// A phone drawn at 136 x 248 in its own coordinates, origin top-left.
// The slot draws the screen contents in the same coordinates (screen area:
// x 7..129, y 7..241). Colours come from the `.wh-*` classes in WatchHelpPage.
defineProps<{ x: number; y: number; scale?: number }>()
</script>

<template>
  <g :transform="`translate(${x} ${y}) scale(${scale ?? 1})`">
    <rect class="wh-dev" x="0" y="0" width="136" height="248" rx="24" />
    <rect class="wh-screen" x="7" y="7" width="122" height="234" rx="18" />
    <rect class="wh-slot" x="56" y="15" width="24" height="5" rx="2.5" />
    <slot />
  </g>
</template>
