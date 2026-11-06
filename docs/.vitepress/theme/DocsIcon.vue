<script setup lang="ts">
// Lucide icons (ISC), matching the documentation's original feature icons.
defineProps<{ name: string }>()
const paths: Record<string, string[]> = {
  'arrow-up-right': ['M7 17 17 7', 'M7 7h10v10'],
  'arrow-right': ['M5 12h14', 'm12 5 7 7-7 7'],
  video: ['m16 10 5-3v10l-5-3', 'M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z'],
  monitor: ['M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M8 21h8M12 17v4'],
  arrows: ['m8 3-4 4 4 4M4 7h16', 'm16 21 4-4-4-4M20 17H4'],
  zap: ['m13 2-10 12h9l-1 8 10-12h-9l1-8Z'],
  code: ['m16 18 6-6-6-6M8 6l-6 6 6 6'],
  sliders: ['M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3', 'M14 2v4M8 10v4M16 18v4'],
  lock: ['M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Z', 'M7 11V7a5 5 0 0 1 10 0v4'],
  send: ['m22 2-7 20-4-9-9-4 20-7Z', 'M22 2 11 13'],
  play: ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z', 'm10 8 6 4-6 4V8Z'],
  record: ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z', 'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z'],
  audio: ['M2 10v4M6 6v12M10 3v18M14 8v8M18 5v14M22 10v4'],
  activity: ['M22 12h-4l-3 9L9 3l-3 9H2'],
  shield: ['M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z'],
}
</script>

<template>
  <svg class="dh-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path v-for="(path, index) in paths[name]" :key="index" :d="path" />
  </svg>
</template>
